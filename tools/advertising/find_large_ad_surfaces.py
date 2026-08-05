#!/usr/bin/env python3
"""Find maximal glass-free illuminated facade rectangles in audited Arnis NBT tiles."""

from __future__ import annotations

import argparse
from collections import Counter, deque
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
import gzip
import hashlib
import json
import os
from pathlib import Path
import sys
from typing import Any


TOOLS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_ROOT / "arnis"))
from arnis_import import NbtReader  # noqa: E402


FORMAT = "cyberdeck:large_ad_surfaces"
FORMAT_VERSION = 3
MIN_WIDTH = 8
MIN_HEIGHT = 4
MAX_WIDTH = 16
MAX_HEIGHT = 256

LIGHT_BLOCKS = frozenset(
    {
        "minecraft:beacon",
        "minecraft:glowstone",
        "minecraft:jack_o_lantern",
        "minecraft:ochre_froglight",
        "minecraft:pearlescent_froglight",
        "minecraft:redstone_lamp",
        "minecraft:sea_lantern",
        "minecraft:shroomlight",
        "minecraft:verdant_froglight",
    }
)

SUPPORT_HASH_ALIASES = {
    "minecraft:glowstone": "cyberdeck:luminous_facade",
    "minecraft:sea_lantern": "cyberdeck:luminous_facade",
    "cyberdeck:camouflaged_sea_lantern": "cyberdeck:luminous_facade",
}

NON_FULL_MARKERS = (
    "_banner",
    "_bed",
    "_button",
    "_candle",
    "_carpet",
    "_coral",
    "_door",
    "_fence",
    "_flower",
    "_head",
    "_leaves",
    "_pane",
    "_pressure_plate",
    "_rail",
    "_sapling",
    "_sign",
    "_skull",
    "_slab",
    "_stairs",
    "_torch",
    "_trapdoor",
    "_wall",
    "_wall_hanging_sign",
    "_wall_sign",
    "_wall_skull",
    "_wall_torch",
)

NON_FULL_NAMES = frozenset(
    {
        "minecraft:air",
        "minecraft:anvil",
        "minecraft:barrier",
        "minecraft:bell",
        "minecraft:brewing_stand",
        "minecraft:cactus",
        "minecraft:campfire",
        "minecraft:chain",
        "minecraft:chest",
        "minecraft:cobweb",
        "minecraft:decorated_pot",
        "minecraft:dragon_egg",
        "minecraft:end_rod",
        "minecraft:flower_pot",
        "minecraft:grass_block",
        "minecraft:grindstone",
        "minecraft:hopper",
        "minecraft:iron_bars",
        "minecraft:ladder",
        "minecraft:lantern",
        "minecraft:lectern",
        "minecraft:lightning_rod",
        "minecraft:rail",
        "minecraft:scaffolding",
        "minecraft:soul_campfire",
        "minecraft:soul_lantern",
        "minecraft:spawner",
        "minecraft:stonecutter",
        "minecraft:tripwire",
        "minecraft:tripwire_hook",
        "minecraft:vine",
    }
)

FACINGS = ("north", "south", "east", "west")
STEPS = {
    "north": (0, 0, -1),
    "south": (0, 0, 1),
    "east": (1, 0, 0),
    "west": (-1, 0, 0),
}

HORIZONTAL_AIR_STEPS = ((-1, 0, 0), (1, 0, 0), (0, 0, -1), (0, 0, 1))


@dataclass(frozen=True)
class SearchConfig:
    min_width: int
    min_height: int
    max_width: int
    max_height: int


