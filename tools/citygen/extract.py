"""
Extract each of the 12 buildings into a vanilla structure .nbt.

Reads the source worlds, crops each building to its tight bounding box (dropping the
ground floor at y=-40; we keep y >= -39 which is the building's own base slab), and writes
a palette-compressed structure file. Only non-air blocks are stored.

Output: <mod>/src/main/resources/data/cyberdeck/structures/hNN.nbt  (also .info json)
"""
import json
import os
from collections import OrderedDict

import anvil
from segment import STRUCT_MIN_Y, GROUND_LIKE, iter_region_files

OUT_DIR = "/Users/derekwang1/aai-labs-modernity/minecraft/projects/cyberdeck/src/main/resources/data/cyberdeck/structure"

# (label, region_dir, x0, x1)  z always -54..70 ; y from STRUCT_MIN_Y up
BUILDINGS = [
    ("h01", "/Users/derekwang1/Downloads/megab-h01-h06/region", 645, 773),
    ("h02", "/Users/derekwang1/Downloads/megab-h01-h06/region", 820, 948),
    ("h03", "/Users/derekwang1/Downloads/megab-h01-h06/region", 995, 1123),
    ("h04", "/Users/derekwang1/Downloads/megab-h01-h06/region", 1170, 1298),
    ("h05", "/Users/derekwang1/Downloads/megab-h01-h06/region", 1345, 1473),
    ("h06", "/Users/derekwang1/Downloads/megab-h01-h06/region", 1520, 1648),
    ("h07", "/Users/derekwang1/Downloads/megab-h07-h12/region", -620, -278),
    ("h08", "/Users/derekwang1/Downloads/megab-h07-h12/region", -232, -102),
    ("h09", "/Users/derekwang1/Downloads/megab-h07-h12/region", -55, 73),
    ("h10", "/Users/derekwang1/Downloads/megab-h07-h12/region", 118, 250),
    ("h11", "/Users/derekwang1/Downloads/megab-h07-h12/region", 295, 423),
    ("h12", "/Users/derekwang1/Downloads/megab-h07-h12/region", 470, 598),
]

# pad z a little in case a building bulges past the observed band
Z0, Z1 = -60, 76


def entry_key(entry: dict) -> str:
    name = entry["Name"]
    props = entry.get("Properties")
    if not props:
        return name
    return name + "|" + ",".join(f"{k}={v}" for k, v in sorted(props.items()))


def extract_one(label, region_dir, x0, x1):
    # First pass: find tight bounds (min/max of x,y,z among non-air, non-ground blocks)
    # Second pass we do together to save reads: collect all blocks keyed by world coord.
    blocks_world = {}  # (wx,wy,wz) -> entry dict
    minx = miny = minz = 10**9
    maxx = maxy = maxz = -10**9

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
                if cxb + 15 < x0 or cxb > x1 or czb + 15 < Z0 or czb > Z1:
                    continue
                for lx in range(16):
                    wx = cxb + lx
                    if wx < x0 or wx > x1:
                        continue
                    for lz in range(16):
                        wz = czb + lz
                        if wz < Z0 or wz > Z1:
                            continue
                        for sy in sorted(lc.sections.keys()):
                            base = sy * 16
                            if base + 15 < STRUCT_MIN_Y:
                                continue
                            for ly in range(16):
                                y = base + ly
                                if y < STRUCT_MIN_Y:
                                    continue
                                e = lc.block_entry(lx, y, lz)
                                if e is None or e["Name"] in GROUND_LIKE:
                                    continue
                                blocks_world[(wx, y, wz)] = e
                                if wx < minx: minx = wx
                                if wx > maxx: maxx = wx
                                if y < miny: miny = y
                                if y > maxy: maxy = y
                                if wz < minz: minz = wz
                                if wz > maxz: maxz = wz

    if not blocks_world:
        print(label, "NO BLOCKS")
        return None

    sx = maxx - minx + 1
    sy = maxy - miny + 1
    sz = maxz - minz + 1

    # Build palette + block list in structure-local coords.
    palette_index = OrderedDict()  # key -> index
    palette_entries = []           # index -> entry dict
    blocks = []
    for (wx, wy, wz), e in blocks_world.items():
        k = entry_key(e)
        idx = palette_index.get(k)
        if idx is None:
            idx = len(palette_entries)
            palette_index[k] = idx
            palette_entries.append({"Name": e["Name"], "Properties": e.get("Properties")})
        blocks.append((wx - minx, wy - miny, wz - minz, idx))

    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, f"{label}.nbt")
    anvil.write_structure(out_path, (sx, sy, sz), palette_entries, blocks)
    info = {
        "label": label,
        "size": [sx, sy, sz],
        "block_count": len(blocks),
        "palette_size": len(palette_entries),
        "file_bytes": os.path.getsize(out_path),
    }
    print(f"{label}: size={sx}x{sy}x{sz} blocks={len(blocks):,} "
          f"palette={len(palette_entries)} file={info['file_bytes']:,}B")
    return info


def main(only=None):
    infos = []
    for (label, d, x0, x1) in BUILDINGS:
        if only and label not in only:
            continue
        info = extract_one(label, d, x0, x1)
        if info:
            infos.append(info)
    with open(os.path.join(OUT_DIR, "_index.json"), "w") as f:
        json.dump({i["label"]: i for i in infos}, f, indent=2)


if __name__ == "__main__":
    import sys
    only = set(sys.argv[1:]) or None
    main(only)
