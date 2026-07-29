"""Refine world-2 band 0 and report tight per-building bounds with smaller gap."""
from segment import build_footprint, segment_x_bands, bounds_for_band

d2 = "/Users/derekwang1/Downloads/megab-h07-h12/region"
cols = build_footprint(d2)
for gap in (4, 3, 2):
    bands = segment_x_bands(cols, gap=gap)
    print(f"gap={gap}: {len(bands)} bands")
    for i, (x0, x1) in enumerate(bands):
        bx0, bz0, by0, bx1, bz1, by1 = bounds_for_band(cols, x0, x1)
        print(f"  band {i}: x[{bx0}..{bx1}] z[{bz0}..{bz1}] "
              f"(w={bx1-bx0+1}, d={bz1-bz0+1}, h={by1-by0+1})")
    print()
