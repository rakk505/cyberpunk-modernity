#!/usr/bin/env python3
"""Generate Tech Weapon item/model resources and gunmetal/cyan texture variants.

The generated model files inherit the original mesh, display transforms, and texture slot names.
Only their texture bindings change, so the Tech lineup stays visually and geometrically identical
to its conventional counterparts while avoiding duplicated multi-thousand-line Blockbench JSON.
"""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/cyberdeck"
ITEMS = ASSETS / "items"
MODELS = ASSETS / "models/item"
TEXTURES = ASSETS / "textures/item"

FIREARMS = (
    "pistol",
    "smg",
    "shotgun",
    "assault_rifle",
    "sniper",
    "overture",
    "unity",
    "yukimura",
    "3516",
    "saratoga",
    "g58_dian",
    "ajax",
    "copperhead",
    "m2038",
    "carnage",
    "grad",
)


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def stable_seed(name: str) -> int:
    return int.from_bytes(hashlib.sha256(name.encode("utf-8")).digest()[:4], "big")


def pattern_mask(size: tuple[int, int], period: int, width: int,
                 seed: int, x_weight: int, y_weight: int) -> Image.Image:
    image = Image.new("L", size)
    image.putdata([
        255 if ((x_weight * x + y_weight * y + seed) % period) < width else 0
        for y in range(size[1])
        for x in range(size[0])
    ])
    return image


def recolor_texture(source: Path, destination: Path) -> None:
    """Desaturate to cool gunmetal, then add thin deterministic cyan trim and panel seams."""
    image = Image.open(source).convert("RGBA")
    red, green, blue, alpha = image.split()
    luminance = ImageOps.grayscale(image.convert("RGB"))

    # Preserve the original shading and wear while collapsing its hue into cool gunmetal.
    lum = list(luminance.get_flattened_data())
    base_pixels: list[tuple[int, int, int, int]] = []
    alpha_values = list(alpha.get_flattened_data())
    for value, opacity in zip(lum, alpha_values, strict=True):
        steel = min(218, 18 + int(value * 0.74))
        base_pixels.append((int(steel * 0.86), int(steel * 0.94), steel, opacity))
    result = Image.new("RGBA", image.size)
    result.putdata(base_pixels)

    # UV-island boundaries produce natural-looking fine inlay around panels. Original colorful
    # panel boundaries are also retained as trim after the panel itself has been desaturated.
    opaque = alpha.point(lambda value: 255 if value > 16 else 0)
    island_edge = ImageChops.subtract(opaque, opaque.filter(ImageFilter.MinFilter(3)))
    saturation = ImageChops.subtract(
        ImageChops.lighter(ImageChops.lighter(red, green), blue),
        ImageChops.darker(ImageChops.darker(red, green), blue),
    )
    saturated = saturation.point(lambda value: 255 if value >= 48 else 0)
    color_edge = ImageChops.subtract(saturated, saturated.filter(ImageFilter.MinFilter(3)))

    # A sparse diagonal inlay guarantees visible cyan gilding even on fully opaque grayscale
    # atlases. Its scale follows the texture, so 16px legacy sheets and 256px UV maps both read as
    # a narrow line rather than a broad repaint.
    width, height = image.size
    seed = stable_seed(source.stem)
    period = max(12, min(width, height) // 3)
    line_width = max(1, min(width, height) // 128)
    stripe = Image.new("L", image.size)
    stripe.putdata([
        255
        if opacity > 16
        and value > 28
        and ((x + 2 * y + seed) % period) < line_width
        else 0
        for y in range(height)
        for x, (value, opacity) in enumerate(
            zip(lum[y * width : (y + 1) * width],
                alpha_values[y * width : (y + 1) * width], strict=True)
        )
    ])

    # Most imported UV islands are only one or two pixels wide, so using every boundary pixel
    # would repaint the atlas cyan. Long deterministic gates keep short, connected pieces of those
    # authored seams and cap the accent to a restrained fraction of the metal body.
    island_period = max(12, min(40, min(width, height) // 3))
    island_gate = pattern_mask(
        image.size, island_period, max(1, round(island_period * 0.05)),
        seed, 1, 3,
    )
    color_period = max(10, min(24, min(width, height) // 5))
    color_gate = pattern_mask(
        image.size, color_period, max(2, round(color_period * 0.20)),
        seed // 3, 2, 1,
    )
    gated_islands = ImageChops.multiply(island_edge, island_gate)
    gated_colors = ImageChops.multiply(color_edge, color_gate)
    accent_mask = ImageChops.lighter(ImageChops.lighter(gated_islands, gated_colors), stripe)
    accent_mask = ImageChops.multiply(accent_mask, opaque)
    cyan = Image.new("RGBA", image.size, (24, 224, 248, 255))
    result = Image.composite(cyan, result, accent_mask)
    result.putalpha(alpha)
    result.save(destination, optimize=True)

    metadata = source.with_suffix(source.suffix + ".mcmeta")
    if metadata.exists():
        shutil.copyfile(metadata, destination.with_suffix(destination.suffix + ".mcmeta"))


def generate_firearm(base_id: str) -> set[tuple[Path, Path]]:
    tech_id = f"tech_{base_id}"
    write_json(
        ITEMS / f"{tech_id}.json",
        {"model": {"type": "minecraft:model", "model": f"cyberdeck:item/{tech_id}_3d"}},
    )

    base_model = json.loads((MODELS / f"{base_id}_3d.json").read_text(encoding="utf-8"))
    bindings: dict[str, str] = {}
    transforms: set[tuple[Path, Path]] = set()
    for slot, resource in base_model.get("textures", {}).items():
        namespace, separator, relative = resource.partition(":item/")
        if namespace != "cyberdeck" or not separator:
            raise ValueError(f"Unsupported texture binding {resource!r} in {base_id}_3d.json")
        tech_relative = f"tech_{relative}"
        bindings[slot] = f"cyberdeck:item/{tech_relative}"
        transforms.add((TEXTURES / f"{relative}.png", TEXTURES / f"{tech_relative}.png"))

    write_json(
        MODELS / f"{tech_id}_3d.json",
        {"parent": f"cyberdeck:item/{base_id}_3d", "textures": bindings},
    )
    return transforms


def main() -> None:
    transforms: set[tuple[Path, Path]] = set()
    for firearm in FIREARMS:
        transforms.update(generate_firearm(firearm))
    for source, destination in sorted(transforms):
        if not source.exists():
            raise FileNotFoundError(source)
        recolor_texture(source, destination)
    print(f"Generated {len(FIREARMS)} Tech firearms and {len(transforms)} texture atlases")


if __name__ == "__main__":
    main()
