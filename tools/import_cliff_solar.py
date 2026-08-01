#!/usr/bin/env python3
"""Convert the supplied Litematica solar panel into a native structure template."""

from __future__ import annotations

import argparse
from collections import Counter
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = ROOT / "src/main/resources/data/neoncity/cliff_infrastructure"
STRUCTURE_PATH = (
    ROOT
    / "src/main/resources/data/neoncity/structure/cliff_infrastructure/solar_panel.nbt"
)
SOURCE_ENTRY = "solar_panel.litematic"

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

AIR_BLOCKS = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
EXPECTED_SIZE = [11, 19, 31]
EXPECTED_BLOCKS = 367
EXPECTED_BLOCK_COUNTS = {
    "minecraft:andesite_wall": 12,
    "minecraft:blue_stained_glass": 240,
    "minecraft:end_rod": 40,
    "minecraft:iron_block": 14,
    "minecraft:iron_trapdoor": 14,
    "minecraft:polished_andesite": 15,
    "minecraft:smooth_quartz_stairs": 27,
    "minecraft:smooth_stone": 1,
    "minecraft:stone_button": 4,
}


class NbtReader:
    def __init__(self, payload: bytes) -> None:
        self.stream = io.BytesIO(gzip.decompress(payload))

    def read(self, length: int) -> bytes:
        payload = self.stream.read(length)
        if len(payload) != length:
            raise ValueError("litematic ended unexpectedly")
        return payload

    def string(self) -> str:
        length = struct.unpack(">H", self.read(2))[0]
        return self.read(length).decode("utf-8")

    def payload(self, tag_type: int) -> object:
        if tag_type == TAG_BYTE:
            return struct.unpack(">b", self.read(1))[0]
        if tag_type == TAG_SHORT:
            return struct.unpack(">h", self.read(2))[0]
        if tag_type == TAG_INT:
            return struct.unpack(">i", self.read(4))[0]
        if tag_type == TAG_LONG:
            return struct.unpack(">q", self.read(8))[0]
        if tag_type == TAG_FLOAT:
            return struct.unpack(">f", self.read(4))[0]
        if tag_type == TAG_DOUBLE:
            return struct.unpack(">d", self.read(8))[0]
        if tag_type == TAG_BYTE_ARRAY:
            return self.read(struct.unpack(">i", self.read(4))[0])
        if tag_type == TAG_STRING:
            return self.string()
        if tag_type == TAG_LIST:
            element_type = self.read(1)[0]
            length = struct.unpack(">i", self.read(4))[0]
            return [self.payload(element_type) for _ in range(length)]
        if tag_type == TAG_COMPOUND:
            result: dict[str, object] = {}
            while True:
                child_type = self.read(1)[0]
                if child_type == TAG_END:
                    return result
                name = self.string()
                result[name] = self.payload(child_type)
        if tag_type == TAG_INT_ARRAY:
            length = struct.unpack(">i", self.read(4))[0]
            return list(struct.unpack(f">{length}i", self.read(length * 4)))
        if tag_type == TAG_LONG_ARRAY:
            length = struct.unpack(">i", self.read(4))[0]
            return list(struct.unpack(f">{length}q", self.read(length * 8)))
        raise ValueError(f"unsupported NBT tag type {tag_type}")

    def root(self) -> dict[str, object]:
        root_type = self.read(1)[0]
        if root_type != TAG_COMPOUND:
            raise ValueError("litematic root is not a compound")
        self.string()
        root = self.payload(root_type)
        if not isinstance(root, dict):
            raise ValueError("litematic root payload is not a compound")
        return root


def nbt_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def named_tag(tag_type: int, name: str, payload: bytes) -> bytes:
    return bytes([tag_type]) + nbt_string(name) + payload


def string_tag(name: str, value: str) -> bytes:
    return named_tag(TAG_STRING, name, nbt_string(value))


def int_tag(name: str, value: int) -> bytes:
    return named_tag(TAG_INT, name, struct.pack(">i", value))


