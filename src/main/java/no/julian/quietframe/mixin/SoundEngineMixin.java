package no.julian.quietframe.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import no.julian.quietframe.QuietFrameClient;
import no.julian.quietframe.audio.QuietAudioController;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {
    @Shadow
    private boolean loaded;

    @Unique
    private final QuietAudioController quietframe$audio = new QuietAudioController();

    @Inject(method = "loadLibrary()V", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/client/sounds/SoundEngine;loaded:Z", shift = At.Shift.AFTER), require = 1, allow = 1)
    private void quietframe$captureContext(CallbackInfo callback) {
        quietframe$audio.loaded(RenderSystem.isOnRenderThread() ? ALC10.alcGetCurrentContext() : 0);
        QuietFrameClient client = QuietFrameClient.get();
        if (client != null) {
            client.setAppliedAudioMultiplier(1);
        }
    }

    @Inject(method = "updateSource(Lnet/minecraft/client/Camera;)V", at = @At("HEAD"), require = 1, allow = 1)
    private void quietframe$updateGain(Camera camera, CallbackInfo callback) {
        QuietFrameClient client = QuietFrameClient.get();
        if (loaded && client != null && RenderSystem.isOnRenderThread()) {
            quietframe$applyGain(client.desiredAudioMultiplier(Minecraft.getInstance()));
            client.setAppliedAudioMultiplier(quietframe$audio.appliedMultiplier());
        }
    }

    @Inject(method = {"destroy()V", "emergencyShutdown()V"}, at = @At("HEAD"), require = 2, allow = 2)
    private void quietframe$restoreGain(CallbackInfo callback) {
        if (loaded && RenderSystem.isOnRenderThread()) {
            quietframe$applyGain(1);
            quietframe$audio.loaded(0);
            QuietFrameClient client = QuietFrameClient.get();
            if (client != null) {
                client.setAppliedAudioMultiplier(1);
            }
        }
    }

    @Unique
    private void quietframe$applyGain(float multiplier) {
        if (quietframe$audio.needsUpdate(multiplier)) {
            quietframe$audio.update(ALC10.alcGetCurrentContext(), multiplier, () -> AL10.alGetListenerf(AL10.AL_GAIN),
                    gain -> AL10.alListenerf(AL10.AL_GAIN, (float) gain));
        }
    }
}
