"""
Build a per-column footprint (any solid block strictly above the ground floor),
then segment it into individual buildings via connected components along X gaps.

Ground floor is y=-40 (from probe). We consider y >= FLOOR+1 as "structure".
"""
import os
import sys
from collections import defaultdict

import anvil

FLOOR_Y = -40
STRUCT_MIN_Y = FLOOR_Y + 1

GROUND_LIKE = {
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
}


def iter_region_files(region_dir):
    for fn in os.listdir(region_dir):
        if not fn.endswith(".mca"):
            continue
        parts = fn.split(".")
        rx, rz = int(parts[1]), int(parts[2])
        yield rx, rz, os.path.join(region_dir, fn)


def build_footprint(region_dir):
    """Return set of (wx,wz) columns that contain a structure block above floor,
    plus per-column (min_y,max_y)."""
    cols = {}
    for rx, rz, path in sorted(iter_region_files(region_dir)):
        reg = anvil.Region(path)
        for clx in range(32):
            for clz in range(32):
                nbt = reg.chunk_nbt(clx, clz)
                if nbt is None:
                    continue
                lc = anvil.load_chunk(nbt)
                if lc is None or not lc.sections:
                    continue
                sec_ys = sorted(lc.sections.keys())
                for lx in range(16):
                    for lz in range(16):
                        wx = lc.cx * 16 + lx
                        wz = lc.cz * 16 + lz
                        cmin = None
                        cmax = None
                        for sy in sec_ys:
                            base = sy * 16
                            if base + 15 < STRUCT_MIN_Y:
                                continue
                            for ly in range(16):
                                y = base + ly
                                if y < STRUCT_MIN_Y:
                                    continue
                                b = lc.block(lx, y, lz)
                                if b in GROUND_LIKE:
                                    continue
                                if cmin is None:
                                    cmin = y
                                cmax = y
                        if cmin is not None:
                            cols[(wx, wz)] = (cmin, cmax)
    return cols


def segment_x_bands(cols, gap=8):
    """Group columns by X into bands separated by >= `gap` empty X slices.
    Buildings are laid out along X, so an X gap separates them."""
    xs = sorted({x for (x, z) in cols})
    if not xs:
        return []
    bands = []
    start = xs[0]
    prev = xs[0]
    for x in xs[1:]:
        if x - prev > gap:
            bands.append((start, prev))
            start = x
        prev = x
    bands.append((start, prev))
    return bands


def bounds_for_band(cols, x0, x1):
    zs = [z for (x, z) in cols if x0 <= x <= x1]
    xs = [x for (x, z) in cols if x0 <= x <= x1]
    ymin = min(cols[(x, z)][0] for (x, z) in cols if x0 <= x <= x1)
    ymax = max(cols[(x, z)][1] for (x, z) in cols if x0 <= x <= x1)
    return (min(xs), min(zs), ymin, max(xs), max(zs), ymax)


def main():
    for d, label in [
        ("/Users/derekwang1/Downloads/megab-h01-h06/region", "H01-H06"),
        ("/Users/derekwang1/Downloads/megab-h07-h12/region", "H07-H12"),
    ]:
        print(f"\n=== {label} ({d}) ===")
        cols = build_footprint(d)
        print("structure columns:", len(cols))
        bands = segment_x_bands(cols, gap=8)
        print(f"detected {len(bands)} X-bands (candidate buildings):")
        for i, (x0, x1) in enumerate(bands):
            bx0, bz0, by0, bx1, bz1, by1 = bounds_for_band(cols, x0, x1)
            print(f"  band {i}: x[{bx0}..{bx1}] z[{bz0}..{bz1}] y[{by0}..{by1}] "
                  f"(w={bx1-bx0+1}, d={bz1-bz0+1}, h={by1-by0+1})")


if __name__ == "__main__":
    main()
