#!/usr/bin/env python3
"""Generate deterministic 64x64 Minecraft skins for the mainline cast."""

from __future__ import annotations

import binascii
import pathlib
import random
import struct
import zlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/cyberdeck/textures/entity"


CAST = {
    "city_npc/corporate_9.png": {
        "seed": 9, "skin": "b98261", "hair": "66605b", "shirt": "34372f",
        "pants": "25262a", "accent": "b13f4a", "metal": "d4b34a", "kind": "jerry",
    },
    "city_npc/corporate_10.png": {
        "seed": 10, "skin": "9b6249", "hair": "171b22", "shirt": "16434b",
        "pants": "202332", "accent": "ef3d92", "metal": "34d6c7", "kind": "kaito",
    },
    "city_npc/corporate_11.png": {
        "seed": 11, "skin": "d8b7a7", "hair": "d7d5cd", "shirt": "e7e7e2",
        "pants": "292932", "accent": "9f1f36", "metal": "a9e4eb", "kind": "selene",
    },
    "city_npc/corporate_12.png": {
        "seed": 12, "skin": "86563f", "hair": "252126", "shirt": "d5d1c4",
        "pants": "30313a", "accent": "b44b2d", "metal": "58c0bf", "kind": "nadira",
    },
    "city_npc/corporate_13.png": {
        "seed": 13, "skin": "ad7659", "hair": "352b2b", "shirt": "4a5554",
        "pants": "282e31", "accent": "6c8d87", "metal": "8d9593", "kind": "jax",
    },
    "city_npc/corporate_14.png": {
        "seed": 14, "skin": "654436", "hair": "252525", "shirt": "263b3d",
        "pants": "20282a", "accent": "3b8584", "metal": "a3aaa3", "kind": "warden",
    },
    "fog_mother.png": {
        "seed": 15, "skin": "829a9d", "hair": "25232e", "shirt": "282538",
        "pants": "24252d", "accent": "8c4b8e", "metal": "5dc5c4", "kind": "fog_mother",
    },
}


def rgba(value: str, alpha: int = 255) -> tuple[int, int, int, int]:
    return tuple(bytes.fromhex(value)) + (alpha,)


def shade(color: tuple[int, int, int, int], delta: int) -> tuple[int, int, int, int]:
    return tuple(max(0, min(255, channel + delta)) for channel in color[:3]) + (color[3],)


def chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(
        ">I", binascii.crc32(kind + payload) & 0xFFFFFFFF
    )


