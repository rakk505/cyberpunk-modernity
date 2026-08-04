#!/usr/bin/env python3
"""Generate the three original parody-logo cards used by small street ads."""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path


SIZE = 128
FONT = {
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
}


class Canvas:
    def __init__(self, top: tuple[int, int, int], bottom: tuple[int, int, int]) -> None:
        self.pixels = bytearray(SIZE * SIZE * 4)
        for y in range(SIZE):
            blend = y / (SIZE - 1)
            color = tuple(round(a * (1.0 - blend) + b * blend) for a, b in zip(top, bottom))
            for x in range(SIZE):
                self.pixel(x, y, (*color, 255))

    def pixel(self, x: int, y: int, color: tuple[int, int, int, int]) -> None:
        if 0 <= x < SIZE and 0 <= y < SIZE:
            offset = (y * SIZE + x) * 4
            self.pixels[offset:offset + 4] = bytes(color)

    def rect(self, x0: int, y0: int, x1: int, y1: int,
             color: tuple[int, int, int, int]) -> None:
        for y in range(max(0, y0), min(SIZE, y1)):
            for x in range(max(0, x0), min(SIZE, x1)):
                self.pixel(x, y, color)

    def disc(self, cx: float, cy: float, radius: float,
             color: tuple[int, int, int, int]) -> None:
        radius_sq = radius * radius
        for y in range(math.floor(cy - radius), math.ceil(cy + radius) + 1):
            for x in range(math.floor(cx - radius), math.ceil(cx + radius) + 1):
                if (x - cx) ** 2 + (y - cy) ** 2 <= radius_sq:
                    self.pixel(x, y, color)

    def line(self, x0: float, y0: float, x1: float, y1: float, width: float,
             color: tuple[int, int, int, int]) -> None:
        steps = max(1, math.ceil(max(abs(x1 - x0), abs(y1 - y0)) * 2))
        for step in range(steps + 1):
            blend = step / steps
            self.disc(x0 + (x1 - x0) * blend, y0 + (y1 - y0) * blend, width, color)

    def text(self, value: str, y: int, scale: int,
             color: tuple[int, int, int, int]) -> None:
        width = len(value) * 6 * scale - scale
        start_x = (SIZE - width) // 2
        for index, character in enumerate(value):
            glyph = FONT[character]
            left = start_x + index * 6 * scale
            for row, bits in enumerate(glyph):
                for column, bit in enumerate(bits):
                    if bit == "1":
                        self.rect(
                            left + column * scale,
                            y + row * scale,
                            left + (column + 1) * scale,
                            y + (row + 1) * scale,
                            color,
                        )


def meta_card() -> Canvas:
    canvas = Canvas((4, 12, 35), (19, 4, 37))
    cyan = (34, 220, 255, 255)
    pink = (248, 54, 207, 255)
    points: list[tuple[float, float]] = []
    for step in range(121):
        angle = step * math.pi * 2 / 120
        denominator = 1 + math.sin(angle) ** 2
        points.append((64 + 39 * math.cos(angle) / denominator,
                       48 + 28 * math.sin(angle) * math.cos(angle) / denominator))
    for first, second in zip(points, points[1:]):
        canvas.line(*first, *second, 3.6, cyan if first[0] < 64 else pink)
    canvas.text("META", 94, 4, (235, 249, 255, 255))
    return canvas


def closedai_card() -> Canvas:
    canvas = Canvas((3, 27, 24), (2, 7, 9))
    mint = (56, 238, 177, 255)
    amber = (255, 190, 60, 255)
    for index in range(8):
        angle = index * math.pi / 4
        cx = 64 + math.cos(angle) * 27
        cy = 47 + math.sin(angle) * 27
        canvas.disc(cx, cy, 7, mint)
        next_angle = (index + 1) * math.pi / 4
        canvas.line(cx, cy, 64 + math.cos(next_angle) * 27,
                    47 + math.sin(next_angle) * 27, 2.5, mint)
    canvas.rect(47, 42, 81, 69, (5, 17, 18, 255))
    canvas.rect(52, 47, 76, 65, amber)
    canvas.rect(57, 32, 71, 50, amber)
    canvas.rect(61, 36, 67, 50, (5, 17, 18, 255))
    canvas.text("CLOSEDAI", 100, 2, (225, 255, 242, 255))
    return canvas


def misanthropic_card() -> Canvas:
    canvas = Canvas((42, 11, 6), (10, 5, 15))
    orange = (255, 114, 39, 255)
    cream = (255, 225, 190, 255)
    canvas.line(27, 77, 39, 25, 5, orange)
    canvas.line(39, 25, 64, 61, 5, orange)
    canvas.line(64, 61, 89, 25, 5, cream)
    canvas.line(89, 25, 101, 77, 5, cream)
    canvas.line(39, 25, 51, 77, 3, cream)
    canvas.line(89, 25, 77, 77, 3, orange)
    canvas.text("MISANTHROPIC", 105, 1, (255, 238, 218, 255))
    return canvas


def write_png(path: Path, canvas: Canvas) -> None:
    rows = bytearray()
    row_size = SIZE * 4
    for y in range(SIZE):
        rows.append(0)
        rows.extend(canvas.pixels[y * row_size:(y + 1) * row_size])

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    payload = b"\x89PNG\r\n\x1a\n"
    payload += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
    payload += chunk(b"IDAT", zlib.compress(bytes(rows), 9))
    payload += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def main() -> None:
    repository = Path(__file__).resolve().parents[1]
    output = repository / "src/main/resources/assets/cyberdeck/textures/ad_logos"
    for name, canvas in {
        "meta": meta_card(),
        "closedai": closedai_card(),
        "misanthropic": misanthropic_card(),
    }.items():
        write_png(output / f"{name}.png", canvas)
    print(f"generated 3 ad logos in {output}")


if __name__ == "__main__":
    main()
