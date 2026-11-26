package no.julian.quietframe;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import no.julian.quietframe.config.QuietFrameConfig;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class KnotSmoke {
    private KnotSmoke() {
    }

    public static void main(String[] args) throws Exception {
        FabricLoader fabric = FabricLoader.getInstance();
        requireVersion(fabric, "fabricloader", "0.19.5");
        requireVersion(fabric, "fabric-api", "0.164.0+26.3");
        requireVersion(fabric, "minecraft", "26.3");
        requireVersion(fabric, "quietframe", "0.1.0");

        String conflict = args[0];
        boolean hooksExpected = conflict.isEmpty();
        if (!hooksExpected && !fabric.isModLoaded(conflict)) {
            throw new AssertionError("Missing conflict fixture " + conflict);
        }

        Map<String, Set<String>> hooks = new HashMap<>();
        hooks.put("com.mojang.blaze3d.platform.FramerateLimitTracker", Set.of("getLatestInputTime"));
        hooks.put("net.minecraft.client.Minecraft", Set.of("renderOnSchedule", "vanillaLimiter", "overrideAutoPause", "recoverRendering"));
        hooks.put("net.minecraft.client.Screenshot", Set.of("excludeBadge", "protectReadback"));
        hooks.put("net.minecraft.client.sounds.SoundEngine", Set.of("captureContext", "updateGain", "restoreGain"));
        hooks.put("com.mojang.blaze3d.platform.Window", Set.of("filterWindowEvent", "markWindowClosed", "getWindowState"));

        for (var entry : hooks.entrySet()) {
            Class<?> target = Class.forName(entry.getKey(), false, KnotSmoke.class.getClassLoader());
            Set<String> methods = Arrays.stream(target.getDeclaredMethods()).map(Method::getName)
                    .collect(Collectors.toSet());
            for (String hook : entry.getValue()) {
                boolean present = methods.stream().anyMatch(name -> name.endsWith("quietframe$" + hook));
                if (present != hooksExpected) {
                    throw new AssertionError(entry.getKey() + " unexpected hook presence for " + hook + ": " + present);
                }
            }
        }
        // Class definitions above force Mixin to validate every required selector and cardinality.
        var initializer = fabric.getEntrypointContainers("client", ClientModInitializer.class).stream()
                .filter(container -> container.getProvider().getMetadata().getId().equals("quietframe"))
                .findFirst().orElseThrow().getEntrypoint();
        if (initializer.getClass() != QuietFrameClient.class) {
            throw new AssertionError("Unexpected client entry point " + initializer.getClass());
        }
        // The active entry point registers a key mapping against a Minecraft instance.
        // The conflicting entry point can safely run without opening a game window.
        if (hooksExpected) {
            captureWhileDisabled();
        } else {
            initializer.onInitializeClient();
            if (QuietFrameClient.get() != initializer || !Files.isRegularFile(fabric.getConfigDir().resolve("quietframe.json"))) {
                throw new AssertionError("Inactive client entry point did not initialize its config");
            }
        }
        System.out.println("KnotSmoke passed (" + (hooksExpected ? hooks.size() + " transformed targets" : conflict + " gated") + ")");
    }

    private static void captureWhileDisabled() throws ReflectiveOperationException {
        QuietFrameClient client = new QuietFrameClient();
        var instance = QuietFrameClient.class.getDeclaredField("instance");
        var config = QuietFrameClient.class.getDeclaredField("config");
        instance.setAccessible(true);
        config.setAccessible(true);
        config.set(client, QuietFrameConfig.DEFAULTS.toggled());
        Object previous = instance.get(null);
        instance.set(null, client);
        try {
            Method protectReadback = Arrays.stream(Screenshot.class.getDeclaredMethods())
                    .filter(method -> method.getName().endsWith("quietframe$protectReadback"))
                    .findFirst().orElseThrow();
            protectReadback.setAccessible(true);
            var recorded = new AtomicReference<Runnable>();
            Operation<Void> submit = arguments -> {
                recorded.set((Runnable) arguments[4]);
                return null;
            };
            int[] completed = {0};
            protectReadback.invoke(null, null, null, null, 0L, (Runnable) () -> completed[0]++, 0, submit);
            if (!client.isCaptureProtected()) {
                throw new AssertionError("Disabled QuietFrame failed to track the in-flight readback");
            }
            config.set(client, QuietFrameConfig.DEFAULTS);
            client.capture().frameEnd();
            if (!client.isCaptureProtected()) {
                throw new AssertionError("Enabling QuietFrame discarded an in-flight readback");
            }
            recorded.get().run();
            client.capture().frameEnd();
            if (completed[0] != 1 || client.isCaptureProtected()) {
                throw new AssertionError("Tracked readback failed to complete after enabling QuietFrame");
            }
        } finally {
            instance.set(null, previous);
        }
    }

    private static void requireVersion(FabricLoader fabric, String id, String expected) {
        String actual = fabric.getModContainer(id).orElseThrow().getMetadata().getVersion().getFriendlyString();
        if (!expected.equals(actual)) {
            throw new AssertionError(id + " expected " + expected + ", got " + actual);
        }
    }
}
