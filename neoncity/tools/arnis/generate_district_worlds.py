#!/usr/bin/env python3
"""Generate provenance-audited Arnis source worlds for A-Z district atlases.

The Minecraft worlds are reproducible build inputs under ``build/`` and are not
committed. Saved OSM JSON and compact hash manifests live under
``provenance/arnis_districts`` so every bundled StructureTemplate can be traced
to an explicit ODbL input with Overture, terrain, and external models disabled.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = PROJECT_ROOT / "provenance/arnis_districts/manifest.json"
DEFAULT_WORLD_ROOT = PROJECT_ROOT / "build/arnis_districts"
DEFAULT_ARNIS = Path.home() / ".local/bin/arnis"


class GenerationFailure(RuntimeError):
    """One manifest entry or generated world violated the build contract."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GenerationFailure(f"cannot read manifest {path}: {error}") from error
    districts = value.get("districts")
    if not isinstance(districts, list) or len(districts) != 26:
        raise GenerationFailure("district manifest must contain exactly 26 entries")
    codes = [entry.get("district") for entry in districts if isinstance(entry, dict)]
    if codes != list("ABCDEFGHIJKLMNOPQRSTUVWXYZ"):
        raise GenerationFailure("district manifest must be ordered exactly A through Z")
    return value


def relative(path: Path) -> str:
    try:
        return path.resolve().relative_to(PROJECT_ROOT).as_posix()
    except ValueError:
        return str(path.resolve())


def generate(
    manifest: dict[str, Any],
    entry: dict[str, Any],
    arnis: Path,
    world_root: Path,
    force: bool,
) -> dict[str, Any]:
    district = entry["district"]
    slug = entry["slug"]
    bbox = entry["bbox"]
    if not (
        isinstance(bbox, list)
        and len(bbox) == 4
        and all(isinstance(value, (int, float)) for value in bbox)
        and bbox[0] < bbox[2]
        and bbox[1] < bbox[3]
    ):
        raise GenerationFailure(f"{district} has an invalid bbox")

    destination = world_root / district.lower()
    if force and destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)

    existing = entry.get("existing_osm")
    if existing is not None:
        osm_path = (PROJECT_ROOT / existing).resolve()
    else:
        osm_path = (
            PROJECT_ROOT
            / "provenance/arnis_districts"
            / district.lower()
            / f"{slug}_osm.json"
        )
    osm_path.parent.mkdir(parents=True, exist_ok=True)

    world = destination / "Arnis World 1"
    if not world.is_dir():
        bbox_value = ",".join(str(value) for value in bbox)
        command = [
            str(arnis),
            "--bbox", bbox_value,
            "--output-dir", str(destination),
            "--projection", "local",
            f"--ground-level={manifest['generation']['ground_level']}",
            "--fillground",
            "--overture", "false",
            "--no-3d",
            "--map-preview",
            "--map-item", "false",
            "--gamemode", "creative",
            "--world-time", str(manifest["generation"]["world_time"]),
        ]
        if osm_path.is_file():
            command[3:3] = ["--file", str(osm_path)]
        else:
            command[3:3] = ["--save-json-file", str(osm_path)]
        completed = subprocess.run(command, cwd=PROJECT_ROOT, check=False)
        if completed.returncode != 0:
            raise GenerationFailure(
                f"Arnis failed for {district} with exit code {completed.returncode}"
            )
    if not osm_path.is_file():
        raise GenerationFailure(f"{district} produced no saved OSM input")
    metadata_path = world / "metadata.json"
    level_path = world / "level.dat"
    preview_path = world / "arnis_world_map.png"
    region_paths = sorted((world / "region").glob("r.*.*.mca"))
    if not (metadata_path.is_file() and level_path.is_file() and preview_path.is_file()):
        raise GenerationFailure(f"{district} generated world is incomplete: {world}")
    if not region_paths:
        raise GenerationFailure(f"{district} generated no region files")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    expected_metadata = {
        "minGeoLat": bbox[0],
        "minGeoLon": bbox[1],
        "maxGeoLat": bbox[2],
        "maxGeoLon": bbox[3],
        "projection": "local",
        "scale": 1.0,
    }
    for key, expected in expected_metadata.items():
        if metadata.get(key) != expected:
            raise GenerationFailure(
                f"{district} metadata {key}={metadata.get(key)!r}, expected {expected!r}"
            )

    audit = {
        "schema_version": 1,
        "district": district,
        "slug": slug,
        "city": entry["city"],
        "culture": entry["culture"],
        "bbox": bbox,
        "generator": {
            "name": "Arnis",
            "version": manifest["arnis_version"],
            "binary_sha256": sha256_file(arnis),
            "settings": manifest["generation"],
        },
        "license": {
            "id": "ODbL-1.0",
            "url": "https://opendatacommons.org/licenses/odbl/1-0/",
            "attribution": "OpenStreetMap contributors",
        },
        "source": {
            "osm_file": relative(osm_path),
            "osm_sha256": sha256_file(osm_path),
        },
        "world": {
            "bundled": False,
            "build_path": relative(world),
            "metadata": metadata,
            "metadata_sha256": sha256_file(metadata_path),
            "level_dat_sha256": sha256_file(level_path),
            "preview_sha256": sha256_file(preview_path),
            "regions": [
                {
                    "file": path.name,
                    "bytes": path.stat().st_size,
                    "sha256": sha256_file(path),
                }
                for path in region_paths
            ],
        },
    }
    if audit["generator"]["binary_sha256"] != manifest["arnis_binary_sha256"]:
        raise GenerationFailure(f"{district} used an unexpected Arnis binary")
    audit_path = (
        PROJECT_ROOT
        / "provenance/arnis_districts"
        / district.lower()
        / "generation.json"
    )
    atomic_write(audit_path, json_bytes(audit))
    return {
        "district": district,
        "world": relative(world),
        "osm": relative(osm_path),
        "metadata_size": [metadata["maxMcX"] + 1, metadata["maxMcZ"] + 1],
        "regions": len(region_paths),
        "audit": relative(audit_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--world-root", type=Path, default=DEFAULT_WORLD_ROOT)
    parser.add_argument("--arnis", type=Path, default=DEFAULT_ARNIS)
    parser.add_argument(
        "--districts",
        default="ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        help="letters to generate, e.g. ACF or A,C,F",
    )
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    manifest = load_manifest(args.manifest.resolve())
    arnis = args.arnis.expanduser().resolve()
    if not arnis.is_file():
        raise GenerationFailure(f"Arnis binary does not exist: {arnis}")
    requested = {value for value in args.districts.upper() if "A" <= value <= "Z"}
    if not requested:
        raise GenerationFailure("--districts selected no A-Z district codes")
    results = []
    for entry in manifest["districts"]:
        if entry["district"] in requested:
            results.append(generate(
                manifest, entry, arnis, args.world_root.resolve(), args.force
            ))
    print(json.dumps({"status": "ok", "districts": results}, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GenerationFailure as error:
        print(json.dumps({"status": "fail", "error": str(error)}, indent=2), file=sys.stderr)
        raise SystemExit(1)
