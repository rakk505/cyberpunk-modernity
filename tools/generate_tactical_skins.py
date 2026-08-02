#!/usr/bin/env python3
"""Generate eight original 64x64 Minecraft tactical patrol skins.

The project-owner reference contributes only broad outfit cues. These deterministic skins are
drawn from scratch with Python's standard library and contain no copied pixels or branding.
"""

from __future__ import annotations

import binascii
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/cyberdeck/textures/entity/faction_enemy"
EQUIPMENT_OUTPUT = ROOT / "src/main/resources/assets/cyberdeck/textures/entity/equipment/humanoid"
SIZE = 64

# skin, hair, uniform, shadow, carrier, accent, lens
PALETTES = [
    (0xD2A078, 0x242126, 0x41483B, 0x252B25, 0x141918, 0xD2A91E, 0xD8EFF2),
    (0x8B573F, 0x121417, 0x303633, 0x1A1F1E, 0x0E1213, 0xD4A62B, 0xD92532),
    (0xE3B38E, 0x4A382B, 0x4A4B43, 0x292C29, 0x171B1D, 0xB49A30, 0xB9E4E8),
    (0x684434, 0x0B0C0F, 0x343B32, 0x1E241F, 0x101415, 0xE0B525, 0xE1A13A),
    (0xB87855, 0x2B1C18, 0x3F4547, 0x23282B, 0x101416, 0xC39D26, 0x77D6E0),
    (0xE5B67F, 0xB9A273, 0x555344, 0x313129, 0x181B19, 0xD5A922, 0xC7EFF2),
    (0xA46C4F, 0x351F1C, 0x3C4036, 0x22261F, 0x101315, 0xD3A324, 0xE43737),
    (0x5B382B, 0x17191D, 0x454B42, 0x272C27, 0x121617, 0xC7A02B, 0xA7D9DD),
]


def rgb(value: int, alpha: int = 255) -> tuple[int, int, int, int]:
    return value >> 16 & 255, value >> 8 & 255, value & 255, alpha


def shade(value: int, amount: int) -> int:
    r, g, b, _ = rgb(value)
    return max(0, r + amount) << 16 | max(0, g + amount) << 8 | max(0, b + amount)


class Skin:
    def __init__(self) -> None:
        self.pixels = bytearray(SIZE * SIZE * 4)

    def pixel(self, x: int, y: int, color: int, alpha: int = 255) -> None:
        offset = (y * SIZE + x) * 4
        self.pixels[offset:offset + 4] = bytes(rgb(color, alpha))

    def rect(self, x: int, y: int, width: int, height: int, color: int) -> None:
        for py in range(y, y + height):
            for px in range(x, x + width):
                self.pixel(px, py, color)

    def png(self) -> bytes:
        return encode_png(SIZE, SIZE, self.pixels)


def encode_png(width: int, height: int, pixels: bytearray) -> bytes:
    stride = width * 4
    rows = b"".join(
        b"\x00" + bytes(pixels[y * stride:(y + 1) * stride])
        for y in range(height)
    )

    def chunk(kind: bytes, data: bytes) -> bytes:
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", binascii.crc32(body))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows, 9))
        + chunk(b"IEND", b"")
    )


def cube_limb(skin: Skin, x: int, y: int, main: int, shadow: int, end: int) -> None:
    skin.rect(x + 4, y, 4, 4, shade(main, 7))
    skin.rect(x + 8, y, 4, 4, shadow)
    skin.rect(x, y + 4, 4, 12, shadow)
    skin.rect(x + 4, y + 4, 4, 12, main)
    skin.rect(x + 8, y + 4, 4, 12, shade(shadow, -5))
    skin.rect(x + 12, y + 4, 4, 12, shade(main, -8))
    for face_x in (x, x + 4, x + 8, x + 12):
        skin.rect(face_x, y + 13, 4, 3, end if face_x == x + 4 else shade(end, -15))


