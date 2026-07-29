#!/usr/bin/env python3
"""Retexture Yukimura's authored UV regions as a worn olive smart pistol."""

from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/cyberdeck"
GEO_PATH = ASSETS / "gun_geo/yukimura.geo.json"
UV_PATH = ASSETS / "textures/item/yukimura_uv.png"
ICON_PATH = ASSETS / "textures/item/yukimura.png"
SMART_LINK_PATH = ASSETS / "textures/item/smart_link.png"


def lerp_color(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def palette_color(
    luminance: float,
    shadow: tuple[int, int, int],
    mid: tuple[int, int, int],
    highlight: tuple[int, int, int],
) -> tuple[int, int, int]:
    if luminance < 0.5:
        return lerp_color(shadow, mid, luminance * 2.0)
    return lerp_color(mid, highlight, (luminance - 0.5) * 2.0)


def collect_uv_mask(image: Image.Image, geo: dict, bone_names: set[str]) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    draw = ImageDraw.Draw(mask)
    description = geo["minecraft:geometry"][0]["description"]
    sx = image.width / description["texture_width"]
    sy = image.height / description["texture_height"]
    for bone in geo["minecraft:geometry"][0]["bones"]:
        if bone.get("name") not in bone_names:
            continue
        for cube in bone.get("cubes", []):
            uv = cube.get("uv")
            if not isinstance(uv, dict):
                continue
            for face in uv.values():
                if not isinstance(face, dict) or "uv" not in face or "uv_size" not in face:
                    continue
                u, v = face["uv"]
                du, dv = face["uv_size"]
                x0 = math.floor(min(u, u + du) * sx)
                y0 = math.floor(min(v, v + dv) * sy)
                x1 = math.ceil(max(u, u + du) * sx)
                y1 = math.ceil(max(v, v + dv) * sy)
                if x1 > x0 and y1 > y0:
                    draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=255)
    return mask


def recolor_mask(
    image: Image.Image,
    mask: Image.Image,
    shadow: tuple[int, int, int],
    mid: tuple[int, int, int],
    highlight: tuple[int, int, int],
    *,
    wear: bool = False,
) -> None:
    pixels = image.load()
    mask_pixels = mask.load()
    for y in range(image.height):
        for x in range(image.width):
            if mask_pixels[x, y] == 0:
                continue
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            nr, ng, nb = palette_color(luminance, shadow, mid, highlight)
            if wear:
                noise = (x * 37 + y * 71 + x * y * 3) % 97
                if noise < 5 and luminance > 0.16:
                    nr, ng, nb = lerp_color((63, 66, 63), (165, 169, 162), luminance)
                elif noise in (11, 12, 13):
                    nr, ng, nb = lerp_color((19, 20, 16), (64, 66, 35), luminance)
            pixels[x, y] = nr, ng, nb, a


def draw_smart_screen(image: Image.Image, box: tuple[int, int, int, int], locked: bool) -> None:
    draw = ImageDraw.Draw(image)
    x0, y0, x1, y1 = box
    draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=(3, 11, 9, 255))
    border = (91, 105, 34, 255)
    glow = (locked and 154 or 212, locked and 238 or 173, locked and 112 or 54, 255)
    draw.rectangle((x0 + 3, y0 + 3, x1 - 4, y1 - 4), outline=border, width=2)
    cx = (x0 + x1) // 2
    cy = (y0 + y1) // 2
    radius = 14
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), outline=glow, width=2)
    draw.line((cx - 22, cy, cx - 6, cy), fill=glow, width=2)
    draw.line((cx + 6, cy, cx + 22, cy), fill=glow, width=2)
    draw.line((cx, cy - 22, cx, cy - 6), fill=glow, width=2)
    draw.line((cx, cy + 6, cx, cy + 22), fill=glow, width=2)
    draw.rectangle((cx - 2, cy - 2, cx + 2, cy + 2), fill=glow)


def retexture_uv() -> None:
    geo = json.loads(GEO_PATH.read_text())
    image = Image.open(UV_PATH).convert("RGBA")

    recolor_mask(image, collect_uv_mask(image, geo, {"lower"}),
                 (22, 24, 25), (113, 118, 119), (213, 215, 209))
    recolor_mask(image, collect_uv_mask(image, geo, {"grip"}),
                 (4, 5, 5), (28, 31, 31), (71, 76, 73))
    recolor_mask(image, collect_uv_mask(image, geo, {"barrel"}),
                 (54, 3, 5), (145, 15, 20), (224, 42, 43))
    recolor_mask(image, collect_uv_mask(image, geo, {"upper"}),
                 (38, 39, 15), (119, 121, 48), (190, 188, 87), wear=True)

    # The two authored illuminated-screen sheets are 32x32 UV units on a 2x atlas.
    draw_smart_screen(image, (128, 0, 192, 64), locked=True)
    draw_smart_screen(image, (192, 0, 256, 64), locked=False)
    image.save(UV_PATH)


def retexture_inventory_icon() -> None:
    image = Image.open(ICON_PATH).convert("RGBA")
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            if 19 <= x <= 57 and 17 <= y <= 30 and r < g * 1.35:
                nr, ng, nb = palette_color(luminance, (38, 39, 15), (119, 121, 48), (190, 188, 87))
            elif y >= 34 and x <= 31 and r < 110:
                nr, ng, nb = palette_color(luminance, (4, 5, 5), (28, 31, 31), (71, 76, 73))
            elif r > g * 1.35 and r > b * 1.35:
                nr, ng, nb = palette_color(luminance, (54, 3, 5), (145, 15, 20), (224, 42, 43))
            else:
                nr, ng, nb = palette_color(luminance, (22, 24, 25), (113, 118, 119), (213, 215, 209))
            pixels[x, y] = nr, ng, nb, a
    image.save(ICON_PATH)


def create_smart_link_icon() -> None:
    image = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    dark = (9, 12, 12, 255)
    steel = (118, 124, 122, 255)
    olive = (153, 153, 62, 255)
    red = (194, 29, 35, 255)
    cyan = (77, 235, 220, 255)

    draw.polygon(((7, 8), (14, 4), (23, 6), (27, 13), (25, 23), (17, 28), (8, 23), (4, 15)), fill=dark)
    draw.line(((7, 8), (14, 4), (23, 6), (27, 13), (25, 23), (17, 28), (8, 23), (4, 15), (7, 8)), fill=steel, width=2)
    draw.rectangle((8, 10, 23, 21), outline=olive, width=2)
    draw.ellipse((12, 10, 21, 19), outline=cyan, width=2)
    draw.line((16, 7, 16, 13), fill=cyan, width=1)
    draw.line((16, 17, 16, 24), fill=cyan, width=1)
    draw.line((9, 15, 14, 15), fill=cyan, width=1)
    draw.line((19, 15, 24, 15), fill=cyan, width=1)
    draw.rectangle((15, 14, 17, 16), fill=red)
    draw.point((10, 22), fill=olive)
    draw.point((22, 22), fill=olive)
    image.save(SMART_LINK_PATH)


if __name__ == "__main__":
    retexture_uv()
    retexture_inventory_icon()
    create_smart_link_icon()
