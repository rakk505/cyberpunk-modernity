"""
Extract each of the smaller cyberpunk buildings (single-building world saves under
Downloads/cyberpunkbuildings) into a vanilla structure .nbt.

Unlike extract.py (which sliced megabuildings out of shared worlds via hardcoded X-bands),
each of these worlds holds exactly one build. We auto-detect the ground floor of each world
(the densest solid layer near the bottom) and keep everything at and above it, cropped to the
tight non-air bounding box.

Output: <mod>/src/main/resources/data/cyberdeck/structure/<name>.nbt
"""
import json
import os
from collections import Counter, OrderedDict

import anvil

ROOT = "/Users/derekwang1/Downloads/cyberpunkbuildings"
OUT_DIR = "/Users/derekwang1/aai-labs-modernity/minecraft/projects/cyberdeck/src/main/resources/data/cyberdeck/structure"

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}

# (output name, source world folder). Duplicates ("Cyberpunk 3", "Build 2") and the huge
# megastructure ("Cyberpunk 2") are intentionally excluded.
BUILDINGS = [
    ("cp_tower", "Cyberpunk"),        # 33x27x26
    ("cp_shack", "Cyberpunk (1)"),    # 15x28x20
    ("cp_garage", "CyberpunkGarage"), # 58x54x82
    ("cp_house", "CyberpunkHouse"),   # 21x46x27
    ("cp_shop", "Build"),             # 13x36x23
]


def iter_region_files(region_dir):
    for fn in os.listdir(region_dir):
        if not fn.endswith(".mca"):
            continue
        parts = fn.split(".")
        rx, rz = int(parts[1]), int(parts[2])
        yield rx, rz, os.path.join(region_dir, fn)


def entry_key(entry: dict) -> str:
    name = entry["Name"]
    props = entry.get("Properties")
    if not props:
        return name
    return name + "|" + ",".join(f"{k}={v}" for k, v in sorted(props.items()))


def load_world(region_dir):
    """Return {(wx,wy,wz): entry} for every non-air block in the world."""
    blocks = {}
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
                                e = lc.block_entry(lx, y, lz)
                                if e is None or e["Name"] in AIR:
                                    continue
                                blocks[(wx, y, wz)] = e
    return blocks


def detect_floor_y(blocks):
    """The floor is the lowest y whose solid-block count is a large fraction of the max layer."""
    layer_count = Counter()
    for (wx, wy, wz) in blocks:
        layer_count[wy] += 1
    if not layer_count:
        return None
    peak = max(layer_count.values())
    threshold = peak * 0.5
    # lowest y that looks like a broad plane (a "floor" slab), not sparse terrain/roots
    for y in sorted(layer_count):
        if layer_count[y] >= threshold:
            return y
    return min(layer_count)


def extract_one(name, world):
    region_dir = os.path.join(ROOT, world, "region")
    blocks = load_world(region_dir)
    if not blocks:
        print(name, "NO BLOCKS")
        return None

    floor_y = detect_floor_y(blocks)
    # Keep the floor slab and everything above it; drop terrain below the floor.
    kept = {pos: e for pos, e in blocks.items() if pos[1] >= floor_y}
    if not kept:
        print(name, "NO BLOCKS ABOVE FLOOR")
        return None

    minx = min(p[0] for p in kept)
    maxx = max(p[0] for p in kept)
    miny = min(p[1] for p in kept)
    maxy = max(p[1] for p in kept)
    minz = min(p[2] for p in kept)
    maxz = max(p[2] for p in kept)

    sx = maxx - minx + 1
    sy = maxy - miny + 1
    sz = maxz - minz + 1

    palette_index = OrderedDict()
    palette_entries = []
    out_blocks = []
    for (wx, wy, wz), e in kept.items():
        k = entry_key(e)
        idx = palette_index.get(k)
        if idx is None:
            idx = len(palette_entries)
            palette_index[k] = idx
            palette_entries.append({"Name": e["Name"], "Properties": e.get("Properties")})
        out_blocks.append((wx - minx, wy - miny, wz - minz, idx))

    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, f"{name}.nbt")
    anvil.write_structure(out_path, (sx, sy, sz), palette_entries, out_blocks)
    info = {
        "name": name,
        "source": world,
        "size": [sx, sy, sz],
        "floor_y": floor_y,
        "block_count": len(out_blocks),
        "palette_size": len(palette_entries),
        "file_bytes": os.path.getsize(out_path),
    }
    print(f"{name}: size={sx}x{sy}x{sz} floor_y={floor_y} blocks={len(out_blocks):,} "
          f"palette={len(palette_entries)} file={info['file_bytes']:,}B")
    return info


def main(only=None):
    infos = []
    for (name, world) in BUILDINGS:
        if only and name not in only:
            continue
        info = extract_one(name, world)
        if info:
            infos.append(info)
    with open(os.path.join(OUT_DIR, "_index2.json"), "w") as f:
        json.dump({i["name"]: i for i in infos}, f, indent=2)


if __name__ == "__main__":
    import sys
    only = set(sys.argv[1:]) or None
    main(only)
