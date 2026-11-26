#!/usr/bin/env python3
"""Draw the QuietFrame README banner with Pillow and local TrueType fonts."""

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from icons import ROOT, render_icon

BANNER = ROOT / "docs/images/banner.png"
WIDTH, HEIGHT = 1280, 420
SCALE = 2


def load_font(path, default, size):
    try:
        return ImageFont.truetype(str(path or default), size * SCALE)
    except OSError as error:
        raise SystemExit(f"Install DejaVu Sans or supply a font file for {path or default}.") from error


def render(sans=None, bold=None):
    def box(*coordinates):
        return tuple(round(coordinate * SCALE) for coordinate in coordinates)

    image = Image.new("RGBA", box(WIDTH, HEIGHT), "#101329")
    pools = Image.new("RGBA", image.size)
    light = ImageDraw.Draw(pools)
    light.ellipse(box(-84, -224, 605, 380), fill="#2c2b5b")
    light.ellipse(box(817, 159, 1520, 644), fill="#16383f")
    image.alpha_composite(pools.filter(ImageFilter.GaussianBlur(100 * SCALE)))
    draw = ImageDraw.Draw(image)

    def text(x, y, value, font, color):
        draw.text(box(x, y), value, font=font, fill=color, anchor="lt")

    for margin in (23, 35):
        draw.rounded_rectangle(box(margin, margin, WIDTH - margin, HEIGHT - margin),
                               radius=34 * SCALE, outline="#303752", width=SCALE)
    draw.line([box(349, 78), box(349, 342)], fill="#414968", width=SCALE)
    image.alpha_composite(render_icon(228 * SCALE), dest=box(80, 96))

    hero = load_font(bold, "DejaVuSans-Bold.ttf", 82)
    subtitle = load_font(sans, "DejaVuSans.ttf", 22)
    number = load_font(bold, "DejaVuSans-Bold.ttf", 32)
    label_font = load_font(bold, "DejaVuSans-Bold.ttf", 15)
    detail_font = load_font(sans, "DejaVuSans.ttf", 12)
    text(398, 76, "QuietFrame", hero, "#f1f2ff")
    text(402, 177, "Background Frame-Rate & Power Saver for Minecraft 26.3 Vulkan",
         subtitle, "#c9d4eb")

    metrics = (
        ("-85%", "Background Power", "RTX 2080 · unfocused after hover", "#aaf8df"),
        ("17.4 W", "Minimized", "RTX 2080 · 1 FPS", "#aaf8df"),
        ("20.0 TPS", "Preserved", "Client ticks · minimized", "#c1c6ff"),
    )
    for index, (value, label, detail, color) in enumerate(metrics):
        x = 402 + index * 253
        draw.rounded_rectangle(box(x, 235, x + 235, 343), radius=14 * SCALE,
                               outline="#43516d", fill="#1b253e", width=SCALE)
        text(x + 17, 250, value, number, color)
        text(x + 18, 291, label, label_font, "#e3e8f7")
        text(x + 18, 316, detail, detail_font, "#b3c2dd")

    return image.convert("RGB").resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sans-font", type=Path)
    parser.add_argument("--bold-font", type=Path)
    args = parser.parse_args()
    BANNER.parent.mkdir(parents=True, exist_ok=True)
    render(args.sans_font, args.bold_font).save(BANNER, optimize=True)
    print(BANNER.relative_to(ROOT))


if __name__ == "__main__":
    main()
