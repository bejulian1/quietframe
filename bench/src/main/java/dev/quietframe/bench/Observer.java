package dev.quietframe.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class Observer implements ClientModInitializer {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static Observer active;
    private Path out;
    private String nonce, profile, capture;
    private long lastPresent, lastStatus, captureAfter;
    private int seq, readyTicks;
    private Phase phase;
    private CompletableFuture<Void> fixture;
    private SoundInstance loop;
    private boolean booted, ready, failed;
    private final Map<String, String> hashes = new LinkedHashMap<>();
    private final Set<String> phases = Set.of("focused", "blur", "hover", "leave", "minimized", "restored", "afk", "wake");

    public void onInitializeClient() {
        nonce = System.getProperty("quietframe.bench.Nonce");
        if (nonce == null) return;
        out = Path.of(System.getProperty("quietframe.bench.Out"));
        profile = System.getProperty("quietframe.bench.Profile");
        active = this;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (failed) return;
            try { tick(client); } catch (Exception e) { fail(client, e); }
        });
    }

    public static boolean inFixture() { return active != null && active.fixture != null; }

    public static void renderAttempt() {
        if (active != null && active.phase != null && System.nanoTime() < active.phase.end) active.phase.attempts++;
    }

    public static void presented(Minecraft client) {
        if (active == null) return;
        long ns = System.nanoTime();
        active.lastPresent = ns;
        if (active.phase != null && ns < active.phase.end) {
            active.phase.presents.add(ns);
            if (client.windowSurface().isSuboptimal()) active.phase.suboptimal++;
        }
        if (active.capture != null && ns >= active.captureAfter && client.isWindowActive()
                && !client.getWindow().isIconified() && client.gui.screen() == null
                && client.gui.overlay() == null && client.gameRenderer.gameRenderState().shouldRenderLevel) {
            String name = active.capture;
            int id = active.seq;
            active.capture = null;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), img -> {
                try (img) {
                    img.writeToFile(active.out.resolve(name + ".png"));
                    active.ack(id);
                } catch (Exception e) { active.fail(client, e); }
            });
        }
    }

    static void json(Path file, Object value) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.toJson(value) + "\n");
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private void tick(Minecraft c) throws Exception {
        if (!booted) {
            if (c.isGameLoadFinished() && c.gui.screen() instanceof TitleScreen title) boot(c, title);
            return;
        }
        if (fixture == null) {
            if (c.level != null && c.player != null && c.gui.screen() == null && c.getSingleplayerServer() != null) {
                var server = c.getSingleplayerServer();
                fixture = server.submit(() -> Fixture.build(server.getPlayerList().getPlayers().getFirst()));
            }
            return;
        }
        if (!ready) {
            if (!fixture.isDone()) return;
            fixture.join();
            if (c.level == null || c.gui.screen() != null || !c.level.getBlockState(Fixture.MARKER).is(Blocks.EMERALD_BLOCK)
                    || !c.levelRenderer.isSectionCompiledAndVisible(Fixture.MARKER, 0)) {
                readyTicks = 0;
                return;
            }
            if (++readyTicks < 80) return;
            if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
            if (!shaderReady()) return;
            loop = new SimpleSoundInstance(Identifier.withDefaultNamespace("block.beacon.ambient"), SoundSource.AMBIENT,
                    .25f, 1f, RandomSource.create(263), true, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
            c.getSoundManager().play(loop);
            c.options.save();
            snapshot(c, "canonical");
            var mode = c.windowSurface().currentConfiguration().orElseThrow().presentMode().name();
            if (mode.startsWith("FIFO")) throw new IllegalStateException("VSync present mode " + mode);
            json(out.resolve("fixture.json"), Map.of("seed", 263, "daytime", 6000, "userFps", c.options.framerateLimit().get(),
                    "vsync", c.options.enableVsync().get(), "pauseOnLostFocus", c.options.pauseOnLostFocus,
                    "presentMode", mode, "camera", List.of(.5,78,28.5,180,15), "shader", profile.startsWith("stack") ? "daylight" : "none"));
            ready = true;
        }
        var s = WindowSample.read(c, loop, lastPresent);
        if (phase != null) {
            if (s.ns() < phase.end) phase.ticks.add(s);
            else {
                phase.save(out);
                phase = null;
                ack(seq);
            }
        }
        if (s.ns() - lastStatus >= 200_000_000L) {
            json(out.resolve("status.json"), s);
            lastStatus = s.ns();
        }
        Path cmd = out.resolve("command.json");
        if (Files.exists(cmd)) {
            var message = JSON.fromJson(Files.readString(cmd), JsonObject.class);
            Files.delete(cmd);
            if (!message.get("nonce").getAsString().equals(nonce) || message.get("seq").getAsInt() != seq + 1)
                throw new IllegalArgumentException("Invalid command nonce or sequence");
            seq++;
            switch (message.get("op").getAsString()) {
                case "ping" -> ack(seq);
                case "phase" -> {
                    String name = message.get("name").getAsString();
                    int seconds = message.get("seconds").getAsInt();
                    if (!phases.contains(name) || seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Invalid phase");
                    byte[] bytes = Files.readAllBytes(c.gameDirectory.toPath().resolve("options.txt"));
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    phase = new Phase(name, hash, System.nanoTime(), seconds);
                }
                case "capture" -> {
                    capture = message.get("name").getAsString();
                    if (!Set.of("before","after").contains(capture) || !s.focused() || s.iconified() || !s.playing())
                        throw new IllegalStateException("Expected focused capture in gameplay");
                    captureAfter = System.nanoTime() + 300_000_000L;
                }
                case "save" -> {
                    snapshot(c, "before-save");
                    c.options.save();
                    snapshot(c, "after-save");
                    json(out.resolve("save-state.json"), Map.of("before", s,
                            "after", WindowSample.read(c, loop, lastPresent)));
                    ack(seq);
                }
                case "stop" -> {
                    snapshot(c, "final");
                    ack(seq);
                    c.stop();
                }
                default -> throw new IllegalArgumentException("Unknown command");
            }
        }
    }

    private boolean shaderReady() throws ReflectiveOperationException {
        if (!profile.startsWith("stack")) return true;
        var entry = FabricLoader.getInstance().getEntrypointContainers("client", ClientModInitializer.class).stream()
                .filter(e -> e.getProvider().getMetadata().getId().equals("lightshade")).findFirst().orElseThrow();
        Class<?> renderer = Class.forName(entry.getEntrypoint().getClass().getPackageName() + ".render.LightshadeRenderer");
        Map<?,?> status = (Map<?,?>)renderer.getMethod("status").invoke(null);
        if (status.get("failure") instanceof String msg && !msg.isBlank()) throw new IllegalStateException(msg);
        return Boolean.TRUE.equals(status.get("enabled")) && "daylight".equals(status.get("pack"));
    }

    private void boot(Minecraft c, TitleScreen title) throws IOException {
        var device = RenderSystem.getDevice().getDeviceInfo();
        if (!device.name().contains("RTX 2080") || !device.backendName().toLowerCase(Locale.ROOT).contains("vulkan"))
            throw new IllegalStateException("Unexpected rendering device " + device);
        int props = SDLVideo.SDL_GetWindowProperties(c.getWindow().handle());
        long xid = SDLProperties.SDL_GetNumberProperty(props, SDLVideo.SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0);
        var mods = new TreeMap<String,String>();
        FabricLoader.getInstance().getAllMods().forEach(m -> mods.put(m.getMetadata().getId(), m.getMetadata().getVersion().getFriendlyString()));
        json(out.resolve("client.json"), Map.of("pid", ProcessHandle.current().pid(), "nonce", nonce, "xid", xid,
                "driver", device.driverInfo(), "device", device.name(), "backend", device.backendName(),
                "sdl", SDLVideo.SDL_GetCurrentVideoDriver(), "mods", mods));
        c.options.enableVsync().set(false);
        c.options.framerateLimit().set(260);
        c.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        c.options.pauseOnLostFocus = false;
        c.options.renderDistance().set(8);
        c.options.simulationDistance().set(6);
        c.options.cloudStatus().set(CloudStatus.OFF);
        c.options.vignette().set(false);
        c.options.bobView().set(false);
        c.options.fov().set(70);
        c.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(.75);
        c.options.getSoundSourceOptionInstance(SoundSource.AMBIENT).set(.5);
        var settings = new LevelSettings("QuietFrame Courtyard", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, true, false), true, WorldDataConfiguration.DEFAULT);
        booted = true;
        c.createWorldOpenFlows().createFreshLevel("courtyard", settings, new WorldOptions(263, false, false),
                regs -> regs.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), title);
    }

    private void snapshot(Minecraft c, String name) throws Exception {
        byte[] bytes = Files.readAllBytes(c.gameDirectory.toPath().resolve("options.txt"));
        Files.write(out.resolve("options-" + name + ".txt"), bytes);
        hashes.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        json(out.resolve("options-hashes.json"), hashes);
    }

    private void ack(int id) throws IOException {
        json(out.resolve("ack-" + id + ".json"), Map.of("seq", id, "nonce", nonce, "ns", System.nanoTime()));
    }

    private void fail(Minecraft c, Throwable error) {
        failed = true;
        error.printStackTrace();
        try { Files.writeString(out.resolve("error.txt"), error.toString()); }
        catch (IOException e) { error.addSuppressed(e); }
        c.stop();
    }
}
