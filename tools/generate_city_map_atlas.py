#!/usr/bin/env python3
"""Build the compact A-Z top-down occupancy atlas used by the city map UI."""

from __future__ import annotations

import gzip
import json
import re
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "citygen"))

from anvil import parse_nbt  # noqa: E402


CATALOG = ROOT / "src/main/resources/data/neoncity/arnis_districts/catalog.json"
STRUCTURES = CATALOG.parent
OUTPUT = ROOT / "src/main/resources/assets/cyberdeck/textures/gui/project_moon_map_atlas.png"
ATLAS_AXIS_CHUNKS = 16
ATLAS_AXIS_BLOCKS = ATLAS_AXIS_CHUNKS * 16
WIDTH = 26 * ATLAS_AXIS_BLOCKS
HEIGHT = 2 * ATLAS_AXIS_BLOCKS
TILE_ID = re.compile(r"^([a-z])/((?:nest|backstreets))_([0-9]+)_([0-9]+)$")

EMPTY = 0
SURFACE = 1
BUILDING = 2
VEGETATION = 3
WATER = 4

NON_STRUCTURAL_SUFFIXES = (
    "air",
    "water",
    "lava",
    "leaves",
    "log",
    "sapling",
    "grass",
    "flower",
    "vine",
    "mushroom",
    "snow",
    "torch",
    "lantern",
    "rail",
    "sign",
    "chain",
    "light",
)
VEGETATION_SUFFIXES = (
    "leaves",
    "log",
    "sapling",
    "grass",
    "flower",
    "vine",
    "vines",
    "mushroom",
)


def has_suffix(block_name: str, suffixes: tuple[str, ...]) -> bool:
    path = block_name.partition(":")[2]
    return any(path == suffix or path.endswith(f"_{suffix}") for suffix in suffixes)


def category(column: list[tuple[int, str]], surface_y: int) -> int:
    if not column:
        return EMPTY
    structural_top = max(
        (y for y, name in column if not has_suffix(name, NON_STRUCTURAL_SUFFIXES)),
        default=-10_000,
    )
    if structural_top >= surface_y + 3:
        return BUILDING
    if any(
        y > surface_y and has_suffix(name, VEGETATION_SUFFIXES)
        for y, name in column
    ):
        return VEGETATION
    if any(has_suffix(name, ("water",)) for _, name in column):
        return WATER
    return SURFACE


def write_grayscale_png(path: Path, width: int, height: int, pixels: bytearray) -> None:
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * width:(y + 1) * width])

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    encoded = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
    encoded += chunk(b"IDAT", zlib.compress(bytes(raw), level=9))
    encoded += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def main() -> None:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    pixels = bytearray(WIDTH * HEIGHT)
    counts = [0] * 5

    for patch in catalog["patches"]:
        match = TILE_ID.match(patch["id"])
        if match is None:
            raise ValueError(f"unexpected atlas tile id: {patch['id']}")
        district_code, zone, tile_x_text, tile_z_text = match.groups()
        tile_x = int(tile_x_text)
        tile_z = int(tile_z_text)
        if tile_x >= ATLAS_AXIS_CHUNKS or tile_z >= ATLAS_AXIS_CHUNKS:
            raise ValueError(
                f"atlas tile is outside {ATLAS_AXIS_CHUNKS}x{ATLAS_AXIS_CHUNKS}: "
                f"{patch['id']}"
            )
        district = ord(district_code) - ord("a")
        zone_row = 0 if zone == "nest" else 1
        anchor = patch["footprint"]["anchor"]
        surface_y = anchor["surface_y"] - anchor["source_y"]

        with gzip.open(STRUCTURES / patch["file"], "rb") as stream:
            template = parse_nbt(stream.read())
        palette = [entry["Name"] for entry in template["palette"]]
        columns: list[list[tuple[int, str]]] = [[] for _ in range(16 * 16)]
        for block in template["blocks"]:
            x, y, z = block["pos"]
            columns[z * 16 + x].append((y, palette[block["state"]]))

        atlas_x = district * ATLAS_AXIS_BLOCKS + tile_x * 16
        atlas_y = zone_row * ATLAS_AXIS_BLOCKS + tile_z * 16
        for z in range(16):
            for x in range(16):
                value = category(columns[z * 16 + x], surface_y)
                pixels[(atlas_y + z) * WIDTH + atlas_x + x] = value
                counts[value] += 1

    expected = 26 * 2 * ATLAS_AXIS_BLOCKS * ATLAS_AXIS_BLOCKS
    if sum(counts) != expected:
        raise ValueError(f"atlas is incomplete: {sum(counts)} of {expected} pixels")
    write_grayscale_png(OUTPUT, WIDTH, HEIGHT, pixels)
    print(f"wrote {OUTPUT.relative_to(ROOT)} ({WIDTH}x{HEIGHT}) categories={counts}")


if __name__ == "__main__":
    main()
