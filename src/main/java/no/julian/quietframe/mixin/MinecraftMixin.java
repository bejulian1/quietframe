package no.julian.quietframe.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import net.minecraft.client.Minecraft;
import no.julian.quietframe.QuietFrameClient;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.LockSupport;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow @Final
    private GpuSurface windowSurface;

    @Unique
    private boolean quietframe$pacedFrame;

    @Shadow
    private void pauseIfInactive() {
        throw new AssertionError();
    }

    @WrapOperation(method = "runTick(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"), require = 1, allow = 1)
    private void quietframe$renderOnSchedule(Minecraft minecraft, boolean tick, Operation<Void> original) {
        QuietFrameClient client = QuietFrameClient.get();
        long park = client == null ? -1 : client.planRender(minecraft);
        if (park < 0) {
            original.call(minecraft, tick);
        } else if (park == 0) {
            quietframe$pacedFrame = true;
            try {
                original.call(minecraft, tick);
            } finally {
                quietframe$pacedFrame = false;
            }
        } else {
            long deadline = System.nanoTime() + park;
            pauseIfInactive();
            client.serviceSkippedRender(minecraft, tick, windowSurface.isAcquired());
            long remaining = deadline - System.nanoTime();
            if (remaining > 0 && !client.isCaptureProtected()) {
                LockSupport.parkNanos(remaining);
            }
        }
    }

    @ModifyExpressionValue(method = "renderFrame(Z)V", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/state/GameRenderState;framerateLimit:I"), require = 1, allow = 1)
    private int quietframe$vanillaLimiter(int vanillaLimit) {
        QuietFrameClient client = QuietFrameClient.get();
        if (client != null && (quietframe$pacedFrame || client.needsFrameFinish())
                && client.finishFrame((Minecraft) (Object) this, quietframe$pacedFrame)) {
            return 260;
        }
        return vanillaLimit;
    }

    @Inject(method = {"emergencySave()V", "clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V", "close()V"},
            at = @At("HEAD"), require = 3, allow = 3)
    private void quietframe$recoverRendering(CallbackInfo callback) {
        QuietFrameClient client = QuietFrameClient.get();
        if (client != null) {
            client.recoverRendering();
        }
    }

    @Inject(method = "pauseIfInactive()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"), cancellable = true, require = 1, allow = 1)
    private void quietframe$overrideAutoPause(CallbackInfo callback) {
        QuietFrameClient client = QuietFrameClient.get();
        if (client != null && client.overridePause()) {
            callback.cancel();
        }
    }
}
