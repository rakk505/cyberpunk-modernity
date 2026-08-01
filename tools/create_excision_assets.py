#!/usr/bin/env python3
"""Build the Excision law-enforcement skin and black Aerodyne variant."""

from __future__ import annotations

from collections import OrderedDict
import gzip
import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib

from import_spudtruck import (
    NbtReader,
    TAG_COMPOUND,
    TAG_INT,
    compound_payload,
    int_tag,
    list_tag,
    nbt_string,
    palette_entry,
)


ROOT = Path(__file__).resolve().parents[1]
TRAUMA_SKIN = ROOT / "src/main/resources/assets/cyberdeck/textures/entity/trauma_team.png"
EXCISION_SKIN = ROOT / "src/main/resources/assets/cyberdeck/textures/entity/excision_agent.png"
TRAUMA_AERODYNE = (
    ROOT / "src/main/resources/data/cyberdeck/structure/trauma_team/aerodyne.nbt"
)
EXCISION_AERODYNE = (
    ROOT / "src/main/resources/data/cyberdeck/structure/excision/aerodyne.nbt"
)
CATALOG = ROOT / "src/main/resources/data/cyberdeck/excision/catalog.json"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def read_indexed_png(path: Path) -> tuple[int, int, list[list[tuple[int, int, int, int]]]]:
    payload = path.read_bytes()
    if not payload.startswith(PNG_SIGNATURE):
        raise ValueError(f"not a PNG: {path}")
    cursor = len(PNG_SIGNATURE)
    width = height = bit_depth = color_type = 0
    palette: list[tuple[int, int, int]] = []
    alpha: list[int] = []
    compressed = bytearray()
    while cursor < len(payload):
        length = struct.unpack(">I", payload[cursor : cursor + 4])[0]
        kind = payload[cursor + 4 : cursor + 8]
        data = payload[cursor + 8 : cursor + 8 + length]
        cursor += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _, _, _ = struct.unpack(">IIBBBBB", data)
        elif kind == b"PLTE":
            palette = [tuple(data[index : index + 3]) for index in range(0, len(data), 3)]
        elif kind == b"tRNS":
            alpha = list(data)
        elif kind == b"IDAT":
            compressed.extend(data)
        elif kind == b"IEND":
            break
    if bit_depth != 8 or color_type != 3 or width != 64 or height != 64:
        raise ValueError("expected a 64x64 8-bit indexed Minecraft skin")

    raw = zlib.decompress(bytes(compressed))
    rows: list[list[int]] = []
    previous = [0] * width
    offset = 0
    for _ in range(height):
        filter_type = raw[offset]
        scanline = list(raw[offset + 1 : offset + 1 + width])
        offset += width + 1
        decoded: list[int] = []
        for index, value in enumerate(scanline):
            left = decoded[index - 1] if index else 0
            above = previous[index]
            upper_left = previous[index - 1] if index else 0
            if filter_type == 1:
                value = (value + left) & 0xFF
            elif filter_type == 2:
                value = (value + above) & 0xFF
            elif filter_type == 3:
                value = (value + ((left + above) // 2)) & 0xFF
            elif filter_type == 4:
                predictor = left + above - upper_left
                distances = (
                    abs(predictor - left),
                    abs(predictor - above),
                    abs(predictor - upper_left),
                )
                nearest = (left, above, upper_left)[distances.index(min(distances))]
                value = (value + nearest) & 0xFF
            elif filter_type != 0:
                raise ValueError(f"unsupported PNG filter {filter_type}")
            decoded.append(value)
        rows.append(decoded)
        previous = decoded

    pixels = []
    for row in rows:
        pixels.append([
            (*palette[index], alpha[index] if index < len(alpha) else 255)
            for index in row
        ])
    return width, height, pixels


def write_rgba_png(path: Path, pixels: list[list[tuple[int, int, int, int]]]) -> None:
    height = len(pixels)
    width = len(pixels[0])
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    encoded = (
        PNG_SIGNATURE
        + png_chunk(b"IHDR", ihdr)
        + png_chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + png_chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def law_enforcement_color(pixel: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    red, green, blue, alpha = pixel
    if alpha == 0:
        return pixel
    high = max(red, green, blue)
    low = min(red, green, blue)
    brightness = (red * 3 + green * 5 + blue * 2) // 10
    if high - low < 18:
        if brightness >= 205:
            return (210, 225, 236, alpha)
        if brightness >= 115:
            return (56, 76, 96, alpha)
        shade = max(7, min(34, brightness // 3))
        return (shade // 2, shade, min(58, shade + 18), alpha)
    if green >= red and green >= blue:
        return (12, max(58, brightness // 2), min(190, brightness + 45), alpha)
    if blue >= red and blue >= green:
        return (15, max(48, brightness // 2), min(210, brightness + 55), alpha)
    if red > green * 1.2:
        return (22, max(66, brightness // 2), min(220, brightness + 60), alpha)
    return (26, 48, 78, alpha)


def paint_excision_skin() -> dict[str, object]:
    width, height, source = read_indexed_png(TRAUMA_SKIN)
    pixels = [[law_enforcement_color(pixel) for pixel in row] for row in source]

    navy = (7, 18, 31, 255)
    navy_light = (15, 36, 58, 255)
    blue = (22, 112, 210, 255)
    blue_light = (76, 188, 255, 255)
    white = (228, 238, 244, 255)
    gold = (238, 180, 48, 255)

    for y in range(8, 16):
        for x in range(8, 16):
            pixels[y][x] = navy_light if y >= 14 else navy
    for x in range(9, 15):
        pixels[11][x] = blue
        pixels[12][x] = blue_light if x in (10, 13) else blue

    for y in range(8, 16):
        for x in range(40, 48):
            pixels[y][x] = (0, 0, 0, 0)
    for x, y in ((41, 9), (42, 9), (43, 9), (41, 10), (41, 11),
                 (42, 11), (43, 11), (41, 12), (41, 13), (42, 13),
                 (43, 13), (44, 9), (46, 9), (45, 10), (45, 11),
                 (45, 12), (44, 13), (46, 13)):
        pixels[y][x] = white

    for y in range(20, 32):
        for x in range(20, 28):
            pixels[y][x] = navy_light if x in (20, 27) else navy
    for x in range(20, 28):
        pixels[21][x] = blue
    for x, y in ((25, 23), (26, 23), (25, 24), (26, 25)):
        pixels[y][x] = gold
    for x in range(21, 27):
        pixels[29][x] = blue

    for start_x, start_y in ((44, 20), (36, 52)):
        for y in range(start_y, start_y + 12):
            for x in range(start_x, start_x + 4):
                pixels[y][x] = navy_light if x in (start_x, start_x + 3) else navy
        for x in range(start_x, start_x + 4):
            pixels[start_y + 2][x] = blue
        pixels[start_y + 3][start_x + 1] = white
        pixels[start_y + 3][start_x + 2] = white

    write_rgba_png(EXCISION_SKIN, pixels)
    return {
        "source": str(TRAUMA_SKIN.relative_to(ROOT)),
        "size": [width, height],
        "sha256": hashlib.sha256(EXCISION_SKIN.read_bytes()).hexdigest(),
        "design": "black/navy law-enforcement uniform, blue panels, EX helmet mark, gold badge",
    }


def block_state(entry: dict[str, object]) -> str:
    name = str(entry["Name"])
    properties = entry.get("Properties")
    if not isinstance(properties, dict) or not properties:
        return name
    assignments = ",".join(f"{key}={properties[key]}" for key in sorted(properties))
    return f"{name}[{assignments}]"


def black_block_name(name: str) -> str:
    colored_suffixes = (
        "banner", "bed", "candle", "carpet", "concrete", "concrete_powder",
        "glazed_terracotta", "shulker_box", "stained_glass", "stained_glass_pane",
        "terracotta", "wall_banner", "wool",
    )
    colors = (
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red",
        "black",
    )
    for color in colors:
        prefix = f"minecraft:{color}_"
        if name.startswith(prefix) and name[len(prefix):] in colored_suffixes:
            return "minecraft:black_" + name[len(prefix):]
    replacements = {
        "minecraft:smooth_quartz": "minecraft:polished_blackstone",
        "minecraft:smooth_quartz_slab": "minecraft:polished_blackstone_slab",
        "minecraft:smooth_quartz_stairs": "minecraft:polished_blackstone_stairs",
        "minecraft:diorite": "minecraft:polished_blackstone",
        "minecraft:diorite_slab": "minecraft:polished_blackstone_slab",
        "minecraft:diorite_stairs": "minecraft:polished_blackstone_stairs",
        "minecraft:diorite_wall": "minecraft:polished_blackstone_wall",
        "minecraft:warped_planks": "minecraft:polished_blackstone",
        "minecraft:warped_slab": "minecraft:polished_blackstone_slab",
        "minecraft:warped_stairs": "minecraft:polished_blackstone_stairs",
        "minecraft:warped_button": "minecraft:polished_blackstone_button",
        "minecraft:stripped_warped_hyphae": "minecraft:polished_basalt",
        "minecraft:pale_oak_trapdoor": "minecraft:dark_oak_trapdoor",
        "minecraft:pale_oak_pressure_plate": "minecraft:polished_blackstone_pressure_plate",
        "minecraft:mangrove_planks": "minecraft:dark_oak_planks",
        "minecraft:mangrove_slab": "minecraft:dark_oak_slab",
        "minecraft:mangrove_stairs": "minecraft:dark_oak_stairs",
        "minecraft:mangrove_trapdoor": "minecraft:dark_oak_trapdoor",
        "minecraft:mangrove_button": "minecraft:polished_blackstone_button",
        "minecraft:mangrove_wall_sign": "minecraft:dark_oak_wall_sign",
        "minecraft:warped_wall_sign": "minecraft:dark_oak_wall_sign",
    }
    return replacements.get(name, name)


def black_state(entry: dict[str, object]) -> str:
    transformed = dict(entry)
    transformed["Name"] = black_block_name(str(entry["Name"]))
    return block_state(transformed)


def paint_aerodyne() -> dict[str, object]:
    source = NbtReader(TRAUMA_AERODYNE.read_bytes()).root()
    source_palette = source["palette"]
    source_blocks = source["blocks"]
    states: OrderedDict[str, int] = OrderedDict()
    remap: list[int] = []
    for entry in source_palette:
        state = black_state(entry)
        if state not in states:
            states[state] = len(states)
        remap.append(states[state])

    block_payloads = []
    for block in source_blocks:
        block_payloads.append(compound_payload([
            list_tag("pos", TAG_INT, [struct.pack(">i", value) for value in block["pos"]]),
            int_tag("state", remap[block["state"]]),
        ]))
    root_tags = [
        int_tag("DataVersion", int(source["DataVersion"])),
        list_tag("size", TAG_INT, [struct.pack(">i", value) for value in source["size"]]),
        list_tag("palette", TAG_COMPOUND, [palette_entry(state) for state in states]),
        list_tag("blocks", TAG_COMPOUND, block_payloads),
        list_tag("entities", TAG_COMPOUND, []),
    ]
    uncompressed = bytes([TAG_COMPOUND]) + nbt_string("") + compound_payload(root_tags)
    encoded = gzip.compress(uncompressed, compresslevel=9, mtime=0)
    EXCISION_AERODYNE.parent.mkdir(parents=True, exist_ok=True)
    EXCISION_AERODYNE.write_bytes(encoded)
    return {
        "source": str(TRAUMA_AERODYNE.relative_to(ROOT)),
        "template": "cyberdeck:excision/aerodyne",
        "size": source["size"],
        "blocks": len(source_blocks),
        "palette_states": len(states),
        "sha256": hashlib.sha256(encoded).hexdigest(),
    }


def main() -> None:
    skin = paint_excision_skin()
    aerodyne = paint_aerodyne()
    CATALOG.parent.mkdir(parents=True, exist_ok=True)
    CATALOG.write_text(
        json.dumps({"skin": skin, "aerodyne": aerodyne}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"skin": skin, "aerodyne": aerodyne}, indent=2))


if __name__ == "__main__":
    sys.exit(main())
