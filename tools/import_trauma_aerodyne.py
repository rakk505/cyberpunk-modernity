#!/usr/bin/env python3
"""Convert the supplied Trauma Team Sponge schematic into a native structure template."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct

from import_spudtruck import (
    NbtReader,
    TAG_COMPOUND,
    TAG_INT,
    compound_payload,
    decode_varints,
    int_tag,
    list_tag,
    nbt_string,
    palette_entry,
)


ROOT = Path(__file__).resolve().parents[1]
STRUCTURE_PATH = (
    ROOT / "src/main/resources/data/cyberdeck/structure/trauma_team/aerodyne.nbt"
)
CATALOG_PATH = ROOT / "src/main/resources/data/cyberdeck/trauma_team/catalog.json"
EXPECTED_SIZE = [23, 9, 11]


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
    palette = blocks.get("Palette")
    block_data = blocks.get("Data")
    if not isinstance(palette, dict) or not isinstance(block_data, bytes):
        raise ValueError("schematic palette or block data has an invalid type")

    states_by_id = {int(index): name for name, index in palette.items()}
    indices = decode_varints(block_data, width * height * length)
    occupied: list[tuple[int, int, int, str]] = []
    for y in range(height):
        for z in range(length):
            for x in range(width):
                state = states_by_id[indices[(y * length + z) * width + x]]
                if state != "minecraft:air":
                    occupied.append((x, y, z, state))
    if not occupied:
        raise ValueError("aerodyne schematic is empty")

    # Preserve the declared canvas, including the authored one-block air padding on the X edge.
    size = [width, height, length]
    if size != EXPECTED_SIZE:
        raise ValueError(f"unexpected occupied aerodyne bounds {size}")

    used_states = sorted({block[3] for block in occupied})
    state_index = {state: index for index, state in enumerate(used_states)}
    template_blocks = []
    for x, y, z, state in occupied:
        template_blocks.append(
            compound_payload(
                [
                    list_tag(
                        "pos",
                        TAG_INT,
                        [
                            struct.pack(">i", x),
                            struct.pack(">i", y),
                            struct.pack(">i", z),
                        ],
                    ),
                    int_tag("state", state_index[state]),
                ]
            )
        )

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
        "template": "cyberdeck:trauma_team/aerodyne",
        "size": size,
        "blocks": len(template_blocks),
        "palette_states": len(used_states),
        "sha256": hashlib.sha256(converted).hexdigest(),
    }
    return converted, metadata


def import_schematic(source: Path) -> None:
    source_payload = source.read_bytes()
    converted, metadata = convert(source_payload)
    STRUCTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STRUCTURE_PATH.write_bytes(converted)
    CATALOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CATALOG_PATH.write_text(
        json.dumps(
            {
                "source": {
                    "name": source.name,
                    "sha256": hashlib.sha256(source_payload).hexdigest(),
                    "format": "Sponge schematic v3 / WorldEdit 7.3.10",
                    "policy": "user-provided; block-entity payloads omitted",
                },
                "aerodyne": metadata,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
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
