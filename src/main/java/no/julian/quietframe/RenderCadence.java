package no.julian.quietframe;

import no.julian.quietframe.FramePolicy.State;

/** Keeps the game servicing events and ticks between complete vanilla frames. */
public final class RenderCadence {
    public static final long MAX_SERVICE_PARK_NS = 15_000_000L;
    static final long SUSPENDED_SUBMIT_INTERVAL_NS = 250_000_000L;
    private long nextRenderDeadlineNs;
    private long nextSuspendedSubmitNs;
    private boolean submittedWhileSuspended;
    private State lastState;
    private int lastFps;

    public void reset() {
        if (lastState != null) {
            nextRenderDeadlineNs = 0;
            lastState = null;
            submittedWhileSuspended = false;
        }
    }

    /** Returns zero when a frame is due, otherwise the maximum park before another service pass. */
    public long plan(long now, State state, int fps) {
        if (fps < 0) {
            reset();
            return 0;
        }
        boolean changed = state != lastState || fps != lastFps;
        lastState = state;
        lastFps = fps;
        if (fps == 0) {
            return MAX_SERVICE_PARK_NS;
        }

        submittedWhileSuspended = false;
        long interval = 1_000_000_000L / fps;
        long remaining = nextRenderDeadlineNs - now;
        if (!changed && remaining > 0) {
            return Math.min(remaining, MAX_SERVICE_PARK_NS);
        }
        // Preserve phase for small wake jitter and leave at least three quarters of a period after a due frame.
        nextRenderDeadlineNs = changed || remaining < -(interval / 4) ? now + interval : nextRenderDeadlineNs + interval;
        return 0;
    }

    void submitWhileSuspended(long now, Runnable submit) {
        if (lastState != null && lastFps == 0
                && (!submittedWhileSuspended || now - nextSuspendedSubmitNs >= 0)) {
            submit.run();
            submittedWhileSuspended = true;
            nextSuspendedSubmitNs = now + SUSPENDED_SUBMIT_INTERVAL_NS;
        }
    }
}
