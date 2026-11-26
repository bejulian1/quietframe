# QuietFrame architecture

QuietFrame is a client-only Fabric mod for Minecraft 26.3. `QuietFrameClient` owns settings and runtime state. `FramePolicy` selects the requested render cap, and `RenderCadence` schedules complete frames. Audio and capture handling keep separate state for their native resources.

## Render and service cadence

`MinecraftMixin` wraps the call to `Minecraft.renderFrame(Z)` inside `Minecraft.runTick(Z)`. Focused and hover-awake frames use the normal renderer, as do loading, protected capture and unavailable-window states. With a QuietFrame cap active, a monotonic deadline decides when the next complete frame is due. QuietFrame takes the lower of its requested cap and the user's frame limit, and avoids a second limiter sleep on frames it paces.

Between frames, the outer loop continues servicing game ticks and SDL events. The skipped-render branch still invokes vanilla's pause check and drains pending render-thread tasks. When the game has loaded and a game tick is due, it runs one `ClientLevel.update()` for the active level. In Minecraft 26.3 this polls the client light queue and runs one light-engine update. The single update per tick bounds the cadence of light draining. Each park is capped at 15 ms and accounts for time already spent on service work.

With `skip_render_when_minimized` enabled, a hidden or minimized game window has a zero render budget. When the surface is unacquired, QuietFrame submits the persistent command encoder initially and then at intervals of at least 250 ms. This offscreen submission advances the Vulkan timeline for fence retirement without acquiring or presenting a swapchain image. Loading, capture and restore can resume full rendering through the normal path.

## SDL3 window state

`WindowMixin` filters SDL3 window events by the ID of the native game window. Matching events invalidate cached flags, and focus gain, restore, maximize and show events reset render pacing and the wake clock. `GameWindowState` reads input focus, mouse focus, and hidden or minimized flags via `SDL_GetWindowFlags`. An unfocused exclusive-fullscreen window uses the minimized FPS cap. True render suspension requires the hidden or minimized flag. AFK detection uses Minecraft's latest input timestamp together with the last window wake.

## Screenshot protection

`ScreenshotMixin` starts `CaptureProtection` when Minecraft enqueues the texture-to-buffer readback. In-flight captures and the frame that observes completion bypass throttling. Completion is idempotent, including synchronous submission failures. Recovery on emergency save, world teardown or close increments the capture generation, so a late callback from a previous generation cannot release a current capture.

If the optional badge has been extracted for the current frame or is already in the framebuffer, F2 readback waits for a badge-free frame. A queued request suppresses badge extraction and keeps full frames running until the readback can use the fresh render target.

## OpenAL listener gain

`SoundEngineMixin` records the current OpenAL context when the sound library loads. The controller writes `AL_GAIN` on the render thread only while the recorded nonzero context is current and the desired multiplier changes. It samples the base gain on the first transition away from neutral, uses that same base throughout attenuation, and reapplies it on return to neutral or before context teardown. Focused play uses the neutral multiplier, visible unfocused play uses the unfocused multiplier, and hidden or minimized play uses the minimized multiplier. Hover affects rendering while keyboard focus governs visible-window audio. Vanilla keeps ownership of volume controls, options saves and source playback.

## Configuration and conflicts

`QuietFrameConfig` validates the JSON settings and atomically replaces the file where supported. The client activates the new settings after a successful save or reload. The pause override is opt-in and intercepts the vanilla auto-pause call while QuietFrame is enabled.

`QuietFrameMixinPlugin` checks for `dynamic_fps` and `fpsreducer` before mixin application. Either mod disables all QuietFrame mixins, keybind and badge registration. The client status command reports the detected conflict. Fabric Knot smoke tests exercise normal mixin application and startup with each conflict ID.

See the benchmark observer for the presentation, tick, gain, screenshot and options checks. [Benchmark methodology](../bench/README.md)
