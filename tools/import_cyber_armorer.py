#!/usr/bin/env python3
"""Import Cyber Armorer (TaCZ) weapons into cyberdeck's animated-Bedrock gun pipeline.

Given the extracted pack, each weapon contributes four assets verbatim - Bedrock geometry,
keyframe animations, the UV atlas, and the flat inventory icon - plus one generated vanilla item
model. The item model is a direct transcription of the geometry: Bedrock cube origins and sizes map
straight onto Java ``from``/``to``, UVs are rescaled from the atlas's pixel space into Java's 0..16
space, and each cube's own pivot/rotation becomes a Java element rotation snapped to the single
axis and fixed angle set that format allows.

Ballistics are NOT copied literally. Each new weapon is scaled against the already-tuned sibling it
shares a frame with, using the pack's own ratios, so a variant sits relative to its base exactly as
the pack intends while the mod's hitscan balance stays where it was tuned.

Usage:
    python tools/import_cyber_armorer.py <extracted-pack-dir> [--print-enum]
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/cyberdeck"

FACES = ("north", "south", "east", "west", "up", "down")
# Java item models allow one rotation axis per element, snapped to these angles.
ALLOWED_ANGLES = (-45.0, -22.5, 0.0, 22.5, 45.0)
AXES = ("x", "y", "z")
# These bones hold a stand-in box for each of the player's hands. The first-person renderer draws
# the real arms at them (BedrockModel.isPlayerArmAnchor); an item model that kept them would show
# two untextured blocks floating beside the weapon.
ARM_ANCHOR_BONES = ("righthand_pos", "lefthand_pos")

# variant id -> the already-ported weapon it shares a frame and balance lineage with.
VARIANTS = {
    "overture_amnesty": "overture",
    "overture_archangel": "overture",
    "overture_crash": "overture",
    "overture_reliable": "overture",
    "overture_rosco": "overture",
    "unity_cheetah": "unity",
    "unity_her_majesty": "unity",
    "yukimura_genjiroh": "yukimura",
    "yukimura_skippy": "yukimura",
    "saratoga_fenrir": "saratoga",
    "saratoga_problem_solver": "saratoga",
    "g58_dian_yinglong": "g58_dian",
    "ajax_moron_labe": "ajax",
    "ajax_pit_bull": "ajax",
    "copperhead_psalm": "copperhead",
    "m2038_bloody_maria": "m2038",
    "m2038_the_headsman": "m2038",
    "carnage_guts": "carnage",
    "grad_05": "grad",
    "grad_borzaya": "grad",
    "grad_overwatch": "grad",
    "grad_sparky": "grad",
}

# Ammo family and the hand-tuned profile of each base weapon, mirroring GunType.
BASE_PROFILES = {
    "overture": dict(ammo="HANDGUN", damage=10.0, spread=1.2, rng=56.0, wind=0,
                     f0=28.0, f1=48.0),
    "unity": dict(ammo="HANDGUN", damage=7.0, spread=1.6, rng=48.0, wind=0, f0=22.0, f1=40.0),
    "yukimura": dict(ammo="HANDGUN", damage=5.5, spread=2.2, rng=44.0, wind=0, f0=18.0, f1=34.0),
    "3516": dict(ammo="HANDGUN", damage=13.0, spread=0.9, rng=64.0, wind=0, f0=32.0, f1=52.0),
    "saratoga": dict(ammo="HANDGUN", damage=4.0, spread=3.0, rng=40.0, wind=0, f0=16.0, f1=30.0),
    "g58_dian": dict(ammo="HANDGUN", damage=4.0, spread=3.2, rng=40.0, wind=0, f0=16.0, f1=30.0),
    "ajax": dict(ammo="HEAVY", damage=7.0, spread=2.2, rng=68.0, wind=0, f0=34.0, f1=58.0),
    "copperhead": dict(ammo="HEAVY", damage=5.0, spread=2.6, rng=60.0, wind=0, f0=30.0, f1=52.0),
    "m2038": dict(ammo="SHOTGUN", damage=4.0, spread=7.0, rng=26.0, wind=0, f0=8.0, f1=18.0),
    "carnage": dict(ammo="SHOTGUN", damage=3.0, spread=9.0, rng=22.0, wind=0, f0=5.0, f1=14.0),
    "grad": dict(ammo="HEAVY", damage=22.0, spread=0.15, rng=128.0, wind=30, f0=100.0, f1=128.0),
}


def load_lenient(path: Path) -> dict:
    """TaCZ data files carry // comments, which are not valid JSON."""
    return json.loads(re.sub(r"^\s*//.*$", "", path.read_text(encoding="utf-8"), flags=re.M))


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def snap_angle(value: float) -> float:
    return min(ALLOWED_ANGLES, key=lambda allowed: abs(allowed - value))


