#!/usr/bin/env python3
"""Rebuild the conservative open-park allowlist from the current Arnis catalog."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = (
    PROJECT_ROOT / "src/main/resources/data/neoncity/arnis_districts/catalog.json"
)
DEFAULT_OUTPUT = DEFAULT_CATALOG.with_name("open_park_tiles.json")
MAXIMUM_BLOCK_COUNT = 320
MAXIMUM_ABOVE_SURFACE = 2

IMPORTER_PATH = Path(__file__).with_name("arnis_import.py")
SPEC = importlib.util.spec_from_file_location("cyberdeck_open_park_import", IMPORTER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load {IMPORTER_PATH}")
IMPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IMPORTER
SPEC.loader.exec_module(IMPORTER)


class AuditFailure(RuntimeError):
    """The catalog or one of its deterministic NBT structures is invalid."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def maximum_above_surface(path: Path, entry: dict[str, Any]) -> int:
    try:
        root = IMPORTER.NbtReader(gzip.decompress(path.read_bytes())).document()
        blocks = root["blocks"]
        anchor = entry["footprint"]["anchor"]
        surface_offset = int(anchor.get("surface_y", anchor["source_y"] + 2)) \
            - int(anchor["source_y"])
        heights = [
            int(block["pos"][1]) - surface_offset
            for block in blocks
            if isinstance(block, dict)
            and isinstance(block.get("pos"), list)
            and len(block["pos"]) == 3
        ]
    except (OSError, KeyError, TypeError, ValueError, gzip.BadGzipFile) as error:
        raise AuditFailure(f"cannot inspect open-park candidate {entry.get('id')}: {error}") from error
    return max(0, max(heights, default=0))


def build_audit(catalog_path: Path) -> dict[str, Any]:
    catalog = IMPORTER.load_catalog(catalog_path)
    entries = catalog["patches"]
    districts = sorted({
        entry["district"]
        for entry in entries
        if isinstance(entry, dict) and isinstance(entry.get("district"), str)
    })
    district_counts = {district: 0 for district in districts}
    height_groups = {height: [] for height in range(MAXIMUM_ABOVE_SURFACE + 1)}
    candidate_count = 0

    for entry in entries:
        if not isinstance(entry, dict):
            raise AuditFailure("catalog contains a non-object patch entry")
        if entry.get("block_count", MAXIMUM_BLOCK_COUNT + 1) > MAXIMUM_BLOCK_COUNT:
            continue
        connectors = entry.get("road_connectors")
        if not isinstance(connectors, list) or not connectors:
            continue
        candidate_count += 1
        file_value = entry.get("file")
        if not isinstance(file_value, str):
            raise AuditFailure(f"candidate {entry.get('id')} has no structure file")
        height = maximum_above_surface(catalog_path.parent / file_value, entry)
        if height > MAXIMUM_ABOVE_SURFACE:
            continue
        patch_id = entry.get("id")
        district = entry.get("district")
        if not isinstance(patch_id, str) or district not in district_counts:
            raise AuditFailure(f"candidate {patch_id!r} has invalid identity metadata")
        height_groups[height].append(patch_id)
        district_counts[district] += 1

    for values in height_groups.values():
        values.sort()
    height_counts = {str(height): len(height_groups[height]) for height in height_groups}
    return {
        "schema_version": 1,
        "generated_from": {
            "catalog_sha256": sha256_file(catalog_path),
            "scanned_sparse_connector_tiles": candidate_count,
        },
        "criteria": {
            "maximum_block_count": MAXIMUM_BLOCK_COUNT,
            "requires_road_connector": True,
            "maximum_occupied_blocks_above_surface": MAXIMUM_ABOVE_SURFACE,
        },
        "tile_count": sum(height_counts.values()),
        "height_counts": height_counts,
        "district_counts": district_counts,
        "tiles_by_max_occupied_blocks_above_surface": {
            str(height): height_groups[height] for height in height_groups
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the checked-in audit differs instead of rewriting it",
    )
    args = parser.parse_args()
    catalog = args.catalog.resolve()
    output = args.output.resolve()
    result = build_audit(catalog)
    encoded = IMPORTER.json_bytes(result)
    if args.check:
        if not output.is_file() or json.loads(output.read_text(encoding="utf-8")) != result:
            raise AuditFailure(f"open-park audit is stale: {output}")
    else:
        IMPORTER.atomic_write(output, encoded)
    print(json.dumps({
        "status": "ok",
        "output": str(output),
        "candidate_count": result["generated_from"]["scanned_sparse_connector_tiles"],
        "tile_count": result["tile_count"],
        "district_counts": result["district_counts"],
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AuditFailure, IMPORTER.ImportFailure, OSError) as error:
        print(json.dumps({"status": "fail", "error": str(error)}, indent=2), file=sys.stderr)
        raise SystemExit(1)