@dataclass(frozen=True)
class Rectangle:
    support: tuple[int, int, int]
    facing: str
    width: int
    height: int
    light_blocks: int
    support_blocks: tuple[str, ...]
    boundary_face: bool

    @property
    def area(self) -> int:
        return self.width * self.height

    @property
    def support_hash(self) -> str:
        normalized_blocks = (
            SUPPORT_HASH_ALIASES.get(block, block) for block in self.support_blocks
        )
        payload = "\n".join(normalized_blocks).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()

    def score(self) -> tuple[float, ...]:
        aspect_error = abs(self.width / self.height - 16.0 / 9.0)
        return (
            self.area,
            -aspect_error,
            self.light_blocks,
            self.width,
            self.height,
            -self.support[1],
        )

    def identity(self) -> tuple[object, ...]:
        return (
            self.support,
            self.facing,
            self.width,
            self.height,
            self.light_blocks,
            self.support_hash,
            self.boundary_face,
        )

    def output_order(self) -> tuple[object, ...]:
        return (
            -self.area,
            abs(self.width / self.height - 16.0 / 9.0),
            -self.light_blocks,
            -self.width,
            -self.height,
            self.support[1],
            FACINGS.index(self.facing),
            self.support[0],
            self.support[2],
            self.support_hash,
        )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_glass(name: str) -> bool:
    return "glass" in name


def is_full_surface_block(name: str) -> bool:
    if name in LIGHT_BLOCKS:
        return True
    if name in NON_FULL_NAMES or is_glass(name):
        return False
    return not any(marker in name for marker in NON_FULL_MARKERS)


def project(position: tuple[int, int, int], facing: str) -> tuple[int, int, int]:
    x, y, z = position
    if facing == "south":
        return z, x, y
    if facing == "north":
        return z, -x, y
    if facing == "west":
        return x, z, y
    if facing == "east":
        return x, -z, y
    raise ValueError(facing)


def unproject(plane: int, horizontal: int, y: int, facing: str) -> tuple[int, int, int]:
    if facing == "south":
        return horizontal, y, plane
    if facing == "north":
        return -horizontal, y, plane
    if facing == "west":
        return plane, y, horizontal
    if facing == "east":
        return plane, y, -horizontal
    raise ValueError(facing)


def add(position: tuple[int, int, int], delta: tuple[int, int, int]) -> tuple[int, int, int]:
    return tuple(position[index] + delta[index] for index in range(3))


def in_horizontal_bounds(position: tuple[int, int, int], size: tuple[int, int, int]) -> bool:
    return 0 <= position[0] < size[0] and 0 <= position[2] < size[2]


def is_exterior_air(
    start: tuple[int, int, int],
    blocks: dict[tuple[int, int, int], str],
    size: tuple[int, int, int],
    cache: dict[tuple[int, int, int], bool],
) -> bool:
    """Check same-height air connectivity to a horizontal structure boundary."""
    cached = cache.get(start)
    if cached is not None:
        return cached
    queue = deque([start])
    visited = {start}
    exterior = False
    while queue:
        position = queue.popleft()
        x, _, z = position
        if x in (0, size[0] - 1) or z in (0, size[2] - 1):
            exterior = True
            break
        for step in HORIZONTAL_AIR_STEPS:
            neighbour = add(position, step)
            known = cache.get(neighbour)
            if known is True:
                exterior = True
                queue.clear()
                break
            if (in_horizontal_bounds(neighbour, size)
                    and neighbour not in blocks
                    and neighbour not in visited
                    and known is None):
                visited.add(neighbour)
                queue.append(neighbour)
    for position in visited:
        cache[position] = exterior
    return exterior


def component_rectangles(
    cells: dict[tuple[int, int], str],
    facing: str,
    plane: int,
    boundary_face: bool,
    config: SearchConfig,
) -> tuple[list[Rectangle], int]:
    glass_cells = {cell for cell, name in cells.items() if is_glass(name)}
    remaining = set(cells) - glass_cells
    rectangles: list[Rectangle] = []
    rejected_for_glass = len(glass_cells)

    while remaining:
        start = min(remaining, key=lambda value: (value[1], value[0]))
        queue = deque([start])
        remaining.remove(start)
        component: set[tuple[int, int]] = set()
        while queue:
            cell = queue.popleft()
            component.add(cell)
            horizontal, y = cell
            for neighbour in (
                (horizontal - 1, y),
                (horizontal + 1, y),
                (horizontal, y - 1),
                (horizontal, y + 1),
            ):
                if neighbour in remaining:
                    remaining.remove(neighbour)
                    queue.append(neighbour)

        eligible = {cell for cell in component if is_full_surface_block(cells[cell])}
        lights = {cell for cell in eligible if cells[cell] in LIGHT_BLOCKS}
        rectangle = largest_rectangle(
            eligible, lights, cells, facing, plane, boundary_face, config
        )
        if rectangle is not None:
            rectangles.append(rectangle)

    return rectangles, rejected_for_glass


