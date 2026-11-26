package no.julian.quietframe;

import org.lwjgl.sdl.SDLVideo;

import java.util.function.LongToIntFunction;
import java.util.function.LongUnaryOperator;

/** Caches the SDL flags for one native game window. */
public final class GameWindowState {
    private int id;
    private long flags;
    private boolean dirty = true;
    private boolean closed;

    boolean sample(long handle, LongToIntFunction getId, LongUnaryOperator getFlags) {
        if (!resolveId(handle, getId)) {
            return false;
        }
        if (dirty) {
            flags = getFlags.applyAsLong(handle);
            dirty = false;
        }
        return true;
    }

    public boolean onEvent(long handle, int windowId, LongToIntFunction getId) {
        if (!resolveId(handle, getId) || id != windowId) {
            return false;
        }
        dirty = true;
        return true;
    }

    private boolean resolveId(long handle, LongToIntFunction getId) {
        if (closed || handle == 0) {
            return false;
        }
        if (id == 0) {
            id = getId.applyAsInt(handle);
        }
        return id != 0;
    }

    public void close() {
        closed = true;
    }

    boolean focused() {
        return (flags & SDLVideo.SDL_WINDOW_INPUT_FOCUS) != 0;
    }

    boolean minimized() {
        return (flags & (SDLVideo.SDL_WINDOW_MINIMIZED | SDLVideo.SDL_WINDOW_HIDDEN)) != 0;
    }

    boolean hovered() {
        return (flags & SDLVideo.SDL_WINDOW_MOUSE_FOCUS) != 0;
    }

    public interface Access {
        GameWindowState quietframe$getWindowState();
    }
}
