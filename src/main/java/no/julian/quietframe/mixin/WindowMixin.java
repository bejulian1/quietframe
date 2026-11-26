package no.julian.quietframe.mixin;

import com.mojang.blaze3d.platform.Window;
import no.julian.quietframe.GameWindowState;
import no.julian.quietframe.QuietFrameClient;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FIRST;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_LAST;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_MAXIMIZED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_RESTORED;
import static org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_SHOWN;

@Mixin(Window.class)
abstract class WindowMixin implements GameWindowState.Access {
    @Unique
    private final GameWindowState quietframe$windowState = new GameWindowState();

    @Shadow
    public abstract long handle();

    @Override
    public GameWindowState quietframe$getWindowState() {
        return quietframe$windowState;
    }

    @Inject(method = "close()V", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLVideo;SDL_DestroyWindow(J)V"), require = 1, allow = 1)
    private void quietframe$markWindowClosed(CallbackInfo callback) {
        quietframe$windowState.close();
    }

    @Inject(method = "handleEvent(Lorg/lwjgl/sdl/SDL_Event;)V", at = @At("HEAD"),
            cancellable = true, require = 1, allow = 1)
    private void quietframe$filterWindowEvent(SDL_Event event, CallbackInfo callback) {
        int type = event.type();
        if (type < SDL_EVENT_WINDOW_FIRST || type > SDL_EVENT_WINDOW_LAST) {
            return;
        }
        if (!quietframe$windowState.onEvent(handle(), event.window().windowID(), SDLVideo::SDL_GetWindowID)) {
            callback.cancel();
            return;
        }
        if (type == SDL_EVENT_WINDOW_FOCUS_GAINED || type == SDL_EVENT_WINDOW_RESTORED
                || type == SDL_EVENT_WINDOW_MAXIMIZED || type == SDL_EVENT_WINDOW_SHOWN) {
            QuietFrameClient client = QuietFrameClient.get();
            if (client != null) {
                client.onWindowWake((Window) (Object) this);
            }
        }
    }
}
