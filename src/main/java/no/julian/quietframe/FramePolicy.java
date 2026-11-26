package no.julian.quietframe;

import no.julian.quietframe.config.QuietFrameConfig;

public final class FramePolicy {
    public enum State {
        BYPASSED("quietframe.state.bypassed"),
        MINIMIZED("quietframe.state.minimized"),
        HOVERED("quietframe.state.hovered"),
        AFK("quietframe.state.afk"),
        UNFOCUSED("quietframe.state.unfocused"),
        FOCUSED("quietframe.state.focused");

        private final String translationKey;

        State(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    private FramePolicy() {
    }

    public static State resolve(QuietFrameConfig config, boolean bypassed, boolean focused,
                                boolean actuallyMinimized, boolean exclusiveFullscreen, boolean hovered,
                                boolean idle) {
        if (!config.enabled() || bypassed) {
            return State.BYPASSED;
        }
        if (actuallyMinimized || (!focused && exclusiveFullscreen)) {
            return State.MINIMIZED;
        }
        if (!focused && hovered && config.hoverWakeup()) {
            return State.HOVERED;
        }
        if (idle && config.afkEnabled()) {
            return State.AFK;
        }
        return focused ? State.FOCUSED : State.UNFOCUSED;
    }

    /** Returns -1 for vanilla cadence and 0 for a suspended renderer. */
    public static int requestedFps(QuietFrameConfig config, State state, boolean actuallyMinimized) {
        return switch (state) {
            case MINIMIZED -> actuallyMinimized && config.skipRenderWhenMinimized() ? 0 : config.minimizedFps();
            case AFK -> config.afkFps();
            case UNFOCUSED -> config.unfocusedFps();
            default -> -1;
        };
    }
}
