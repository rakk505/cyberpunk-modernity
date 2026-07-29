"""Probe a source world: find ground level, ground block, and building footprint bounds."""
import os
import sys
from collections import Counter

import anvil

REGION_DIRS = [
    "/Users/derekwang1/Downloads/megab-h01-h06/region",
    "/Users/derekwang1/Downloads/megab-h07-h12/region",
]


def iter_region_files(region_dir):
    for fn in os.listdir(region_dir):
        if not fn.endswith(".mca"):
            continue
        parts = fn.split(".")  # r.X.Z.mca
        rx, rz = int(parts[1]), int(parts[2])
        yield rx, rz, os.path.join(region_dir, fn)


def probe(region_dir):
    print(f"\n=== {region_dir} ===")
    ground_counter = Counter()
    y_present = Counter()
    total_chunks = 0
    minx = miny = minz = 10**9
    maxx = maxy = maxz = -10**9
    nonair_col_block = Counter()

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
                total_chunks += 1
                cx = lc.cx
                cz = lc.cz
                # Sample a coarse grid of columns in this chunk
                for lx in range(0, 16, 2):
                    for lz in range(0, 16, 2):
                        wx = cx * 16 + lx
                        wz = cz * 16 + lz
                        # scan vertical column
                        top_solid = None
                        for sy in sorted(lc.sections.keys()):
                            base = sy * 16
                            for ly in range(16):
                                y = base + ly
                                b = lc.block(lx, y, lz)
                                if b != "minecraft:air" and b != "minecraft:cave_air" and b != "minecraft:void_air":
                                    y_present[y] += 1
                                    nonair_col_block[b] += 1
                                    if y < miny: miny = y
                                    if y > maxy: maxy = y
                                    if wx < minx: minx = wx
                                    if wx > maxx: maxx = wx
                                    if wz < minz: minz = wz
                                    if wz > maxz: maxz = wz
    print("chunks with data:", total_chunks)
    print("world block bounds x[%d..%d] y[%d..%d] z[%d..%d]" % (minx, maxx, miny, maxy, minz, maxz))
    print("most common y (ground-ish):", y_present.most_common(6))
    print("most common non-air blocks:", nonair_col_block.most_common(12))


if __name__ == "__main__":
    for d in REGION_DIRS:
        probe(d)
