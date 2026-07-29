"""
Probe the new single-building worlds under Downloads/cyberpunkbuildings.

For each world it reports:
  - the dominant "ground" block and the y-level where a broad flat floor sits
  - the tight non-air bounding box (all coords) so we can pick a crop
  - a sample of the most common non-air blocks (to understand the palette / colors)

These are separate world saves, each holding roughly one building, so there is no
X-band segmentation to do -- we just crop each world's structure above its floor.
"""
import os
import sys
from collections import Counter, defaultdict

import anvil

ROOT = "/Users/derekwang1/Downloads/cyberpunkbuildings"

WORLDS = [
    "Cyberpunk",
    "Cyberpunk (1)",
    "Cyberpunk 2",
    "Cyberpunk 3",
    "CyberpunkGarage",
    "CyberpunkHouse",
    "Build",
    "Build 2",
]

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def iter_region_files(region_dir):
    for fn in os.listdir(region_dir):
        if not fn.endswith(".mca"):
            continue
        parts = fn.split(".")
        rx, rz = int(parts[1]), int(parts[2])
        yield rx, rz, os.path.join(region_dir, fn)


def probe(world):
    region_dir = os.path.join(ROOT, world, "region")
    if not os.path.isdir(region_dir):
        print(f"{world}: NO region dir")
        return

    minx = miny = minz = 10**9
    maxx = maxy = maxz = -10**9
    block_counter = Counter()
    # count solid blocks per y-layer to find the flat ground plane (the y with the most blocks)
    layer_count = Counter()
    layer_block = defaultdict(Counter)
    total = 0

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
                cxb = lc.cx * 16
                czb = lc.cz * 16
                for lx in range(16):
                    wx = cxb + lx
                    for lz in range(16):
                        wz = czb + lz
                        for sy in sorted(lc.sections.keys()):
                            base = sy * 16
                            for ly in range(16):
                                y = base + ly
                                name = lc.block(lx, y, lz)
                                if name in AIR:
                                    continue
                                total += 1
                                block_counter[name] += 1
                                layer_count[y] += 1
                                layer_block[y][name] += 1
                                if wx < minx: minx = wx
                                if wx > maxx: maxx = wx
                                if y < miny: miny = y
                                if y > maxy: maxy = y
                                if wz < minz: minz = wz
                                if wz > maxz: maxz = wz

    if total == 0:
        print(f"{world}: EMPTY")
        return

    # The ground plane is the densest layer near the bottom.
    dense_layers = sorted(layer_count.items(), key=lambda kv: (-kv[1], kv[0]))[:3]
    print(f"\n=== {world} ===")
    print(f"  bounds x[{minx}..{maxx}] y[{miny}..{maxy}] z[{minz}..{maxz}] "
          f"size {maxx-minx+1}x{maxy-miny+1}x{maxz-minz+1}  solid={total:,}")
    print(f"  densest layers (y: count / top block):")
    for y, c in dense_layers:
        top = layer_block[y].most_common(1)[0]
        print(f"    y={y}: {c:,}  {top[0]} x{top[1]:,}")
    print(f"  top blocks: " + ", ".join(f"{n.split(':')[-1]}={c:,}" for n, c in block_counter.most_common(8)))


def main():
    only = sys.argv[1:] or WORLDS
    for w in only:
        probe(w)


if __name__ == "__main__":
    main()
