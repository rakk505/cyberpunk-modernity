"""
Convert the Bedrock Blockbench pistol (geo.json, a voxelised 1x1x1-cube model) into a
Java item model with `elements`, plus first/third-person `display` transforms so the pistol
renders as a proper 3D model when held.

Bedrock geo UVs are in the texture's own pixel space (here 128x128); Java model UVs are always
in a 0..16 space, so every UV is scaled by 16/texture_size.
"""
import json
import sys

GEO = "/Users/derekwang1/Downloads/Assets-P1/pistol/Tutorial_Image_Generation_04.geo.json"
OUT = "/Users/derekwang1/aai-labs-modernity/minecraft/projects/cyberdeck/src/main/resources/assets/cyberdeck/models/item/pistol_3d.json"

FACES = ["north", "south", "east", "west", "up", "down"]


def main():
    geo = json.load(open(GEO))
    g = geo["minecraft:geometry"][0]
    tw = g["description"]["texture_width"]
    th = g["description"]["texture_height"]
    ux = 16.0 / tw
    uy = 16.0 / th

    elements = []
    for bone in g["bones"]:
        for cube in bone.get("cubes", []):
            ox, oy, oz = cube["origin"]
            sx, sy, sz = cube["size"]
            frm = [ox, oy, oz]
            to = [ox + sx, oy + sy, oz + sz]
            faces = {}
            uv = cube.get("uv", {})
            for face in FACES:
                fd = uv.get(face)
                if fd is None:
                    continue
                u0, v0 = fd["uv"]
                du, dv = fd.get("uv_size", [1.0, 1.0])
                # Scale bedrock pixel UV -> java 0..16 space.
                x0 = u0 * ux
                y0 = v0 * uy
                x1 = (u0 + du) * ux
                y1 = (v0 + dv) * uy
                faces[face] = {"uv": [round(x0, 4), round(y0, 4), round(x1, 4), round(y1, 4)],
                               "texture": "#layer0"}
            elements.append({"from": frm, "to": to, "faces": faces})

    model = {
        "textures": {"layer0": "cyberdeck:item/pistol_model", "particle": "cyberdeck:item/pistol_model"},
        "elements": elements,
        "display": {
            "thirdperson_righthand": {
                "rotation": [0, 90, 0], "translation": [0, 2, 1], "scale": [0.55, 0.55, 0.55]
            },
            "thirdperson_lefthand": {
                "rotation": [0, 90, 0], "translation": [0, 2, 1], "scale": [0.55, 0.55, 0.55]
            },
            "firstperson_righthand": {
                "rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]
            },
            "firstperson_lefthand": {
                "rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]
            },
            "ground": {
                "rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]
            },
            "fixed": {
                "rotation": [0, 90, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]
            }
        }
    }

    with open(OUT, "w") as f:
        json.dump(model, f)
    print(f"wrote {OUT}: {len(elements)} elements")


if __name__ == "__main__":
    main()
