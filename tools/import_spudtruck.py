#!/usr/bin/env python3
"""Convert the user-provided WorldEdit spud truck into a native structure template."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import struct


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = ROOT / "src/main/resources/data/neoncity/merchant_trucks"
STRUCTURE_PATH = (
    ROOT
    / "src/main/resources/data/neoncity/structure/merchant_trucks/spudtruck.nbt"
)

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

STATE_PATTERN = re.compile(r"^([^\[]+)(?:\[([^]]+)\])?$")
BODY_SOURCE_BLOCKS = {
    "minecraft:gray_concrete",
    "minecraft:gray_concrete_powder",
    "minecraft:gray_glazed_terracotta",
}
SERVICE_COUNTER_SHUTTERS = {(4, 2, 5), (5, 2, 5)}


def omitted_block(x: int, y: int, z: int, state: str) -> bool:
    block_name = state.split("[", 1)[0]
    if block_name.endswith("_wall_sign") or block_name.endswith(":wall_sign"):
        return True
    # These two inward-facing shutters meet across the serving aperture and make
    # the otherwise-visible merchant impossible to target from ground level.
    return (x, y, z) in SERVICE_COUNTER_SHUTTERS \
        and block_name == "minecraft:pale_oak_trapdoor"


class NbtReader:
    def __init__(self, payload: bytes) -> None:
        self.stream = io.BytesIO(gzip.decompress(payload))

    def read(self, length: int) -> bytes:
        payload = self.stream.read(length)
        if len(payload) != length:
            raise ValueError("schematic ended unexpectedly")
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
            raise ValueError("schematic root is not a compound")
        self.string()
        root = self.payload(root_type)
        if not isinstance(root, dict):
            raise ValueError("schematic root payload is not a compound")
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


def palette_entry(state: str) -> bytes:
    match = STATE_PATTERN.fullmatch(state)
    if match is None:
        raise ValueError(f"invalid block state {state}")
    tags = [string_tag("Name", match.group(1))]
    if match.group(2):
        properties = []
        for assignment in match.group(2).split(","):
            key, value = assignment.split("=", 1)
            properties.append(string_tag(key, value))
        tags.append(named_tag(TAG_COMPOUND, "Properties", compound_payload(properties)))
    return compound_payload(tags)


def decode_varints(payload: bytes, expected: int) -> list[int]:
    result: list[int] = []
    cursor = 0
    while cursor < len(payload) and len(result) < expected:
        value = 0
        shift = 0
        while True:
            if cursor >= len(payload) or shift > 28:
                raise ValueError("invalid schematic block-state varint")
            current = payload[cursor]
            cursor += 1
            value |= (current & 0x7F) << shift
            if current & 0x80 == 0:
                break
            shift += 7
        result.append(value)
    if len(result) != expected or cursor != len(payload):
        raise ValueError("schematic block data does not match its declared volume")
    return result


def convert(source_payload: bytes) -> tuple[bytes, dict[str, object]]:
    root = NbtReader(source_payload).root()
    schematic = root.get("Schematic")
    if not isinstance(schematic, dict) or schematic.get("Version") != 3:
        raise ValueError("expected a Sponge schematic v3 root")
    blocks = schematic.get("Blocks")
    if not isinstance(blocks, dict):
        raise ValueError("schematic has no Blocks compound")

    width = int(schematic["Width"])
    height = int(schematic["Height"])
    length = int(schematic["Length"])
    volume = width * height * length
    palette = blocks["Palette"]
    block_data = blocks["Data"]
    if not isinstance(palette, dict) or not isinstance(block_data, bytes):
        raise ValueError("schematic palette or block data has an invalid type")
    states_by_id = {int(index): name for name, index in palette.items()}
    indices = decode_varints(block_data, volume)

    occupied: list[tuple[int, int, int, str]] = []
    for y in range(height):
        for z in range(length):
            for x in range(width):
                index = (y * length + z) * width + x
                state = states_by_id[indices[index]]
                if state != "minecraft:air" and not omitted_block(x, y, z, state):
                    occupied.append((x, y, z, state))
    if not occupied:
        raise ValueError("truck schematic is empty")

    min_x = min(block[0] for block in occupied)
    min_y = min(block[1] for block in occupied)
    min_z = min(block[2] for block in occupied)
    max_x = max(block[0] for block in occupied)
    max_y = max(block[1] for block in occupied)
    max_z = max(block[2] for block in occupied)
    size = [max_x - min_x + 1, max_y - min_y + 1, max_z - min_z + 1]
    if size != [14, 8, 7]:
        raise ValueError(f"unexpected occupied truck bounds {size}")

    used_states = sorted({block[3] for block in occupied})
    state_index = {state: index for index, state in enumerate(used_states)}
    template_blocks = []
    for x, y, z, state in occupied:
        template_blocks.append(compound_payload([
            list_tag("pos", TAG_INT, [
                struct.pack(">i", x - min_x),
                struct.pack(">i", y - min_y),
                struct.pack(">i", z - min_z),
            ]),
            int_tag("state", state_index[state]),
        ]))

    root_tags = [
        int_tag("DataVersion", int(schematic["DataVersion"])),
        list_tag("size", TAG_INT, [struct.pack(">i", value) for value in size]),
        list_tag("palette", TAG_COMPOUND, [palette_entry(state) for state in used_states]),
        list_tag("blocks", TAG_COMPOUND, template_blocks),
        list_tag("entities", TAG_COMPOUND, []),
    ]
    uncompressed = bytes([TAG_COMPOUND]) + nbt_string("") + compound_payload(root_tags)
    converted = gzip.compress(uncompressed, compresslevel=9, mtime=0)
    metadata = {
        "id": "spudtruck",
        "template": "neoncity:merchant_trucks/spudtruck",
        "size": size,
        "blocks": len(template_blocks),
        "body_source_blocks": sorted(BODY_SOURCE_BLOCKS),
        "sha256": hashlib.sha256(converted).hexdigest(),
    }
    return converted, metadata


def import_schematic(source: Path) -> None:
    source_payload = source.read_bytes()
    converted, metadata = convert(source_payload)
    STRUCTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STRUCTURE_PATH.write_bytes(converted)
    catalog = {
        "source": {
            "name": source.name,
            "sha256": hashlib.sha256(source_payload).hexdigest(),
            "format": "Sponge schematic v3 / WorldEdit 7.3.10",
            "policy": "user-provided; stale wall signs, obstructing counter shutters, "
                      "and block-entity data omitted",
        },
        "truck": metadata,
    }
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    (OUTPUT_ROOT / "catalog.json").write_text(
        json.dumps(catalog, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"Imported {source} as {metadata['size']} / {metadata['blocks']} blocks "
        f"({metadata['sha256']})"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("schematic", type=Path)
    args = parser.parse_args()
    import_schematic(args.schematic.resolve())


if __name__ == "__main__":
    main()
