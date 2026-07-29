#!/usr/bin/env python3
"""Generate eight original 64x64 Minecraft corporate-worker skins.

The script uses only Python's standard library and writes deterministic RGBA PNGs. Keeping the
source generator beside the textures makes their provenance and redistribution rights explicit.
"""

from __future__ import annotations

import binascii
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/cyberdeck/textures/entity/city_npc"
SIZE = 64


PALETTES = [
    # skin, hair, suit, suit shadow, shirt, tie/accent, shoe
    (0xD7A07A, 0x2B2020, 0x202634, 0x151923, 0xDDE8F0, 0x19D3E6, 0x10131A),
    (0x8C573D, 0x171218, 0x182B48, 0x101B2E, 0xE8E3D5, 0xED3FA9, 0x101626),
    (0xF0C5A0, 0xA86A37, 0xE1DDD2, 0xB9B3A8, 0x232A38, 0xE9B949, 0x22242B),
    (0x68402F, 0x080A0E, 0x551D30, 0x35101D, 0xD9D9E2, 0xF05C42, 0x170B10),
    (0xB97754, 0x3D241C, 0x3C414A, 0x252930, 0xBFD3CB, 0x6BFF9C, 0x16191D),
    (0xE0AA72, 0xE2D2B3, 0x0D555C, 0x08383D, 0xE9F2EF, 0x56E1FF, 0x09262A),
    (0xA96E50, 0x4D1016, 0x17191F, 0x0A0B0E, 0xC5CAD3, 0xE43C3C, 0x08090B),
    (0x5B3829, 0x25282D, 0xB9AA8D, 0x8D8068, 0x203A65, 0x66B6FF, 0x302A22),
]


def rgb(value: int, alpha: int = 255) -> tuple[int, int, int, int]:
    return (value >> 16 & 255, value >> 8 & 255, value & 255, alpha)


def darken(value: int, amount: int) -> int:
    r, g, b, _ = rgb(value)
    return max(0, r - amount) << 16 | max(0, g - amount) << 8 | max(0, b - amount)


class Skin:
    def __init__(self) -> None:
        self.pixels = bytearray(SIZE * SIZE * 4)

    def pixel(self, x: int, y: int, color: int, alpha: int = 255) -> None:
        index = (y * SIZE + x) * 4
        self.pixels[index:index + 4] = bytes(rgb(color, alpha))

    def rect(self, x: int, y: int, width: int, height: int, color: int) -> None:
        for py in range(y, y + height):
            for px in range(x, x + width):
                self.pixel(px, py, color)

    def png(self) -> bytes:
        rows = b"".join(
            b"\x00" + bytes(self.pixels[y * SIZE * 4:(y + 1) * SIZE * 4])
            for y in range(SIZE)
        )

        def chunk(kind: bytes, data: bytes) -> bytes:
            body = kind + data
            return struct.pack(">I", len(data)) + body + struct.pack(">I", binascii.crc32(body))

        return (
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9))
            + chunk(b"IEND", b"")
        )


def fill_head(skin: Skin, tone: int, hair: int, variant: int) -> None:
    shadow = darken(tone, 20)
    # Head top/bottom and four side faces.
    skin.rect(8, 0, 8, 8, hair)
    skin.rect(16, 0, 8, 8, shadow)
    skin.rect(0, 8, 8, 8, shadow)
    skin.rect(8, 8, 8, 8, tone)
    skin.rect(16, 8, 8, 8, shadow)
    skin.rect(24, 8, 8, 8, hair)
    # Hairline, brows, eyes, nose, and a restrained mouth.
    hair_depth = 2 + (variant % 2)
    skin.rect(8, 8, 8, hair_depth, hair)
    if variant in (2, 5):
        skin.pixel(8, 11, hair)
        skin.pixel(15, 11, hair)
    skin.pixel(10, 11, 0xE9F6FF)
    skin.pixel(13, 11, 0xE9F6FF)
    eye = (0x2A86A8, 0x3D2416, 0x426B35, 0x362635)[variant % 4]
    skin.pixel(10, 12, eye)
    skin.pixel(13, 12, eye)
    skin.pixel(12, 13, shadow)
    skin.rect(11, 15, 3, 1, darken(tone, 38))
    # Side hair/undercut variations.
    if variant % 3 == 0:
        skin.rect(0, 8, 2, 5, hair)
        skin.rect(22, 8, 2, 5, hair)


