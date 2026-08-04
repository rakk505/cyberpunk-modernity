#!/usr/bin/env python3
"""Generate eight original 64x64 Minecraft R Corp paramilitary skins.

The project-owner reference contributes only broad outfit direction: orange hard-shell protection,
gray technical fabric, dark load-bearing equipment, and protective headgear. Every pixel is drawn
deterministically from scratch; no source pixels, logos, text, or character designs are copied.
"""

from __future__ import annotations

import binascii
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/cyberdeck/textures/entity/faction_enemy"
SIZE = 64

# skin, hair, fabric, fabric shadow, orange shell, lens
PALETTES = [
    (0xD4A17D, 0x242126, 0x596167, 0x343A40, 0xE66B1B, 0xD9EDF0),
    (0x8A573F, 0x111419, 0x4B555D, 0x2B333A, 0xF0781D, 0xE84032),
    (0xE1B38E, 0x49382D, 0x626A6D, 0x3B4247, 0xD95F18, 0xF1B33A),
    (0x684435, 0x0B0D10, 0x454F56, 0x282F35, 0xEA721C, 0xD7E7E8),
    (0xB97856, 0x291C19, 0x59636B, 0x343B42, 0xF17B20, 0x77D4DB),
    (0xE4B67F, 0xB5A070, 0x697075, 0x3F464B, 0xD96419, 0xE9D6A5),
    (0xA46C50, 0x351F1D, 0x505A60, 0x2E353A, 0xEC6D17, 0xE43A32),
    (0x5B392C, 0x171A1E, 0x5E666A, 0x363D41, 0xF27B1C, 0x9DDDE0),
]

HEADGEAR = (
    "full_visor",
    "sealed_helmet",
    "open_helmet",
    "balaclava",
    "goggle_helmet",
    "patrol_cap",
    "full_mask",
    "hood",
)


def rgba(value: int, alpha: int = 255) -> tuple[int, int, int, int]:
    return value >> 16 & 255, value >> 8 & 255, value & 255, alpha


def shade(value: int, amount: int) -> int:
    red, green, blue, _ = rgba(value)
    return (
        max(0, min(255, red + amount)) << 16
        | max(0, min(255, green + amount)) << 8
        | max(0, min(255, blue + amount))
    )


class Skin:
    def __init__(self) -> None:
        self.pixels = bytearray(SIZE * SIZE * 4)

    def pixel(self, x: int, y: int, color: int, alpha: int = 255) -> None:
        offset = (y * SIZE + x) * 4
        self.pixels[offset:offset + 4] = bytes(rgba(color, alpha))

    def rect(self, x: int, y: int, width: int, height: int, color: int) -> None:
        for pixel_y in range(y, y + height):
            for pixel_x in range(x, x + width):
                self.pixel(pixel_x, pixel_y, color)

    def fabric(self, x: int, y: int, width: int, height: int,
               color: int, variant: int) -> None:
        """Draw subdued digital-weave texture without random or source-image input."""
        self.rect(x, y, width, height, color)
        for pixel_y in range(y, y + height):
            for pixel_x in range(x, x + width):
                value = pixel_x * 19 + pixel_y * 31 + variant * 43
                if value % 17 == 0:
                    self.pixel(pixel_x, pixel_y, shade(color, 10))
                elif value % 23 == 0:
                    self.pixel(pixel_x, pixel_y, shade(color, -9))

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


def limb(skin: Skin, x: int, y: int, main: int, shadow: int,
         end: int, variant: int) -> None:
    """Draw one standard four-pixel-wide player limb cube."""
    skin.fabric(x + 4, y, 4, 4, shade(main, 8), variant)
    skin.fabric(x + 8, y, 4, 4, shadow, variant)
    skin.fabric(x, y + 4, 4, 12, shadow, variant)
    skin.fabric(x + 4, y + 4, 4, 12, main, variant)
    skin.fabric(x + 8, y + 4, 4, 12, shade(shadow, -5), variant)
    skin.fabric(x + 12, y + 4, 4, 12, shade(main, -9), variant)
    for face_x in (x, x + 4, x + 8, x + 12):
        skin.rect(face_x, y + 13, 4, 3,
                  end if face_x == x + 4 else shade(end, -15))


