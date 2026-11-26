package dev.quietframe.bench.mixin;

import dev.quietframe.bench.Observer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow protected abstract void setPosition(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
    private void bench$pose(DeltaTracker delta, CallbackInfo ci) {
        if (Observer.inFixture()) {
            setPosition(0.5, 78, 28.5);
            setRotation(180, 15);
        }
    }
}
