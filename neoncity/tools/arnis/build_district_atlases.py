#!/usr/bin/env python3
"""Select coherent Arnis neighborhoods and build the A-Z runtime atlas catalog.

Each district contributes two independently coherent 8x8-chunk atlases:
the densest/tallest candidate for its Nest and a medium-grain Backstreets
crop. Runtime maps whole urban zone regions through these atlases; individual
chunks are never randomly shuffled. Outskirts are wilderness outside the city
and deliberately have no Arnis atlas.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import importlib.util
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = PROJECT_ROOT / "provenance/arnis_districts/manifest.json"
WORLD_ROOT = PROJECT_ROOT / "build/arnis_districts"
CATALOG_PATH = (
    PROJECT_ROOT
    / "src/main/resources/data/neoncity/arnis_districts/catalog.json"
)
OUTPUT_DIR = CATALOG_PATH.parent / "structures"
IMPORTER_PATH = Path(__file__).with_name("arnis_import.py")

SPEC = importlib.util.spec_from_file_location("neoncity_arnis_import", IMPORTER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load {IMPORTER_PATH}")
IMPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IMPORTER
SPEC.loader.exec_module(IMPORTER)


class AtlasBuildFailure(RuntimeError):
    """A source world could not yield the required coherent zone atlases."""


@dataclass(frozen=True)
class ChunkMetric:
    x: int
    z: int
    above_surface: int
    max_height: int
    roads: int
    vegetation: int
    water: int


@dataclass(frozen=True)
class CropMetric:
    x: int
    z: int
    size: int
    above_surface: int
    max_height: int
    roads: int
    vegetation: int
    water: int
    occupied_chunks: int

    @property
    def max_x(self) -> int:
        return self.x + self.size - 1

    @property
    def max_z(self) -> int:
        return self.z + self.size - 1


VEGETATION_PARTS = ("leaves", "log", "grass", "moss", "vine", "flower", "sapling")
WATER_PARTS = ("water", "kelp", "seagrass")


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AtlasBuildFailure(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise AtlasBuildFailure(f"{path} is not a JSON object")
    return value


def atomic_json(path: Path, value: Any) -> None:
    IMPORTER.atomic_write(path, IMPORTER.json_bytes(value))


def chunk_metric(
    store: Any,
    chunk_x: int,
    chunk_z: int,
    min_y: int,
    max_y: int,
    surface_y: int,
) -> ChunkMetric:
    selection = IMPORTER.Selection("scan", chunk_x, chunk_z, chunk_x, chunk_z)
    patch = IMPORTER.build_patch(store, selection, min_y, max_y, False)
    surface_offset = surface_y - patch.min_source_y
    above = 0
    vegetation = 0
    water = 0
    max_height = 0
    for _, y, _, state in patch.blocks:
        name = state.name
        if any(part in name for part in WATER_PARTS):
            water += 1
        if y <= surface_offset:
            continue
        above += 1
        max_height = max(max_height, y - surface_offset)
        if any(part in name for part in VEGETATION_PARTS):
            vegetation += 1
    roads = sum(
        1
        for source_y, name in patch.top_surface.values()
        if abs(source_y - surface_y) <= 2 and name in IMPORTER.ROAD_BLOCKS
    )
    return ChunkMetric(
        chunk_x, chunk_z, above, max_height, roads, vegetation, water
    )


def crop_metric(
    metrics: dict[tuple[int, int], ChunkMetric],
    origin_x: int,
    origin_z: int,
    size: int,
) -> CropMetric:
    values = [
        metrics[(x, z)]
        for z in range(origin_z, origin_z + size)
        for x in range(origin_x, origin_x + size)
    ]
    return CropMetric(
        x=origin_x,
        z=origin_z,
        size=size,
        above_surface=sum(value.above_surface for value in values),
        max_height=max(value.max_height for value in values),
        roads=sum(value.roads for value in values),
        vegetation=sum(value.vegetation for value in values),
        water=sum(value.water for value in values),
        occupied_chunks=sum(value.above_surface >= 48 for value in values),
    )


def overlap(left: CropMetric, right: CropMetric) -> int:
    width = max(0, min(left.max_x, right.max_x) - max(left.x, right.x) + 1)
    depth = max(0, min(left.max_z, right.max_z) - max(left.z, right.z) + 1)
    return width * depth


def choose_crops(candidates: list[CropMetric]) -> dict[str, CropMetric]:
    viable = [
        value for value in candidates
        if value.occupied_chunks >= value.size * value.size // 3
        and value.water < value.size * value.size * 256 * 5
    ]
    if len(viable) < 2:
        viable = [value for value in candidates if value.occupied_chunks >= 4]
    if len(viable) < 2:
        raise AtlasBuildFailure("source has fewer than two viable coherent crops")

    nest = max(
        viable,
        key=lambda value: (
            value.above_surface
            + value.max_height * 800
            + value.occupied_chunks * 1200
            + value.roads * 8
            - value.water * 2
        ),
    )
    non_overlapping = [
        value for value in viable
        if value != nest and overlap(value, nest) <= value.size * value.size // 4
    ]
    if not non_overlapping:
        non_overlapping = [value for value in viable if value != nest]

    ordered_density = sorted(value.above_surface for value in non_overlapping)
    back_target = ordered_density[round((len(ordered_density) - 1) * 0.58)]
    backstreets = min(
        non_overlapping,
        key=lambda value: (
            abs(value.above_surface - back_target)
            - value.roads * 3
            - value.occupied_chunks * 100
            + value.water
        ),
    )
    return {
        "NEST": nest,
        "BACKSTREETS": backstreets,
    }


def scan_district(
    entry: dict[str, Any],
    manifest: dict[str, Any],
) -> tuple[dict[str, CropMetric], dict[str, Any]]:
    district = entry["district"]
    world = WORLD_ROOT / district.lower() / "Arnis World 1"
    metadata = load_json(world / "metadata.json")
    atlas_size = int(manifest["atlas"]["chunks_per_axis"])
    min_y = int(manifest["atlas"]["source_min_y"])
    max_y = int(manifest["atlas"]["source_max_y"])
    surface_y = int(manifest["atlas"]["surface_y"])
    min_chunk_x = math.ceil(int(metadata.get("minMcX", 0)) / 16)
    min_chunk_z = math.ceil(int(metadata.get("minMcZ", 0)) / 16)
    max_chunk_x = (int(metadata["maxMcX"]) + 1) // 16 - 1
    max_chunk_z = (int(metadata["maxMcZ"]) + 1) // 16 - 1
    if max_chunk_x - min_chunk_x + 1 < atlas_size:
        raise AtlasBuildFailure(f"{district} source is too narrow for {atlas_size} chunks")
    if max_chunk_z - min_chunk_z + 1 < atlas_size:
        raise AtlasBuildFailure(f"{district} source is too shallow for {atlas_size} chunks")

    store = IMPORTER.RegionStore(world, "overworld")
    metrics: dict[tuple[int, int], ChunkMetric] = {}
    for z in range(min_chunk_z, max_chunk_z + 1):
        for x in range(min_chunk_x, max_chunk_x + 1):
            metrics[(x, z)] = chunk_metric(
                store, x, z, min_y, max_y, surface_y
            )
    candidates = [
        crop_metric(metrics, x, z, atlas_size)
        for z in range(min_chunk_z, max_chunk_z - atlas_size + 2)
        for x in range(min_chunk_x, max_chunk_x - atlas_size + 2)
    ]
    crops = choose_crops(candidates)
    selection_record = {
        "schema_version": 1,
        "district": district,
        "source_world": str(world.relative_to(PROJECT_ROOT)),
        "atlas_chunks": atlas_size,
        "chunk_bounds_scanned": {
            "min_x": min_chunk_x,
            "min_z": min_chunk_z,
            "max_x": max_chunk_x,
            "max_z": max_chunk_z,
        },
        "zones": {
            zone: {
                "selection": (
                    f"{zone.lower()}={crop.x},{crop.z}:{crop.max_x},{crop.max_z}"
                ),
                "above_surface_blocks": crop.above_surface,
                "max_height_above_surface": crop.max_height,
                "occupied_chunks": crop.occupied_chunks,
                "road_surface_cells": crop.roads,
                "vegetation_blocks": crop.vegetation,
                "water_blocks": crop.water,
            }
            for zone, crop in crops.items()
        },
    }
    selection_path = (
        PROJECT_ROOT
        / "provenance/arnis_districts"
        / district.lower()
        / "selection.json"
    )
    atomic_json(selection_path, selection_record)
    return crops, selection_record


def import_district(
    entry: dict[str, Any],
    manifest: dict[str, Any],
    crops: dict[str, CropMetric],
) -> None:
    district = entry["district"]
    world = WORLD_ROOT / district.lower() / "Arnis World 1"
    audit = load_json(
        PROJECT_ROOT
        / "provenance/arnis_districts"
        / district.lower()
        / "generation.json"
    )
    bbox = ",".join(str(value) for value in entry["bbox"])
    atlas = manifest["atlas"]
    for zone, crop in crops.items():
        selection = f"{zone.lower()}={crop.x},{crop.z}:{crop.max_x},{crop.max_z}"
        command = [
            sys.executable,
            str(IMPORTER_PATH),
            "import",
            str(world),
            "--district", district,
            "--source-id", f"osm-{entry['slug']}-district-atlas-v2",
            "--source-name", entry["city"],
            "--source-url", "https://www.openstreetmap.org/copyright",
            "--source-sha256", audit["source"]["osm_sha256"],
            f"--geo-bbox={bbox}",
            "--license", "ODbL-1.0",
            "--license-url", "https://opendatacommons.org/licenses/odbl/1-0/",
            "--attribution", "OpenStreetMap contributors",
            "--selection", selection,
            "--min-y", str(atlas["source_min_y"]),
            "--max-y", str(atlas["source_max_y"]),
            "--surface-y", str(atlas["surface_y"]),
            "--placement-zone", zone,
            "--catalog", str(CATALOG_PATH),
            "--output-dir", str(OUTPUT_DIR),
            "--defer-catalog-validation",
        ]
        completed = subprocess.run(command, cwd=PROJECT_ROOT, check=False)
        if completed.returncode != 0:
            raise AtlasBuildFailure(
                f"import failed for {district} {zone} with exit {completed.returncode}"
            )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--districts", default="ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        help="letters to scan/import, e.g. ABC or A,B,C",
    )
    parser.add_argument("--scan-only", action="store_true")
    parser.add_argument(
        "--reset-output",
        action="store_true",
        help="remove only the generated arnis_districts catalog/output before importing",
    )
    args = parser.parse_args()
    manifest = load_json(MANIFEST_PATH)
    requested = {value for value in args.districts.upper() if "A" <= value <= "Z"}
    if args.reset_output and not args.scan_only and CATALOG_PATH.parent.exists():
        shutil.rmtree(CATALOG_PATH.parent)
    results = []
    for entry in manifest["districts"]:
        if entry["district"] not in requested:
            continue
        crops, record = scan_district(entry, manifest)
        if not args.scan_only:
            import_district(entry, manifest, crops)
        results.append({"district": entry["district"], "zones": record["zones"]})
    if not args.scan_only:
        summary = IMPORTER.validate_catalog(CATALOG_PATH)
    else:
        summary = None
    print(json.dumps({"status": "ok", "districts": results, "catalog": summary}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AtlasBuildFailure, IMPORTER.ImportFailure, OSError) as error:
        print(json.dumps({"status": "fail", "error": str(error)}, indent=2), file=sys.stderr)
        raise SystemExit(1)
