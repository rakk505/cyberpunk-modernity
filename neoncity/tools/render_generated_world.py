#!/usr/bin/env python3
"""Render and audit a real generated Neon City Anvil window."""

from __future__ import annotations

import argparse
from collections import Counter
import importlib.util
import json
import math
from pathlib import Path
import struct
import sys
from typing import Any
import zlib


AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def _helpers() -> Any:
    root = Path(__file__).resolve().parents[2]
    source = root / "tools" / "citygen" / "anvil.py"
    spec = importlib.util.spec_from_file_location("neon_nbt", source)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load NBT helper: {source}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


NBT = _helpers()


def _color(name: str) -> tuple[int, int, int]:
    if "water" in name:
        return (24, 72, 150)
    if "sea_lantern" in name or "froglight" in name or "shroomlight" in name:
        return (240, 230, 150)
    if "cyan" in name or "light_blue" in name:
        return (25, 190, 210)
    if "magenta" in name or "purple" in name:
        return (205, 42, 210)
    if "red" in name:
        return (185, 42, 48)
    if "yellow" in name or "gold" in name:
        return (230, 190, 45)
    if "orange" in name or "copper" in name:
        return (175, 92, 55)
    if "white" in name or "quartz" in name:
        return (220, 224, 225)
    if "glass" in name:
        return (83, 145, 170)
    if "leaves" in name or "moss" in name or "grass" in name:
        return (56, 125, 70)
    if "black" in name or "deepslate" in name:
        return (38, 41, 48)
    if "iron" in name or "smooth_stone" in name:
        return (145, 151, 157)
    if "tuff" in name or "mud_bricks" in name:
        return (88, 82, 74)
    return (105, 110, 118)


def _png(path: Path, width: int, height: int, pixels: bytearray) -> None:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    scanlines = bytearray()
    stride = width * 3
    for y in range(height):
        scanlines.append(0)
        scanlines.extend(pixels[y * stride : (y + 1) * stride])
    payload = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(bytes(scanlines), 9))
               + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def _decode_window(
        world: Path,
        min_chunk_x: int,
        max_chunk_x: int,
        min_chunk_z: int,
        max_chunk_z: int) -> dict[str, Any]:
    chunks_x = max_chunk_x - min_chunk_x + 1
    chunks_z = max_chunk_z - min_chunk_z + 1
    width = chunks_x * 16
    depth = chunks_z * 16
    top_y = [-10_000] * (width * depth)
    top_name: list[str | None] = [None] * (width * depth)
    block_counts: Counter[str] = Counter()
    parsed = 0
    data_versions: Counter[int] = Counter()
    region_cache: dict[tuple[int, int], Any | None] = {}
    region_dir = world / "region"
    if not region_dir.is_dir():
        modern = world / "dimensions" / "minecraft" / "overworld" / "region"
        if modern.is_dir():
            region_dir = modern

    for chunk_z in range(min_chunk_z, max_chunk_z + 1):
        for chunk_x in range(min_chunk_x, max_chunk_x + 1):
            region_key = (math.floor(chunk_x / 32), math.floor(chunk_z / 32))
            if region_key not in region_cache:
                path = region_dir / f"r.{region_key[0]}.{region_key[1]}.mca"
                region_cache[region_key] = NBT.Region(str(path)) if path.is_file() else None
            region = region_cache[region_key]
            if region is None:
                continue
            root = region.chunk_nbt(chunk_x % 32, chunk_z % 32)
            if root is None:
                continue
            parsed += 1
            data_versions[int(root.get("DataVersion", 0))] += 1
            sections = root.get("sections", [])
            for section in sections:
                section_y = int(section["Y"])
                decoded = NBT.decode_section_blocks(section)
                if decoded is None:
                    continue
                raw_palette, indices = decoded
                palette = [str(entry["Name"]) for entry in raw_palette]
                for linear, state_index in enumerate(indices):
                    name = palette[state_index]
                    if name in AIR:
                        continue
                    local_y = linear >> 8
                    local_z = (linear >> 4) & 15
                    local_x = linear & 15
                    y = section_y * 16 + local_y
                    image_x = (chunk_x - min_chunk_x) * 16 + local_x
                    image_z = (chunk_z - min_chunk_z) * 16 + local_z
                    index = image_z * width + image_x
                    block_counts[name] += 1
                    if y >= top_y[index]:
                        top_y[index] = y
                        top_name[index] = name
    return {
        "width": width,
        "depth": depth,
        "top_y": top_y,
        "top_name": top_name,
        "parsed_chunks": parsed,
        "expected_chunks": chunks_x * chunks_z,
        "data_versions": dict(sorted(data_versions.items())),
        "block_counts": block_counts,
    }


