package no.julian.quietframe.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import no.julian.quietframe.CaptureProtection;
import no.julian.quietframe.QuietFrameClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.File;
import java.util.function.Consumer;

@Mixin(Screenshot.class)
abstract class ScreenshotMixin {
    @WrapOperation(method = "grab(Lnet/minecraft/client/Minecraft;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;"
                    + "Ljava/util/function/Consumer;)V"), require = 1, allow = 1)
    private static void quietframe$excludeBadge(File directory, RenderTarget target, Consumer<Component> consumer,
                                                Operation<Void> original, Minecraft minecraft) {
        QuietFrameClient client = QuietFrameClient.get();
        if (client != null && client.deferScreenshotForBadge(
                () -> original.call(directory, minecraft.gameRenderer.mainRenderTarget(), consumer))) {
            return;
        }
        original.call(directory, target, consumer);
    }

    @WrapOperation(method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;"
                    + "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;"
                    + "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;I)V"), require = 1, allow = 1)
    private static void quietframe$protectReadback(CommandEncoder encoder, GpuTexture texture, GpuBuffer buffer,
                                                   long offset, Runnable callback, int mipLevel, Operation<Void> original) {
        QuietFrameClient client = QuietFrameClient.get();
        if (client == null) {
            original.call(encoder, texture, buffer, offset, callback, mipLevel);
            return;
        }
        CaptureProtection.Completion completion = client.capture().begin(callback);
        try {
            original.call(encoder, texture, buffer, offset, completion, mipLevel);
        } catch (RuntimeException | Error error) {
            completion.finish();
            throw error;
        }
    }
}
