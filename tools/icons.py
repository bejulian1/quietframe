#!/usr/bin/env python3
"""Draw the QuietFrame icon with Pillow."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
ICON = ROOT / "src/main/resources/assets/quietframe/icon.png"


def render_icon(size=512):
    scale = 4
    extent = 256 * scale

    def box(*coordinates):
        return tuple(round(coordinate * scale) for coordinate in coordinates)

    image = Image.new("RGBA", (extent, extent), "#151730")
    pools = Image.new("RGBA", image.size)
    light = ImageDraw.Draw(pools)
    light.ellipse(box(-80, -65, 189, 166), fill="#49478a")
    light.ellipse(box(98, 106, 324, 339), fill="#185458")
    image.alpha_composite(pools.filter(ImageFilter.GaussianBlur(45 * scale)))

    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(box(5, 5, 250, 250), 48 * scale, outline="#6d76a1", width=2 * scale)

    # Opposing crop corners leave the aperture open on its long diagonal.
    for points, color in (
        (((53, 117), (53, 54), (116, 54)), "#afb5ff"),
        (((203, 139), (203, 202), (140, 202)), "#79e3cf"),
    ):
        draw.line([box(*point) for point in points], fill=color, width=11 * scale, joint="curve")
        for x, y in (points[0], points[-1]):
            draw.ellipse(box(x - 5.5, y - 5.5, x + 5.5, y + 5.5), fill=color)

    moon = Image.new("L", image.size)
    crescent = ImageDraw.Draw(moon)
    crescent.ellipse(box(75, 77, 175, 177), fill=255)
    crescent.ellipse(box(101, 60, 193, 152), fill=0)
    image.paste("#adf8de", (0, 0), moon)

    for x in (142, 164):
        draw.rounded_rectangle(box(x, 100, x + 12, 134), 4 * scale, fill="#adf8de")

    silhouette = Image.new("L", image.size)
    ImageDraw.Draw(silhouette).rounded_rectangle((0, 0, extent - 1, extent - 1), 52 * scale, fill=255)
    image.putalpha(silhouette)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main():
    ICON.parent.mkdir(parents=True, exist_ok=True)
    render_icon().save(ICON, optimize=True)
    print(ICON.relative_to(ROOT))


if __name__ == "__main__":
    main()
