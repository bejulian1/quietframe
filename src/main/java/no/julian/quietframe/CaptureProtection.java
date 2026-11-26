package no.julian.quietframe;

/** Extends screenshot protection through the frame that observes the readback's completion. */
public final class CaptureProtection {
    private volatile boolean protectedFrame;
    private int pending;
    private boolean completed;
    private long generation;

    public synchronized Completion begin(Runnable callback) {
        Completion completion = new Completion(callback, generation);
        pending++;
        protectedFrame = true;
        return completion;
    }

    public boolean isActive() {
        return protectedFrame;
    }

    public synchronized void frameEnd() {
        if (completed) {
            completed = false;
            protectedFrame = pending > 0;
        }
    }

    public synchronized void reset() {
        generation++;
        pending = 0;
        completed = false;
        protectedFrame = false;
    }

    public final class Completion implements Runnable {
        private final Runnable callback;
        private final long startedIn;
        private boolean finished;

        private Completion(Runnable callback, long startedIn) {
            this.callback = callback;
            this.startedIn = startedIn;
        }

        @Override
        public void run() {
            try {
                callback.run();
            } finally {
                finish();
            }
        }

        public void finish() {
            synchronized (CaptureProtection.this) {
                if (!finished && startedIn == generation) {
                    finished = true;
                    pending--;
                    completed = true;
                }
            }
        }
    }
}