def base_head(skin: Skin, tone: int, hair: int, variant: int) -> None:
    shadow = shade(tone, -17)
    skin.rect(8, 0, 8, 8, hair)
    skin.rect(16, 0, 8, 8, shadow)
    skin.rect(0, 8, 8, 8, shadow)
    skin.rect(8, 8, 8, 8, tone)
    skin.rect(16, 8, 8, 8, shade(tone, -12))
    skin.rect(24, 8, 8, 8, shade(hair, -5))

    hairline = 1 + variant % 3
    skin.rect(8, 8, 8, hairline, hair)
    if variant in (2, 5, 7):
        skin.pixel(8, 10, hair)
        skin.pixel(15, 10, hair)
    skin.pixel(10, 12, 0x1B2024)
    skin.pixel(13, 12, 0x1B2024)
    skin.pixel(12, 13, shadow)
    skin.rect(11, 15, 3, 1, shade(tone, -35))


def helmet_shell(skin: Skin, shell: int, dark: int, coverage: int = 5) -> None:
    """Draw an orange composite shell on the head's second layer."""
    skin.rect(40, 0, 8, 8, shell)
    skin.rect(48, 0, 8, 8, shade(shell, -24))
    skin.rect(32, 8, 8, coverage, shade(shell, -16))
    skin.rect(40, 8, 8, coverage, shell)
    skin.rect(48, 8, 8, coverage, shade(shell, -11))
    skin.rect(56, 8, 8, min(7, coverage + 1), shade(shell, -23))
    skin.rect(40, 8, 8, 1, shade(shell, 14))
    skin.rect(56, 13, 8, 1, dark)


