package dev.quietframe.bench.mixin;

import dev.quietframe.bench.Observer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class PresentMixin {
    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void bench$render(boolean tick, CallbackInfo ci) {
        Observer.renderAttempt();
    }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER))
    private void bench$present(boolean tick, CallbackInfo ci) {
        Observer.presented((Minecraft)(Object)this);
    }
}
