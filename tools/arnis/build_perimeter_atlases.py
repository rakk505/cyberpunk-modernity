#!/usr/bin/env python3
"""Build provenance-audited 16x16 atlases for the fixed perimeter districts.

The manifest is zone-oriented so a district may intentionally use different
real cities for its Nest and Backstreets. Source worlds remain ignored build
artifacts; saved OSM inputs and compact generation/selection audits are tracked.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = PROJECT_ROOT / "provenance/arnis_districts/perimeter_manifest.json"
DEFAULT_WORLD_ROOT = PROJECT_ROOT / "build/arnis_perimeter"
DEFAULT_CATALOG = (
    PROJECT_ROOT / "src/main/resources/data/neoncity/arnis_districts/catalog.json"
)
DEFAULT_ARNIS = Path.home() / ".local/bin/arnis"
PROVENANCE_ROOT = PROJECT_ROOT / "provenance/arnis_districts"
EXPECTED_DISTRICTS = frozenset(
    ("J", "Q", "AE", "YI", "WANG", "XI", "UI", "UANG", "PON", "POK", "PAK")
)
EXPECTED_SOURCE_COUNT = len(EXPECTED_DISTRICTS) * 2

IMPORTER_PATH = Path(__file__).with_name("arnis_import.py")
SPEC = importlib.util.spec_from_file_location("cyberdeck_arnis_import", IMPORTER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load {IMPORTER_PATH}")
IMPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IMPORTER
SPEC.loader.exec_module(IMPORTER)


class PerimeterBuildFailure(RuntimeError):
    """A manifest, source world, selection, or import violated the build contract."""


@dataclass(frozen=True)
class ChunkMetric:
    x: int
    z: int
    above_surface: int
    max_height: int
    roads: int
    vegetation: int
    water: int
    structural_columns: int
    floating_columns: int


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
    structural_columns: int
    floating_columns: int
    occupied_chunks: int
    developed_chunks: int

    @property
    def max_x(self) -> int:
        return self.x + self.size - 1

    @property
    def max_z(self) -> int:
        return self.z + self.size - 1


VEGETATION_PARTS = ("leaves", "log", "grass", "moss", "vine", "flower", "sapling")
WATER_PARTS = ("water", "kelp", "seagrass")
NON_STRUCTURAL_PARTS = VEGETATION_PARTS + WATER_PARTS + (
    "air", "torch", "lantern", "light", "rail", "chain", "sign",
)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise PerimeterBuildFailure(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise PerimeterBuildFailure(f"{path} is not a JSON object")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def relative(path: Path) -> str:
    try:
        return path.resolve().relative_to(PROJECT_ROOT).as_posix()
    except ValueError:
        return str(path.resolve())


def atomic_json(path: Path, value: Any) -> None:
    IMPORTER.atomic_write(path, IMPORTER.json_bytes(value))


def source_key(entry: dict[str, Any]) -> str:
    return f"{entry['district']}:{entry['zone']}"


def validate_manifest(path: Path) -> dict[str, Any]:
    manifest = load_json(path)
    if manifest.get("schema_version") != 1:
        raise PerimeterBuildFailure("perimeter manifest schema_version must be 1")
    if manifest.get("arnis_version") != IMPORTER.ARNIS_VERSION:
        raise PerimeterBuildFailure("perimeter manifest Arnis version disagrees with importer")
    expected_hash = manifest.get("arnis_binary_sha256")
    if not isinstance(expected_hash, str) or len(expected_hash) != 64:
        raise PerimeterBuildFailure("perimeter manifest has no Arnis binary SHA-256")
    atlas = manifest.get("atlas")
    if not isinstance(atlas, dict) or atlas.get("chunks_per_axis") != 16:
        raise PerimeterBuildFailure("perimeter atlases must be exactly 16x16 chunks")
    for field in ("source_min_y", "source_max_y", "surface_y"):
        if not isinstance(atlas.get(field), int):
            raise PerimeterBuildFailure(f"perimeter manifest atlas.{field} must be an integer")
    if not atlas["source_min_y"] <= atlas["surface_y"] <= atlas["source_max_y"]:
        raise PerimeterBuildFailure("atlas surface Y is outside the imported Y range")

    sources = manifest.get("sources")
    if not isinstance(sources, list) or len(sources) != EXPECTED_SOURCE_COUNT:
        raise PerimeterBuildFailure(
            f"perimeter manifest must contain exactly {EXPECTED_SOURCE_COUNT} zone sources"
        )
    keys: set[str] = set()
    slugs: set[str] = set()
    zones_by_district: dict[str, set[str]] = {}
    for entry in sources:
        if not isinstance(entry, dict):
            raise PerimeterBuildFailure("perimeter source entry is not an object")
        district = entry.get("district")
        zone = entry.get("zone")
        slug = entry.get("slug")
        if not isinstance(district, str) or not IMPORTER._DISTRICT.fullmatch(district):
            raise PerimeterBuildFailure(f"invalid district key {district!r}")
        if district not in EXPECTED_DISTRICTS:
            raise PerimeterBuildFailure(f"unexpected perimeter district {district}")
        if zone not in IMPORTER.PLACEMENT_ZONES:
            raise PerimeterBuildFailure(f"invalid placement zone {zone!r} for {district}")
        if not isinstance(slug, str) or not IMPORTER._SLUG.fullmatch(slug):
            raise PerimeterBuildFailure(f"invalid source slug {slug!r}")
        key = source_key(entry)
        if key in keys or slug in slugs:
            raise PerimeterBuildFailure(f"duplicate perimeter source key or slug: {key}")
        keys.add(key)
        slugs.add(slug)
        zones_by_district.setdefault(district, set()).add(zone)
        bbox = entry.get("bbox")
        if not (
            isinstance(bbox, list)
            and len(bbox) == 4
            and all(isinstance(value, (int, float)) for value in bbox)
            and -90 <= bbox[0] < bbox[2] <= 90
            and -180 <= bbox[1] < bbox[3] <= 180
        ):
            raise PerimeterBuildFailure(f"{key} has an invalid geographic bbox")
        if not isinstance(entry.get("city"), str) or not isinstance(entry.get("culture"), str):
            raise PerimeterBuildFailure(f"{key} must declare city and culture strings")
    if set(zones_by_district) != EXPECTED_DISTRICTS or any(
        zones != set(IMPORTER.PLACEMENT_ZONES) for zones in zones_by_district.values()
    ):
        raise PerimeterBuildFailure(
            "every perimeter district must declare one Nest and one Backstreets source"
        )
    return manifest


def source_paths(
    entry: dict[str, Any], world_root: Path
) -> tuple[Path, Path, Path, Path]:
    district = entry["district"].lower()
    zone = entry["zone"].lower()
    destination = world_root / district / zone
    world = destination / "Arnis World 1"
    provenance = PROVENANCE_ROOT / district
    osm = provenance / f"{zone}_{entry['slug']}_osm.json"
    generation_audit = provenance / f"{zone}_generation.json"
    return destination, world, osm, generation_audit


def require_world(world: Path) -> tuple[dict[str, Any], list[Path]]:
    required = (world / "metadata.json", world / "level.dat", world / "arnis_world_map.png")
    if not all(path.is_file() for path in required):
        raise PerimeterBuildFailure(f"Arnis world is incomplete: {world}")
    regions = sorted((world / "region").glob("r.*.*.mca"))
    if not regions:
        raise PerimeterBuildFailure(f"Arnis world contains no region files: {world}")
    return load_json(world / "metadata.json"), regions


def generation_command(
    manifest: dict[str, Any], entry: dict[str, Any], arnis: Path, destination: Path, osm: Path
) -> list[str]:
    generation = manifest["generation"]
    command = [
        str(arnis),
        "--bbox", ",".join(str(value) for value in entry["bbox"]),
    ]
    if osm.is_file():
        command.extend(("--file", str(osm)))
    else:
        command.extend(("--save-json-file", str(osm)))
    command.extend((
        "--output-dir", str(destination),
        "--scale", str(generation["scale"]),
        "--projection", str(generation["projection"]),
        f"--ground-level={generation['ground_level']}",
        "--fillground",
        "--overture", "false",
        "--no-3d",
        "--map-preview",
        "--map-item", "false",
        "--gamemode", str(generation["gamemode"]),
        "--world-time", str(generation["world_time"]),
    ))
    return command


def generate_source(
    manifest: dict[str, Any],
    manifest_path: Path,
    entry: dict[str, Any],
    arnis: Path,
    world_root: Path,
    force: bool,
) -> dict[str, Any]:
    destination, world, osm, audit_path = source_paths(entry, world_root)
    if force and destination.exists():
        shutil.rmtree(destination)
    if destination.exists() and not world.is_dir():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)
    osm.parent.mkdir(parents=True, exist_ok=True)
    if not world.is_dir():
        command = generation_command(manifest, entry, arnis, destination, osm)
        print(f"[arnis] generating {source_key(entry)} from {entry['city']}", flush=True)
        completed = subprocess.run(command, cwd=PROJECT_ROOT, check=False)
        if completed.returncode != 0:
            raise PerimeterBuildFailure(
                f"Arnis failed for {source_key(entry)} with exit {completed.returncode}"
            )
    else:
        print(f"[arnis] reusing {relative(world)} for {source_key(entry)}", flush=True)
    if not osm.is_file():
        raise PerimeterBuildFailure(f"{source_key(entry)} produced no saved OSM input")

    metadata, regions = require_world(world)
    bbox = entry["bbox"]
    expected_metadata = {
        "minGeoLat": bbox[0],
        "minGeoLon": bbox[1],
        "maxGeoLat": bbox[2],
        "maxGeoLon": bbox[3],
        "projection": manifest["generation"]["projection"],
        "scale": manifest["generation"]["scale"],
    }
    for name, expected in expected_metadata.items():
        actual = metadata.get(name)
        equal = actual == expected
        if isinstance(actual, (int, float)) and isinstance(expected, (int, float)):
            equal = math.isclose(float(actual), float(expected), abs_tol=1.0e-9)
        if not equal:
            raise PerimeterBuildFailure(
                f"{source_key(entry)} metadata {name}={actual!r}, expected {expected!r}; "
                "rerun with --force-generate"
            )

    binary_hash = sha256_file(arnis)
    if binary_hash != manifest["arnis_binary_sha256"]:
        raise PerimeterBuildFailure(
            f"Arnis binary hash {binary_hash} disagrees with the perimeter manifest"
        )
    audit = {
        "schema_version": 1,
        "kind": "neoncity:arnis_zone_source_generation",
        "district": entry["district"],
        "zone": entry["zone"],
        "slug": entry["slug"],
        "city": entry["city"],
        "culture": entry["culture"],
        "bbox": bbox,
        "manifest": {
            "file": relative(manifest_path),
            "sha256": sha256_file(manifest_path),
        },
        "generator": {
            "name": "Arnis",
            "version": manifest["arnis_version"],
            "binary_sha256": binary_hash,
            "settings": manifest["generation"],
        },
        "license": {
            "id": "ODbL-1.0",
            "url": "https://opendatacommons.org/licenses/odbl/1-0/",
            "attribution": "OpenStreetMap contributors",
        },
        "source": {
            "osm_file": relative(osm),
            "osm_sha256": sha256_file(osm),
        },
        "world": {
            "bundled": False,
            "build_path": relative(world),
            "metadata": metadata,
            "metadata_sha256": sha256_file(world / "metadata.json"),
            "level_dat_sha256": sha256_file(world / "level.dat"),
            "preview_sha256": sha256_file(world / "arnis_world_map.png"),
            "regions": [
                {
                    "file": path.name,
                    "bytes": path.stat().st_size,
                    "sha256": sha256_file(path),
                }
                for path in regions
            ],
        },
    }
    atomic_json(audit_path, audit)
    return audit


def is_structural(name: str) -> bool:
    path = name.partition(":")[2]
    return not any(part in path for part in NON_STRUCTURAL_PARTS)


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
    max_height = 0
    vegetation = 0
    water = 0
    structural: dict[tuple[int, int], int] = {}
    for x, y, z, state in patch.blocks:
        name = state.name
        if any(part in name for part in WATER_PARTS):
            water += 1
        if y <= surface_offset:
            continue
        above += 1
        max_height = max(max_height, y - surface_offset)
        if any(part in name for part in VEGETATION_PARTS):
            vegetation += 1
        if is_structural(name):
            column = (x, z)
            structural[column] = min(structural.get(column, y), y)
    floating = sum(value > surface_offset + 1 for value in structural.values())
    roads = sum(
        1
        for source_height, name in patch.top_surface.values()
        if abs(source_height - surface_y) <= 2 and name in IMPORTER.ROAD_BLOCKS
    )
    return ChunkMetric(
        chunk_x,
        chunk_z,
        above,
        max_height,
        roads,
        vegetation,
        water,
        len(structural),
        floating,
    )


def crop_metric(
    metrics: dict[tuple[int, int], ChunkMetric], origin_x: int, origin_z: int, size: int
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
        structural_columns=sum(value.structural_columns for value in values),
        floating_columns=sum(value.floating_columns for value in values),
        occupied_chunks=sum(value.above_surface >= 48 for value in values),
        developed_chunks=sum(value.structural_columns >= 16 for value in values),
    )


def crop_score(crop: CropMetric, zone: str) -> int:
    floating_penalty = crop.floating_columns * 1200
    water_penalty = crop.water * 3
    if zone == "NEST":
        return (
            crop.above_surface
            + crop.max_height * 1200
            + crop.occupied_chunks * 2200
            + crop.developed_chunks * 900
            + crop.roads * 8
            + crop.vegetation
            - floating_penalty
            - water_penalty
        )
    return (
        crop.above_surface
        + crop.occupied_chunks * 1900
        + crop.developed_chunks * 1100
        + crop.roads * 12
        + crop.vegetation * 2
        - crop.max_height * 180
        - floating_penalty
        - water_penalty
    )


def choose_crop(candidates: list[CropMetric], zone: str) -> tuple[CropMetric, str]:
    if not candidates:
        raise PerimeterBuildFailure("source world yielded no 16x16 crop candidates")
    area = candidates[0].size * candidates[0].size
    water_limit = area * 256 * 5
    quality_tiers = (
        ("strict", lambda value: value.occupied_chunks >= area * 2 // 5
         and value.developed_chunks >= area // 3 and value.water < water_limit),
        ("developed", lambda value: value.occupied_chunks >= area // 3
         and value.developed_chunks >= area // 4 and value.water < water_limit),
        ("occupied", lambda value: value.occupied_chunks >= area // 6),
        ("fallback", lambda value: True),
    )
    for tier, predicate in quality_tiers:
        viable = [value for value in candidates if predicate(value)]
        if viable:
            return max(
                viable,
                key=lambda value: (
                    crop_score(value, zone),
                    value.occupied_chunks,
                    value.developed_chunks,
                    -value.floating_columns,
                    -value.z,
                    -value.x,
                ),
            ), tier
    raise AssertionError("fallback crop tier must always be populated")


def metric_record(crop: CropMetric, zone: str) -> dict[str, Any]:
    return {
        "selection": f"{zone.lower()}={crop.x},{crop.z}:{crop.max_x},{crop.max_z}",
        "score": crop_score(crop, zone),
        "above_surface_blocks": crop.above_surface,
        "max_height_above_surface": crop.max_height,
        "occupied_chunks": crop.occupied_chunks,
        "developed_chunks": crop.developed_chunks,
        "road_surface_cells": crop.roads,
        "vegetation_blocks": crop.vegetation,
        "water_blocks": crop.water,
        "structural_columns": crop.structural_columns,
        "floating_structural_columns": crop.floating_columns,
    }


def select_source(
    manifest: dict[str, Any], entry: dict[str, Any], world_root: Path
) -> tuple[CropMetric, dict[str, Any]]:
    _, world, osm, generation_path = source_paths(entry, world_root)
    metadata, _ = require_world(world)
    atlas = manifest["atlas"]
    size = int(atlas["chunks_per_axis"])
    min_chunk_x = math.ceil(int(metadata.get("minMcX", 0)) / 16)
    min_chunk_z = math.ceil(int(metadata.get("minMcZ", 0)) / 16)
    max_chunk_x = (int(metadata["maxMcX"]) + 1) // 16 - 1
    max_chunk_z = (int(metadata["maxMcZ"]) + 1) // 16 - 1
    if max_chunk_x - min_chunk_x + 1 < size or max_chunk_z - min_chunk_z + 1 < size:
        raise PerimeterBuildFailure(
            f"{source_key(entry)} source is smaller than one {size}x{size} atlas"
        )

    print(
        f"[scan] {source_key(entry)} chunks "
        f"{min_chunk_x},{min_chunk_z}:{max_chunk_x},{max_chunk_z}",
        flush=True,
    )
    store = IMPORTER.RegionStore(world, "overworld")
    metrics: dict[tuple[int, int], ChunkMetric] = {}
    for z in range(min_chunk_z, max_chunk_z + 1):
        for x in range(min_chunk_x, max_chunk_x + 1):
            metrics[(x, z)] = chunk_metric(
                store,
                x,
                z,
                int(atlas["source_min_y"]),
                int(atlas["source_max_y"]),
                int(atlas["surface_y"]),
            )
    candidates = [
        crop_metric(metrics, x, z, size)
        for z in range(min_chunk_z, max_chunk_z - size + 2)
        for x in range(min_chunk_x, max_chunk_x - size + 2)
    ]
    crop, quality_tier = choose_crop(candidates, entry["zone"])
    generation_audit = load_json(generation_path)
    record = {
        "schema_version": 1,
        "kind": "neoncity:arnis_zone_atlas_selection",
        "district": entry["district"],
        "zone": entry["zone"],
        "slug": entry["slug"],
        "city": entry["city"],
        "source_world": relative(world),
        "source_osm": {
            "file": relative(osm),
            "sha256": generation_audit["source"]["osm_sha256"],
        },
        "atlas_chunks": size,
        "chunk_bounds_scanned": {
            "min_x": min_chunk_x,
            "min_z": min_chunk_z,
            "max_x": max_chunk_x,
            "max_z": max_chunk_z,
        },
        "candidate_count": len(candidates),
        "quality_tier": quality_tier,
        "chosen": metric_record(crop, entry["zone"]),
    }
    selection_path = (
        PROVENANCE_ROOT / entry["district"].lower() / f"{entry['zone'].lower()}_selection.json"
    )
    atomic_json(selection_path, record)
    print(
        f"[scan] selected {record['chosen']['selection']} tier={quality_tier} "
        f"score={record['chosen']['score']}",
        flush=True,
    )
    return crop, record


def import_source(
    manifest: dict[str, Any],
    entry: dict[str, Any],
    crop: CropMetric,
    world_root: Path,
    catalog: Path,
) -> dict[str, Any]:
    _, world, _, generation_path = source_paths(entry, world_root)
    generation = load_json(generation_path)
    atlas = manifest["atlas"]
    source_id = f"osm-{entry['slug']}-{entry['zone'].lower()}-atlas-v1-16x16"
    arguments = [
        "import",
        str(world),
        "--district", entry["district"],
        "--source-id", source_id,
        "--source-name", entry["city"],
        "--source-url", "https://www.openstreetmap.org/copyright",
        "--source-sha256", generation["source"]["osm_sha256"],
        "--geo-bbox=" + ",".join(str(value) for value in entry["bbox"]),
        "--license", "ODbL-1.0",
        "--license-url", "https://opendatacommons.org/licenses/odbl/1-0/",
        "--attribution", "OpenStreetMap contributors",
        "--selection",
        f"{entry['zone'].lower()}={crop.x},{crop.z}:{crop.max_x},{crop.max_z}",
        "--min-y", str(atlas["source_min_y"]),
        "--max-y", str(atlas["source_max_y"]),
        "--surface-y", str(atlas["surface_y"]),
        "--placement-zone", entry["zone"],
        "--catalog", str(catalog),
        "--output-dir", str(catalog.parent / "structures"),
        "--defer-catalog-validation",
    ]
    print(f"[import] {source_key(entry)} -> {entry['district'].lower()}/{entry['zone'].lower()}_*", flush=True)
    return IMPORTER.import_world(IMPORTER.parser().parse_args(arguments))


def replace_catalog_entries(
    manifest: dict[str, Any],
    selections: dict[str, CropMetric],
    world_root: Path,
    catalog_path: Path,
) -> dict[str, Any]:
    catalog = IMPORTER.load_catalog(catalog_path)
    structures = catalog_path.parent / "structures"
    original_catalog = catalog_path.read_bytes()
    targets = set(EXPECTED_DISTRICTS)
    removed = [entry for entry in catalog["patches"] if entry.get("district") in targets]
    retained = [entry for entry in catalog["patches"] if entry.get("district") not in targets]
    backup_parent = PROJECT_ROOT / "build"
    backup_parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="perimeter-atlas-backup-", dir=backup_parent) as temp:
        backup = Path(temp)
        for district in targets:
            source = structures / district.lower()
            if source.is_dir():
                shutil.copytree(source, backup / district.lower())
        try:
            catalog["patches"] = retained
            IMPORTER.atomic_write(catalog_path, IMPORTER.json_bytes(catalog))
            for district in targets:
                shutil.rmtree(structures / district.lower(), ignore_errors=True)
            imports = []
            for entry in manifest["sources"]:
                imports.append(import_source(
                    manifest,
                    entry,
                    selections[source_key(entry)],
                    world_root,
                    catalog_path,
                ))
            validation = IMPORTER.validate_catalog(catalog_path)
        except BaseException:
            IMPORTER.atomic_write(catalog_path, original_catalog)
            for district in targets:
                destination = structures / district.lower()
                shutil.rmtree(destination, ignore_errors=True)
                saved = backup / district.lower()
                if saved.is_dir():
                    shutil.copytree(saved, destination)
            raise
    return {
        "removed_patch_count": len(removed),
        "imported_atlas_count": len(imports),
        "validation": validation,
    }


def selected_sources(
    manifest: dict[str, Any], requested: list[str] | None
) -> list[dict[str, Any]]:
    if not requested:
        return list(manifest["sources"])
    normalized = {value.strip().upper() for item in requested for value in item.split(",") if value.strip()}
    known = {source_key(entry) for entry in manifest["sources"]}
    unknown = normalized - known
    if unknown:
        raise PerimeterBuildFailure(f"unknown --source values: {', '.join(sorted(unknown))}")
    return [entry for entry in manifest["sources"] if source_key(entry) in normalized]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--world-root", type=Path, default=DEFAULT_WORLD_ROOT)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--arnis", type=Path, default=DEFAULT_ARNIS)
    parser.add_argument(
        "--source",
        action="append",
        help="generate/scan selected DISTRICT:ZONE keys; partial selection cannot be imported",
    )
    parser.add_argument("--force-generate", action="store_true")
    parser.add_argument("--generate-only", action="store_true")
    parser.add_argument("--scan-only", action="store_true")
    args = parser.parse_args()
    if args.generate_only and args.scan_only:
        raise PerimeterBuildFailure("--generate-only and --scan-only are mutually exclusive")

    manifest_path = args.manifest.resolve()
    manifest = validate_manifest(manifest_path)
    arnis = args.arnis.expanduser().resolve()
    if not arnis.is_file():
        raise PerimeterBuildFailure(f"Arnis binary does not exist: {arnis}")
    if sha256_file(arnis) != manifest["arnis_binary_sha256"]:
        raise PerimeterBuildFailure("selected Arnis binary does not match the manifest")
    world_root = args.world_root.resolve()
    catalog = args.catalog.resolve()
    sources = selected_sources(manifest, args.source)
    if len(sources) != len(manifest["sources"]) and not (args.generate_only or args.scan_only):
        raise PerimeterBuildFailure("partial --source selection requires --generate-only or --scan-only")

    generation_results = []
    for entry in sources:
        generation_results.append(generate_source(
            manifest,
            manifest_path,
            entry,
            arnis,
            world_root,
            args.force_generate,
        ))
    if args.generate_only:
        print(json.dumps({
            "status": "ok",
            "phase": "generate",
            "sources": [source_key(entry) for entry in sources],
        }, indent=2, sort_keys=True))
        return 0

    selections: dict[str, CropMetric] = {}
    selection_records = []
    for entry in sources:
        crop, record = select_source(manifest, entry, world_root)
        selections[source_key(entry)] = crop
        selection_records.append(record)
    if args.scan_only:
        print(json.dumps({
            "status": "ok",
            "phase": "scan",
            "selections": selection_records,
        }, indent=2, sort_keys=True))
        return 0

    replacement = replace_catalog_entries(manifest, selections, world_root, catalog)
    print(json.dumps({
        "status": "ok",
        "phase": "complete",
        "sources": [source_key(entry) for entry in sources],
        "replacement": replacement,
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (PerimeterBuildFailure, IMPORTER.ImportFailure, OSError) as error:
        print(json.dumps({"status": "fail", "error": str(error)}, indent=2), file=sys.stderr)
        raise SystemExit(1)
