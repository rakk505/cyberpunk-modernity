#!/usr/bin/env python3
"""Rewrite imported Arnis glowstone into sea-lantern floor lighting.

The default mode is a read-only audit. Pass ``--apply`` to update structure
files, catalog hashes, and the adjacent open-park audit. Pass ``--check`` in CI
to require that a catalog has already been migrated.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import gzip
import importlib.util
import json
from pathlib import Path
import shutil
import sys
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = (
    PROJECT_ROOT / "src/main/resources/data/neoncity/arnis_districts/catalog.json"
)
IMPORTER_PATH = Path(__file__).with_name("arnis_import.py")
AUDIT_PATH = Path(__file__).with_name("refresh_open_park_audit.py")


def _load_module(name: str, path: Path) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


IMPORTER = _load_module("cyberdeck_arnis_light_import", IMPORTER_PATH)


class RewriteFailure(RuntimeError):
    """A structure or catalog cannot be migrated without losing data."""


@dataclass(frozen=True)
class Rewrite:
    data: bytes
    palette_size: int
    stats: Counter[str]


def rewrite_structure(data: bytes, label: str) -> Rewrite | None:
    try:
        root = IMPORTER.NbtReader(gzip.decompress(data)).document()
        size = root["size"]
        palette = [IMPORTER._state(value) for value in root["palette"]]
        blocks = root["blocks"]
        data_version = root["DataVersion"]
    except (OSError, KeyError, TypeError, ValueError) as error:
        raise RewriteFailure(f"cannot parse {label}: {error}") from error
    if size[:3:2] != [16, 16] or not isinstance(size[1], int) or size[1] < 1:
        raise RewriteFailure(f"{label} is not a supported 16x16 Arnis tile")
    if root.get("entities") != []:
        raise RewriteFailure(f"{label} contains entities")

    raw_blocks: list[tuple[int, int, int, Any]] = []
    for block in blocks:
        try:
            position = block["pos"]
            state = palette[block["state"]]
        except (IndexError, KeyError, TypeError) as error:
            raise RewriteFailure(f"{label} contains a malformed block") from error
        if "nbt" in block or not (
            isinstance(position, list)
            and len(position) == 3
            and all(isinstance(value, int) for value in position)
        ):
            raise RewriteFailure(f"{label} contains block-entity data or an invalid position")
        raw_blocks.append((position[0], position[1], position[2], state))

    normalized, stats = IMPORTER.normalize_embedded_lights(raw_blocks)
    if not stats:
        return None
    canonical_palette = sorted(
        {value[3] for value in normalized},
        key=lambda item: (item.name, item.properties),
    )
    canonical_blocks = sorted(normalized, key=lambda item: (item[1], item[2], item[0]))
    patch = IMPORTER.Patch(
        selection=IMPORTER.Selection(label, 0, 0, 0, 0),
        source_versions={data_version},
        palette=canonical_palette,
        blocks=canonical_blocks,
        min_source_y=0,
        max_source_y=size[1] - 1,
        stripped_dangerous=Counter(),
        stripped_block_entities=Counter(),
        missing_chunks=[],
        top_surface={},
    )
    encoded = IMPORTER.structure_bytes(patch, data_version)
    summary = IMPORTER.validate_structure(encoded)
    if summary["blocks"] != len(blocks) or summary["size"] != size:
        raise RewriteFailure(f"{label} changed its size or block count")
    return Rewrite(encoded, summary["palette"], stats)


def refresh_open_park_audit(catalog_path: Path) -> Path | None:
    output = catalog_path.with_name("open_park_tiles.json")
    if not output.is_file():
        return None
    audit = _load_module("cyberdeck_arnis_light_audit", AUDIT_PATH)
    value = audit.build_audit(catalog_path)
    IMPORTER.atomic_write(output, IMPORTER.json_bytes(value))
    return output


def _structure_path(catalog_path: Path, relative: str) -> Path:
    root = catalog_path.parent.resolve()
    path = (root / relative).resolve()
    try:
        path.relative_to(root)
    except ValueError as error:
        raise RewriteFailure(f"structure path escapes its catalog: {relative}") from error
    return path


def _transaction_root(catalog_path: Path) -> Path:
    return catalog_path.parent / ".embedded-lighting-transaction"


def commit_transaction(catalog_path: Path, transaction_root: Path) -> dict[str, Any]:
    journal_path = transaction_root / "transaction.json"
    try:
        journal = json.loads(journal_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RewriteFailure(f"cannot read lighting transaction {journal_path}") from error
    if journal.get("schema_version") != 1 or journal.get("catalog") != str(catalog_path):
        raise RewriteFailure(f"lighting transaction does not belong to {catalog_path}")
    records = journal.get("files")
    if not isinstance(records, list):
        raise RewriteFailure("lighting transaction has no file manifest")

    for record in records:
        if not isinstance(record, dict) or not all(
            isinstance(record.get(key), str)
            for key in ("file", "old_sha256", "new_sha256")
        ):
            raise RewriteFailure("lighting transaction contains a malformed file record")
        destination = _structure_path(catalog_path, record["file"])
        staged = _structure_path(transaction_root / "catalog.json", f"files/{record['file']}")
        staged_data = staged.read_bytes()
        if IMPORTER.sha256_bytes(staged_data) != record["new_sha256"]:
            raise RewriteFailure(f"staged lighting file is corrupt: {record['file']}")
        current_hash = IMPORTER.sha256_file(destination)
        if current_hash == record["new_sha256"]:
            continue
        if current_hash != record["old_sha256"]:
            raise RewriteFailure(
                f"cannot resume lighting transaction; unexpected hash for {record['file']}"
            )
        IMPORTER.atomic_write(destination, staged_data)

    staged_catalog = (transaction_root / "catalog.json").read_bytes()
    if IMPORTER.sha256_bytes(staged_catalog) != journal.get("catalog_sha256"):
        raise RewriteFailure("staged lighting catalog is corrupt")
    current_catalog_hash = IMPORTER.sha256_file(catalog_path)
    if current_catalog_hash not in {
        journal.get("old_catalog_sha256"),
        journal.get("catalog_sha256"),
    }:
        raise RewriteFailure("catalog changed after the lighting transaction was prepared")
    IMPORTER.atomic_write(catalog_path, staged_catalog)
    IMPORTER.validate_catalog(catalog_path)
    audit_path = refresh_open_park_audit(catalog_path)
    report = journal.get("report")
    if not isinstance(report, dict):
        raise RewriteFailure("lighting transaction has no result report")
    report["open_park_audit"] = str(audit_path) if audit_path is not None else None
    shutil.rmtree(transaction_root)
    return report


def migrate(catalog_path: Path, apply: bool) -> dict[str, Any]:
    transaction_root = _transaction_root(catalog_path)
    journal_path = transaction_root / "transaction.json"
    if journal_path.is_file():
        if not apply:
            raise RewriteFailure(
                f"pending lighting transaction; resume it with --apply: {journal_path}"
            )
        return commit_transaction(catalog_path, transaction_root)
    if apply and transaction_root.exists():
        # Preparation never mutates source files before the journal is durable.
        shutil.rmtree(transaction_root)

    original_catalog_data = catalog_path.read_bytes()
    catalog = IMPORTER.load_catalog(catalog_path)
    aggregate: Counter[str] = Counter()
    changed_files = 0
    changed_bytes = 0
    file_records: list[dict[str, str]] = []

    for entry in catalog["patches"]:
        if not isinstance(entry, dict) or not isinstance(entry.get("file"), str):
            raise RewriteFailure("catalog contains a patch without a structure file")
        path = _structure_path(catalog_path, entry["file"])
        source = path.read_bytes()
        if entry.get("sha256") != IMPORTER.sha256_bytes(source):
            raise RewriteFailure(f"catalog hash is stale for {entry.get('id')}")
        rewrite = rewrite_structure(source, str(entry.get("id", path.name)))
        if rewrite is None:
            continue
        changed_files += 1
        changed_bytes += len(rewrite.data) - len(source)
        aggregate.update(rewrite.stats)
        entry["sha256"] = IMPORTER.sha256_bytes(rewrite.data)
        entry["compressed_bytes"] = len(rewrite.data)
        entry["palette_size"] = rewrite.palette_size
        IMPORTER.validate_structure(rewrite.data, entry)
        if apply:
            staged_path = transaction_root / "files" / entry["file"]
            IMPORTER.atomic_write(staged_path, rewrite.data)
            file_records.append({
                "file": entry["file"],
                "old_sha256": IMPORTER.sha256_bytes(source),
                "new_sha256": entry["sha256"],
            })

    report = {
        "status": "ok",
        "mode": "apply" if apply else "audit",
        "catalog": str(catalog_path),
        "changed_files": changed_files,
        "compressed_byte_delta": changed_bytes,
        "covered": aggregate["covered"],
        "camouflaged": aggregate["camouflaged"],
        "fallback": aggregate["fallback"],
        "surfaces": {
            surface: aggregate[f"surface:{surface}"]
            for surface in IMPORTER.LIGHT_SURFACE_ORDER
        },
        "open_park_audit": None,
    }
    if not apply or not changed_files:
        return report

    catalog_data = IMPORTER.json_bytes(catalog)
    IMPORTER.atomic_write(transaction_root / "catalog.json", catalog_data)
    journal = {
        "schema_version": 1,
        "catalog": str(catalog_path),
        "old_catalog_sha256": IMPORTER.sha256_bytes(original_catalog_data),
        "catalog_sha256": IMPORTER.sha256_bytes(catalog_data),
        "files": file_records,
        "report": report,
    }
    # The journal is the commit marker and is written only after every output validates.
    IMPORTER.atomic_write(journal_path, IMPORTER.json_bytes(journal))
    return commit_transaction(catalog_path, transaction_root)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="rewrite files in place")
    mode.add_argument(
        "--check",
        action="store_true",
        help="fail if any catalog structure still contains glowstone",
    )
    args = parser.parse_args(argv)
    result = migrate(args.catalog.resolve(), args.apply)
    print(json.dumps(result, indent=2, sort_keys=True))
    if args.check and result["changed_files"]:
        raise RewriteFailure(
            f"{result['changed_files']} Arnis structures still require lighting migration"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RewriteFailure, IMPORTER.ImportFailure, OSError) as error:
        print(json.dumps({"status": "fail", "error": str(error)}, indent=2), file=sys.stderr)
        raise SystemExit(1)
