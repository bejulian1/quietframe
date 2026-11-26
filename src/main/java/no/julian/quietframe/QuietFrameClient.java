package no.julian.quietframe;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.client.gui.screens.worldselection.FileFixerProgressScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import no.julian.quietframe.FramePolicy.State;
import no.julian.quietframe.audio.QuietAudioController;
import no.julian.quietframe.config.QuietFrameConfig;
import no.julian.quietframe.config.QuietFrameMixinPlugin;
import no.julian.quietframe.mixin.FramerateLimitTrackerAccessor;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

public final class QuietFrameClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuietFrame");
    private static volatile QuietFrameClient instance;

    private final RenderCadence cadence = new RenderCadence();
    private final CaptureProtection capture = new CaptureProtection();
    private final ArrayDeque<Runnable> badgeScreenshotRequests = new ArrayDeque<>();
    private QuietFrameConfig config = QuietFrameConfig.DEFAULTS;
    private Path configFile;
    private String conflict;
    private Window sampledWindow;
    private long lastWakeMillis;
    private State state = State.BYPASSED;
    private Bypass bypass = Bypass.LOADING;
    private int requestedFps = -1;
    private int effectiveFps = -1;
    private float appliedAudioMultiplier = 1;
    private Component badge;
    private boolean badgeExtracted;
    private boolean badgeOnFramebuffer;

    public static QuietFrameClient get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        configFile = loader.getConfigDir().resolve("quietframe.json");
        conflict = QuietFrameMixinPlugin.conflict(loader::isModLoaded);
        try {
            if (Files.notExists(configFile)) {
                config.save(configFile);
            } else {
                reload();
            }
        } catch (IOException error) {
            LOGGER.warn("Could not load QuietFrame settings. Using defaults.", error);
        }
        lastWakeMillis = Util.getMillis();
        instance = this;
        QuietFrameCommand.register(this);
        if (conflict == null) {
            registerControls();
        } else {
            LOGGER.info("QuietFrame is inactive because {} is installed.", conflict);
        }
    }

    private void registerControls() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("quietframe", "controls"));
        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.quietframe.toggle", InputConstants.UNKNOWN.getValue(), category));
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            while (toggle.consumeClick()) {
                minecraft.gui.hud.setOverlayMessage(QuietFrameCommand.toggle(this), false);
            }
        });
        // Inherit vanilla's in-game visibility condition, including F1.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR,
                Identifier.fromNamespaceAndPath("quietframe", "status"), (graphics, delta) -> drawBadge(graphics));
    }

    private void drawBadge(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!config.enabled() || !config.showStatusBadge() || minecraft.gui.screen() != null
                || isLoading(minecraft) || isCaptureProtected()) {
            return;
        }
        if (badge == null) {
            badge = effectiveFps < 0
                    ? Component.translatable("quietframe.badge", stateName())
                    : Component.translatable("quietframe.badge.fps", stateName(), effectiveFps);
        }
        markBadgeExtracted();
        graphics.text(minecraft.font, badge, 5, 5, 0xBACDD3DA);
    }

    void markBadgeExtracted() {
        badgeExtracted = true;
    }

    public void onWindowWake(Window window) {
        if (window == sampledWindow) {
            lastWakeMillis = Util.getMillis();
            cadence.reset();
        }
    }

    private GameWindowState sampleWindow(Minecraft minecraft) {
        Window window = minecraft.getWindow();
        if (window == null) {
            return null;
        }
        var snapshot = ((GameWindowState.Access) (Object) window).quietframe$getWindowState();
        boolean wasFocused = snapshot.focused();
        boolean wasMinimized = snapshot.minimized();
        if (!snapshot.sample(window.handle(), SDLVideo::SDL_GetWindowID, SDLVideo::SDL_GetWindowFlags)) {
            return null;
        }
        if (window != sampledWindow || (snapshot.focused() && !wasFocused) || (!snapshot.minimized() && wasMinimized)) {
            lastWakeMillis = Util.getMillis();
        }
        sampledWindow = window;
        return snapshot;
    }

    private static boolean isLoading(Minecraft minecraft) {
        if (!minecraft.isGameLoadFinished() || minecraft.gui == null || minecraft.gui.overlay() != null) {
            return true;
        }
        Screen screen = minecraft.gui.screen();
        return screen instanceof ProgressScreen || screen instanceof LevelLoadingScreen || screen instanceof ConnectScreen
                || screen instanceof ServerReconfigScreen || screen instanceof FileFixerProgressScreen;
    }

    private void refreshPolicy(Minecraft minecraft) {
        GameWindowState window;
        if (conflict != null) {
            setPolicy(State.BYPASSED, Bypass.CONFLICT, -1, -1);
        } else if (!config.enabled()) {
            setPolicy(State.BYPASSED, Bypass.DISABLED, -1, -1);
        } else if (isLoading(minecraft)) {
            lastWakeMillis = Util.getMillis();
            setPolicy(State.BYPASSED, Bypass.LOADING, -1, -1);
        } else if (isCaptureProtected()) {
            setPolicy(State.BYPASSED, Bypass.CAPTURE, -1, -1);
        } else if ((window = sampleWindow(minecraft)) == null) {
            setPolicy(State.BYPASSED, Bypass.WINDOW, -1, -1);
        } else {
            boolean focused = window.focused();
            boolean actuallyMinimized = window.minimized();
            boolean exclusive = !focused && !actuallyMinimized && minecraft.getWindow().isExclusiveFullscreen();
            boolean hovered = window.hovered();
            boolean idle = config.afkEnabled() && !actuallyMinimized && !exclusive
                    && (focused || !hovered || !config.hoverWakeup()) && isIdle(minecraft);
            State next = FramePolicy.resolve(config, false, focused, actuallyMinimized, exclusive, hovered, idle);
            int requested = FramePolicy.requestedFps(config, next, actuallyMinimized);
            int effective = requested > 0 ? Math.min(requested, minecraft.options.framerateLimit().get()) : requested;
            setPolicy(next, Bypass.NONE, requested, effective);
        }
    }

    private boolean isIdle(Minecraft minecraft) {
        long lastInput = ((FramerateLimitTrackerAccessor) minecraft.getFramerateLimitTracker()).quietframe$getLatestInputTime();
        return Util.getMillis() - Math.max(lastInput, lastWakeMillis) >= config.afkTimeoutSeconds() * 1_000L;
    }

    private void setPolicy(State next, Bypass reason, int requested, int effective) {
        if (state != next || effectiveFps != effective) {
            badge = null;
        }
        state = next;
        bypass = reason;
        requestedFps = requested;
        effectiveFps = effective;
    }

    /** Returns -1 for a vanilla frame, 0 for a due QuietFrame-paced frame, or a park duration. */
    public long planRender(Minecraft minecraft) {
        refreshPolicy(minecraft);
        if (effectiveFps < 0) {
            cadence.reset();
            return -1;
        }
        return cadence.plan(System.nanoTime(), state, effectiveFps);
    }

    public void serviceSkippedRender(Minecraft minecraft, boolean tick, boolean surfaceAcquired) {
        serviceSkippedLighting(minecraft.isGameLoadFinished(), tick, minecraft.level);
        if (!surfaceAcquired) {
            cadence.submitWhileSuspended(System.nanoTime(), QuietFrameClient::submitOffscreen);
        }
        RenderSystem.executePendingTasks();
    }

    static void serviceSkippedLighting(boolean gameLoadFinished, boolean tick, ClientLevel level) {
        if (gameLoadFinished && tick && level != null) {
            // In 26.3 this is exactly pollLightUpdates() followed by one light-engine update.
            level.update();
        }
    }

    static void submitOffscreen() {
        // The persistent encoder signals its Vulkan timeline even with no swapchain image acquired.
        RenderSystem.getDevice().createCommandEncoder().submit();
    }

    public CaptureProtection capture() {
        return capture;
    }

    public void recoverRendering() {
        capture.reset();
        badgeScreenshotRequests.clear();
        badgeOnFramebuffer |= badgeExtracted;
        badgeExtracted = false;
        cadence.reset();
    }

    public boolean isCaptureProtected() {
        return capture.isActive() || !badgeScreenshotRequests.isEmpty();
    }

    public boolean deferScreenshotForBadge(Runnable screenshot) {
        if (!badgeOnFramebuffer && !badgeExtracted) {
            return false;
        }
        badgeScreenshotRequests.addLast(screenshot);
        return true;
    }

    public boolean needsFrameFinish() {
        return badgeOnFramebuffer || badgeExtracted || isCaptureProtected();
    }

    public boolean finishFrame(Minecraft minecraft, boolean pacedFrame) {
        badgeOnFramebuffer = badgeExtracted;
        badgeExtracted = false;
        if (isCaptureProtected()) {
            capture.frameEnd();
            if (!badgeOnFramebuffer) {
                Runnable screenshot;
                while ((screenshot = badgeScreenshotRequests.pollFirst()) != null) {
                    screenshot.run();
                }
            }
            cadence.reset();
            return false;
        }
        if (pacedFrame && (!config.enabled() || isLoading(minecraft))) {
            cadence.reset();
            return false;
        }
        return pacedFrame;
    }

    public boolean overridePause() {
        return config.enabled() && config.pauseOnLostFocusOverride();
    }

    public float desiredAudioMultiplier(Minecraft minecraft) {
        if (!config.enabled() || !config.audioAttenuation()) {
            return 1;
        }
        GameWindowState window = minecraft == null || isLoading(minecraft) ? null : sampleWindow(minecraft);
        return window == null ? 1 : QuietAudioController.multiplier(config, false, window.focused(), window.minimized());
    }

    public void setAppliedAudioMultiplier(float multiplier) {
        appliedAudioMultiplier = multiplier;
    }

    QuietFrameConfig config() {
        return config;
    }

    String conflict() {
        return conflict;
    }

    void reload() throws IOException {
        config = QuietFrameConfig.load(configFile);
        cadence.reset();
    }

    void save(QuietFrameConfig next) throws IOException {
        next.save(configFile);
        config = next;
        cadence.reset();
    }

    Component status(Minecraft minecraft) {
        refreshPolicy(minecraft);
        Component reason = conflict == null
                ? Component.translatable(bypass.translationKey)
                : Component.translatable(bypass.translationKey, conflict);
        Object requested = requestedFps < 0 ? Component.translatable("quietframe.status.vanilla") : requestedFps;
        int vanillaFps = effectiveFps < 0 ? minecraft.getFramerateLimitTracker().getFramerateLimit() : 0;
        Object effective = effectiveFpsLabel(effectiveFps, vanillaFps);
        return Component.translatable("quietframe.command.status", stateName(), reason, requested, effective,
                Float.toString(appliedAudioMultiplier));
    }

    static Object effectiveFpsLabel(int quietFrameFps, int vanillaFps) {
        if (quietFrameFps >= 0) {
            return quietFrameFps;
        }
        return vanillaFps >= 260 ? Component.translatable("quietframe.status.unlimited") : vanillaFps;
    }

    private Component stateName() {
        return Component.translatable(state.translationKey());
    }

    static void logConfigError(IOException error) {
        LOGGER.warn("Could not update QuietFrame settings.", error);
    }

    private enum Bypass {
        NONE("quietframe.bypass.none"),
        CONFLICT("quietframe.bypass.conflict"),
        DISABLED("quietframe.bypass.disabled"),
        LOADING("quietframe.bypass.loading"),
        CAPTURE("quietframe.bypass.capture"),
        WINDOW("quietframe.bypass.window");

        private final String translationKey;

        Bypass(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
