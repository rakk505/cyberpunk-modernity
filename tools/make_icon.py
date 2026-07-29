#!/usr/bin/env python3
"""Render a 16x16 flat GUI icon (left side view) from a Bedrock geo.json."""
import sys

sys.path.insert(0, __import__("os").path.dirname(__file__))
from render_bbmodel import load_cubes, render  # noqa: E402

from PIL import Image  # noqa: E402


def main():
    geo, out = sys.argv[1], sys.argv[2]
    cubes = load_cubes(geo)
    # Render a large side view on transparent-ish bg then downscale to 16x16.
    img = render(cubes, "left", size=(256, 160), base_color=(38, 42, 50))
    # crop tight to non-background
    bg = img.getpixel((0, 0))
    px = img.load()
    W, H = img.size
    minx, miny, maxx, maxy = W, H, 0, 0
    for y in range(H):
        for x in range(W):
            if px[x, y] != bg:
                minx, miny = min(minx, x), min(miny, y)
                maxx, maxy = max(maxx, x), max(maxy, y)
    crop = img.crop((minx, miny, maxx + 1, maxy + 1))
    # paste centered into a square, then downscale
    side = max(crop.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    cx = (side - crop.size[0]) // 2
    cy = (side - crop.size[1]) // 2
    sq.paste(crop.convert("RGBA"), (cx, cy))
    # make the white-ish background transparent
    data = sq.load()
    for y in range(sq.size[1]):
        for x in range(sq.size[0]):
            r, g, b, a = data[x, y]
            if r > 230 and g > 230 and b > 230:
                data[x, y] = (0, 0, 0, 0)
    icon = sq.resize((16, 16), Image.NEAREST)
    icon.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