def compound_payload(tags: list[bytes]) -> bytes:
    return b"".join(tags) + bytes([TAG_END])


def list_tag(name: str, element_type: int, payloads: list[bytes]) -> bytes:
    payload = bytes([element_type]) + struct.pack(">i", len(payloads)) + b"".join(payloads)
    return named_tag(TAG_LIST, name, payload)


def state_key(state: dict[str, object]) -> tuple[str, tuple[tuple[str, str], ...]]:
    name = state.get("Name")
    if not isinstance(name, str):
        raise ValueError("block-state palette entry has no Name")
    raw_properties = state.get("Properties", {})
    if not isinstance(raw_properties, dict):
        raise ValueError(f"block-state properties for {name} are not a compound")
    properties = tuple(sorted((str(key), str(value)) for key, value in raw_properties.items()))
    return name, properties


def palette_entry(state: tuple[str, tuple[tuple[str, str], ...]]) -> bytes:
    name, properties = state
    tags = [string_tag("Name", name)]
    if properties:
        tags.append(named_tag(
            TAG_COMPOUND,
            "Properties",
            compound_payload([string_tag(key, value) for key, value in properties]),
        ))
    return compound_payload(tags)


def packed_value(values: list[int], index: int, bits: int) -> int:
    mask64 = (1 << 64) - 1
    bit_index = index * bits
    long_index = bit_index >> 6
    bit_offset = bit_index & 63
    value = (values[long_index] & mask64) >> bit_offset
    if bit_offset + bits > 64:
        value |= (values[long_index + 1] & mask64) << (64 - bit_offset)
    return value & ((1 << bits) - 1)