def base_head(skin: Skin, tone: int, hair: int, variant: int) -> None:
    skin.rect(8, 0, 8, 8, hair)
    skin.rect(16, 0, 8, 8, shade(tone, -18))
    skin.rect(0, 8, 8, 8, shade(tone, -14))
    skin.rect(8, 8, 8, 8, tone)
    skin.rect(16, 8, 8, 8, shade(tone, -12))
    skin.rect(24, 8, 8, 8, hair)
    skin.rect(8, 8, 8, 2, hair)
    if variant in (2, 5, 7):
        skin.pixel(8, 10, hair)
        skin.pixel(15, 10, hair)
    skin.pixel(10, 12, 0x1C2021)
    skin.pixel(13, 12, 0x1C2021)
    skin.pixel(12, 13, shade(tone, -24))
    skin.rect(11, 15, 3, 1, shade(tone, -36))


def headgear(skin: Skin, variant: int, carrier: int, accent: int, lens: int) -> None:
    helmet = shade(carrier, 18)
    # Overlay cube: top, bottom, right, front, left, back.
    if variant in (0, 1, 4, 6):
        skin.rect(40, 0, 8, 8, helmet)
        skin.rect(32, 8, 8, 5, shade(helmet, -8))
        skin.rect(40, 8, 8, 4, helmet)
        skin.rect(48, 8, 8, 5, shade(helmet, -5))
        skin.rect(56, 8, 8, 6, shade(helmet, -12))
        skin.rect(41, 9, 6, 1, accent)
    elif variant in (2, 5):
        # Knit cap / patrol cap.
        skin.rect(40, 0, 8, 3, helmet)
        skin.rect(32, 8, 8, 3, helmet)
        skin.rect(40, 8, 8, 3, helmet)
        skin.rect(48, 8, 8, 3, helmet)
        skin.rect(56, 8, 8, 3, shade(helmet, -8))
        if variant == 5:
            skin.rect(39, 10, 10, 1, shade(helmet, 8))

    if variant in (0, 2, 4, 5, 7):
        # Wraparound eye protection.
        skin.rect(40, 11, 8, 2, shade(carrier, 5))
        skin.rect(41, 11, 2, 1, lens)
        skin.rect(45, 11, 2, 1, lens)
        skin.rect(32, 11, 2, 2, shade(carrier, 5))
        skin.rect(54, 11, 2, 2, shade(carrier, 5))
    if variant in (1, 6):
        # Full mask with compact red/amber optics.
        skin.rect(40, 11, 8, 5, carrier)
        skin.rect(41, 12, 2, 1, lens)
        skin.rect(45, 12, 2, 1, lens)
        skin.pixel(43, 14, shade(carrier, 25))
        skin.pixel(44, 14, shade(carrier, 25))
    if variant == 3:
        # Balaclava, leaving only the eyes visible.
        skin.rect(40, 0, 8, 8, carrier)
        skin.rect(48, 0, 8, 8, shade(carrier, -8))
        skin.rect(32, 8, 8, 8, shade(carrier, -5))
        skin.rect(40, 8, 8, 8, carrier)
        skin.rect(48, 8, 8, 8, shade(carrier, -5))
        skin.rect(56, 8, 8, 8, shade(carrier, -10))
        skin.rect(41, 11, 6, 2, shade(carrier, 8))
        skin.pixel(42, 11, lens)
        skin.pixel(45, 11, lens)

    # Headset ear cup and mic for half the variants.
    if variant in (0, 4, 5, 7):
        skin.rect(32, 11, 2, 3, carrier)
        skin.pixel(34, 14, accent)
        skin.pixel(35, 15, carrier)


