#!/usr/bin/env python3
"""Offline Arnis 3.0.0 Java-world to vanilla structure patch importer.

The importer is intentionally dependency-free.  It reads modern Anvil region
files, strips entities and block-entity payloads, writes deterministic gzip NBT
StructureTemplates, and maintains a provenance catalog for runtime selection.

Examples (run from the ``neoncity`` project directory)::

    python3 tools/arnis/arnis_import.py import ../ArnisWorld \
      --district H --source-id hong-kong-core --source-name "Hong Kong core" \
      --source-sha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
      --license ODbL-1.0 --attribution "OpenStreetMap contributors" \
      --selection harbor=12,8:14,10 --selection hillside=18,4:19,6

    python3 tools/arnis/arnis_import.py list
    python3 tools/arnis/arnis_import.py validate

Rectangle endpoints are inclusive chunk coordinates.  No source geometry,
paths, or license claims are invented: source metadata is required on import.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import gzip
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import struct
import sys
import tempfile
from typing import Any, BinaryIO, Iterable, Iterator, NamedTuple
import zlib


ARNIS_VERSION = "3.0.0"
SCHEMA_VERSION = 1
FORMAT = "neoncity:arnis_patch_catalog"
DEFAULT_NAMESPACE = "neoncity"
DEFAULT_DATA_VERSION = 0  # 0 means retain the highest source DataVersion.
MAX_TEMPLATE_AXIS = 16
MAX_ATLAS_CHUNKS = 16
MIN_WORLD_Y = -64
CITY_SURFACE_Y = 72
MAX_PLACED_Y = 318
PLACEMENT_ZONES = frozenset(("NEST", "BACKSTREETS"))

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

AIR = frozenset(("minecraft:air", "minecraft:cave_air", "minecraft:void_air"))
DANGEROUS = frozenset(
    (
        "minecraft:barrier",
        "minecraft:chain_command_block",
        "minecraft:command_block",
        "minecraft:jigsaw",
        "minecraft:light",
        "minecraft:repeating_command_block",
        "minecraft:structure_block",
        "minecraft:structure_void",
    )
)
ROAD_BLOCKS = frozenset(
    (
        "minecraft:black_concrete",
        "minecraft:black_concrete_powder",
        "minecraft:gray_concrete",
        "minecraft:light_gray_concrete",
        "minecraft:smooth_stone",
        "minecraft:stone_bricks",
        "minecraft:polished_andesite",
        "minecraft:gravel",
        "minecraft:deepslate_tiles",
        "minecraft:polished_deepslate",
    )
)

_SLUG = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")
_DISTRICT = re.compile(r"^[A-Z][A-Z0-9_]{0,15}$")
_REGION_FILE = re.compile(r"^r\.(-?\d+)\.(-?\d+)\.mca$")
_ATLAS_TILE = re.compile(r"^(.+)_([0-9]+)_([0-9]+)$")


class ImportFailure(RuntimeError):
    """A source, selection, or output failed validation."""


class State(NamedTuple):
    name: str
    properties: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class Selection:
    name: str
    min_x: int
    min_z: int
    max_x: int
    max_z: int

    @property
    def chunks_x(self) -> int:
        return self.max_x - self.min_x + 1

    @property
    def chunks_z(self) -> int:
        return self.max_z - self.min_z + 1

    @property
    def size_x(self) -> int:
        return self.chunks_x * 16

    @property
    def size_z(self) -> int:
        return self.chunks_z * 16


@dataclass
class Patch:
    selection: Selection
    source_versions: set[int]
    palette: list[State]
    blocks: list[tuple[int, int, int, State]]
    min_source_y: int
    max_source_y: int
    stripped_dangerous: Counter[str]
    stripped_block_entities: Counter[str]
    missing_chunks: list[tuple[int, int]]
    top_surface: dict[tuple[int, int], tuple[int, str]]

    @property
    def size(self) -> tuple[int, int, int]:
        return (
            self.selection.size_x,
            self.max_source_y - self.min_source_y + 1,
            self.selection.size_z,
        )


class NbtReader:
    """Bounds-checked NBT reader returning plain Python values."""

    def __init__(self, data: bytes) -> None:
        self.data = memoryview(data)
        self.offset = 0

    def read(self, size: int) -> bytes:
        if size < 0 or self.offset + size > len(self.data):
            raise ImportFailure(
                f"truncated NBT at byte {self.offset} (wanted {size} bytes)"
            )
        result = self.data[self.offset : self.offset + size].tobytes()
        self.offset += size
        return result

    def unpack(self, fmt: str) -> Any:
        return struct.unpack(fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self) -> str:
        length = self.unpack(">H")
        try:
            return self.read(length).decode("utf-8")
        except UnicodeDecodeError as error:
            raise ImportFailure("invalid UTF-8 in NBT string") from error

    def length(self, kind: str) -> int:
        value = self.unpack(">i")
        if value < 0 or value > 100_000_000:
            raise ImportFailure(f"invalid NBT {kind} length {value}")
        return value

    def payload(self, tag: int) -> Any:
        if tag == TAG_BYTE:
            return self.unpack(">b")
        if tag == TAG_SHORT:
            return self.unpack(">h")
        if tag == TAG_INT:
            return self.unpack(">i")
        if tag == TAG_LONG:
            return self.unpack(">q")
        if tag == TAG_FLOAT:
            return self.unpack(">f")
        if tag == TAG_DOUBLE:
            return self.unpack(">d")
        if tag == TAG_BYTE_ARRAY:
            return self.read(self.length("byte array"))
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            item_tag = self.unpack(">B")
            count = self.length("list")
            return [self.payload(item_tag) for _ in range(count)]
        if tag == TAG_COMPOUND:
            value: dict[str, Any] = {}
            while True:
                child_tag = self.unpack(">B")
                if child_tag == TAG_END:
                    return value
                name = self.string()
                if name in value:
                    raise ImportFailure(f"duplicate NBT key {name!r}")
                value[name] = self.payload(child_tag)
        if tag == TAG_INT_ARRAY:
            return [self.unpack(">i") for _ in range(self.length("int array"))]
        if tag == TAG_LONG_ARRAY:
            return [self.unpack(">q") for _ in range(self.length("long array"))]
        raise ImportFailure(f"unsupported NBT tag {tag}")

    def document(self) -> dict[str, Any]:
        tag = self.unpack(">B")
        if tag != TAG_COMPOUND:
            raise ImportFailure(f"NBT root is tag {tag}, not a compound")
        self.string()
        value = self.payload(TAG_COMPOUND)
        if self.offset != len(self.data):
            raise ImportFailure(f"NBT has {len(self.data) - self.offset} trailing bytes")
        return value


class RegionStore:
    """Lazy region-file reader for one Java dimension."""

    def __init__(self, world: Path, dimension: str) -> None:
        if dimension == "overworld":
            self.region_dir = world / "region"
        elif dimension == "nether":
            self.region_dir = world / "DIM-1" / "region"
        elif dimension == "end":
            self.region_dir = world / "DIM1" / "region"
        else:
            candidate = Path(dimension)
            if candidate.is_absolute() or ".." in candidate.parts:
                raise ImportFailure("custom dimension must be a relative world path")
            self.region_dir = world / candidate / "region"
        if not self.region_dir.is_dir():
            raise ImportFailure(f"region directory does not exist: {self.region_dir}")
        self._regions: dict[tuple[int, int], bytes] = {}
        self.used_regions: set[Path] = set()
        self.chunk_files: dict[tuple[int, int], set[Path]] = {}

    def _region(self, rx: int, rz: int) -> tuple[Path, bytes]:
        key = (rx, rz)
        path = self.region_dir / f"r.{rx}.{rz}.mca"
        if key not in self._regions:
            if not path.is_file():
                raise FileNotFoundError(path)
            data = path.read_bytes()
            if len(data) < 8192:
                raise ImportFailure(f"region {path.name} is shorter than its 8 KiB header")
            self._regions[key] = data
        self.used_regions.add(path)
        return path, self._regions[key]

    def chunk(self, chunk_x: int, chunk_z: int) -> dict[str, Any] | None:
        rx, rz = chunk_x // 32, chunk_z // 32
        try:
            path, region = self._region(rx, rz)
        except FileNotFoundError:
            return None
        source_files = {path}
        self.chunk_files[(chunk_x, chunk_z)] = source_files
        local_x, local_z = chunk_x % 32, chunk_z % 32
        slot = local_x + local_z * 32
        location = region[slot * 4 : slot * 4 + 4]
        sector_offset = int.from_bytes(location[:3], "big")
        sector_count = location[3]
        if sector_offset == 0 or sector_count == 0:
            return None
        start = sector_offset * 4096
        allocated = sector_count * 4096
        if start + allocated > len(region):
            raise ImportFailure(f"chunk {chunk_x},{chunk_z} points outside {path.name}")
        length = struct.unpack(">I", region[start : start + 4])[0]
        if length < 2 or length + 4 > allocated:
            raise ImportFailure(f"invalid record length for chunk {chunk_x},{chunk_z}")
        compression = region[start + 4]
        external = bool(compression & 0x80)
        compression &= 0x7F
        if external:
            external_path = self.region_dir / f"c.{chunk_x}.{chunk_z}.mcc"
            if not external_path.is_file():
                raise ImportFailure(f"missing external stream {external_path.name}")
            payload = external_path.read_bytes()
            self.used_regions.add(external_path)
            source_files.add(external_path)
            self.chunk_files[(chunk_x, chunk_z)] = source_files
        else:
            payload = region[start + 5 : start + 4 + length]
        try:
            if compression == 1:
                raw = gzip.decompress(payload)
            elif compression == 2:
                raw = zlib.decompress(payload)
            elif compression == 3:
                raw = payload
            elif compression == 4:
                raise ImportFailure(
                    "LZ4-compressed Anvil chunks are unsupported; resave the world "
                    "with zlib compression before importing"
                )
            else:
                raise ImportFailure(
                    f"unsupported compression {compression} at chunk {chunk_x},{chunk_z}"
                )
        except (OSError, zlib.error) as error:
            raise ImportFailure(f"cannot decompress chunk {chunk_x},{chunk_z}") from error
        root = NbtReader(raw).document()
        # Pre-1.18 saves wrapped chunk content in Level; accepting it costs
        # nothing and gives a clearer migration route for an older Arnis map.
        chunk = root.get("Level", root)
        if not isinstance(chunk, dict):
            raise ImportFailure(f"chunk {chunk_x},{chunk_z} root is malformed")
        actual = (chunk.get("xPos"), chunk.get("zPos"))
        if actual != (chunk_x, chunk_z):
            raise ImportFailure(
                f"region slot {chunk_x},{chunk_z} contains chunk {actual[0]},{actual[1]}"
            )
        self.chunk_files[(chunk_x, chunk_z)] = source_files
        return chunk


def _state(value: Any) -> State:
    if not isinstance(value, dict) or not isinstance(value.get("Name"), str):
        raise ImportFailure("block-state palette entry has no string Name")
    name = value["Name"]
    if ":" not in name:
        raise ImportFailure(f"invalid block identifier {name!r}")
    properties = value.get("Properties", {})
    if not isinstance(properties, dict) or any(
        not isinstance(key, str) or not isinstance(item, str)
        for key, item in properties.items()
    ):
        raise ImportFailure(f"invalid properties for block {name}")
    return State(name, tuple(sorted(properties.items())))


def _indices(palette_size: int, packed: Any) -> Iterable[int]:
    if palette_size < 1:
        raise ImportFailure("empty block-state palette")
    if palette_size == 1:
        if packed not in (None, []) and any(value != 0 for value in packed):
            raise ImportFailure("singleton palette has non-zero data")
        return (0 for _ in range(4096))
    if not isinstance(packed, list):
        raise ImportFailure("multi-state section has no packed data")
    bits = max(4, (palette_size - 1).bit_length())
    mask = (1 << bits) - 1
    per_long = 64 // bits
    padded_count = math.ceil(4096 / per_long)
    dense_count = math.ceil(4096 * bits / 64)
    words = [value & 0xFFFFFFFFFFFFFFFF for value in packed]

    def checked(value: int) -> int:
        if value >= palette_size:
            raise ImportFailure(
                f"packed palette index {value} exceeds palette size {palette_size}"
            )
        return value

    if len(words) == padded_count:
        return (
            checked((words[index // per_long] >> ((index % per_long) * bits)) & mask)
            for index in range(4096)
        )
    if len(words) == dense_count:
        def dense() -> Iterator[int]:
            for index in range(4096):
                bit_index = index * bits
                word_index, shift = divmod(bit_index, 64)
                value = words[word_index] >> shift
                if shift + bits > 64:
                    value |= words[word_index + 1] << (64 - shift)
                yield checked(value & mask)
        return dense()
    raise ImportFailure(
        f"packed block states contain {len(words)} longs; expected "
        f"{padded_count} (modern) or {dense_count} (legacy)"
    )


def _chunk_blocks(chunk: dict[str, Any]) -> Iterator[tuple[int, int, int, State]]:
    sections = chunk.get("sections", chunk.get("Sections"))
    if not isinstance(sections, list):
        raise ImportFailure("chunk has no sections list")
    seen_y: set[int] = set()
    for section in sorted(sections, key=lambda item: item.get("Y", 0)):
        if not isinstance(section, dict) or not isinstance(section.get("Y"), int):
            raise ImportFailure("malformed chunk section")
        section_y = section["Y"]
        if section_y in seen_y:
            raise ImportFailure(f"duplicate chunk section Y={section_y}")
        seen_y.add(section_y)
        block_states = section.get("block_states")
        if block_states is None:
            # Legacy names are accepted for worlds that were upgraded in place.
            palette_values = section.get("Palette")
            packed = section.get("BlockStates")
        elif isinstance(block_states, dict):
            palette_values = block_states.get("palette")
            packed = block_states.get("data")
        else:
            raise ImportFailure("section block_states is not a compound")
        if palette_values is None:
            continue
        if not isinstance(palette_values, list):
            raise ImportFailure("section palette is not a list")
        palette = [_state(entry) for entry in palette_values]
        for linear, palette_index in enumerate(_indices(len(palette), packed)):
            state = palette[palette_index]
            if state.name in AIR:
                continue
            x = linear & 15
            z = (linear >> 4) & 15
            y = section_y * 16 + (linear >> 8)
            yield x, y, z, state


def _block_entities(chunk: dict[str, Any]) -> Counter[str]:
    values = chunk.get("block_entities", chunk.get("TileEntities", []))
    if not isinstance(values, list):
        raise ImportFailure("chunk block_entities is not a list")
    result: Counter[str] = Counter()
    for value in values:
        if isinstance(value, dict):
            result[str(value.get("id", "unknown"))] += 1
        else:
            result["unknown"] += 1
    return result


def build_patch(
    regions: RegionStore,
    selection: Selection,
    requested_min_y: int | None,
    requested_max_y: int | None,
    allow_missing: bool,
) -> Patch:
    raw_blocks: list[tuple[int, int, int, State]] = []
    versions: set[int] = set()
    dangerous: Counter[str] = Counter()
    block_entities: Counter[str] = Counter()
    missing: list[tuple[int, int]] = []
    top_surface: dict[tuple[int, int], tuple[int, str]] = {}

    for chunk_z in range(selection.min_z, selection.max_z + 1):
        for chunk_x in range(selection.min_x, selection.max_x + 1):
            chunk = regions.chunk(chunk_x, chunk_z)
            if chunk is None:
                missing.append((chunk_x, chunk_z))
                continue
            data_version = chunk.get("DataVersion")
            if isinstance(data_version, int):
                versions.add(data_version)
            block_entities.update(_block_entities(chunk))
            for local_x, world_y, local_z, state in _chunk_blocks(chunk):
                if requested_min_y is not None and world_y < requested_min_y:
                    continue
                if requested_max_y is not None and world_y > requested_max_y:
                    continue
                patch_x = (chunk_x - selection.min_x) * 16 + local_x
                patch_z = (chunk_z - selection.min_z) * 16 + local_z
                if state.name in DANGEROUS:
                    dangerous[state.name] += 1
                    continue
                raw_blocks.append((patch_x, world_y, patch_z, state))
                column = (patch_x, patch_z)
                previous = top_surface.get(column)
                if previous is None or world_y >= previous[0]:
                    top_surface[column] = (world_y, state.name)

    if missing and not allow_missing:
        sample = ", ".join(f"{x},{z}" for x, z in missing[:8])
        raise ImportFailure(
            f"selection {selection.name!r} has {len(missing)} missing chunks: {sample}; "
            "pass --allow-missing to preserve them as air"
        )
    if not raw_blocks:
        raise ImportFailure(f"selection {selection.name!r} contains no importable blocks")
    min_y = requested_min_y if requested_min_y is not None else min(v[1] for v in raw_blocks)
    max_y = requested_max_y if requested_max_y is not None else max(v[1] for v in raw_blocks)
    if min_y > max_y:
        raise ImportFailure("minimum Y is above maximum Y")
    palette = sorted({value[3] for value in raw_blocks}, key=lambda item: (item.name, item.properties))
    blocks = sorted(
        ((x, y - min_y, z, state) for x, y, z, state in raw_blocks),
        key=lambda item: (item[1], item[2], item[0]),
    )
    return Patch(
        selection=selection,
        source_versions=versions,
        palette=palette,
        blocks=blocks,
        min_source_y=min_y,
        max_source_y=max_y,
        stripped_dangerous=dangerous,
        stripped_block_entities=block_entities,
        missing_chunks=missing,
        top_surface=top_surface,
    )


def _utf(stream: BinaryIO, value: str) -> None:
    encoded = value.encode("utf-8")
    if len(encoded) > 65535:
        raise ImportFailure("NBT string exceeds 65535 encoded bytes")
    stream.write(struct.pack(">H", len(encoded)))
    stream.write(encoded)


def _header(stream: BinaryIO, tag: int, name: str) -> None:
    stream.write(bytes((tag,)))
    _utf(stream, name)


def structure_bytes(patch: Patch, data_version: int) -> bytes:
    palette_index = {state: index for index, state in enumerate(patch.palette)}
    raw = io.BytesIO()
    _header(raw, TAG_COMPOUND, "")
    _header(raw, TAG_INT, "DataVersion")
    raw.write(struct.pack(">i", data_version))
    _header(raw, TAG_LIST, "size")
    raw.write(bytes((TAG_INT,)))
    raw.write(struct.pack(">i", 3))
    raw.write(struct.pack(">iii", *patch.size))
    _header(raw, TAG_LIST, "palette")
    raw.write(bytes((TAG_COMPOUND,)))
    raw.write(struct.pack(">i", len(patch.palette)))
    for state in patch.palette:
        _header(raw, TAG_STRING, "Name")
        _utf(raw, state.name)
        if state.properties:
            _header(raw, TAG_COMPOUND, "Properties")
            for key, value in state.properties:
                _header(raw, TAG_STRING, key)
                _utf(raw, value)
            raw.write(bytes((TAG_END,)))
        raw.write(bytes((TAG_END,)))
    _header(raw, TAG_LIST, "blocks")
    raw.write(bytes((TAG_COMPOUND,)))
    raw.write(struct.pack(">i", len(patch.blocks)))
    for x, y, z, state in patch.blocks:
        _header(raw, TAG_LIST, "pos")
        raw.write(bytes((TAG_INT,)))
        raw.write(struct.pack(">i", 3))
        raw.write(struct.pack(">iii", x, y, z))
        _header(raw, TAG_INT, "state")
        raw.write(struct.pack(">i", palette_index[state]))
        raw.write(bytes((TAG_END,)))
    _header(raw, TAG_LIST, "entities")
    raw.write(bytes((TAG_COMPOUND,)))
    raw.write(struct.pack(">i", 0))
    raw.write(bytes((TAG_END,)))
    compressed = io.BytesIO()
    with gzip.GzipFile(fileobj=compressed, mode="wb", filename="", mtime=0) as stream:
        stream.write(raw.getvalue())
    return compressed.getvalue()


def road_connectors(
    patch: Patch, source_surface_y: int | None = None
) -> list[dict[str, Any]]:
    """Infer road runs touching a patch edge from topmost vanilla materials."""
    size_x, _, size_z = patch.size
    edges = (
        ("north", size_x, lambda offset: (offset, 0)),
        ("south", size_x, lambda offset: (offset, size_z - 1)),
        ("west", size_z, lambda offset: (0, offset)),
        ("east", size_z, lambda offset: (size_x - 1, offset)),
    )
    result: list[dict[str, Any]] = []
    for edge, length, coordinate in edges:
        candidates: list[tuple[int, int, str]] = []
        for offset in range(length):
            surface = patch.top_surface.get(coordinate(offset))
            if (
                surface is not None
                and surface[1] in ROAD_BLOCKS
                and (
                    source_surface_y is None
                    or abs(surface[0] - source_surface_y) <= 2
                )
            ):
                candidates.append((offset, surface[0], surface[1]))
        start = 0
        while start < len(candidates):
            end = start + 1
            while end < len(candidates) and candidates[end][0] == candidates[end - 1][0] + 1:
                end += 1
            run = candidates[start:end]
            if len(run) >= 3:
                heights = sorted(value[1] for value in run)
                materials = Counter(value[2] for value in run)
                result.append(
                    {
                        "edge": edge,
                        "offset": run[0][0],
                        "width": len(run),
                        "source_y": heights[len(heights) // 2],
                        "material": materials.most_common(1)[0][0],
                        "kind": "road",
                        "inferred": True,
                        "confidence": round(min(0.95, 0.55 + len(run) * 0.04), 2),
                    }
                )
            start = end
    return result


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() == data:
        path.chmod(0o644)
        return
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o644)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def parse_selection(value: str) -> Selection:
    try:
        name, bounds = value.split("=", 1)
        low, high = bounds.split(":", 1)
        min_x, min_z = (int(item) for item in low.split(","))
        max_x, max_z = (int(item) for item in high.split(","))
    except (ValueError, TypeError) as error:
        raise argparse.ArgumentTypeError(
            "selection must be NAME=MIN_CHUNK_X,MIN_CHUNK_Z:MAX_CHUNK_X,MAX_CHUNK_Z"
        ) from error
    if not _SLUG.fullmatch(name):
        raise argparse.ArgumentTypeError(
            "selection name must be a lowercase resource-safe slug"
        )
    if min_x > max_x or min_z > max_z:
        raise argparse.ArgumentTypeError("selection minimum must not exceed maximum")
    selection = Selection(name, min_x, min_z, max_x, max_z)
    if (selection.chunks_x > MAX_ATLAS_CHUNKS
            or selection.chunks_z > MAX_ATLAS_CHUNKS):
        raise argparse.ArgumentTypeError(
            f"selection exceeds the supported {MAX_ATLAS_CHUNKS}x"
            f"{MAX_ATLAS_CHUNKS}-chunk coherent atlas; split it into smaller atlases"
        )
    return selection


def split_selection(selection: Selection) -> list[Selection]:
    """Split a reviewed multi-chunk crop into runtime-safe one-chunk tiles."""
    if selection.chunks_x == 1 and selection.chunks_z == 1:
        return [selection]
    return [
        Selection(
            f"{selection.name}_{chunk_x - selection.min_x}_{chunk_z - selection.min_z}",
            chunk_x,
            chunk_z,
            chunk_x,
            chunk_z,
        )
        for chunk_z in range(selection.min_z, selection.max_z + 1)
        for chunk_x in range(selection.min_x, selection.max_x + 1)
    ]


def parse_geo_bbox(value: str) -> dict[str, float]:
    try:
        min_lat, min_lon, max_lat, max_lon = (float(item) for item in value.split(","))
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "geo bbox must be MIN_LAT,MIN_LON,MAX_LAT,MAX_LON"
        ) from error
    if not (-90 <= min_lat < max_lat <= 90 and -180 <= min_lon < max_lon <= 180):
        raise argparse.ArgumentTypeError("geo bbox coordinates or ordering are invalid")
    return {
        "min_lat": min_lat,
        "min_lon": min_lon,
        "max_lat": max_lat,
        "max_lon": max_lon,
    }


def default_catalog() -> Path:
    project = Path(__file__).resolve().parents[2]
    return project / "src/main/resources/data/neoncity/arnis/catalog.json"


def load_catalog(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {
            "format": FORMAT,
            "schema_version": SCHEMA_VERSION,
            "arnis_version": ARNIS_VERSION,
            "patches": [],
        }
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ImportFailure(f"cannot parse catalog {path}: {error}") from error
    if not isinstance(value, dict) or value.get("format") != FORMAT:
        raise ImportFailure(f"{path} is not a {FORMAT} catalog")
    if value.get("schema_version") != SCHEMA_VERSION:
        raise ImportFailure(
            f"unsupported catalog schema {value.get('schema_version')!r}"
        )
    if not isinstance(value.get("patches"), list):
        raise ImportFailure("catalog patches is not a list")
    return value


def relative_file(path: Path, catalog_path: Path) -> str:
    try:
        return path.resolve().relative_to(catalog_path.parent.resolve()).as_posix()
    except ValueError as error:
        raise ImportFailure("output directory must be inside the catalog directory") from error


def import_world(args: argparse.Namespace) -> dict[str, Any]:
    world = args.world.resolve()
    if not world.is_dir() or not (world / "level.dat").is_file():
        raise ImportFailure("world must be an unpacked Java save containing level.dat")
    if not _DISTRICT.fullmatch(args.district):
        raise ImportFailure("district must be an uppercase ASCII code such as A, AE, or WANG")
    if not args.selection:
        raise ImportFailure("at least one --selection is required")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.source_sha256):
        raise ImportFailure("source SHA-256 must contain exactly 64 hexadecimal digits")
    placement_zones = sorted(set(args.placement_zone or ("NEST", "BACKSTREETS")))
    if not placement_zones or any(zone not in PLACEMENT_ZONES for zone in placement_zones):
        raise ImportFailure("placement zones must be Nest or Backstreets")
    selections = [
        tile for selection in args.selection for tile in split_selection(selection)
    ]
    names = [item.name for item in selections]
    if len(names) != len(set(names)):
        raise ImportFailure("selection names must be unique")
    catalog_path = args.catalog.resolve()
    output_dir = (args.output_dir or (catalog_path.parent / "structures")).resolve()
    relative_file(output_dir / "probe", catalog_path)
    catalog = load_catalog(catalog_path)
    regions = RegionStore(world, args.dimension)
    imported: list[dict[str, Any]] = []
    world_metadata: dict[str, Any] | None = None
    metadata_path = world / "metadata.json"
    if metadata_path.is_file():
        try:
            metadata_value = json.loads(metadata_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise ImportFailure("Arnis metadata.json is not valid JSON") from error
        if not isinstance(metadata_value, dict):
            raise ImportFailure("Arnis metadata.json root is not an object")
        world_metadata = {
            "file": metadata_path.name,
            "sha256": sha256_file(metadata_path),
            "values": metadata_value,
        }

    for selection in selections:
        patch = build_patch(
            regions,
            selection,
            args.min_y,
            args.max_y,
            args.allow_missing,
        )
        source_surface_y = (
            args.surface_y if args.surface_y is not None else patch.min_source_y + 2
        )
        if not patch.min_source_y <= source_surface_y <= patch.max_source_y:
            raise ImportFailure(
                f"selection {selection.name!r} surface Y {source_surface_y} is outside "
                f"the imported range {patch.min_source_y}..{patch.max_source_y}"
            )
        data_version = args.data_version or max(patch.source_versions, default=0)
        if data_version <= 0:
            raise ImportFailure(
                f"selection {selection.name!r} has no source DataVersion; "
                "provide --data-version"
            )
        data = structure_bytes(patch, data_version)
        district_slug = args.district.lower()
        output_path = output_dir / district_slug / f"{selection.name}.nbt"
        atomic_write(output_path, data)
        patch_id = f"{district_slug}/{selection.name}"
        source_bounds = {
            "chunks": {
                "min_x": selection.min_x,
                "min_z": selection.min_z,
                "max_x": selection.max_x,
                "max_z": selection.max_z,
            },
            "blocks": {
                "min_x": selection.min_x * 16,
                "min_y": patch.min_source_y,
                "min_z": selection.min_z * 16,
                "max_x": (selection.max_x + 1) * 16 - 1,
                "max_y": patch.max_source_y,
                "max_z": (selection.max_z + 1) * 16 - 1,
            },
        }
        selected_source_files: set[Path] = set()
        for chunk_z in range(selection.min_z, selection.max_z + 1):
            for chunk_x in range(selection.min_x, selection.max_x + 1):
                selected_source_files.update(regions.chunk_files.get((chunk_x, chunk_z), set()))
        region_records = []
        for source_path in sorted(selected_source_files, key=lambda item: item.name):
            region_records.append(
                {
                    "file": source_path.name,
                    "sha256": sha256_file(source_path),
                    "bytes": source_path.stat().st_size,
                }
            )
        entry = {
            "id": patch_id,
            "district": args.district,
            "placement_zones": placement_zones,
            "file": relative_file(output_path, catalog_path),
            "sha256": sha256_bytes(data),
            "compressed_bytes": len(data),
            "block_count": len(patch.blocks),
            "palette_size": len(patch.palette),
            "footprint": {
                "blocks": {"x": patch.size[0], "y": patch.size[1], "z": patch.size[2]},
                "chunks": {"x": selection.chunks_x, "z": selection.chunks_z},
                "anchor": {
                    "source_y": patch.min_source_y,
                    "surface_y": source_surface_y,
                },
            },
            "source": {
                "arnis_version": ARNIS_VERSION,
                "world": world.name,
                "dimension": args.dimension,
                "id": args.source_id,
                "name": args.source_name,
                "url": args.source_url,
                "input_sha256": args.source_sha256.lower(),
                "geographic_bbox": args.geo_bbox,
                "world_metadata": world_metadata,
                "bbox": source_bounds,
                "data_versions": sorted(patch.source_versions),
                "files": region_records,
                "license": {
                    "id": args.license,
                    "url": args.license_url,
                    "attribution": args.attribution,
                },
            },
            "normalization": {
                "entities": "stripped",
                "block_entity_payloads": "stripped",
                "air": "omitted",
                "dangerous_blocks": dict(sorted(patch.stripped_dangerous.items())),
                "source_block_entities": dict(sorted(patch.stripped_block_entities.items())),
                "missing_chunks_as_air": [
                    {"x": x, "z": z} for x, z in patch.missing_chunks
                ],
                "template_data_version": data_version,
                "canonical_order": "y,z,x",
                "gzip_mtime": 0,
            },
            "road_connectors": road_connectors(patch, source_surface_y),
        }
        # Verify bytes before making the catalog point at them.
        validate_structure(data, entry)
        existing = {item.get("id"): item for item in catalog["patches"]}
        existing[patch_id] = entry
        catalog["patches"] = [existing[key] for key in sorted(existing)]
        imported.append(entry)

    atomic_write(catalog_path, json_bytes(catalog))
    # Validate the complete on-disk catalog, including pre-existing entries.
    if not args.defer_catalog_validation:
        validate_catalog(catalog_path)
    return {
        "status": "ok",
        "catalog": str(catalog_path),
        "imported": [
            {
                "id": entry["id"],
                "sha256": entry["sha256"],
                "blocks": entry["block_count"],
                "road_connectors": len(entry["road_connectors"]),
            }
            for entry in imported
        ],
    }


def validate_structure(data: bytes, entry: dict[str, Any] | None = None) -> dict[str, Any]:
    if len(data) < 10 or data[4:8] != b"\x00\x00\x00\x00":
        raise ImportFailure("structure gzip header does not have deterministic mtime=0")
    try:
        root = NbtReader(gzip.decompress(data)).document()
    except OSError as error:
        raise ImportFailure("structure is not valid gzip NBT") from error
    size = root.get("size")
    palette = root.get("palette")
    blocks = root.get("blocks")
    entities = root.get("entities")
    data_version = root.get("DataVersion")
    if not (
        isinstance(size, list)
        and len(size) == 3
        and all(isinstance(value, int) and value > 0 for value in size)
    ):
        raise ImportFailure("structure has invalid size")
    if size[0] > MAX_TEMPLATE_AXIS or size[2] > MAX_TEMPLATE_AXIS:
        raise ImportFailure("runtime structure exceeds one complete chunk")
    if not isinstance(data_version, int) or data_version <= 0:
        raise ImportFailure("structure has invalid DataVersion")
    if not isinstance(palette, list) or not palette:
        raise ImportFailure("structure has empty or invalid palette")
    states = [_state(value) for value in palette]
    if states != sorted(states, key=lambda item: (item.name, item.properties)):
        raise ImportFailure("structure palette is not canonical")
    unsafe = [state.name for state in states if state.name in AIR or state.name in DANGEROUS]
    if unsafe:
        raise ImportFailure(f"structure palette contains unsafe blocks: {unsafe[:5]}")
    if not isinstance(blocks, list) or not blocks:
        raise ImportFailure("structure has no blocks")
    previous = (-1, -1, -1)
    for block in blocks:
        if not isinstance(block, dict) or "nbt" in block:
            raise ImportFailure("structure block is malformed or contains block-entity NBT")
        position = block.get("pos")
        state_index = block.get("state")
        if not isinstance(position, list) or len(position) != 3:
            raise ImportFailure("structure block has invalid position")
        if not isinstance(state_index, int) or not 0 <= state_index < len(states):
            raise ImportFailure("structure block has invalid palette index")
        if any(not isinstance(value, int) for value in position) or any(
            not 0 <= position[index] < size[index] for index in range(3)
        ):
            raise ImportFailure(f"structure block is outside bounds: {position}")
        current = (position[1], position[2], position[0])
        if current <= previous:
            raise ImportFailure("structure blocks are not in canonical y,z,x order")
        previous = current
    if entities != []:
        raise ImportFailure("structure entities list is not empty")
    if entry is not None:
        if entry.get("sha256") != sha256_bytes(data):
            raise ImportFailure(f"hash mismatch for catalog patch {entry.get('id')}")
        if entry.get("compressed_bytes") != len(data):
            raise ImportFailure(f"byte-count mismatch for patch {entry.get('id')}")
        footprint = entry.get("footprint", {}).get("blocks")
        if footprint != {"x": size[0], "y": size[1], "z": size[2]}:
            raise ImportFailure(f"footprint mismatch for patch {entry.get('id')}")
        if entry.get("block_count") != len(blocks):
            raise ImportFailure(f"block-count mismatch for patch {entry.get('id')}")
        if entry.get("palette_size") != len(states):
            raise ImportFailure(f"palette-size mismatch for patch {entry.get('id')}")
        anchor = entry.get("footprint", {}).get("anchor", {})
        source_y = anchor.get("source_y")
        surface_y = anchor.get("surface_y")
        if not isinstance(source_y, int):
            raise ImportFailure(f"patch {entry.get('id')} has no vertical anchor")
        # Schema v1 catalogs created before surface_y used minY+2. Keep those
        # catalogs readable while all newly imported production patches record
        # the street/deck level explicitly.
        if surface_y is None:
            surface_y = source_y + 2
        if not isinstance(surface_y, int):
            raise ImportFailure(f"patch {entry.get('id')} has an invalid surface anchor")
        if not source_y <= surface_y < source_y + size[1]:
            raise ImportFailure(f"patch {entry.get('id')} has an invalid surface anchor")
    return {
        "size": size,
        "blocks": len(blocks),
        "palette": len(states),
        "data_version": data_version,
    }


def validate_catalog(path: Path) -> dict[str, Any]:
    catalog = load_catalog(path)
    ids: set[str] = set()
    summaries = []
    atlas_groups: dict[str, set[tuple[int, int]]] = {}
    atlas_zones: dict[str, tuple[str, ...]] = {}
    for entry in catalog["patches"]:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            raise ImportFailure("catalog contains a patch without an id")
        patch_id = entry["id"]
        if patch_id in ids:
            raise ImportFailure(f"duplicate catalog patch id {patch_id}")
        ids.add(patch_id)
        district = entry.get("district")
        if not isinstance(district, str) or not _DISTRICT.fullmatch(district):
            raise ImportFailure(f"patch {patch_id} has an invalid district code")
        if not patch_id.startswith(f"{district.lower()}/"):
            raise ImportFailure(f"patch {patch_id} does not match district {district}")
        placement_zones = entry.get("placement_zones", ["NEST", "BACKSTREETS"])
        if (
            not isinstance(placement_zones, list)
            or not placement_zones
            or len(placement_zones) != len(set(placement_zones))
            or any(zone not in PLACEMENT_ZONES for zone in placement_zones)
        ):
            raise ImportFailure(f"patch {patch_id} has invalid placement zones")
        file_value = entry.get("file")
        if not isinstance(file_value, str):
            raise ImportFailure(f"patch {patch_id} has no file")
        relative = Path(file_value)
        if relative.is_absolute() or ".." in relative.parts:
            raise ImportFailure(f"patch {patch_id} has unsafe file path")
        if relative.as_posix() != f"structures/{patch_id}.nbt":
            raise ImportFailure(f"patch {patch_id} file path does not match its catalog id")
        structure_path = path.parent / relative
        if not structure_path.is_file():
            raise ImportFailure(f"patch {patch_id} is missing {file_value}")
        source = entry.get("source")
        if not isinstance(source, dict) or source.get("arnis_version") != ARNIS_VERSION:
            raise ImportFailure(f"patch {patch_id} has invalid Arnis provenance")
        license_value = source.get("license")
        if not isinstance(license_value, dict) or not license_value.get("id"):
            raise ImportFailure(f"patch {patch_id} has no source license")
        source_hash = source.get("input_sha256")
        if not isinstance(source_hash, str) or not re.fullmatch(
            r"[0-9a-f]{64}", source_hash
        ):
            raise ImportFailure(f"patch {patch_id} has no valid source SHA-256")
        source_bbox = source.get("bbox")
        if not isinstance(source_bbox, dict) or not all(
            isinstance(source_bbox.get(kind), dict) for kind in ("chunks", "blocks")
        ):
            raise ImportFailure(f"patch {patch_id} has no source bounding box")
        connectors = entry.get("road_connectors")
        if not isinstance(connectors, list):
            raise ImportFailure(f"patch {patch_id} has invalid road connectors")
        for connector in connectors:
            if not isinstance(connector, dict) or connector.get("edge") not in {
                "north", "south", "west", "east"
            }:
                raise ImportFailure(f"patch {patch_id} has an invalid connector edge")
            offset = connector.get("offset")
            width = connector.get("width")
            if (
                not isinstance(offset, int)
                or not isinstance(width, int)
                or width < 3
                or offset < 0
                or offset + width > 16
            ):
                raise ImportFailure(f"patch {patch_id} has an out-of-bounds connector run")
        chunks = entry.get("footprint", {}).get("chunks", {})
        if chunks != {"x": 1, "z": 1}:
            raise ImportFailure(
                f"runtime patch {patch_id} must be one chunk; re-import the source "
                "selection so it is split into a named tile mosaic"
            )
        summary = validate_structure(structure_path.read_bytes(), entry)
        if summary["size"][0] != 16 or summary["size"][2] != 16:
            raise ImportFailure(f"runtime patch {patch_id} is not exactly 16x16 blocks")
        anchor = entry["footprint"]["anchor"]
        source_y = anchor["source_y"]
        surface_y = anchor.get("surface_y", source_y + 2)
        placed_bottom = CITY_SURFACE_Y - (surface_y - source_y)
        placed_top = placed_bottom + summary["size"][1] - 1
        if placed_bottom < MIN_WORLD_Y or placed_top > MAX_PLACED_Y:
            raise ImportFailure(
                f"patch {patch_id} places at Y={placed_bottom}..{placed_top}, outside "
                f"the runtime range {MIN_WORLD_Y}..{MAX_PLACED_Y}"
            )
        match = _ATLAS_TILE.fullmatch(patch_id)
        if match:
            atlas_id = match.group(1)
            atlas_groups.setdefault(atlas_id, set()).add(
                (int(match.group(2)), int(match.group(3)))
            )
            zones = tuple(sorted(placement_zones))
            previous_zones = atlas_zones.setdefault(atlas_id, zones)
            if previous_zones != zones:
                raise ImportFailure(f"atlas {atlas_id} mixes placement-zone contracts")
        summaries.append(summary)
    for atlas_id, coordinates in atlas_groups.items():
        max_x = max(value[0] for value in coordinates)
        max_z = max(value[1] for value in coordinates)
        expected = {
            (x, z) for z in range(max_z + 1) for x in range(max_x + 1)
        }
        if (max_x >= MAX_ATLAS_CHUNKS
                or max_z >= MAX_ATLAS_CHUNKS
                or coordinates != expected):
            raise ImportFailure(
                f"atlas {atlas_id} is sparse or exceeds the supported "
                f"{MAX_ATLAS_CHUNKS}x{MAX_ATLAS_CHUNKS} mosaic"
            )
    return {
        "status": "ok",
        "catalog": str(path),
        "patch_count": len(summaries),
        "block_count": sum(value["blocks"] for value in summaries),
    }


def list_catalog(path: Path, as_json: bool) -> None:
    catalog = load_catalog(path)
    if as_json:
        print(json.dumps(catalog["patches"], indent=2, sort_keys=True))
        return
    if not catalog["patches"]:
        print("No Arnis patches imported.")
        return
    print("ID\tDISTRICT\tCHUNKS\tBLOCKS\tCONNECTORS\tSOURCE")
    for entry in catalog["patches"]:
        chunks = entry.get("footprint", {}).get("chunks", {})
        source = entry.get("source", {})
        print(
            f"{entry.get('id')}\t{entry.get('district')}\t"
            f"{chunks.get('x')}x{chunks.get('z')}\t{entry.get('block_count')}\t"
            f"{len(entry.get('road_connectors', []))}\t{source.get('name')}"
        )


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    subparsers = root.add_subparsers(dest="command", required=True)
    importer = subparsers.add_parser("import", help="import selected Arnis chunks")
    importer.add_argument("world", type=Path, help="unpacked Arnis Java world")
    importer.add_argument("--district", required=True, help="district code, e.g. H")
    importer.add_argument("--source-id", required=True, help="stable source dataset id")
    importer.add_argument("--source-name", required=True, help="human-readable source name")
    importer.add_argument("--source-url", default=None)
    importer.add_argument(
        "--source-sha256",
        required=True,
        help="SHA-256 of the OSM/source input supplied to Arnis",
    )
    importer.add_argument(
        "--geo-bbox",
        type=parse_geo_bbox,
        default=None,
        help="source MIN_LAT,MIN_LON,MAX_LAT,MAX_LON",
    )
    importer.add_argument("--license", required=True, help="license/SPDX id")
    importer.add_argument("--license-url", default=None)
    importer.add_argument("--attribution", required=True)
    importer.add_argument(
        "--selection",
        action="append",
        type=parse_selection,
        help="NAME=MIN_X,MIN_Z:MAX_X,MAX_Z (inclusive; repeatable)",
    )
    importer.add_argument(
        "--dimension",
        default="overworld",
        help="overworld, nether, end, or a relative custom dimension path",
    )
    importer.add_argument("--min-y", type=int, default=None)
    importer.add_argument("--max-y", type=int, default=None)
    importer.add_argument(
        "--surface-y",
        type=int,
        default=None,
        help="source street/ground Y aligned to the generated city deck",
    )
    importer.add_argument(
        "--placement-zone",
        action="append",
        choices=sorted(PLACEMENT_ZONES),
        help="runtime zone allowed to use this atlas (repeatable)",
    )
    importer.add_argument(
        "--data-version",
        type=int,
        default=DEFAULT_DATA_VERSION,
        help="output DataVersion (default: highest selected source version)",
    )
    importer.add_argument("--allow-missing", action="store_true")
    importer.add_argument(
        "--defer-catalog-validation",
        action="store_true",
        help="validate newly written NBT now and defer the full catalog scan",
    )
    importer.add_argument("--catalog", type=Path, default=default_catalog())
    importer.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="must be inside the catalog directory (default: arnis/structures)",
    )
    listing = subparsers.add_parser("list", help="list catalog patches")
    listing.add_argument("--catalog", type=Path, default=default_catalog())
    listing.add_argument("--json", action="store_true")
    validator = subparsers.add_parser("validate", help="validate catalog and NBT files")
    validator.add_argument("--catalog", type=Path, default=default_catalog())
    return root


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "import":
            result = import_world(args)
            print(json.dumps(result, indent=2, sort_keys=True))
        elif args.command == "list":
            list_catalog(args.catalog.resolve(), args.json)
        elif args.command == "validate":
            print(json.dumps(validate_catalog(args.catalog.resolve()), indent=2, sort_keys=True))
        else:
            raise ImportFailure(f"unknown command {args.command}")
    except (ImportFailure, OSError) as error:
        print(f"arnis import failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
