#!/usr/bin/env python3
"""Lightweight orthographic renderer for a Bedrock geo.json / bbmodel.

Draws box wireframe+shaded projections (left side and isometric) so we can
visually sanity-check the SMG silhouette against the reference. Not a substitute
for Blockbench, just a fast resemblance check.
"""
import json
import math
import sys

from PIL import Image, ImageDraw

FACE_SHADE = {
    "up": 1.0,
    "down": 0.45,
    "north": 0.7,
    "south": 0.85,
    "east": 0.6,
    "west": 0.75,
}


def load_cubes(geo_path):
    data = json.load(open(geo_path))
    geo = data["minecraft:geometry"][0]
    cubes = []
    for bone in geo.get("bones", []):
        for c in bone.get("cubes", []):
            o = c["origin"]
            s = c["size"]
            rot = c.get("rotation", [0, 0, 0])
            piv = c.get("pivot", [0, 0, 0])
            cubes.append((o, s, rot, piv))
    return cubes


def rot_point(p, rot_deg, pivot):
    x, y, z = p[0] - pivot[0], p[1] - pivot[1], p[2] - pivot[2]
    rx, ry, rz = (math.radians(a) for a in rot_deg)
    # X
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    # Y
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    # Z
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return [x + pivot[0], y + pivot[1], z + pivot[2]]


def corners(o, s):
    return [
        [o[0] + dx * s[0], o[1] + dy * s[1], o[2] + dz * s[2]]
        for dx in (0, 1)
        for dy in (0, 1)
        for dz in (0, 1)
    ]


FACES = {
    "west": [0, 1, 3, 2],
    "east": [4, 6, 7, 5],
    "down": [0, 4, 5, 1],
    "up": [2, 3, 7, 6],
    "north": [0, 2, 6, 4],
    "south": [1, 5, 7, 3],
}


def project(pt, mode):
    x, y, z = pt
    if mode == "left":
        # look along -X: use Z horizontal, Y vertical
        return (z, -y)
    # isometric
    ix = (x - z) * math.cos(math.radians(30))
    iy = (x + z) * math.sin(math.radians(30)) - y
    return (ix, iy)


def render(cubes, mode, size=(640, 360), base_color=(40, 44, 52)):
    quads = []
    for o, s, rot, piv in cubes:
        cs = [rot_point(c, rot, piv) for c in corners(o, s)]
        # depth per face for painter's algorithm
        for fname, idx in FACES.items():
            poly3 = [cs[i] for i in idx]
            depth = sum((p[0] + p[2]) if mode == "iso" else -p[0] for p in poly3) / 4
            quads.append((depth, fname, [project(p, mode) for p in poly3]))
    quads.sort(key=lambda q: q[0])
    xs = [p[0] for _, _, poly in quads for p in poly]
    ys = [p[1] for _, _, poly in quads for p in poly]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    W, H = size
    pad = 24
    scale = min((W - 2 * pad) / (maxx - minx), (H - 2 * pad) / (maxy - miny))

    def tf(p):
        return (pad + (p[0] - minx) * scale, pad + (p[1] - miny) * scale)

    img = Image.new("RGB", (W, H), (245, 245, 245))
    d = ImageDraw.Draw(img)
    for _, fname, poly in quads:
        sh = FACE_SHADE[fname]
        col = tuple(int(c * sh) for c in base_color)
        d.polygon([tf(p) for p in poly], fill=col, outline=(20, 20, 24))
    return img


def main():
    geo = sys.argv[1]
    out = sys.argv[2]
    cubes = load_cubes(geo)
    left = render(cubes, "left")
    iso = render(cubes, "iso")
    combo = Image.new("RGB", (640, 720), (255, 255, 255))
    combo.paste(left, (0, 0))
    combo.paste(iso, (0, 360))
    combo.save(out)
    print("wrote", out, "cubes:", len(cubes))


if __name__ == "__main__":
    main()