def convert(source_payload: bytes) -> tuple[bytes, dict[str, object]]:
    root = NbtReader(source_payload).root()
    if root.get("Version") != 6:
        raise ValueError(f"expected Litematica version 6, got {root.get('Version')}")
    regions = root.get("Regions")
    if not isinstance(regions, dict) or len(regions) != 1:
        raise ValueError("expected exactly one litematic region")
    region_name, region = next(iter(regions.items()))
    if not isinstance(region, dict):
        raise ValueError("litematic region is not a compound")

    raw_size = region.get("Size")
    raw_position = region.get("Position")
    raw_palette = region.get("BlockStatePalette")
    raw_states = region.get("BlockStates")
    if not isinstance(raw_size, dict) or not isinstance(raw_position, dict):
        raise ValueError("litematic region has no position or size")
    if not isinstance(raw_palette, list) or not isinstance(raw_states, list):
        raise ValueError("litematic region has no palette or packed states")

    signed_size = tuple(int(raw_size[axis]) for axis in ("x", "y", "z"))
    position = tuple(int(raw_position[axis]) for axis in ("x", "y", "z"))
    size = tuple(abs(value) for value in signed_size)
    volume = size[0] * size[1] * size[2]
    palette = [state_key(entry) for entry in raw_palette if isinstance(entry, dict)]
    if len(palette) != len(raw_palette):
        raise ValueError("litematic palette contains a non-compound entry")
    bits = max(2, (len(palette) - 1).bit_length())
    required_longs = (volume * bits + 63) // 64
    if len(raw_states) != required_longs:
        raise ValueError(
            f"packed state array has {len(raw_states)} longs, expected {required_longs}"
        )

    occupied: list[tuple[int, int, int, tuple[str, tuple[tuple[str, str], ...]]]] = []
    for index in range(volume):
        palette_index = packed_value(raw_states, index, bits)
        if palette_index >= len(palette):
            raise ValueError(f"invalid palette index {palette_index}")
        state = palette[palette_index]
        if state[0] in AIR_BLOCKS:
            continue
        local_x = index % size[0]
        local_z = (index // size[0]) % size[2]
        local_y = index // (size[0] * size[2])
        source = (local_x, local_y, local_z)
        world = tuple(
            position[axis] + (source[axis] if signed_size[axis] >= 0 else -source[axis])
            for axis in range(3)
        )
        occupied.append((world[0], world[1], world[2], state))
    if not occupied:
        raise ValueError("solar panel litematic is empty")

    minimum = tuple(min(block[axis] for block in occupied) for axis in range(3))
    maximum = tuple(max(block[axis] for block in occupied) for axis in range(3))
    normalized_size = [maximum[axis] - minimum[axis] + 1 for axis in range(3)]
    if normalized_size != EXPECTED_SIZE or len(occupied) != EXPECTED_BLOCKS:
        raise ValueError(
            f"unexpected solar bounds/count {normalized_size} / {len(occupied)}"
        )

    block_counts = Counter(block[3][0] for block in occupied)
    if dict(sorted(block_counts.items())) != EXPECTED_BLOCK_COUNTS:
        raise ValueError(f"unexpected solar palette counts {dict(block_counts)}")

    used_states = sorted({block[3] for block in occupied})
    state_index = {state: index for index, state in enumerate(used_states)}
    normalized = sorted(
        (
            block[0] - minimum[0],
            block[1] - minimum[1],
            block[2] - minimum[2],
            block[3],
        )
        for block in occupied
    )
    template_blocks = [
        compound_payload([
            list_tag("pos", TAG_INT, [
                struct.pack(">i", x),
                struct.pack(">i", y),
                struct.pack(">i", z),
            ]),
            int_tag("state", state_index[state]),
        ])
        for x, y, z, state in normalized
    ]
    data_version = int(root["MinecraftDataVersion"])
    root_tags = [
        int_tag("DataVersion", data_version),
        list_tag("size", TAG_INT, [struct.pack(">i", value) for value in normalized_size]),
        list_tag("palette", TAG_COMPOUND, [palette_entry(state) for state in used_states]),
        list_tag("blocks", TAG_COMPOUND, template_blocks),
        list_tag("entities", TAG_COMPOUND, []),
    ]
    uncompressed = bytes([TAG_COMPOUND]) + nbt_string("") + compound_payload(root_tags)
    converted = gzip.compress(uncompressed, compresslevel=9, mtime=0)
    metadata = {
        "id": "solar_panel",
        "template": "neoncity:cliff_infrastructure/solar_panel",
        "size": normalized_size,
        "blocks": len(template_blocks),
        "support_bounds": [6, 0, 14, 8, 4, 16],
        "block_counts": dict(sorted(block_counts.items())),
        "sha256": hashlib.sha256(converted).hexdigest(),
        "source_region": region_name,
        "source_signed_size": list(signed_size),
        "source_position": list(position),
    }
    return converted, metadata


def import_archive(archive: Path) -> None:
    archive_payload = archive.read_bytes()
    with zipfile.ZipFile(io.BytesIO(archive_payload)) as source:
        try:
            source_payload = source.read(SOURCE_ENTRY)
        except KeyError as error:
            raise ValueError(f"archive has no {SOURCE_ENTRY}") from error
    converted, metadata = convert(source_payload)

    STRUCTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STRUCTURE_PATH.write_bytes(converted)
    catalog = {
        "source": {
            "name": archive.name,
            "entry": SOURCE_ENTRY,
            "archive_sha256": hashlib.sha256(archive_payload).hexdigest(),
            "entry_sha256": hashlib.sha256(source_payload).hexdigest(),
            "format": "Litematica v6",
            "minecraft_data_version": 3120,
            "policy": "user-provided; air and entities omitted; signed region normalized",
        },
        "templates": [metadata],
    }
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    (OUTPUT_ROOT / "catalog.json").write_text(
        json.dumps(catalog, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"Imported {archive}:{SOURCE_ENTRY} as {metadata['size']} / "
        f"{metadata['blocks']} blocks ({metadata['sha256']})"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    import_archive(args.archive.resolve())


if __name__ == "__main__":
    main()