def _topdown(decoded: dict[str, Any], output: Path) -> None:
    width = decoded["width"]
    depth = decoded["depth"]
    tops = [value for value in decoded["top_y"] if value > -10_000]
    low = min(tops, default=0)
    high = max(tops, default=1)
    span = max(1, high - low)
    pixels = bytearray(width * depth * 3)
    for z in range(depth):
        for x in range(width):
            index = z * width + x
            name = decoded["top_name"][index]
            if name is None:
                color = (10, 12, 18)
            else:
                base = _color(name)
                height = decoded["top_y"][index]
                factor = 0.58 + 0.42 * (height - low) / span
                if x % 16 == 0 or z % 16 == 0:
                    factor *= 0.88
                color = tuple(min(255, round(channel * factor)) for channel in base)
            at = index * 3
            pixels[at : at + 3] = bytes(color)
    _png(output, width, depth, pixels)


def _isometric(decoded: dict[str, Any], output: Path) -> None:
    source_width = decoded["width"]
    source_depth = decoded["depth"]
    step = 2
    samples_x = source_width // step
    samples_z = source_depth // step
    canvas_width = (samples_x + samples_z) * 3 + 100
    canvas_height = (samples_x + samples_z) * 3 // 2 + 430
    pixels = bytearray([8, 10, 16]) * (canvas_width * canvas_height)

    def set_pixel(x: int, y: int, color: tuple[int, int, int]) -> None:
        if 0 <= x < canvas_width and 0 <= y < canvas_height:
            at = (y * canvas_width + x) * 3
            pixels[at : at + 3] = bytes(color)

    ground_line = 270
    for diagonal in range(samples_x + samples_z - 1):
        for sx in range(samples_x):
            sz = diagonal - sx
            if not 0 <= sz < samples_z:
                continue
            source_x = sx * step
            source_z = sz * step
            index = source_z * source_width + source_x
            name = decoded["top_name"][index]
            if name is None:
                continue
            y_value = decoded["top_y"][index]
            base_x = (sx - sz) * 3 + canvas_width // 2
            base_y = (sx + sz) * 3 // 2 + ground_line
            top_y = base_y - max(0, y_value) * 5 // 4
            base = _color(name)
            side = tuple(round(channel * 0.48) for channel in base)
            for py in range(top_y + 2, base_y + 1):
                set_pixel(base_x, py, side)
                set_pixel(base_x + 1, py, side)
            for dx, dy in ((0, 0), (-1, 1), (1, 1), (0, 2), (2, 1)):
                set_pixel(base_x + dx, top_y + dy, base)
    _png(output, canvas_width, canvas_height, pixels)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("world", type=Path)
    parser.add_argument(
        "--min-chunk", type=int, default=-7,
        help="minimum chunk coordinate for both axes (default: %(default)s)")
    parser.add_argument(
        "--max-chunk", type=int, default=7,
        help="maximum chunk coordinate for both axes (default: %(default)s)")
    parser.add_argument(
        "--min-chunk-x", type=int,
        help="minimum X chunk coordinate (overrides --min-chunk)")
    parser.add_argument(
        "--max-chunk-x", type=int,
        help="maximum X chunk coordinate (overrides --max-chunk)")
    parser.add_argument(
        "--min-chunk-z", type=int,
        help="minimum Z chunk coordinate (overrides --min-chunk)")
    parser.add_argument(
        "--max-chunk-z", type=int,
        help="maximum Z chunk coordinate (overrides --max-chunk)")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    min_chunk_x = args.min_chunk if args.min_chunk_x is None else args.min_chunk_x
    max_chunk_x = args.max_chunk if args.max_chunk_x is None else args.max_chunk_x
    min_chunk_z = args.min_chunk if args.min_chunk_z is None else args.min_chunk_z
    max_chunk_z = args.max_chunk if args.max_chunk_z is None else args.max_chunk_z
    if min_chunk_x > max_chunk_x:
        parser.error("minimum X chunk coordinate cannot exceed maximum X chunk coordinate")
    if min_chunk_z > max_chunk_z:
        parser.error("minimum Z chunk coordinate cannot exceed maximum Z chunk coordinate")
    decoded = _decode_window(
        args.world.resolve(), min_chunk_x, max_chunk_x, min_chunk_z, max_chunk_z)
    output = args.output_dir.resolve()
    _topdown(decoded, output / "generated_topdown.png")
    _isometric(decoded, output / "generated_isometric.png")
    heights = [value for value in decoded["top_y"] if value > -10_000]
    audit = {
        "status": "pass" if decoded["parsed_chunks"] == decoded["expected_chunks"] else "partial",
        "chunk_bounds": {
            "min_x": min_chunk_x,
            "max_x": max_chunk_x,
            "min_z": min_chunk_z,
            "max_z": max_chunk_z,
        },
        "expected_chunks": decoded["expected_chunks"],
        "parsed_chunks": decoded["parsed_chunks"],
        "data_versions": decoded["data_versions"],
        "surface_columns": len(heights),
        "surface_y_min": min(heights, default=None),
        "surface_y_max": max(heights, default=None),
        "non_air_blocks": sum(decoded["block_counts"].values()),
        "distinct_blocks": len(decoded["block_counts"]),
        "top_blocks": [
            {"block": name, "count": count}
            for name, count in decoded["block_counts"].most_common(30)
        ],
    }
    (output / "generated_world_audit.json").write_text(
            json.dumps(audit, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(audit, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
