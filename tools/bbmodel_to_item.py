#!/usr/bin/env python3
"""Convert an img2blockbench .bbmodel into a vanilla Minecraft Java item model JSON.

Java item/block models only allow a single rotation axis snapped to
{-45,-22.5,0,22.5,45} degrees, so angled cuboids are snapped to the nearest
allowed value on their dominant axis. UVs are rescaled from the atlas resolution
to Minecraft's 0-16 texture space.
"""
import json
import sys

ALLOWED = [-45, -22.5, 0, 22.5, 45]
AXES = ["x", "y", "z"]


def snap_angle(a):
    return min(ALLOWED, key=lambda v: abs(v - a))


def dominant_rotation(rot):
    # pick the axis with the largest magnitude; Java allows only one axis
    idx = max(range(3), key=lambda i: abs(rot[i]))
    if abs(rot[idx]) < 0.01:
        return None
    return AXES[idx], snap_angle(rot[idx])


def convert(bb_path, texture_ref, out_path):
    m = json.load(open(bb_path))
    res = m.get("resolution", {"width": 16, "height": 16})
    sw, sh = res["width"], res["height"]
    ux, uy = 16.0 / sw, 16.0 / sh

    elements = []
    for e in m["elements"]:
        if e.get("type") != "cube":
            continue
        el = {
            "from": [round(v, 4) for v in e["from"]],
            "to": [round(v, 4) for v in e["to"]],
            "faces": {},
        }
        rot = e.get("rotation", [0, 0, 0])
        dr = dominant_rotation(rot)
        if dr:
            axis, ang = dr
            el["rotation"] = {
                "origin": [round(v, 4) for v in e.get("origin", [0, 0, 0])],
                "axis": axis,
                "angle": ang,
            }
        for face, fd in e["faces"].items():
            uv = fd.get("uv")
            if uv is None:
                continue
            el["faces"][face] = {
                "uv": [
                    round(uv[0] * ux, 4),
                    round(uv[1] * uy, 4),
                    round(uv[2] * ux, 4),
                    round(uv[3] * uy, 4),
                ],
                "texture": "#layer0",
            }
        elements.append(el)

    model = {
        "credit": "img2blockbench",
        "texture_size": [sw, sh],
        "textures": {"layer0": texture_ref, "particle": texture_ref},
        "elements": elements,
        "display": {
            "thirdperson_righthand": {
                "rotation": [0, -90, 25],
                "translation": [0, 4, 0.5],
                "scale": [0.55, 0.55, 0.55],
            },
            "thirdperson_lefthand": {
                "rotation": [0, 90, -25],
                "translation": [0, 4, 0.5],
                "scale": [0.55, 0.55, 0.55],
            },
            "firstperson_righthand": {
                "rotation": [0, -55, 0],
                "translation": [0, 4, 2],
                "scale": [0.7, 0.7, 0.7],
            },
            "firstperson_lefthand": {
                "rotation": [0, 55, 0],
                "translation": [0, 4, 2],
                "scale": [0.7, 0.7, 0.7],
            },
            "gui": {
                "rotation": [30, 135, 0],
                "translation": [0, 0, 0],
                "scale": [0.62, 0.62, 0.62],
            },
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
            "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
        },
    }
    json.dump(model, open(out_path, "w"), indent=2)
    print("wrote", out_path, "elements:", len(elements))


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2], sys.argv[3])