def largest_rectangle(
    cells: set[tuple[int, int]],
    lights: set[tuple[int, int]],
    cell_blocks: dict[tuple[int, int], str],
    facing: str,
    plane: int,
    boundary_face: bool,
    config: SearchConfig,
) -> Rectangle | None:
    if not cells:
        return None
    min_horizontal = min(value[0] for value in cells)
    max_horizontal = max(value[0] for value in cells)
    min_y = min(value[1] for value in cells)
    max_y = max(value[1] for value in cells)
    columns = max_horizontal - min_horizontal + 1
    rows = max_y - min_y + 1

    light_prefix = [[0] * (columns + 1) for _ in range(rows + 1)]
    for row in range(rows):
        running = 0
        for column in range(columns):
            cell = (min_horizontal + column, min_y + row)
            running += int(cell in lights)
            light_prefix[row + 1][column + 1] = light_prefix[row][column + 1] + running

    def light_count(left: int, bottom: int, width: int, height: int) -> int:
        right = left + width
        top = bottom + height
        return (
            light_prefix[top][right]
            - light_prefix[bottom][right]
            - light_prefix[top][left]
            + light_prefix[bottom][left]
        )

    heights = [0] * columns
    best: Rectangle | None = None
    for row in range(rows):
        world_y = min_y + row
        for column in range(columns):
            cell = (min_horizontal + column, world_y)
            heights[column] = heights[column] + 1 if cell in cells else 0

        for left in range(columns):
            available_height = config.max_height
            max_right = min(columns, left + config.max_width)
            for right in range(left, max_right):
                available_height = min(available_height, heights[right])
                width = right - left + 1
                if width < config.min_width or available_height < config.min_height:
                    continue
                height = min(available_height, config.max_height)
                bottom_row = row - height + 1
                lights_in_rectangle = light_count(left, bottom_row, width, height)
                support = unproject(
                    plane,
                    min_horizontal + left,
                    min_y + bottom_row,
                    facing,
                )
                support_blocks = tuple(
                    cell_blocks[(
                        min_horizontal + left + column,
                        min_y + bottom_row + support_row,
                    )]
                    for support_row in range(height)
                    for column in range(width)
                )
                candidate = Rectangle(
                    support,
                    facing,
                    width,
                    height,
                    lights_in_rectangle,
                    support_blocks,
                    boundary_face,
                )
                if best is None or candidate.score() > best.score():
                    best = candidate
    return best


def scan_patch(
    arguments: tuple[Path, dict[str, Any], SearchConfig]
) -> tuple[str, list[Rectangle], dict[str, int]]:
    catalog_root, patch, config = arguments
    structure = catalog_root / patch["file"]
    document = NbtReader(gzip.decompress(structure.read_bytes())).document()
    size = tuple(int(value) for value in document["size"])
    palette = [str(value["Name"]) for value in document["palette"]]
    lit_palette = int(any(name in LIGHT_BLOCKS for name in palette))

    blocks = {
        tuple(int(value) for value in block["pos"]): palette[int(block["state"])]
        for block in document["blocks"]
        if palette[int(block["state"])] not in {
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air"
        }
    }
    exterior_air_cache: dict[tuple[int, int, int], bool] = {}
    by_plane: dict[tuple[str, int], dict[tuple[int, int], str]] = {}
    boundary_planes: set[tuple[str, int]] = set()
    interior_faces_excluded = 0
    for position, name in blocks.items():
        if not (is_full_surface_block(name) or is_glass(name)):
            continue
        for facing in FACINGS:
            target = add(position, STEPS[facing])
            target_in_bounds = in_horizontal_bounds(target, size)
            if target_in_bounds and target in blocks:
                continue
            if (target_in_bounds
                    and not is_exterior_air(target, blocks, size, exterior_air_cache)):
                interior_faces_excluded += 1
                continue
            plane, horizontal, y = project(position, facing)
            plane_key = (facing, plane)
            by_plane.setdefault(plane_key, {})[(horizontal, y)] = name
            if not target_in_bounds:
                boundary_planes.add(plane_key)

    candidates: list[Rectangle] = []
    glass_excluded = 0
    for (facing, plane), cells in by_plane.items():
        rectangles, rejected = component_rectangles(
            cells, facing, plane, (facing, plane) in boundary_planes, config
        )
        candidates.extend(rectangles)
        glass_excluded += rejected
    unique = {rectangle.identity(): rectangle for rectangle in candidates}
    surfaces = sorted(unique.values(), key=Rectangle.output_order)
    return patch["id"], surfaces, {
        "glass_excluded": glass_excluded,
        "interior_faces_excluded": interior_faces_excluded,
        "lit_palette": lit_palette,
    }


