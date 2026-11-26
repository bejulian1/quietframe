package no.julian.quietframe;

import com.mojang.renderpearl.backend.vulkan.DestructionQueue;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import no.julian.quietframe.FramePolicy.State;
import no.julian.quietframe.audio.QuietAudioController;
import no.julian.quietframe.config.QuietFrameConfig;
import no.julian.quietframe.config.QuietFrameMixinPlugin;
import org.lwjgl.sdl.SDLVideo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class CoreTests {
    private static final QuietFrameConfig DEFAULTS = QuietFrameConfig.DEFAULTS;
    private static final long SERVICE_NS = RenderCadence.MAX_SERVICE_PARK_NS;
    private static int checks;

    private CoreTests() {
    }

    public static void main(String[] args) throws Exception {
        config(Path.of(args[0]));
        statePriority();
        cadence();
        windowState();
        audio();
        capture();
        badgeScreenshots();
        statusFps();
        conflicts();
        SuspendedRenderTests.run();
        System.out.println("CoreTests passed (" + checks + " checks)");
    }

    private static void config(Path directory) throws IOException {
        Path file = directory.resolve("config/quietframe.json");
        check(QuietFrameConfig.load(file).equals(DEFAULTS), "missing config uses defaults");
        check(DEFAULTS.enabled() && DEFAULTS.unfocusedFps() == 10 && DEFAULTS.minimizedFps() == 1
                && DEFAULTS.afkEnabled() && DEFAULTS.afkTimeoutSeconds() == 60 && DEFAULTS.afkFps() == 15
                && DEFAULTS.hoverWakeup() && !DEFAULTS.skipRenderWhenMinimized() && DEFAULTS.audioAttenuation()
                && DEFAULTS.unfocusedVolumeMultiplier() == 0.25f && DEFAULTS.minimizedVolumeMultiplier() == 0
                && !DEFAULTS.showStatusBadge() && !DEFAULTS.pauseOnLostFocusOverride(), "pinned default settings");
        DEFAULTS.save(file);
        check(QuietFrameConfig.load(file).equals(DEFAULTS), "first save round-trips");
        var profile = DEFAULTS.withProfile(true).toggled();
        profile.save(file);
        check(QuietFrameConfig.load(file).equals(profile), "replacement save round-trips");
        try (var files = Files.list(file.getParent())) {
            check(files.toList().equals(java.util.List.of(file)), "successful save removes its temporary file");
        }
        check(profile.withProfile(false).equals(DEFAULTS.toggled()), "balanced profile preserves enabled setting");
        Files.writeString(file, """
                {"unfocused_fps":260,"minimized_fps":1,"afk_timeout_seconds":86400,"afk_fps":1,
                 "unfocused_volume_multiplier":1,"minimized_volume_multiplier":0,"show_status_badge":true,
                 "pause_on_lost_focus_override":true,"skip_render_when_minimized":true,"hover_wakeup":false}
                """);
        var customized = QuietFrameConfig.load(file).withProfile(true);
        check(customized.showStatusBadge() && customized.pauseOnLostFocusOverride()
                && customized.skipRenderWhenMinimized() && !customized.hoverWakeup(), "preset preserves preferences");

        for (String malformed : new String[]{
                "", "[]", "null", "{", "{} {}", "{\"enabled\":null}", "{\"enabled\":\"true\"}",
                "{\"unfocused_fps\":0}", "{\"minimized_fps\":261}", "{\"afk_fps\":1.5}",
                "{\"afk_timeout_seconds\":86401}", "{\"afk_timeout_seconds\":0}", "{\"afk_fps\":\"1\"}",
                "{\"afk_fps\":99999999999999}", "{\"unfocused_volume_multiplier\":NaN}",
                "{\"minimized_volume_multiplier\":1.00000001}", "{\"unfocused_volume_multiplier\":-1e-99}",
                "{\"enabled\":true,}", "{ /* comment */ \"enabled\":true }"
        }) {
            Files.writeString(file, malformed);
            try {
                QuietFrameConfig.load(file);
                throw new AssertionError("Accepted malformed settings " + malformed);
            } catch (IOException expected) {
                check(Files.readString(file).equals(malformed), "failed load preserves file");
            }
        }

        for (String duplicate : new String[]{
                """
                {"minimized_fps":0,"minimized_fps":3}
                """, """
                {"unknown":[{"nested":null,"nested":true}]}
                """
        }) {
            Files.writeString(file, duplicate);
            try {
                QuietFrameConfig.load(file);
                throw new AssertionError("Accepted duplicate JSON keys");
            } catch (IOException expected) {
                check(expected.getMessage().contains("Duplicate JSON key"), "duplicate key is rejected before validation");
                check(Files.readString(file).equals(duplicate), "duplicate key rejection preserves the config");
            }
        }

        Path occupied = Files.createDirectory(file.resolveSibling("occupied"));
        Path sentinel = Files.writeString(occupied.resolve("sentinel"), "existing data");
        try {
            DEFAULTS.save(occupied);
            throw new AssertionError("Save replaced an occupied directory");
        } catch (IOException expected) {
            check(Files.readString(sentinel).equals("existing data"), "failed save preserves previous data");
        }
        try (var files = Files.list(file.getParent())) {
            check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "failed save removes its temporary file");
        }
    }

    private static void statePriority() {
        check(FramePolicy.resolve(DEFAULTS.toggled(), false, false, true, true, true, true) == State.BYPASSED,
                "disable wins");
        check(FramePolicy.resolve(DEFAULTS, true, true, true, false, false, false) == State.BYPASSED,
                "protected state wins");
        check(FramePolicy.resolve(DEFAULTS, false, true, true, false, true, true) == State.MINIMIZED,
                "actual minimization wins over focus, hover and idle");
        check(FramePolicy.resolve(DEFAULTS, false, false, false, true, true, false) == State.MINIMIZED,
                "unfocused exclusive fullscreen uses minimized pacing");
        check(FramePolicy.resolve(DEFAULTS, false, false, false, false, true, true) == State.HOVERED,
                "hover wins over idle");
        check(FramePolicy.resolve(DEFAULTS, false, true, false, false, true, true) == State.AFK,
                "focused pointer does not keep AFK awake");
        check(FramePolicy.resolve(DEFAULTS, false, false, false, false, false, true) == State.AFK,
                "unfocused window can become AFK");
        check(FramePolicy.resolve(DEFAULTS, false, false, false, false, false, false) == State.UNFOCUSED,
                "recent input while unfocused");
        check(FramePolicy.resolve(DEFAULTS, false, true, false, true, false, false) == State.FOCUSED,
                "focused exclusive fullscreen remains full speed");
        check(FramePolicy.requestedFps(DEFAULTS, State.MINIMIZED, true) == 1, "minimized defaults to one FPS");
        check(FramePolicy.requestedFps(DEFAULTS, State.AFK, false) == 15, "AFK defaults to 15 FPS");
        check(FramePolicy.requestedFps(DEFAULTS, State.UNFOCUSED, false) == 10, "unfocused defaults to 10 FPS");
        check(FramePolicy.requestedFps(DEFAULTS, State.HOVERED, false) == -1, "hover uses vanilla");
        var skip = new QuietFrameConfig(true, 10, 1, false, 60, 15, false, true, true, 0.25f, 0, false, false);
        check(FramePolicy.resolve(skip, false, false, false, false, true, true) == State.UNFOCUSED,
                "disabled hover and AFK policies");
        check(FramePolicy.requestedFps(skip, State.MINIMIZED, true) == 0, "explicit hidden or iconified skip");
        check(FramePolicy.requestedFps(skip, State.MINIMIZED, false) == 1, "exclusive fullscreen alone cannot skip");
    }

    private static void cadence() {
        RenderCadence cadence = new RenderCadence();
        check(cadence.plan(0, State.UNFOCUSED, 10) == 0, "first frame is immediate");
        check(cadence.plan(1, State.UNFOCUSED, 10) == SERVICE_NS, "background service park is bounded");
        check(cadence.plan(99_000_000, State.UNFOCUSED, 10) == 1_000_000, "park to deadline when close");
        check(cadence.plan(101_000_000, State.UNFOCUSED, 10) == 0, "render after deadline");
        check(cadence.plan(200_000_000, State.UNFOCUSED, 10) == 0, "ordinary overshoot does not drift cadence");
        check(cadence.plan(2_000_000_000, State.UNFOCUSED, 10) == 0, "render once after a stall");
        check(cadence.plan(2_000_000_001, State.UNFOCUSED, 10) == SERVICE_NS, "no catch-up burst");
        check(cadence.plan(2_000_000_002, State.HOVERED, -1) == 0, "hover clears the deadline immediately");
        check(cadence.plan(2_000_000_003, State.UNFOCUSED, 10) == 0, "blur after hover starts a fresh cadence");
        check(cadence.plan(2_000_000_004, State.MINIMIZED, 0) == SERVICE_NS, "skip still permits service");
        check(cadence.plan(3_000_000_000L, State.MINIMIZED, 0) == SERVICE_NS, "suspension has no due frame");
        check(cadence.plan(3_000_000_001L, State.MINIMIZED, 1) == 0, "new setting starts rendering immediately");
        check(cadence.plan(3_900_000_000L, State.MINIMIZED, 1) == SERVICE_NS, "one FPS still services events");
        check(cadence.plan(4_000_000_001L, State.MINIMIZED, 1) == 0, "one FPS exact deadline");
        check(cadence.plan(4_000_000_002L, State.AFK, 1) == 0, "new reduced state resets even at equal FPS");
        for (long lateFrame : new long[]{199_999_999L, 200_000_000L, 200_000_001L}) {
            cadence.reset();
            check(cadence.plan(0, State.UNFOCUSED, 10) == 0, "first frame of overrun fixture");
            check(cadence.plan(lateFrame, State.UNFOCUSED, 10) == 0, "near-one-period overrun produces a due frame");
            check(cadence.plan(lateFrame + 1, State.UNFOCUSED, 10) == SERVICE_NS, "rebase prevents adjacent due frames");
            check(cadence.plan(lateFrame + 75_000_000L, State.UNFOCUSED, 10) == SERVICE_NS,
                    "material lateness rebases to a full period");
            check(cadence.plan(lateFrame + 100_000_000L, State.UNFOCUSED, 10) == 0, "rebased deadline is due");
        }
        cadence.reset();
        check(cadence.plan(0, State.UNFOCUSED, 10) == 0, "first frame of jitter fixture");
        check(cadence.plan(125_000_000L, State.UNFOCUSED, 10) == 0, "quarter-period jitter retains phase");
        check(cadence.plan(199_999_999L, State.UNFOCUSED, 10) == 1, "phase retains minimum spacing for jitter");
        check(cadence.plan(200_000_000L, State.UNFOCUSED, 10) == 0, "unaltered phase deadline is due");
        cadence.reset();
        check(cadence.plan(Long.MAX_VALUE - 1, State.AFK, 10) == 0, "reset clears same-state deadline");
        check(cadence.plan(Long.MIN_VALUE + 5, State.AFK, 10) == SERVICE_NS, "deadline survives nanoTime wrap");
    }

    private static void windowState() {
        GameWindowState window = new GameWindowState();
        long[] flags = {0, SDLVideo.SDL_WINDOW_INPUT_FOCUS | SDLVideo.SDL_WINDOW_MINIMIZED};
        int[] reads = {0};
        java.util.function.LongToIntFunction getId = handle -> handle == 101 ? 17 : 42;
        java.util.function.LongUnaryOperator getFlags = handle -> {
            reads[0]++;
            return flags[handle == 101 ? 0 : 1];
        };
        check(!window.onEvent(101, 42, getId), "foreign window events are ignored before the first sample");
        check(window.sample(101, getId, getFlags) && !window.focused() && !window.minimized() && !window.hovered(),
                "startup uses the already-blurred game window");
        check(!window.onEvent(101, getId.applyAsInt(202), getId) && window.sample(101, getId, getFlags) && reads[0] == 1
                && !window.focused() && !window.minimized(), "foreign focus and minimize flags cannot change policy");
        flags[0] = SDLVideo.SDL_WINDOW_MOUSE_FOCUS | SDLVideo.SDL_WINDOW_HIDDEN;
        check(window.onEvent(101, 17, getId) && window.sample(101, getId, getFlags) && window.minimized() && window.hovered()
                && !window.focused(), "game-window hide and mouse focus use their own SDL flags");
        flags[0] = SDLVideo.SDL_WINDOW_MINIMIZED;
        check(window.onEvent(101, 17, getId) && window.sample(101, getId, getFlags) && window.minimized() && !window.hovered(),
                "game-window minimization is read directly");
        flags[0] = SDLVideo.SDL_WINDOW_INPUT_FOCUS;
        check(window.onEvent(101, 17, getId) && window.sample(101, getId, getFlags) && window.focused() && !window.minimized(),
                "game-window restore and focus update the policy");
        window.close();
        check(!window.onEvent(101, 17, getId) && !window.sample(101, getId, getFlags) && reads[0] == 4,
                "closed window discards events and never samples its stale nonzero handle");
        GameWindowState closedBeforeSampling = new GameWindowState();
        closedBeforeSampling.close();
        check(!closedBeforeSampling.sample(101, getId, getFlags) && reads[0] == 4,
                "close before first sample also prevents native calls");
    }

    private static void audio() {
        check(QuietAudioController.multiplier(DEFAULTS, false, true, false) == 1, "focused gain");
        check(QuietAudioController.multiplier(DEFAULTS, false, false, false) == 0.25f, "unfocused gain");
        check(QuietAudioController.multiplier(DEFAULTS, false, true, true) == 0, "actual minimization gain");
        check(QuietAudioController.multiplier(DEFAULTS, true, false, true) == 1, "loading resets gain");
        check(QuietAudioController.multiplier(DEFAULTS.toggled(), false, false, true) == 1, "disable resets gain");
        var audioDisabled = new QuietFrameConfig(true, 10, 1, true, 60, 15, true, false, false, 0.25f, 0, false, false);
        check(QuietAudioController.multiplier(audioDisabled, false, false, true) == 1, "attenuation switch resets gain");

        QuietAudioController controller = new QuietAudioController();
        AtomicReference<Double> gain = new AtomicReference<>(0.8);
        int[] writes = {0};
        java.util.function.DoubleConsumer setter = value -> {
            writes[0]++;
            gain.set(value);
        };
        controller.update(0, 0, gain::get, setter);
        controller.loaded(17);
        controller.update(17, 1, gain::get, setter);
        controller.update(18, 0, gain::get, setter);
        check(writes[0] == 0, "absent, foreign, or neutral context makes no gain writes");
        controller.update(17, 0.25f, gain::get, setter);
        controller.update(17, 0.25f, gain::get, setter);
        check(writes[0] == 1 && gain.get().floatValue() == 0.2f, "attenuate once relative to current base");
        controller.update(17, 0, gain::get, setter);
        check(gain.get() == 0 && controller.appliedMultiplier() == 0, "minimize keeps zero listener gain");
        controller.update(18, 1, gain::get, setter);
        check(controller.appliedMultiplier() == 0, "restore waits for matching context");
        controller.update(17, 1, gain::get, setter);
        check(Float.floatToRawIntBits(gain.get().floatValue()) == Float.floatToRawIntBits(0.8f),
                "focus restores exact base gain");
        controller.update(17, 0, gain::get, setter);
        controller.loaded(17);
        gain.set(0.6);
        controller.update(17, 0.25f, gain::get, setter);
        check(gain.get().floatValue() == 0.15f, "reload captures even a reused context independently");
    }

    private static void capture() throws InterruptedException {
        CaptureProtection capture = new CaptureProtection();
        check(!capture.isActive(), "no pending screenshot");
        var first = capture.begin(() -> {});
        var second = capture.begin(() -> {});
        check(capture.isActive(), "overlapping screenshots protected");
        Thread completion = new Thread(first);
        completion.start();
        completion.join();
        first.finish();
        capture.frameEnd();
        check(capture.isActive(), "duplicate completion does not affect another readback");
        second.run();
        check(capture.isActive(), "last completion preserves protection through the frame");
        capture.frameEnd();
        check(!capture.isActive(), "frame end clears completed protection");

        var rejected = capture.begin(() -> { throw new AssertionError("Rejected capture callback ran"); });
        rejected.finish();
        capture.frameEnd();
        check(!capture.isActive(), "synchronous submission rejection releases protection");

        QuietFrameClient client = new QuietFrameClient();
        var queue = new DestructionQueue<Runnable>(2, Runnable::run);
        OutOfMemoryError failure = new OutOfMemoryError("Injected Vulkan callback failure");
        int[] callbacks = {0};
        queue.add(client.capture().begin(() -> { throw failure; }));
        var dropped = client.capture().begin(() -> callbacks[0]++);
        queue.add(dropped);
        queue.rotate();
        try {
            queue.rotate();
            throw new AssertionError("Expected the first Vulkan callback to fail");
        } catch (OutOfMemoryError expected) {
            check(expected == failure, "callback failure reaches the render loop");
        }
        for (int pass = 0; pass < 10; pass++) {
            queue.rotate();
            client.capture().frameEnd();
        }
        check(callbacks[0] == 0 && client.isCaptureProtected(), "Vulkan dropped the detached batch's sibling");
        client.recoverRendering();
        check(!client.isCaptureProtected(), "recovery releases the abandoned generation");
        var fresh = client.capture().begin(() -> callbacks[0]++);
        dropped.run();
        client.capture().frameEnd();
        check(callbacks[0] == 1 && client.isCaptureProtected(), "late old completion cannot release new capture");
        queue.add(fresh);
        queue.rotate();
        queue.rotate();
        check(callbacks[0] == 2 && client.isCaptureProtected(), "new generation can finish through Vulkan retirement");
        client.capture().frameEnd();
        check(!client.isCaptureProtected(), "new generation releases protection at frame end");
    }

    private static void badgeScreenshots() {
        QuietFrameClient client = new QuietFrameClient();
        int[] screenshots = {0};
        Runnable capture = () -> screenshots[0]++;
        check(!client.deferScreenshotForBadge(capture), "clean framebuffer accepts an immediate screenshot");
        client.markBadgeExtracted();
        client.finishFrame(null, false);
        check(client.deferScreenshotForBadge(capture) && client.isCaptureProtected(),
                "request before extraction waits for the next badge-free frame");
        client.finishFrame(null, false);
        check(screenshots[0] == 1 && !client.isCaptureProtected(), "request drains after a clean frame");

        client.markBadgeExtracted();
        check(client.deferScreenshotForBadge(capture), "request after badge extraction also defers");
        client.finishFrame(null, false);
        check(screenshots[0] == 1 && client.isCaptureProtected(), "frame that already extracted the badge cannot drain");
        check(client.deferScreenshotForBadge(capture), "another request joins before the next extraction");
        client.finishFrame(null, false);
        check(screenshots[0] == 3 && !client.isCaptureProtected(), "next clean frame drains both requests");
    }

    private static void statusFps() {
        check(Integer.valueOf(260).equals(QuietFrameClient.effectiveFpsLabel(260, 260)),
                "QuietFrame 260 FPS is numeric");
        Object unlimited = QuietFrameClient.effectiveFpsLabel(-1, 260);
        check(unlimited instanceof Component component
                && component.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("quietframe.status.unlimited"), "vanilla 260 uses unlimited label");
        check(Integer.valueOf(120).equals(QuietFrameClient.effectiveFpsLabel(-1, 120)), "finite vanilla FPS stays numeric");
    }

    private static void conflicts() {
        check(QuietFrameMixinPlugin.conflict(Set.of("moreculling", "sodium", "dynamicfps")::contains) == null,
                "unrelated mods allow all hooks");
        check("dynamic_fps".equals(QuietFrameMixinPlugin.conflict(Set.of("dynamic_fps")::contains)),
                "Dynamic FPS gates hooks");
        check("fpsreducer".equals(QuietFrameMixinPlugin.conflict(Set.of("fpsreducer")::contains)),
                "FPS Reducer gates hooks");
    }

    static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
        checks++;
    }
}
