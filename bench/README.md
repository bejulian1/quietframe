# QuietFrame client benchmark

This observer launches a fresh Minecraft 26.3 singleplayer client with Fabric Loader 0.19.5, Fabric API 0.164.0+26.3, Java 25 and the Vulkan renderer. Every profile uses the same seed-263 Overworld courtyard, fixed camera, paused daylight clock at noon, hidden HUD, ambient beacon loop and 1280×720 window. The integrated server and client continue ticking. The saved master and ambient sliders are 75% and 50%. VSync and pause on focus loss are disabled and the user frame limit is 260.

## Run

Run these commands from the project root with an offline Loom cache, JDK 25 and Minecraft assets available. The default cache is `GRADLE_USER_HOME` or `~/.gradle`. The disposable game and live protocol files use the system temporary directory. The runner archives each launch on completion. The runner accepts `--gradle-home`, `--java-home`, `--mods-dir` and individual `--<mod>-jar` overrides. It discovers distribution JARs in sibling project builds and PrismLauncher instances, reads their Fabric metadata, hashes the inputs and stages copies in a disposable game directory. Keep an unlocked desktop dedicated to the measurement while the controller moves the pointer and changes focus.

```sh
(cd bench && ./gradlew --offline --no-daemon compileJava)
python3 tools/run_bench.py --probe-desktop
python3 tools/run_bench.py --vulkan-desktop --phase-sec 10 --reps 1
python3 tools/run_bench.py --vulkan-desktop --phase-sec 20 --reps 2 --out bench/build/full-paired
```

The four profiles are `baseline`, `quietframe`, `stack-baseline` and `stack`. The stack includes Sodium, Lithium, FerriteCore, Lightshade, SwiftDraw, TickSight and LuxStride, with the Daylight shader enabled. The treatment adds QuietFrame as the eighth mod. Each pair reverses its launch order on even repetitions. To select the smaller pair, pass `--profiles baseline,quietframe`. The default minimized cap is 1 FPS. Pass `--skip-render-when-minimized` to exercise complete render suspension. Both arms use the same desktop actions and fixture. The AFK timeout is four phase lengths plus 15 seconds, allowing blur, hover and leave to finish before the focused AFK test.

Desktop automation uses libX11 and xprop. The XTest input backend also requires libXtst. The probe checks the active window, pointer position, actual receipt of an F8 keypress, the window manager's iconic state and restoration. On GNOME/XWayland the fallback uses Mutter's virtual seat via Python GObject, a single primary monitor, and xrandr. It uses the monitor stream for pointer coordinate mapping. The runner checks the SDL driver, obtains the actual game XID from SDL and verifies observed window state before scoring. Rejected transitions are recorded in the run report. A free area outside the game rectangle is needed for the focus sink. NVIDIA telemetry requires nvidia-smi and the Linux proc filesystem. PNG comparison requires Pillow. The desktop lock covers probing and all paired launches.

## Measurements

Each launch runs focused, blurred with the pointer outside, hovered without keyboard focus, pointer outside again, minimized, restored, AFK and keyboard wake. The movie clock in the Daylight shader uses a fixed value in both stack arms. Real input updates the vanilla inactivity timestamp. Captures use the game's GPU readback after a fresh focused present before blur and after restore.

The observer records successful application present returns and render attempts separately. It counts client ticks and successful presents in half-open monotonic windows. Tick events close the measurement even with zero presents. FPS is count divided by elapsed window duration. p95 uses nearest rank on wholly contained inter-present intervals and is empty for fewer than two presents. Worst tick gap includes both clipped window edges. Per-tick CSVs include the raw SDL flags and pixel extent, listener gain from the current OpenAL context, gameplay state, sound activity, last input and last present timestamps. The before-save and after-save files bracket an ordinary options save in the blur phase. The observer records SDL state, gain and sound activity immediately before and after that save. Byte hashes are compared against the initial canonical save, focused recovery and normal exit.

An independent sampler measures client-process CPU from Linux user/system ticks, including integrated-server threads. One saturated core contributes 100%. It verifies the process start time on every read. Board-wide utilization, watts, graphics/memory MHz and temperature come from the matched GPU UUID at 1 Hz. The runner aligns the client and controller clocks by minimum round trip, records the uncertainty, and integrates readings by overlap with each scored phase. Missing readings or excessive sampling gaps leave the affected metric empty.

## Results

By default results go into a new directory under `bench/build`. Each launch preserves its manifest, loaded mods, surface diagnostics, telemetry, clock calibration, transition acknowledgments, options snapshots and per-phase options hashes, PNGs, per-phase JSON, ticks, presents and client logs. The root contains the following files.

| File | Contents |
| --- | --- |
| summary.csv | Absolute phase measurements, verification status and any observed failures |
| paired.csv | Matched A/B observations, differences, validity and percentage changes |
| telemetry.csv | All raw board and process samples with profile, repetition and phase |
| comparisons.json | Paired observations, median percentage changes, paired bootstrap intervals, RGBA pixel counts and verification issues |

The runner checks 19.5 TPS and a worst tick gap below 150 ms, the configured background cap and minimized render budget, live audio gain, the options bytes, and four-channel pixel equality within and across arms. It retains raw measurements on a failed check and exits with status 2 on failed or unsupported cases. A one-pair smoke run has no confidence interval. Two pairs give a coarse estimate. NVIDIA board readings include competing desktop workloads. Surface diagnostics come from game and renderer logs and the observed swapchain status.

## Observer protocol

Each launch receives a fresh output directory and nonce. The controller atomically renames a JSON file to `command.json` containing `nonce`, a strictly increasing integer `seq` and an `op`. It waits for the matching `ack-<seq>.json` before issuing the next command. Supported operations are `ping`, `phase` with `name` and `seconds`, `capture` with `name` equal to `before` or `after`, `save` and `stop`. The observer publishes `client.json` with the real JVM PID and game XID, and refreshes `status.json` on the client thread. Timed commands acknowledge completion after results have been written. Independent controller deadlines catch stalled game ticks.