def main() -> None:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=repository / "src/main/resources/data/neoncity/arnis_districts/catalog.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=repository
        / "src/main/resources/data/cyberdeck/advertising/large_ad_surfaces.json",
    )
    parser.add_argument("--workers", type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()

    config = SearchConfig(MIN_WIDTH, MIN_HEIGHT, MAX_WIDTH, MAX_HEIGHT)
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    patches = catalog["patches"]
    if args.limit is not None:
        patches = patches[: args.limit]
    work = [(args.catalog.parent, patch, config) for patch in patches]
    results: list[tuple[str, list[Rectangle], dict[str, int]]] = []
    with ProcessPoolExecutor(max_workers=max(1, args.workers)) as executor:
        for index, result in enumerate(executor.map(scan_patch, work, chunksize=32), 1):
            results.append(result)
            if index % 1000 == 0:
                print(f"scanned {index}/{len(work)} templates", file=sys.stderr)

    placements: dict[str, list[dict[str, object]]] = {}
    diagnostics: Counter[str] = Counter()
    dimensions: Counter[str] = Counter()
    all_surfaces: list[Rectangle] = []
    for patch_id, surfaces, stats in results:
        diagnostics.update(stats)
        if not surfaces:
            continue
        placements[patch_id] = [
            {
                "support": list(rectangle.support),
                "facing": rectangle.facing,
                "width": rectangle.width,
                "height": rectangle.height,
                "area": rectangle.area,
                "light_blocks": rectangle.light_blocks,
                "support_hash": rectangle.support_hash,
                "boundary_face": rectangle.boundary_face,
            }
            for rectangle in surfaces
        ]
        all_surfaces.extend(surfaces)
        dimensions.update(
            f"{rectangle.width}x{rectangle.height}" for rectangle in surfaces
        )

    surface_count = len(all_surfaces)
    template_placement_count = len(placements)
    max_dimensions = {
        "width": max((surface.width for surface in all_surfaces), default=0),
        "height": max((surface.height for surface in all_surfaces), default=0),
        "area": max((surface.area for surface in all_surfaces), default=0),
    }

    output = {
        "format": FORMAT,
        "version": FORMAT_VERSION,
        "source_catalog_sha256": sha256(args.catalog),
        "constraints": {
            "min_width": config.min_width,
            "min_height": config.min_height,
            "max_width": config.max_width,
            "max_height": config.max_height,
            "prefers_full_light_blocks": True,
            "reject_glass_in_rectangle": True,
            "boundary_faces": "included_pending_runtime_validation",
            "target_air": "horizontal_boundary_connected",
        },
        "template_count": len(work),
        "template_placement_count": template_placement_count,
        "surface_count": surface_count,
        "placement_count": surface_count,
        "diagnostics": {
            "surface_count": surface_count,
            "templates_with_surfaces": template_placement_count,
            "templates_with_light_palette": diagnostics["lit_palette"],
            "glass_cells_excluded": diagnostics["glass_excluded"],
            "interior_faces_excluded": diagnostics["interior_faces_excluded"],
            "boundary_surface_count": sum(
                surface.boundary_face for surface in all_surfaces
            ),
            "multi_surface_templates": sum(
                len(surfaces) > 1 for _, surfaces, _ in results
            ),
            "max_dimensions": max_dimensions,
            "dimension_counts": dict(sorted(dimensions.items())),
        },
        "placements": dict(sorted(placements.items())),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"wrote {surface_count} surfaces across {template_placement_count} "
        f"templates from {len(work)} audited templates to {args.output}"
    )


if __name__ == "__main__":
    main()
