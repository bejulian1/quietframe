package no.julian.quietframe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;

import static no.julian.quietframe.CoreTests.check;
import static no.julian.quietframe.FramePolicy.State.MINIMIZED;

final class SuspendedRenderTests {
    private SuspendedRenderTests() {
    }

    static void run() throws ReflectiveOperationException {
        gpuSubmissions();
        lightUpdates();
    }

    private static void gpuSubmissions() throws ReflectiveOperationException {
        VulkanCommandEncoder vulkan = fixture(VulkanCommandEncoder.class);
        Field current = field(VulkanCommandEncoder.class, "currentSubmitIndex");
        Field completed = field(VulkanCommandEncoder.class, "completedSubmitIndex");
        current.setLong(vulkan, 2);
        int[] submissions = {0};
        var encoder = proxy(CommandEncoder.class, (object, method, arguments) -> switch (method.getName()) {
            case "createFence" -> vulkan.createFence();
            case "submit" -> {
                // Simulate a signaled GPU timeline. RenderPearl's real fence remains responsible for polling.
                long submitted = current.getLong(vulkan);
                current.setLong(vulkan, submitted + 1);
                completed.setLong(vulkan, submitted);
                submissions[0]++;
                yield null;
            }
            default -> throw new AssertionError("Unexpected encoder operation " + method);
        });
        var device = proxy(GpuDevice.class, (object, method, arguments) -> {
            if (method.getName().equals("createCommandEncoder")) {
                return encoder;
            }
            throw new AssertionError("Unexpected device operation " + method);
        });

        Field installed = field(RenderSystem.class, "DEVICE");
        Object previous = installed.get(null);
        installed.set(null, device);
        try {
            RenderCadence cadence = new RenderCadence();
            int[] retired = {0};
            Runnable fencedTask = () -> retired[0]++;
            RenderSystem.queueFencedTask(fencedTask);
            RenderSystem.executePendingTasks();
            check(retired[0] == 0, "real Vulkan fence cannot retire an unsubmitted command");
            int peakPending = 0;
            long serviceStep = RenderCadence.MAX_SERVICE_PARK_NS;
            for (int pass = 0; pass < 1_200; pass++) {
                long now = pass * serviceStep;
                RenderSystem.queueFencedTask(fencedTask);
                peakPending = Math.max(peakPending, pass + 2 - retired[0]);
                if (cadence.plan(now, MINIMIZED, 0) <= 0) {
                    throw new AssertionError("Suspended render unexpectedly became due");
                }
                cadence.submitWhileSuspended(now, QuietFrameClient::submitOffscreen);
                RenderSystem.executePendingTasks();
                if (pass == 0) {
                    check(retired[0] == 2, "maintenance submit retires a fence while still minimized");
                }
            }
            int maxPending = Math.toIntExact(Math.ceilDiv(RenderCadence.SUSPENDED_SUBMIT_INTERVAL_NS, serviceStep));
            check(peakPending == maxPending && retired[0] >= 1_201 - maxPending,
                    "repeated minimized submissions keep pending GPU work bounded");
            check(submissions[0] == 1 + 1_199 / maxPending, "suspension submits only on its bounded cadence");
            long now = 1_200 * serviceStep;
            cadence.plan(now, MINIMIZED, 1);
            cadence.submitWhileSuspended(now, QuietFrameClient::submitOffscreen);
            check(submissions[0] == 1 + 1_199 / maxPending, "normal paced frames do not trigger maintenance submits");
            cadence.plan(now, MINIMIZED, 0);
            cadence.submitWhileSuspended(now, QuietFrameClient::submitOffscreen);
            RenderSystem.executePendingTasks();
            check(retired[0] == 1_201, "a new suspension immediately retires any remaining work");
        } finally {
            installed.set(null, previous);
        }
    }

    private static void lightUpdates() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ClientLevel level = fixture(ClientLevel.class);
        ClientChunkCache chunks = fixture(ClientChunkCache.class);
        field(ClientChunkCache.class, "level").set(chunks, level);
        var queue = new ArrayDeque<Runnable>();
        field(ClientLevel.class, "lightUpdateQueue").set(level, queue);
        field(ClientLevel.class, "chunkSource").set(level, chunks);
        int[] lightPasses = {0};
        int[] applied = {0};
        field(ClientChunkCache.class, "lightEngine").set(chunks, new LevelLightEngine(chunks, false, false) {
            @Override
            public int runLightUpdates() {
                lightPasses[0]++;
                if (applied[0] != lightPasses[0]) {
                    throw new AssertionError("Light engine ran before the queued packet update");
                }
                return super.runLightUpdates();
            }
        });

        Runnable lightPacket = () -> applied[0]++;
        level.queueLightUpdate(lightPacket);
        QuietFrameClient.serviceSkippedLighting(false, true, level);
        QuietFrameClient.serviceSkippedLighting(true, false, level);
        QuietFrameClient.serviceSkippedLighting(true, true, null);
        check(queue.size() == 1 && lightPasses[0] == 0, "light service obeys the vanilla render guards");
        for (int pass = 0; pass < 1_200; pass++) {
            QuietFrameClient.serviceSkippedLighting(true, true, level);
            if (!queue.isEmpty()) {
                throw new AssertionError("Queued client lighting accumulated during suspension");
            }
            if (pass < 1_199) {
                level.queueLightUpdate(lightPacket);
            }
        }
        check(applied[0] == 1_200 && lightPasses[0] == 1_200,
                "1,200 skipped passes each service the stock client light queue and engine");
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // These stock classes normally require an initialized Vulkan device or world.
    private static <T> T fixture(Class<T> type) throws ReflectiveOperationException {
        Class<?> allocator = Class.forName("sun.misc.Unsafe");
        Object instance = field(allocator, "theUnsafe").get(null);
        return type.cast(allocator.getMethod("allocateInstance", Class.class).invoke(instance, type));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
