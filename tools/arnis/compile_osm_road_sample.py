#!/usr/bin/env python3
"""Compile every tracked 16x16 Arnis crop into runtime OSM road ribbons."""

from __future__ import annotations

import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROVENANCE = ROOT / "provenance/arnis_districts"
CATALOG_PROVENANCE = ROOT / "provenance/arnis_catalog_sources/index.json"
ARNIS_CATALOG = ROOT / "src/main/resources/data/neoncity/arnis_districts/catalog.json"
OUTPUT = ROOT / "src/main/resources/data/neoncity/osm_roads"

DRIVABLE = {
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
    "secondary", "secondary_link", "tertiary", "tertiary_link", "residential",
    "living_street", "unclassified", "service", "road",
}

DEFAULT_LANES = {
    "motorway": 4, "motorway_link": 2, "trunk": 4, "trunk_link": 2,
    "primary": 3, "primary_link": 2, "secondary": 2, "secondary_link": 1,
    "tertiary": 2, "tertiary_link": 1, "residential": 2,
    "living_street": 1, "unclassified": 2, "service": 1, "road": 2,
}


def lane_count(tags: dict, kind: str) -> int:
    raw = str(tags.get("lanes", ""))
    values = []
    for part in raw.replace("|", ";").split(";"):
        try:
            values.append(int(float(part.strip())))
        except ValueError:
            pass
    return max(1, min(8, max(values, default=DEFAULT_LANES[kind])))


def road_width(tags: dict, kind: str, lanes: int) -> float:
    raw = str(tags.get("width", "")).lower().replace("meters", "").replace("m", "").strip()
    try:
        explicit = float(raw)
        if explicit > 0:
            return round(max(3.0, min(26.0, explicit)), 2)
    except ValueError:
        pass
    shoulder = 2.0 if kind in {
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"
    } else 1.0
    minimum = 5.0 if kind == "service" else 4.0
    return round(max(minimum, min(26.0, lanes * 3.0 + shoulder)), 2)


def project(lat: float, lon: float, metadata: dict, crop_x: int, crop_z: int) -> tuple[float, float]:
    x_ratio = (lon - metadata["minGeoLon"]) / (
        metadata["maxGeoLon"] - metadata["minGeoLon"]
    )
    z_ratio = (metadata["maxGeoLat"] - lat) / (
        metadata["maxGeoLat"] - metadata["minGeoLat"]
    )
    source_x = metadata["minMcX"] + x_ratio * (metadata["maxMcX"] - metadata["minMcX"])
    source_z = metadata["minMcZ"] + z_ratio * (metadata["maxMcZ"] - metadata["minMcZ"])
    return source_x - crop_x, source_z - crop_z


def clip_segment(
    first: tuple[float, float], second: tuple[float, float], size: float
) -> tuple[tuple[float, float], tuple[float, float]] | None:
    """Liang-Barsky clip against the atlas square."""
    x0, z0 = first
    x1, z1 = second
    dx, dz = x1 - x0, z1 - z0
    low, high = 0.0, 1.0
    for p, q in ((-dx, x0), (dx, size - x0), (-dz, z0), (dz, size - z0)):
        if p == 0:
            if q < 0:
                return None
            continue
        ratio = q / p
        if p < 0:
            low = max(low, ratio)
        else:
            high = min(high, ratio)
        if low > high:
            return None
    return ((x0 + low * dx, z0 + low * dz), (x0 + high * dx, z0 + high * dz))


def compile_roads(osm: dict, metadata: dict, min_chunk_x: int, min_chunk_z: int) -> tuple[list, int]:
    nodes = {
        element["id"]: (element["lat"], element["lon"])
        for element in osm["elements"]
        if element.get("type") == "node" and "lat" in element and "lon" in element
    }
    roads = []
    segment_count = 0
    for way in osm["elements"]:
        tags = way.get("tags", {})
        kind = tags.get("highway")
        if way.get("type") != "way" or kind not in DRIVABLE:
            continue
        projected = [
            project(*nodes[node_id], metadata, min_chunk_x * 16, min_chunk_z * 16)
            for node_id in way.get("nodes", [])
            if node_id in nodes
        ]
        segments = []
        for first, second in zip(projected, projected[1:]):
            clipped = clip_segment(first, second, 255.999)
            if clipped is None or math.dist(*clipped) < 0.15:
                continue
            segments.append([[round(x, 3), round(z, 3)] for x, z in clipped])
        if not segments:
            continue
        lanes = lane_count(tags, kind)
        segment_count += len(segments)
        roads.append({
            "id": way["id"],
            "kind": kind,
            "oneway": str(tags.get("oneway", "no")).lower() in {"yes", "1", "true"},
            "lanes": lanes,
            "width": road_width(tags, kind, lanes),
            "maxspeed": tags.get("maxspeed", ""),
            "segments": segments,
        })

    return roads, segment_count


def write_sample(
    slug: str,
    city: str,
    district: str,
    zone: str,
    metadata: dict,
    min_chunk_x: int,
    min_chunk_z: int,
    osm: dict,
    source: str,
) -> dict:
    roads, segment_count = compile_roads(
        osm, metadata, min_chunk_x, min_chunk_z
    )
    if not roads:
        raise RuntimeError(f"{slug}: compiled road overlay is empty")
    result = {
        "format": "neoncity:osm_road_sample",
        "version": 2,
        "sample": slug,
        "city": city,
        "district": district,
        "zone": zone,
        "atlas_chunks": 16,
        "atlas_blocks": 256,
        "source_chunk_origin": [min_chunk_x, min_chunk_z],
        "projection": metadata,
        "source": source,
        "road_count": len(roads),
        "segment_count": segment_count,
        "roads": roads,
    }
    (OUTPUT / f"{slug}.json").write_text(
        json.dumps(result, separators=(",", ":")) + "\n", encoding="utf-8"
    )
    print(f"wrote {slug}: {len(roads)} roads, {segment_count} clipped segments")
    return {
        "id": slug,
        "name": city,
        "district": district,
        "zone": zone,
        "resource": f"{slug}.json",
    }


