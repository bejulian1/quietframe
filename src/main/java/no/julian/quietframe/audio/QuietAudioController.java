package no.julian.quietframe.audio;

import no.julian.quietframe.config.QuietFrameConfig;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public final class QuietAudioController {
    private long context;
    private float appliedMultiplier = 1;
    private float baseGain = 1;

    public static float multiplier(QuietFrameConfig config, boolean loading, boolean focused,
                                   boolean actuallyMinimized) {
        if (!config.enabled() || !config.audioAttenuation() || loading) {
            return 1;
        }
        if (actuallyMinimized) {
            return config.minimizedVolumeMultiplier();
        }
        return focused ? 1 : config.unfocusedVolumeMultiplier();
    }

    public void loaded(long currentContext) {
        context = currentContext;
        appliedMultiplier = 1;
        baseGain = 1;
    }

    public float appliedMultiplier() {
        return appliedMultiplier;
    }

    public boolean needsUpdate(float multiplier) {
        return context != 0 && appliedMultiplier != multiplier;
    }

    public void update(long currentContext, float multiplier, DoubleSupplier getGain, DoubleConsumer setGain) {
        if (!needsUpdate(multiplier) || context != currentContext) {
            return;
        }
        if (appliedMultiplier == 1) {
            baseGain = (float) getGain.getAsDouble();
        }
        setGain.accept(baseGain * multiplier);
        appliedMultiplier = multiplier;
    }
}