def make_skin(index: int) -> Skin:
    tone, hair, uniform, shadow, carrier, accent, lens = PALETTES[index]
    skin = Skin()
    base_head(skin, tone, hair, index)
    headgear(skin, index, carrier, accent, lens)

    # Torso cube with a dark undershirt, shoulder panels, harness, radio, and utility belt.
    skin.rect(20, 16, 8, 4, uniform)
    skin.rect(28, 16, 8, 4, shadow)
    skin.rect(16, 20, 4, 12, shadow)
    skin.rect(20, 20, 8, 12, uniform)
    skin.rect(28, 20, 4, 12, shadow)
    skin.rect(32, 20, 8, 12, shade(uniform, -10))
    skin.rect(22, 20, 4, 4, carrier)
    skin.pixel(20, 21, accent)
    skin.pixel(27, 21, accent)
    skin.rect(20, 24, 8, 1, carrier)
    skin.rect(21, 25, 2, 4, shade(carrier, 9))
    skin.rect(25, 25, 2, 4, shade(carrier, 9))
    skin.pixel(26, 26, accent)
    skin.rect(20, 30, 8, 2, carrier)
    skin.rect(23, 30, 2, 1, accent)

    # Sleeves/gloves and cargo trousers/boots.
    cube_limb(skin, 40, 16, uniform, shadow, carrier)
    cube_limb(skin, 32, 48, uniform, shadow, carrier)
    trousers = shade(uniform, -7)
    cube_limb(skin, 0, 16, trousers, shade(shadow, -5), 0x111516)
    cube_limb(skin, 16, 48, trousers, shade(shadow, -5), 0x111516)

    # Knee pads, cargo pockets, elbow patches, and restrained identification marks.
    skin.rect(4, 25, 4, 2, carrier)
    skin.rect(20, 57, 4, 2, carrier)
    skin.rect(0, 22, 2, 3, shade(carrier, 8))
    skin.rect(16, 54, 2, 3, shade(carrier, 8))
    skin.rect(44, 24, 4, 2, carrier)
    skin.rect(36, 56, 4, 2, carrier)
    skin.pixel(45, 21, accent)
    skin.pixel(37, 53, accent)

    # A few silhouettes use rolled sleeves without changing the wide-arm model.
    if index in (2, 5):
        skin.rect(44, 26, 4, 3, tone)
        skin.rect(40, 26, 4, 3, shade(tone, -14))
        skin.rect(48, 26, 4, 3, shade(tone, -10))
        skin.rect(52, 26, 4, 3, shade(tone, -18))
        skin.rect(36, 58, 4, 3, tone)
        skin.rect(32, 58, 4, 3, shade(tone, -14))
        skin.rect(40, 58, 4, 3, shade(tone, -10))
        skin.rect(44, 58, 4, 3, shade(tone, -18))
    return skin


def make_vest_texture(overlay: bool) -> bytes:
    width, height = 64, 32
    pixels = bytearray(width * height * 4)

    def rect(x: int, y: int, w: int, h: int, color: int) -> None:
        for py in range(y, y + h):
            for px in range(x, x + w):
                offset = (py * width + px) * 4
                pixels[offset:offset + 4] = bytes(rgb(color))

    if not overlay:
        # Body cube only; arm UVs remain transparent so tactical sleeves stay visible.
        rect(20, 16, 8, 4, 0x9A9A9A)
        rect(28, 16, 8, 4, 0x757575)
        rect(16, 20, 4, 12, 0x777777)
        rect(20, 20, 8, 12, 0x969696)
        rect(28, 20, 4, 12, 0x767676)
        rect(32, 20, 8, 12, 0x858585)
    else:
        # Undyed carrier details: shoulder straps, MOLLE rows, pockets, and clasp.
        rect(20, 20, 2, 10, 0x111516)
        rect(26, 20, 2, 10, 0x111516)
        rect(22, 24, 4, 1, 0x2D3432)
        rect(21, 26, 3, 4, 0x202625)
        rect(24, 26, 3, 4, 0x202625)
        rect(23, 26, 2, 1, 0xC9A226)
        rect(20, 30, 8, 2, 0x111516)
        rect(32, 21, 8, 1, 0x1D2322)
        rect(32, 29, 8, 2, 0x111516)
    return encode_png(width, height, pixels)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for index in range(len(PALETTES)):
        path = OUTPUT / f"tactical_{index}.png"
        path.write_bytes(make_skin(index).png())
        print(path.relative_to(ROOT))
    EQUIPMENT_OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, overlay in (("bulletproof_vest", False), ("bulletproof_vest_overlay", True)):
        path = EQUIPMENT_OUTPUT / f"{name}.png"
        path.write_bytes(make_vest_texture(overlay))
        print(path.relative_to(ROOT))


if __name__ == "__main__":
    main()
