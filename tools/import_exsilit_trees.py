#!/usr/bin/env python3
"""Convert park-scale trees from Exsilit's legacy MCEdit archive to structure NBT."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = ROOT / "src/main/resources/data/neoncity/park_trees"
STRUCTURE_ROOT = OUTPUT_ROOT / "structures"
SOURCE_PATTERN = re.compile(r"/t([0-9]{3})\.schematic$")
ALLOWED_BLOCKS = {0, 17, 18, 161, 162}
CONIFER_IDS = {
    "t001", "t002", "t004", "t007", "t008", "t009", "t010", "t011", "t012",
    "t067", "t112", "t113", "t114", "t115", "t145", "t146", "t147", "t153",
    "t156", "t157", "t160", "t184", "t204", "t206", "t207", "t209", "t210",
    "t211", "t212", "t213", "t216", "t217", "t230", "t231", "t246", "t255",
    "t265", "t266", "t267", "t268", "t286", "t287", "t288",
}
DATA_VERSION = 3955

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def read_string(stream: io.BytesIO) -> str:
    length = struct.unpack(">H", stream.read(2))[0]
    return stream.read(length).decode("utf-8")


def read_schematic(payload: bytes) -> dict[str, object]:
    stream = io.BytesIO(gzip.decompress(payload))
    if stream.read(1) != bytes([TAG_COMPOUND]):
        raise ValueError("schematic root is not an NBT compound")
    if read_string(stream) != "Schematic":
        raise ValueError("unexpected schematic root name")

    result: dict[str, object] = {}
    while True:
        raw_type = stream.read(1)
        if not raw_type:
            raise ValueError("schematic ended before its root compound")
        tag_type = raw_type[0]
        if tag_type == TAG_END:
            return result
        name = read_string(stream)
        if tag_type == TAG_SHORT:
            result[name] = struct.unpack(">h", stream.read(2))[0]
        elif tag_type == TAG_BYTE_ARRAY:
            length = struct.unpack(">i", stream.read(4))[0]
            result[name] = stream.read(length)
        elif tag_type == TAG_STRING:
            result[name] = read_string(stream)
        elif tag_type == TAG_LIST:
            stream.read(1)
            length = struct.unpack(">i", stream.read(4))[0]
            if length != 0:
                raise ValueError(f"unsupported non-empty {name} list")
            result[name] = []
        else:
            raise ValueError(f"unsupported schematic tag {tag_type} ({name})")


def legacy_block_ids(schematic: dict[str, object]) -> list[int]:
    blocks = schematic["Blocks"]
    if not isinstance(blocks, bytes):
        raise ValueError("Blocks is not a byte array")
    add_blocks = schematic.get("AddBlocks")
    if add_blocks is not None and not isinstance(add_blocks, bytes):
        raise ValueError("AddBlocks is not a byte array")

    result = []
    for index, block_id in enumerate(blocks):
        if add_blocks:
            packed = add_blocks[index // 2]
            block_id |= ((packed >> (4 * (index % 2))) & 0xF) << 8
        result.append(block_id)
    return result


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


def palette_entry(name: str, properties: dict[str, str] | None = None) -> bytes:
    tags = [string_tag("Name", name)]
    if properties:
        property_tags = [string_tag(key, value) for key, value in sorted(properties.items())]
        tags.append(named_tag(TAG_COMPOUND, "Properties", compound_payload(property_tags)))
    return compound_payload(tags)


def state_for(block_id: int, block_data: int) -> int:
    if block_id in (18, 161):
        return 3
    orientation = block_data & 0xC
    if orientation == 0x4:
        return 1
    if orientation == 0x8:
        return 2
    return 0


def convert_schematic(schematic: dict[str, object]) -> tuple[bytes, list[int], int]:
    width = int(schematic["Width"])
    height = int(schematic["Height"])
    length = int(schematic["Length"])
    block_ids = legacy_block_ids(schematic)
    block_data = schematic["Data"]
    if not isinstance(block_data, bytes) or len(block_data) != len(block_ids):
        raise ValueError("Data array does not match Blocks")
    unexpected = set(block_ids) - ALLOWED_BLOCKS
    if unexpected:
        raise ValueError(f"non-tree blocks found: {sorted(unexpected)}")

    occupied = []
    for y in range(height):
        for z in range(length):
            for x in range(width):
                index = y * length * width + z * width + x
                if block_ids[index] != 0:
                    occupied.append((x, y, z, index))
    if not occupied:
        raise ValueError("tree schematic is empty")

    min_x = min(block[0] for block in occupied)
    min_y = min(block[1] for block in occupied)
    min_z = min(block[2] for block in occupied)
    max_x = max(block[0] for block in occupied)
    max_y = max(block[1] for block in occupied)
    max_z = max(block[2] for block in occupied)
    size = [max_x - min_x + 1, max_y - min_y + 1, max_z - min_z + 1]

    palette = [
        palette_entry("minecraft:oak_log", {"axis": "y"}),
        palette_entry("minecraft:oak_log", {"axis": "x"}),
        palette_entry("minecraft:oak_log", {"axis": "z"}),
        palette_entry(
            "minecraft:oak_leaves",
            {"distance": "1", "persistent": "true", "waterlogged": "false"},
        ),
    ]
    blocks = []
    for x, y, z, index in occupied:
        position = [x - min_x, y - min_y, z - min_z]
        tags = [
            list_tag("pos", TAG_INT, [struct.pack(">i", value) for value in position]),
            int_tag("state", state_for(block_ids[index], block_data[index])),
        ]
        blocks.append(compound_payload(tags))

    root_tags = [
        int_tag("DataVersion", DATA_VERSION),
        list_tag("size", TAG_INT, [struct.pack(">i", value) for value in size]),
        list_tag("palette", TAG_COMPOUND, palette),
        list_tag("blocks", TAG_COMPOUND, blocks),
        list_tag("entities", TAG_COMPOUND, []),
    ]
    uncompressed = bytes([TAG_COMPOUND]) + nbt_string("") + compound_payload(root_tags)
    return gzip.compress(uncompressed, compresslevel=9, mtime=0), size, len(blocks)


def import_archive(archive: Path) -> None:
    archive_bytes = archive.read_bytes()
    archive_sha256 = hashlib.sha256(archive_bytes).hexdigest()
    STRUCTURE_ROOT.mkdir(parents=True, exist_ok=True)
    for stale in STRUCTURE_ROOT.glob("*.nbt"):
        stale.unlink()

    templates = []
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as source:
        for source_name in sorted(source.namelist()):
            match = SOURCE_PATTERN.search(source_name)
            if not match:
                continue
            schematic_payload = source.read(source_name)
            schematic = read_schematic(schematic_payload)
            width = int(schematic["Width"])
            height = int(schematic["Height"])
            length = int(schematic["Length"])
            ids = set(legacy_block_ids(schematic))
            if not ids <= ALLOWED_BLOCKS:
                continue
            if width > 11 or length > 11 or not 8 <= height <= 24:
                continue

            template_id = f"t{match.group(1)}"
            converted, size, block_count = convert_schematic(schematic)
            output_path = STRUCTURE_ROOT / f"{template_id}.nbt"
            output_path.write_bytes(converted)
            templates.append(
                {
                    "id": template_id,
                    "source": source_name,
                    "form": "conifer" if template_id in CONIFER_IDS else "broadleaf",
                    "size": size,
                    "blocks": block_count,
                    "sha256": hashlib.sha256(converted).hexdigest(),
                }
            )

    catalog = {
        "source": {
            "name": archive.name,
            "author": "Exsilit",
            "sha256": archive_sha256,
            "selection": "logs/leaves only; maximum 11x24x11 source bounds",
        },
        "templates": templates,
    }
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    (OUTPUT_ROOT / "catalog.json").write_text(
        json.dumps(catalog, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Imported {len(templates)} park trees from {archive} ({archive_sha256})")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    args = parser.parse_args()
    import_archive(args.archive.resolve())


if __name__ == "__main__":
    main()