def save_png(path: pathlib.Path, pixels: list[list[tuple[int, int, int, int]]]) -> None:
    raw = b"".join(b"\x00" + b"".join(bytes(pixel) for pixel in row) for row in pixels)
    encoded = b"\x89PNG\r\n\x1a\n"
    encoded += chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6, 0, 0, 0))
    encoded += chunk(b"IDAT", zlib.compress(raw, 9))
    encoded += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def draw(spec: dict[str, object]) -> list[list[tuple[int, int, int, int]]]:
    transparent = (0, 0, 0, 0)
    pixels = [[transparent for _ in range(64)] for _ in range(64)]
    skin = rgba(str(spec["skin"]))
    hair = rgba(str(spec["hair"]))
    shirt = rgba(str(spec["shirt"]))
    pants = rgba(str(spec["pants"]))
    accent = rgba(str(spec["accent"]))
    metal = rgba(str(spec["metal"]))
    rng = random.Random(int(spec["seed"]))

    def fill(x: int, y: int, width: int, height: int, color: tuple[int, int, int, int]) -> None:
        for py in range(y, y + height):
            for px in range(x, x + width):
                pixels[py][px] = color

    def pixel(x: int, y: int, color: tuple[int, int, int, int]) -> None:
        pixels[y][x] = color

    # Base player atlas: head, torso, right arm/leg, left arm/leg.
    for box in ((8, 0, 16, 8), (0, 8, 32, 8)):
        fill(*box, skin)
    fill(20, 16, 16, 4, shirt)
    fill(16, 20, 24, 12, shirt)
    fill(44, 16, 8, 4, shirt)
    fill(40, 20, 16, 12, shirt)
    fill(4, 16, 8, 4, pants)
    fill(0, 20, 16, 12, pants)
    fill(20, 48, 8, 4, pants)
    fill(16, 52, 16, 12, pants)
    fill(36, 48, 8, 4, shirt)
    fill(32, 52, 16, 12, shirt)

    # Face and universal shading.
    eye = rgba("15171b")
    eye_glow = metal
    pixel(10, 11, eye)
    pixel(13, 11, eye_glow if spec["kind"] in {"selene", "fog_mother"} else eye)
    pixel(11, 14, shade(skin, -28))
    pixel(12, 14, shade(skin, -28))
    fill(8, 8, 8, 2, hair)
    fill(24, 8, 8, 3, hair)
    for _ in range(20):
        x = rng.randrange(16, 56)
        y = rng.randrange(20, 64)
        if pixels[y][x][3] and pixels[y][x] in (shirt, pants):
            pixels[y][x] = shade(pixels[y][x], rng.choice((-12, -7, 8)))

    kind = str(spec["kind"])
    if kind == "jerry":
        fill(40, 8, 8, 3, hair)
        fill(20, 21, 2, 10, accent)
        fill(26, 22, 2, 7, rgba("25252c"))
        fill(27, 23, 1, 1, metal)
        fill(44, 22, 1, 8, accent)
        fill(36, 54, 1, 8, accent)
    elif kind == "kaito":
        fill(40, 8, 8, 2, hair)
        fill(40, 13, 8, 3, rgba("252a35"))
        pixel(43, 14, metal)
        pixel(44, 14, rgba("20242b"))
        fill(23, 20, 2, 12, accent)
        fill(28, 20, 1, 12, metal)
        fill(44, 23, 4, 2, accent)
    elif kind == "selene":
        fill(40, 8, 8, 4, hair)
        fill(23, 20, 2, 12, rgba("18191e"))
        fill(26, 20, 1, 12, accent)
        fill(35, 21, 2, 10, metal)
        for y in range(22, 31, 2):
            pixel(36, y, rgba("f4fbff"))
    elif kind == "nadira":
        fill(40, 8, 8, 3, hair)
        fill(20, 20, 1, 12, accent)
        fill(27, 20, 1, 12, accent)
        fill(24, 22, 3, 4, rgba("263f43"))
        fill(25, 23, 1, 2, metal)
        fill(44, 29, 4, 3, shade(accent, -22))
        fill(36, 61, 4, 3, shade(accent, -22))
    elif kind == "jax":
        fill(40, 8, 8, 2, hair)
        pixel(41, 12, accent)
        pixel(42, 13, accent)
        fill(21, 20, 1, 12, metal)
        fill(27, 20, 1, 12, accent)
        fill(44, 25, 4, 1, rgba("747c79"))
        fill(36, 57, 4, 1, rgba("747c79"))
    elif kind == "warden":
        fill(40, 8, 8, 2, rgba("292b2b"))
        fill(40, 10, 8, 6, rgba("343a3a"))
        fill(41, 11, 2, 2, metal)
        fill(45, 11, 2, 2, metal)
        fill(43, 13, 2, 2, rgba("1b1f20"))
        fill(20, 20, 8, 2, accent)
        fill(23, 22, 2, 9, metal)
    elif kind == "fog_mother":
        fill(40, 8, 8, 4, hair)
        fill(40, 13, 8, 3, rgba("292633"))
        for x in (41, 43, 45, 47):
            pixel(x, 14, metal)
        fill(20, 20, 8, 3, accent)
        fill(23, 23, 2, 9, metal)
        for x, y in ((16, 32), (18, 34), (30, 33), (50, 34), (52, 36), (54, 33)):
            fill(x, y, 1, 8, accent)

    return pixels


def main() -> None:
    for relative_path, spec in CAST.items():
        save_png(OUTPUT / relative_path, draw(spec))
        print(relative_path)


if __name__ == "__main__":
    main()