def headgear(skin: Skin, variant: int, shell: int, lens: int) -> None:
    dark = 0x171C20
    black = 0x0C1013
    mode = HEADGEAR[variant]

    if mode == "full_visor":
        helmet_shell(skin, shell, dark, 5)
        skin.rect(40, 11, 8, 3, dark)
        skin.rect(41, 11, 6, 2, lens)
        skin.rect(32, 11, 2, 4, dark)
        skin.rect(54, 11, 2, 4, dark)
        skin.rect(41, 14, 6, 2, black)
    elif mode == "sealed_helmet":
        helmet_shell(skin, shell, dark, 6)
        skin.rect(40, 11, 8, 5, black)
        skin.rect(41, 12, 2, 1, lens)
        skin.rect(45, 12, 2, 1, lens)
        skin.rect(42, 14, 4, 1, shade(shell, -34))
    elif mode == "open_helmet":
        helmet_shell(skin, shell, dark, 3)
        skin.rect(32, 10, 2, 5, dark)
        skin.rect(54, 10, 2, 5, dark)
        skin.rect(40, 11, 8, 2, dark)
        skin.rect(41, 11, 2, 1, lens)
        skin.rect(45, 11, 2, 1, lens)
        skin.pixel(34, 14, shell)
        skin.pixel(35, 15, dark)
    elif mode == "balaclava":
        skin.rect(40, 0, 8, 8, dark)
        skin.rect(48, 0, 8, 8, black)
        skin.rect(32, 8, 8, 8, black)
        skin.rect(40, 8, 8, 8, dark)
        skin.rect(48, 8, 8, 8, black)
        skin.rect(56, 8, 8, 8, black)
        skin.rect(41, 11, 6, 2, shade(dark, 10))
        skin.pixel(42, 11, lens)
        skin.pixel(45, 11, lens)
        skin.rect(40, 8, 8, 1, shell)
    elif mode == "goggle_helmet":
        helmet_shell(skin, shell, dark, 4)
        skin.rect(40, 11, 8, 2, black)
        skin.rect(41, 11, 2, 1, lens)
        skin.rect(45, 11, 2, 1, lens)
        skin.rect(32, 11, 2, 3, black)
        skin.rect(54, 11, 2, 3, black)
        skin.rect(41, 14, 6, 2, dark)
        skin.pixel(43, 14, shell)
    elif mode == "patrol_cap":
        skin.rect(40, 0, 8, 3, shell)
        skin.rect(32, 8, 8, 3, shade(shell, -17))
        skin.rect(40, 8, 8, 3, shell)
        skin.rect(48, 8, 8, 3, shade(shell, -10))
        skin.rect(56, 8, 8, 3, shade(shell, -22))
        skin.rect(39, 10, 10, 1, shade(shell, 9))
        skin.rect(40, 12, 8, 2, dark)
        skin.rect(41, 12, 2, 1, lens)
        skin.rect(45, 12, 2, 1, lens)
        skin.rect(32, 11, 2, 4, dark)
        skin.pixel(34, 14, shell)
        skin.pixel(35, 15, dark)
    elif mode == "full_mask":
        helmet_shell(skin, shell, dark, 4)
        skin.rect(32, 11, 8, 5, black)
        skin.rect(40, 11, 8, 5, dark)
        skin.rect(48, 11, 8, 5, black)
        skin.rect(56, 11, 8, 5, black)
        skin.rect(41, 12, 2, 1, lens)
        skin.rect(45, 12, 2, 1, lens)
        skin.pixel(43, 14, shell)
        skin.pixel(44, 14, shell)
    else:  # hood with a low-profile respirator
        skin.rect(40, 0, 8, 8, 0x242B30)
        skin.rect(48, 0, 8, 8, 0x171C20)
        skin.rect(32, 8, 8, 7, 0x171C20)
        skin.rect(40, 8, 8, 4, 0x242B30)
        skin.rect(48, 8, 8, 7, 0x171C20)
        skin.rect(56, 8, 8, 7, 0x111519)
        skin.rect(40, 12, 8, 4, dark)
        skin.rect(41, 12, 2, 1, lens)
        skin.rect(45, 12, 2, 1, lens)
        skin.rect(43, 14, 2, 1, shell)

    # Restrained, original two-pixel unit mark. No source branding is reproduced.
    skin.pixel(46, 9, 0xF1EEE7)
    skin.pixel(46, 10, shade(shell, 20))


def torso(skin: Skin, fabric: int, shadow: int, shell: int, variant: int) -> None:
    skin.fabric(20, 16, 8, 4, shade(fabric, 8), variant)
    skin.fabric(28, 16, 8, 4, shadow, variant)
    skin.fabric(16, 20, 4, 12, shadow, variant)
    skin.fabric(20, 20, 8, 12, fabric, variant)
    skin.fabric(28, 20, 4, 12, shadow, variant)
    skin.fabric(32, 20, 8, 12, shade(fabric, -8), variant)

    # Undersuit collar and harness remain visible around the separate Bulletproof Vest layer.
    skin.rect(22, 20, 4, 3, 0x161C20)
    skin.rect(20, 23, 2, 7, 0x1A2024)
    skin.rect(26, 23, 2, 7, 0x1A2024)
    skin.rect(20, 30, 8, 2, 0x101519)
    skin.rect(23, 30, 2, 1, shell)
    skin.pixel(27, 22, 0xE8ECE9)

    # Skin-layer utility pouches; the equipment vest renders in front of this carrier.
    skin.rect(20, 36, 2, 9, 0x151B1F)
    skin.rect(26, 36, 2, 9, 0x151B1F)
    skin.rect(22, 40, 4, 1, 0x343C40)
    skin.rect(21, 42, 3, 4, 0x222A2E)
    skin.rect(24, 42, 3, 4, 0x222A2E)
    skin.pixel(25, 43, shell)
    skin.rect(20, 46, 8, 2, 0x101519)


