#!/usr/bin/env python3
"""Compile reproducible urban-morphology profiles from saved Arnis OSM data."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import statistics
from typing import Any


EARTH_METERS_PER_DEGREE = 111_320.0
DISTRICTS = {
    "japanese_neon": {
        "label": "Kairocho",
        "source_city": "Tokyo / Shinjuku",
        "default": Path("provenance/tokyo/shinjuku_core_osm.json"),
        "bbox": [35.686000, 139.688000, 35.699500, 139.709500],
        "runtime_intent": "tight mixed parcels, rail megablock, layered alleys",
    },
    "korean_corporate": {
        "label": "Haneul Tech Quarter",
        "source_city": "Seoul / Gangnam",
        "default": Path("provenance/seoul/seoul_gangnam_osm.json"),
        "bbox": [37.496200, 127.026500, 37.509300, 127.047500],
        "runtime_intent": "glass corporate podiums, diagonal side streets, skybridges",
    },
    "chinese_harbor": {
        "label": "Longwei Harbor",
        "source_city": "Shanghai / Lujiazui",
        "default": Path("provenance/shanghai/osm_lujiazui.json"),
        "bbox": [31.229500, 121.488500, 31.243500, 121.506500],
        "runtime_intent": "monumental towers, river curves, red-gold-teal podiums",
    },
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _number(tags: dict[str, Any], name: str) -> float | None:
    value = tags.get(name)
    if value is None:
        return None
    try:
        return float(str(value).split(";")[0].strip().lower().removesuffix("m"))
    except ValueError:
        return None


def _quantiles(values: list[float]) -> dict[str, float] | None:
    if not values:
        return None
    ordered = sorted(values)

    def pick(fraction: float) -> float:
        index = round((len(ordered) - 1) * fraction)
        return round(ordered[index], 3)

    return {
        "min": round(ordered[0], 3),
        "p25": pick(0.25),
        "median": pick(0.5),
        "p75": pick(0.75),
        "p90": pick(0.9),
        "max": round(ordered[-1], 3),
    }


def _polygon_area(points: list[tuple[float, float]]) -> float:
    if len(points) < 3:
        return 0.0
    return abs(sum(
        left[0] * right[1] - right[0] * left[1]
        for left, right in zip(points, points[1:] + points[:1])
    )) * 0.5


def _orientation_entropy(bins: Counter[int], bin_count: int) -> float:
    total = sum(bins.values())
    if not total:
        return 0.0
    entropy = -sum(
        (count / total) * math.log(count / total)
        for count in bins.values()
        if count
    )
    return round(entropy / math.log(bin_count), 6)


def compile_profile(
    path: Path, description: dict[str, str], bbox: list[float]
) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    elements = document.get("elements", [])
    nodes = {
        int(element["id"]): (float(element["lat"]), float(element["lon"]))
        for element in elements
        if element.get("type") == "node" and "lat" in element and "lon" in element
    }
    if not nodes:
        raise RuntimeError(f"no georeferenced OSM nodes in {path}")
    min_lat, min_lon, max_lat, max_lon = bbox
    center_lat = (min_lat + max_lat) * 0.5
    center_lon = (min_lon + max_lon) * 0.5
    lon_scale = EARTH_METERS_PER_DEGREE * math.cos(math.radians(center_lat))

    def meters(node_id: int) -> tuple[float, float] | None:
        point = nodes.get(node_id)
        if point is None:
            return None
        if not (min_lat <= point[0] <= max_lat and min_lon <= point[1] <= max_lon):
            return None
        return (
            (point[1] - center_lon) * lon_scale,
            (point[0] - center_lat) * EARTH_METERS_PER_DEGREE,
        )

    building_areas: list[float] = []
    building_heights: list[float] = []
    road_lengths: list[float] = []
    road_classes: Counter[str] = Counter()
    orientation_bins: Counter[int] = Counter()
    diagonal_segments = 0
    road_segments = 0
    strong_turns = 0
    total_turns = 0
    building_count = 0

    for element in elements:
        if element.get("type") != "way":
            continue
        tags = element.get("tags") or {}
        points = [meters(int(node_id)) for node_id in element.get("nodes", [])]
        points = [point for point in points if point is not None]
        if "building" in tags and len(points) >= 3:
            building_count += 1
            area = _polygon_area(points)
            if 2.0 <= area <= 2_000_000.0:
                building_areas.append(area)
            height = _number(tags, "height")
            if height is None:
                levels = _number(tags, "building:levels")
                height = None if levels is None else levels * 3.2
            if height is not None and 2.0 <= height <= 1_000.0:
                building_heights.append(height)

        highway = tags.get("highway")
        if highway is None or len(points) < 2:
            continue
        road_classes[str(highway)] += 1
        headings: list[float] = []
        length = 0.0
        for left, right in zip(points, points[1:]):
            dx = right[0] - left[0]
            dz = right[1] - left[1]
            segment_length = math.hypot(dx, dz)
            if segment_length < 0.25:
                continue
            length += segment_length
            # Undirected street orientation in [0, 180).
            heading = math.degrees(math.atan2(dz, dx)) % 180.0
            headings.append(heading)
            orientation_bins[int(heading // 15.0) % 12] += 1
            distance_to_cardinal = min(
                abs(heading - angle) for angle in (0.0, 90.0, 180.0)
            )
            if distance_to_cardinal >= 12.0:
                diagonal_segments += 1
            road_segments += 1
        if length:
            road_lengths.append(length)
        for previous, current in zip(headings, headings[1:]):
            delta = abs(current - previous)
            delta = min(delta, 180.0 - delta)
            total_turns += 1
            if delta >= 15.0:
                strong_turns += 1

    width = (max_lon - min_lon) * lon_scale
    depth = (max_lat - min_lat) * EARTH_METERS_PER_DEGREE
    bbox_area = max(1.0, width * depth)
    footprint_area = sum(building_areas)
    return {
        **description,
        "source": {
            "path": str(path),
            "sha256": _sha256(path),
            "center_lat": round(center_lat, 7),
            "center_lon": round(center_lon, 7),
            "approximate_size_m": {"x": round(width, 1), "z": round(depth, 1)},
        },
        "buildings": {
            "ways": building_count,
            "area_m2": _quantiles(building_areas),
            "height_m": _quantiles(building_heights),
            "height_samples": len(building_heights),
            "approximate_footprint_coverage": round(footprint_area / bbox_area, 6),
        },
        "roads": {
            "ways": sum(road_classes.values()),
            "segments": road_segments,
            "length_m": round(sum(road_lengths), 1),
            "orientation_entropy": _orientation_entropy(orientation_bins, 12),
            "diagonal_segment_fraction": round(
                diagonal_segments / max(1, road_segments), 6
            ),
            "strong_turn_fraction": round(strong_turns / max(1, total_turns), 6),
            "classes": dict(road_classes.most_common()),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("src/main/resources/data/neoncity/cultural_profiles.json"),
    )
    args = parser.parse_args()
    root = args.project_root.resolve()
    profiles = {}
    for identifier, config in DISTRICTS.items():
        source = (root / config["default"]).resolve()
        profiles[identifier] = compile_profile(
            source,
            {
                "label": config["label"],
                "source_city": config["source_city"],
                "runtime_intent": config["runtime_intent"],
            },
            config["bbox"],
        )
        profiles[identifier]["source"]["path"] = source.relative_to(root).as_posix()
    result = {
        "schema_version": 1,
        "note": (
            "Urban morphology informs procedural parameters; source geometry is not "
            "stitched across projections and no proprietary game assets are used."
        ),
        "profiles": profiles,
    }
    output = (root / args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
