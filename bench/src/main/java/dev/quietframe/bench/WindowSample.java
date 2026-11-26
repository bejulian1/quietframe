package dev.quietframe.bench;

import dev.quietframe.bench.mixin.ActivityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.util.Util;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;

record WindowSample(long ns, long flags, boolean focused, boolean iconified, boolean hovered,
                    int width, int height, long lastInputMs, long idleMs, Float gain,
                    boolean soundPlaying, boolean playing, long lastPresentNs) {
    static WindowSample read(Minecraft client, SoundInstance loop, long lastPresent) {
        var window = client.getWindow();
        long flags = SDLVideo.SDL_GetWindowFlags(window.handle());
        long last = ((ActivityAccessor)client.getFramerateLimitTracker()).bench$latestInputTime();
        try (var stack = MemoryStack.stackPush()) {
            var w = stack.mallocInt(1);
            var h = stack.mallocInt(1);
            boolean extent = SDLVideo.SDL_GetWindowSizeInPixels(window.handle(), w, h);
            Float gain = ALC10.alcGetCurrentContext() == 0 ? null : AL10.alGetListenerf(AL10.AL_GAIN);
            return new WindowSample(System.nanoTime(), flags, (flags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0,
                    (flags & SDLVideo.SDL_WINDOW_MINIMIZED) != 0, (flags & SDLVideo.SDL_WINDOW_MOUSE_FOCUS) != 0,
                    extent ? w.get(0) : -1, extent ? h.get(0) : -1, last, Util.getMillis() - last, gain,
                    client.getSoundManager().isActive(loop), client.level != null && !client.isPaused()
                    && client.gui.screen() == null && client.gui.overlay() == null, lastPresent);
        }
    }
}
