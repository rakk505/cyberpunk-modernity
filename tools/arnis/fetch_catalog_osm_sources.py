#!/usr/bin/env python3
"""Fetch current OSM highway snapshots for catalog atlases lacking raw provenance."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "src/main/resources/data/neoncity/arnis_districts/catalog.json"
PROVENANCE = ROOT / "provenance/arnis_catalog_sources"
ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
SOURCE_ID = re.compile(r"^osm-(.+)-district-atlas-")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def retained_hashes() -> set[str]:
    hashes = set()
    for path in (ROOT / "provenance/arnis_districts").glob("*/*_generation.json"):
        value = json.loads(path.read_text(encoding="utf-8"))
        hashes.add(value["source"]["osm_sha256"])
    return hashes


def catalog_sources() -> list[dict]:
    patches = json.loads(CATALOG.read_text(encoding="utf-8"))["patches"]
    unique = {}
    for patch in patches:
        source = patch["source"]
        unique.setdefault(source["input_sha256"], source)
    return list(unique.values())


def source_slug(source: dict) -> str:
    match = SOURCE_ID.match(source["id"])
    if match is None:
        raise RuntimeError(f"unrecognized Arnis source id: {source['id']}")
    return match.group(1)


def overpass_query(source: dict) -> str:
    bounds = source["geographic_bbox"]
    return (
        "[out:json][timeout:90];"
        f"way[\"highway\"]({bounds['min_lat']},{bounds['min_lon']},"
        f"{bounds['max_lat']},{bounds['max_lon']});"
        "(._;>;);out body;"
    )


def fetch(query: str) -> tuple[bytes, str]:
    body = urlencode({"data": query}).encode("ascii")
    error = None
    for attempt in range(6):
        endpoint = ENDPOINTS[attempt % len(ENDPOINTS)]
        request = Request(
            endpoint,
            data=body,
            headers={"User-Agent": "CyberpunkModernityOSMCompiler/1.0"},
        )
        try:
            with urlopen(request, timeout=120) as response:
                return response.read(), endpoint
        except (HTTPError, URLError, TimeoutError) as exception:
            error = exception
            time.sleep(min(2 ** attempt, 20))
    raise RuntimeError(f"all Overpass requests failed: {error}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="replace retained snapshots")
    args = parser.parse_args()
    PROVENANCE.mkdir(parents=True, exist_ok=True)
    retained = retained_hashes()
    records = []
    for source in sorted(catalog_sources(), key=lambda item: item["id"]):
        if source["input_sha256"] in retained:
            continue
        slug = source_slug(source)
        output = PROVENANCE / f"{slug}_osm.json"
        query = overpass_query(source)
        endpoint = None
        if args.force or not output.exists():
            print(f"[fetch] {source['name']} ({slug})", flush=True)
            raw, endpoint = fetch(query)
            value = json.loads(raw)
            if not value.get("elements"):
                raise RuntimeError(f"Overpass returned no elements for {source['name']}")
            output.write_text(
                json.dumps(value, separators=(",", ":")) + "\n", encoding="utf-8"
            )
            time.sleep(0.5)
        raw = output.read_bytes()
        value = json.loads(raw)
        records.append({
            "slug": slug,
            "name": source["name"],
            "source_id": source["id"],
            "geographic_bbox": source["geographic_bbox"],
            "original_arnis_input_sha256": source["input_sha256"],
            "snapshot_file": output.relative_to(ROOT).as_posix(),
            "snapshot_sha256": sha256_bytes(raw),
            "timestamp_osm_base": value.get("osm3s", {}).get("timestamp_osm_base", ""),
            "query": query,
            "endpoint": endpoint or "retained snapshot",
            "license": source["license"],
        })
    manifest = {
        "format": "neoncity:arnis_catalog_osm_sources",
        "version": 1,
        "source_count": len(records),
        "sources": records,
    }
    (PROVENANCE / "index.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(f"[done] retained {len(records)} catalog OSM sources", flush=True)


if __name__ == "__main__":
    main()