def fill_limb(skin: Skin, x: int, y: int, suit: int, shadow: int,
              end_color: int, end_rows: int) -> None:
    # Standard wide-arm/leg cube: top, bottom, right, front, left, back.
    skin.rect(x + 4, y, 4, 4, suit)
    skin.rect(x + 8, y, 4, 4, shadow)
    skin.rect(x, y + 4, 4, 12, shadow)
    skin.rect(x + 4, y + 4, 4, 12, suit)
    skin.rect(x + 8, y + 4, 4, 12, shadow)
    skin.rect(x + 12, y + 4, 4, 12, darken(suit, 10))
    skin.rect(x + 4, y + 16 - end_rows, 4, end_rows, end_color)
    skin.rect(x, y + 16 - end_rows, 4, end_rows, darken(end_color, 14))
    skin.rect(x + 8, y + 16 - end_rows, 4, end_rows, darken(end_color, 14))
    skin.rect(x + 12, y + 16 - end_rows, 4, end_rows, darken(end_color, 20))


def make_skin(index: int) -> Skin:
    tone, hair, suit, suit_shadow, shirt, accent, shoe = PALETTES[index]
    skin = Skin()
    fill_head(skin, tone, hair, index)

    # Torso cube.
    skin.rect(20, 16, 8, 4, suit)
    skin.rect(28, 16, 8, 4, suit_shadow)
    skin.rect(16, 20, 4, 12, suit_shadow)
    skin.rect(20, 20, 8, 12, suit)
    skin.rect(28, 20, 4, 12, suit_shadow)
    skin.rect(32, 20, 8, 12, darken(suit, 10))
    # Shirt opening, lapels, tie/lanyard and luminous corporate badge.
    skin.rect(22, 20, 4, 8, shirt)
    for dy in range(5):
        skin.pixel(21 + min(2, dy // 2), 20 + dy, suit_shadow)
        skin.pixel(26 - min(2, dy // 2), 20 + dy, suit_shadow)
    skin.pixel(24, 21, accent)
    skin.pixel(23, 22, accent)
    skin.pixel(24, 23, accent)
    skin.pixel(24, 24, accent)
    skin.pixel(23, 25, accent)
    skin.pixel(26, 22, 0xEAFBFF)
    skin.pixel(27, 22, accent)
    skin.pixel(26, 23, accent)
    skin.pixel(27, 23, darken(accent, 35))
    # Belt.
    skin.rect(20, 30, 8, 2, suit_shadow)
    skin.pixel(24, 30, accent)

    # Arms end in visible hands; legs end in dress shoes.
    fill_limb(skin, 40, 16, suit, suit_shadow, tone, 3)
    fill_limb(skin, 32, 48, suit, suit_shadow, tone, 3)
    trousers = darken(suit, 8)
    fill_limb(skin, 0, 16, trousers, darken(trousers, 12), shoe, 2)
    fill_limb(skin, 16, 48, trousers, darken(trousers, 12), shoe, 2)

    # Sleeve accent and subtle cybernetic wrist light differ per worker.
    skin.pixel(45, 26, accent)
    skin.pixel(37, 58, accent)
    if index % 2:
        skin.rect(44, 20, 4, 1, accent)
    return skin


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for index in range(len(PALETTES)):
        path = OUTPUT / f"corporate_{index}.png"
        path.write_bytes(make_skin(index).png())
        print(path.relative_to(ROOT))


if __name__ == "__main__":
    main()