def dominant_rotation(rotation: list[float]) -> tuple[str, float] | None:
    index = max(range(3), key=lambda axis: abs(rotation[axis]))
    if abs(rotation[index]) < 1e-4:
        return None
    return AXES[index], snap_angle(rotation[index])


def model_extent(elements: list[dict]) -> list[float]:
    low = [min(element["from"][axis] for element in elements) for axis in range(3)]
    high = [max(element["to"][axis] for element in elements) for axis in range(3)]
    return [high[axis] - low[axis] for axis in range(3)]


def family_scale(pack: Path, base: str) -> float:
    """
    How much the shipped item model shrank its source geometry.

    Bigger frames were normalised down so they sit sensibly in the hand and inventory - a Grad is
    kept at roughly 0.47, an Overture at 1.0. A variant has to inherit its family's factor exactly,
    or it renders next to its own sibling at a different size.
    """
    shipped = json.loads((ASSETS / f"models/item/{base}_3d.json").read_text(encoding="utf-8"))
    source = convert_geometry(pack / f"assets/cyber_armorer/geo_models/gun/{base}_geo.json",
                              base, {}, 1.0)
    shipped_extent = model_extent(shipped["elements"])
    source_extent = model_extent(source["elements"])
    return sum(shipped_extent[axis] / source_extent[axis] for axis in range(3)) / 3.0


def convert_geometry(geo_path: Path, texture_id: str, display: dict, scale: float) -> dict:
    geometry = load_lenient(geo_path)["minecraft:geometry"][0]
    description = geometry["description"]
    texture_width = description["texture_width"]
    texture_height = description["texture_height"]
    scale_u = 16.0 / texture_width
    scale_v = 16.0 / texture_height

    elements = []
    for bone in geometry["bones"]:
        if bone["name"] in ARM_ANCHOR_BONES:
            continue
        for cube in bone.get("cubes", []):
            origin = cube["origin"]
            size = cube["size"]
            faces = {}
            for face in FACES:
                mapping = cube.get("uv", {}).get(face)
                if mapping is None:
                    continue
                u, v = mapping["uv"]
                du, dv = mapping.get("uv_size", [1.0, 1.0])
                faces[face] = {
                    "uv": [round(u * scale_u, 4), round(v * scale_v, 4),
                           round((u + du) * scale_u, 4), round((v + dv) * scale_v, 4)],
                    "texture": "#layer0",
                }
            # A cube with no mapped faces contributes nothing but vertex count.
            if not faces:
                continue
            element = {
                "from": [round(value * scale, 5) for value in origin],
                "to": [round((origin[axis] + size[axis]) * scale, 5) for axis in range(3)],
                "faces": faces,
            }
            rotation = dominant_rotation(cube.get("rotation", [0.0, 0.0, 0.0]))
            if rotation is not None:
                axis, angle = rotation
                element["rotation"] = {
                    "origin": [round(value * scale, 5) for value in cube.get("pivot", origin)],
                    "axis": axis,
                    "angle": angle,
                }
            elements.append(element)

    return {
        "credit": "Converted from Cyber Armorer (TaCZ) Bedrock geometry",
        "texture_size": [texture_width, texture_height],
        "textures": {
            "layer0": f"cyberdeck:item/{texture_id}_uv",
            "particle": f"cyberdeck:item/{texture_id}_uv",
        },
        "elements": elements,
        "display": display,
    }


