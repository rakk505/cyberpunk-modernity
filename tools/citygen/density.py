"""Report solid-block count per Y layer for one building band to see real extent."""
import os
from collections import defaultdict
import anvil
from segment import FLOOR_Y, STRUCT_MIN_Y, GROUND_LIKE, iter_region_files

# World 1, band 0 => H01: x[645..773] z[-54..70]
D = "/Users/derekwang1/Downloads/megab-h01-h06/region"
X0, X1, Z0, Z1 = 645, 773, -54, 70

per_y = defaultdict(int)
total = 0
for rx, rz, path in sorted(iter_region_files(D)):
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
            if cxb + 15 < X0 or cxb > X1 or czb + 15 < Z0 or czb > Z1:
                continue
            for lx in range(16):
                wx = cxb + lx
                if wx < X0 or wx > X1:
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
                            b = lc.block(lx, y, lz)
                            if b in GROUND_LIKE:
                                continue
                            per_y[y] += 1
                            total += 1

ys = sorted(per_y)
print("H01 total solid blocks above floor:", total)
print("y range with content:", ys[0], "..", ys[-1])
# print histogram every 16 layers
for y in range(ys[0], ys[-1] + 1, 16):
    cnt = sum(per_y.get(yy, 0) for yy in range(y, y + 16))
    bar = "#" * (cnt // 2000)
    print(f"y {y:4d}..{y+15:4d}: {cnt:7d} {bar}")