def compile_tracked_sample(generation_path: Path) -> dict:
    zone_name = generation_path.stem.removesuffix("_generation")
    selection_path = generation_path.with_name(f"{zone_name}_selection.json")
    generation = json.loads(generation_path.read_text(encoding="utf-8"))
    selection = json.loads(selection_path.read_text(encoding="utf-8"))
    osm_path = ROOT / generation["source"]["osm_file"]
    osm = json.loads(osm_path.read_text(encoding="utf-8"))
    metadata = generation["world"]["metadata"]
    chosen = selection["chosen"]["selection"].split("=", 1)[1]
    minimum, maximum = chosen.split(":", 1)
    min_chunk_x, min_chunk_z = (int(value) for value in minimum.split(","))
    max_chunk_x, max_chunk_z = (int(value) for value in maximum.split(","))
    if max_chunk_x - min_chunk_x != 15 or max_chunk_z - min_chunk_z != 15:
        raise RuntimeError(f"{generation_path}: sample crop is not 16x16 chunks")
    return write_sample(
        generation["slug"], generation["city"], generation["district"],
        generation["zone"], metadata, min_chunk_x, min_chunk_z, osm,
        osm_path.relative_to(ROOT).as_posix(),
    )


def source_slug(source_id: str) -> str:
    prefix = "osm-"
    suffix = "-district-atlas-"
    if not source_id.startswith(prefix) or suffix not in source_id:
        raise RuntimeError(f"unrecognized Arnis catalog source id: {source_id}")
    return source_id[len(prefix):source_id.index(suffix)]


def compile_catalog_samples(known_pairs: set[tuple[str, str]]) -> tuple[list[dict], dict[str, str]]:
    manifest = json.loads(CATALOG_PROVENANCE.read_text(encoding="utf-8"))
    snapshots = {
        source["original_arnis_input_sha256"]: ROOT / source["snapshot_file"]
        for source in manifest["sources"]
    }
    patches = json.loads(ARNIS_CATALOG.read_text(encoding="utf-8"))["patches"]
    atlases = {}
    for patch in patches:
        for zone in patch["placement_zones"]:
            key = (patch["district"], zone)
            if key in known_pairs:
                continue
            atlas = atlases.setdefault(key, {"source": patch["source"], "chunks": []})
            if atlas["source"]["input_sha256"] != patch["source"]["input_sha256"]:
                raise RuntimeError(f"{key}: atlas mixes OSM sources")
            atlas["chunks"].append(patch["source"]["bbox"]["chunks"])

    samples = []
    aliases = {}
    for (district, zone), atlas in sorted(atlases.items()):
        source = atlas["source"]
        snapshot = snapshots.get(source["input_sha256"])
        if snapshot is None:
            raise RuntimeError(f"missing catalog OSM snapshot for {source['name']}")
        min_chunk_x = min(chunk["min_x"] for chunk in atlas["chunks"])
        min_chunk_z = min(chunk["min_z"] for chunk in atlas["chunks"])
        max_chunk_x = max(chunk["max_x"] for chunk in atlas["chunks"])
        max_chunk_z = max(chunk["max_z"] for chunk in atlas["chunks"])
        if (max_chunk_x - min_chunk_x, max_chunk_z - min_chunk_z) != (15, 15):
            raise RuntimeError(f"{district}/{zone}: catalog crop is not 16x16 chunks")
        base_slug = source_slug(source["id"])
        slug = f"{base_slug}_{zone.lower()}"
        sample = write_sample(
            slug, f"{source['name']} / {zone.title()}", district, zone,
            source["world_metadata"]["values"], min_chunk_x, min_chunk_z,
            json.loads(snapshot.read_text(encoding="utf-8")),
            snapshot.relative_to(ROOT).as_posix(),
        )
        samples.append(sample)
        if zone == "NEST":
            aliases[base_slug] = slug
    return samples, aliases


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for old in OUTPUT.glob("*.json"):
        old.unlink()
    tracked_paths = sorted(PROVENANCE.glob("*/*_generation.json"))
    samples = [compile_tracked_sample(path) for path in tracked_paths]
    known_pairs = {(sample["district"], sample["zone"]) for sample in samples}
    catalog_samples, catalog_aliases = compile_catalog_samples(known_pairs)
    samples.extend(catalog_samples)
    samples.sort(key=lambda sample: (sample["district"], sample["zone"], sample["id"]))
    aliases = {"singapore": "singapore_raffles_place", "tokyo": "tokyo_shinjuku_nest"}
    aliases.update(catalog_aliases)
    if len(samples) != 70:
        raise RuntimeError(f"expected 70 district-zone overlays, compiled {len(samples)}")
    index = {
        "format": "neoncity:osm_road_sample_index",
        "version": 1,
        "aliases": aliases,
        "samples": samples,
    }
    (OUTPUT / "index.json").write_text(
        json.dumps(index, separators=(",", ":")) + "\n", encoding="utf-8"
    )
    print(f"wrote index: {len(samples)} samples")


if __name__ == "__main__":
    main()