def import_weapon(pack: Path, weapon: str, base: str) -> int:
    pack_assets = pack / "assets/cyber_armorer"
    copies = [
        (pack_assets / f"geo_models/gun/{weapon}_geo.json",
         ASSETS / f"gun_geo/{weapon}.geo.json"),
        (pack_assets / f"animations/{weapon}.animation.json",
         ASSETS / f"gun_anim/{weapon}.animation.json"),
        (pack_assets / f"textures/gun/uv/{weapon}.png",
         ASSETS / f"textures/item/{weapon}_uv.png"),
        (pack_assets / f"textures/gun/slot/{weapon}.png",
         ASSETS / f"textures/item/{weapon}.png"),
    ]
    for source, destination in copies:
        if not source.is_file():
            raise FileNotFoundError(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)

    # Each variant keeps its family's tuned in-hand framing rather than inventing a new one.
    base_model = json.loads(
        (ASSETS / f"models/item/{base}_3d.json").read_text(encoding="utf-8"))
    model = convert_geometry(
        pack_assets / f"geo_models/gun/{weapon}_geo.json", weapon, base_model["display"],
        family_scale(pack, base))
    write_json(ASSETS / f"models/item/{weapon}_3d.json", model)
    write_json(
        ASSETS / f"items/{weapon}.json",
        {"model": {"type": "minecraft:model", "model": f"cyberdeck:item/{weapon}_3d"}})
    return len(model["elements"])


def enum_line(pack: Path, weapon: str, base: str) -> str:
    data = load_lenient(pack / f"data/cyber_armorer/data/guns/{weapon}_data.json")
    base_data = load_lenient(pack / f"data/cyber_armorer/data/guns/{base}_data.json")
    profile = BASE_PROFILES[base]

    bullet = data["bullet"]
    base_bullet = base_data["bullet"]
    pellets = int(bullet.get("bullet_amount", 1))
    base_pellets = int(base_bullet.get("bullet_amount", 1))
    per_pellet = bullet["damage"] / pellets
    base_per_pellet = base_bullet["damage"] / base_pellets
    damage = round(profile["damage"] * per_pellet / base_per_pellet, 1)

    def falloff(source: dict, index: int) -> float:
        adjust = source["extra_damage"]["damage_adjust"]
        return float(adjust[index]["distance"])

    # Falloff carries the variant's range character; maximum reach stays at the family's value.
    # Scaling reach too would leave short-range variants unable to hit anything at all, which is
    # a hitscan artifact rather than something the pack's projectile ballistics actually say.
    near = profile["f0"] * falloff(bullet, 0) / falloff(base_bullet, 0)
    far = profile["f1"] * falloff(bullet, 1) / falloff(base_bullet, 1)
    reach = max(profile["rng"], far * 1.2)
    # Full-auto weapons round below one tick; two ticks is the floor the base ports already use.
    cooldown = max(2, round(1200.0 / data["rpm"]))
    reload_ticks = round(data["reload"]["cooldown"]["empty"] * 20)

    # A shotgun frame firing a single projectile is a slug, and a slug is not a spread weapon.
    spread = profile["spread"] / 4.0 if pellets == 1 and base_pellets > 1 else profile["spread"]

    constant = weapon.upper()
    if constant[0].isdigit():
        constant = "WEAPON_" + constant
    family = "THREE_FIVE_ONE_SIX" if base == "3516" else base.upper()
    return (f'    {constant}("{weapon}", {family}, AmmoType.{profile["ammo"]}, {damage}f, '
            f'{pellets}, {round(spread, 2)}f, {round(reach, 1)}, {cooldown}, {profile["wind"]}, '
            f'{round(near, 1)}, {round(far, 1)}, {data["ammo_amount"]}, {reload_ticks}),')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path, help="extracted Cyber Armorer pack directory")
    parser.add_argument("--print-enum", action="store_true",
                        help="print GunType constants instead of importing assets")
    arguments = parser.parse_args()

    if arguments.print_enum:
        for weapon, base in VARIANTS.items():
            print(enum_line(arguments.pack, weapon, base))
        return

    total = 0
    for weapon, base in VARIANTS.items():
        elements = import_weapon(arguments.pack, weapon, base)
        total += elements
        print(f"{weapon:24} <- {base:12} {elements:5} elements")
    print(f"Imported {len(VARIANTS)} weapons, {total} model elements")


if __name__ == "__main__":
    main()
