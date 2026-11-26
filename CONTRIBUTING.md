# Contributing

Use a bug report for incorrect behavior and a feature request for changes to how QuietFrame handles rendering, window state or audio. Include Minecraft, Fabric Loader, Fabric API and QuietFrame versions plus the operating system, GPU driver and relevant mods. For focus or minimize problems, describe the exact sequence of pointer movement, focus changes, window state and input. [Issue forms](https://github.com/bejulian1/quietframe/issues/new/choose)

Run the build and verification commands from the repository root with Python 3.11+, JDK 25 and a populated Prism Launcher cache. [Build prerequisites](README.md#build-and-test)

```sh
python3 tools/build.py
python3 tools/test.py
```

For changes that affect window transitions, rendering, capture or sound, describe what you tested on a real Vulkan client. For performance comparisons, include the fixture, phase length, run order and phase validity. [Benchmark methodology](bench/README.md) · [Architecture](docs/ARCHITECTURE.md)

To regenerate the artwork, install Pillow and the DejaVu Sans fonts, then run the generators from the repository root.

```sh
python3 tools/icons.py
python3 tools/render_docs_images.py
```

Keep pull requests focused and describe expected behavior, test results and any follow-up work. Remove credentials, account identifiers and local paths before attaching logs or benchmark artifacts.