def armor_overlays(skin: Skin, shell: int, variant: int) -> None:
    dark = 0x151A1E
    plate_shadow = shade(shell, -25)

    # Right arm second layer: orange shoulder cap, charcoal elbow pad, slim unit stripe.
    skin.rect(44, 32, 4, 4, shell)
    skin.rect(48, 32, 4, 4, plate_shadow)
    skin.rect(40, 36, 4, 5, plate_shadow)
    skin.rect(44, 36, 4, 5, shell)
    skin.rect(48, 36, 4, 5, shade(shell, -13))
    skin.rect(52, 36, 4, 5, plate_shadow)
    skin.rect(44, 42, 4, 3, dark)
    skin.pixel(47, 38, 0xF1EEE7)

    # Left arm second layer uses the mirrored 1.8 player UV island.
    skin.rect(52, 48, 4, 4, shell)
    skin.rect(56, 48, 4, 4, plate_shadow)
    skin.rect(48, 52, 4, 5, plate_shadow)
    skin.rect(52, 52, 4, 5, shell)
    skin.rect(56, 52, 4, 5, shade(shell, -13))
    skin.rect(60, 52, 4, 5, plate_shadow)
    skin.rect(52, 58, 4, 3, dark)
    skin.pixel(52, 54, 0xF1EEE7)

    # Independent thigh shells and knee pads keep the bright identity visible below the vest.
    skin.rect(4, 32, 4, 4, plate_shadow)
    skin.rect(4, 36, 4, 6, shell)
    skin.rect(0, 36, 4, 6, plate_shadow)
    skin.rect(8, 36, 4, 6, shade(shell, -12))
    skin.rect(12, 36, 4, 6, plate_shadow)
    skin.rect(4, 42, 4, 3, dark)

    skin.rect(4, 48, 4, 4, plate_shadow)
    skin.rect(4, 52, 4, 6, shell)
    skin.rect(0, 52, 4, 6, plate_shadow)
    skin.rect(8, 52, 4, 6, shade(shell, -12))
    skin.rect(12, 52, 4, 6, plate_shadow)
    skin.rect(4, 58, 4, 3, dark)

    # Pouch placement and small markings differ across the squad.
    if variant % 2 == 0:
        skin.rect(8, 43, 4, 4, 0x20272B)
        skin.pixel(9, 43, shell)
    else:
        skin.rect(8, 59, 4, 4, 0x20272B)
        skin.pixel(10, 59, shell)
    if variant == 4:
        skin.rect(8, 43, 4, 4, 0x8F2528)
        skin.pixel(9, 44, 0xE5E1D7)
        skin.pixel(10, 44, 0xE5E1D7)


def make_skin(index: int) -> Skin:
    tone, hair, fabric, shadow, shell, lens = PALETTES[index]
    skin = Skin()
    base_head(skin, tone, hair, index)
    headgear(skin, index, shell, lens)
    torso(skin, fabric, shadow, shell, index)

    glove = 0x11171B
    boot = 0x101519
    limb(skin, 40, 16, fabric, shadow, glove, index)
    limb(skin, 32, 48, fabric, shadow, glove, index)
    trousers = shade(fabric, -7)
    limb(skin, 0, 16, trousers, shade(shadow, -5), boot, index)
    limb(skin, 16, 48, trousers, shade(shadow, -5), boot, index)
    armor_overlays(skin, shell, index)

    # Open-head variants retain visibly different operators; sealed variants keep the shared kit.
    if index in (2, 5):
        skin.rect(44, 27, 4, 2, tone)
        skin.rect(40, 27, 4, 2, shade(tone, -14))
        skin.rect(48, 27, 4, 2, shade(tone, -10))
        skin.rect(52, 27, 4, 2, shade(tone, -18))
        skin.rect(36, 59, 4, 2, tone)
        skin.rect(32, 59, 4, 2, shade(tone, -14))
        skin.rect(40, 59, 4, 2, shade(tone, -10))
        skin.rect(44, 59, 4, 2, shade(tone, -18))
    return skin


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for index in range(len(PALETTES)):
        path = OUTPUT / f"r_corp_{index}.png"
        path.write_bytes(make_skin(index).png())
        print(path.relative_to(ROOT))


if __name__ == "__main__":
    main()
