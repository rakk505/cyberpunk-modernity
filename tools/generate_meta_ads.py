#!/usr/bin/env python3
"""Generate four original, silent Meta parody campaigns as sprite sheets.

The generator intentionally uses only Python's standard library.  It writes the
same 160x90, 8 FPS, 4x4 sheet layout consumed by ``AdClip`` and never downloads
or embeds third-party video, audio, logo pixels, fonts, or artwork.
"""

from __future__ import annotations

import argparse
import math
import shutil
import struct
import tempfile
import zlib
from dataclasses import dataclass
from pathlib import Path


WIDTH = 160
HEIGHT = 90
FPS = 8
SHEET_COLUMNS = 4
SHEET_ROWS = 4
FRAMES_PER_SHEET = SHEET_COLUMNS * SHEET_ROWS
SHEET_WIDTH = WIDTH * SHEET_COLUMNS
SHEET_HEIGHT = HEIGHT * SHEET_ROWS


@dataclass(frozen=True)
class Campaign:
    clip_id: str
    duration_seconds: int
    title: str
    slogan: str
    style: str
    palette: tuple[tuple[int, int, int], ...]


CAMPAIGNS = (
    Campaign(
        "meta_logo",
        30,
        "META",
        "CONNECT FORWARD",
        "logo",
        ((3, 9, 29), (22, 225, 255), (247, 66, 220), (230, 248, 255)),
    ),
    Campaign(
        "meta_glasses",
        30,
        "META VISION",
        "SEE BEYOND",
        "glasses",
        ((4, 20, 29), (46, 239, 198), (255, 174, 54), (226, 255, 248)),
    ),
    Campaign(
        "meta_ai",
        45,
        "META AI",
        "IDEAS IN MOTION",
        "ai",
        ((13, 5, 35), (151, 91, 255), (40, 235, 255), (245, 236, 255)),
    ),
    Campaign(
        "meta_future",
        45,
        "META FUTURE",
        "BUILD WHAT FOLLOWS",
        "future",
        ((2, 12, 31), (44, 139, 255), (255, 79, 132), (255, 230, 151)),
    ),
)
CAMPAIGN_BY_ID = {campaign.clip_id: campaign for campaign in CAMPAIGNS}


FONT = {
    " ": ("00000",) * 7,
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "D": ("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "F": ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    "G": ("01111", "10000", "10000", "10111", "10001", "10001", "01110"),
    "H": ("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    "J": ("00111", "00010", "00010", "00010", "10010", "10010", "01100"),
    "K": ("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "P": ("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
    "Q": ("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "U": ("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "W": ("10001", "10001", "10001", "10101", "10101", "11011", "10001"),
    "X": ("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
}


class Canvas:
    def __init__(self, width: int, height: int, color: tuple[int, int, int]) -> None:
        self.width = width
        self.height = height
        self.pixels = bytearray(bytes(color) * width * height)

    def pixel(self, x: int, y: int, color: tuple[int, int, int]) -> None:
        if 0 <= x < self.width and 0 <= y < self.height:
            offset = (y * self.width + x) * 3
            self.pixels[offset:offset + 3] = bytes(color)

    def rect(
        self,
        x0: int,
        y0: int,
        x1: int,
        y1: int,
        color: tuple[int, int, int],
    ) -> None:
        x0, x1 = max(0, x0), min(self.width, x1)
        y0, y1 = max(0, y0), min(self.height, y1)
        if x0 >= x1 or y0 >= y1:
            return
        row = bytes(color) * (x1 - x0)
        for y in range(y0, y1):
            offset = (y * self.width + x0) * 3
            self.pixels[offset:offset + len(row)] = row

    def disc(
        self,
        cx: float,
        cy: float,
        radius: float,
        color: tuple[int, int, int],
    ) -> None:
        radius_squared = radius * radius
        for y in range(math.floor(cy - radius), math.ceil(cy + radius) + 1):
            delta = radius_squared - (y - cy) ** 2
            if delta < 0:
                continue
            extent = math.sqrt(delta)
            self.rect(math.ceil(cx - extent), y, math.floor(cx + extent) + 1, y + 1, color)

    def line(
        self,
        x0: float,
        y0: float,
        x1: float,
        y1: float,
        width: float,
        color: tuple[int, int, int],
    ) -> None:
        steps = max(1, math.ceil(max(abs(x1 - x0), abs(y1 - y0)) * 1.5))
        for step in range(steps + 1):
            blend = step / steps
            self.disc(
                x0 + (x1 - x0) * blend,
                y0 + (y1 - y0) * blend,
                width,
                color,
            )

    def outline_rect(
        self,
        x0: int,
        y0: int,
        x1: int,
        y1: int,
        width: int,
        color: tuple[int, int, int],
    ) -> None:
        self.rect(x0, y0, x1, y0 + width, color)
        self.rect(x0, y1 - width, x1, y1, color)
        self.rect(x0, y0, x0 + width, y1, color)
        self.rect(x1 - width, y0, x1, y1, color)

    def text(
        self,
        value: str,
        center_x: int,
        y: int,
        scale: int,
        color: tuple[int, int, int],
    ) -> None:
        advance = 6 * scale
        x0 = center_x - (len(value) * advance - scale) // 2
        for character_index, character in enumerate(value):
            glyph = FONT[character]
            for row_index, row in enumerate(glyph):
                for column_index, value_at_pixel in enumerate(row):
                    if value_at_pixel == "1":
                        self.rect(
                            x0 + character_index * advance + column_index * scale,
                            y + row_index * scale,
                            x0 + character_index * advance + (column_index + 1) * scale,
                            y + (row_index + 1) * scale,
                            color,
                        )


def blend(
    first: tuple[int, int, int],
    second: tuple[int, int, int],
    amount: float,
) -> tuple[int, int, int]:
    return tuple(round(a * (1.0 - amount) + b * amount) for a, b in zip(first, second))


def background(campaign: Campaign, frame: int) -> Canvas:
    base, primary, _, _ = campaign.palette
    canvas = Canvas(WIDTH, HEIGHT, base)
    for y in range(HEIGHT):
        color = blend(base, primary, y / (HEIGHT - 1) * 0.16)
        canvas.rect(0, y, WIDTH, y + 1, color)
    scanline = frame % 10
    scan_color = blend(base, primary, 0.25)
    for y in range(scanline, HEIGHT, 10):
        canvas.rect(0, y, WIDTH, y + 1, scan_color)
    for index in range(13):
        x = (index * 47 + frame * (index % 3 + 1)) % WIDTH
        y = (index * 29 + frame // 2) % HEIGHT
        canvas.pixel(x, y, blend(primary, (255, 255, 255), 0.25))
    return canvas


def logo_frame(campaign: Campaign, frame: int) -> Canvas:
    canvas = background(campaign, frame)
    _, primary, accent, light = campaign.palette
    phase = frame * math.pi / 48.0
    points: list[tuple[float, float]] = []
    for step in range(97):
        angle = step * math.pi * 2.0 / 96.0
        denominator = 1.0 + math.sin(angle) ** 2
        points.append((
            80 + 42 * math.cos(angle) / denominator,
            35 + 23 * math.sin(angle) * math.cos(angle) / denominator,
        ))
    highlight = frame % 96
    for index, (first, second) in enumerate(zip(points, points[1:])):
        color = primary if index < 48 else accent
        width = 3.4 if (index - highlight) % 96 < 9 else 1.8
        canvas.line(*first, *second, width, color)
    glow_x = 80 + math.cos(phase) * 31
    glow_y = 35 + math.sin(phase * 2.0) * 11
    canvas.disc(glow_x, glow_y, 3.0, light)
    canvas.text(campaign.title, 80, 61, 2, light)
    canvas.text(campaign.slogan, 80, 81, 1, primary)
    return canvas


def glasses_frame(campaign: Campaign, frame: int) -> Canvas:
    canvas = background(campaign, frame)
    _, primary, accent, light = campaign.palette
    canvas.text(campaign.title, 80, 5, 2, light)
    lens_y = 31
    canvas.outline_rect(24, lens_y, 72, 64, 3, primary)
    canvas.outline_rect(88, lens_y, 136, 64, 3, primary)
    canvas.line(72, 37, 80, 34, 2.2, accent)
    canvas.line(80, 34, 88, 37, 2.2, accent)
    canvas.line(24, 35, 9, 29, 2.2, primary)
    canvas.line(136, 35, 151, 29, 2.2, primary)
    scan_x = 28 + (frame * 2) % 40
    canvas.rect(scan_x, lens_y + 4, scan_x + 2, 60, accent)
    inverse_scan_x = 132 - (frame * 2) % 40
    canvas.rect(inverse_scan_x, lens_y + 4, inverse_scan_x + 2, 60, accent)
    pulse = 2 + (frame // 3) % 3
    canvas.disc(48, 47, pulse, light)
    canvas.disc(112, 47, pulse, light)
    for index in range(4):
        marker_x = 94 + index * 8
        marker_y = 39 + ((index * 7 + frame) % 13)
        canvas.rect(marker_x, marker_y, marker_x + 3, marker_y + 2, accent)
    canvas.text(campaign.slogan, 80, 79, 1, primary)
    return canvas


def ai_frame(campaign: Campaign, frame: int) -> Canvas:
    canvas = background(campaign, frame)
    _, primary, accent, light = campaign.palette
    canvas.text(campaign.title, 80, 5, 2, light)
    nodes = (
        (80, 44), (53, 31), (108, 30), (45, 56), (115, 57),
        (66, 67), (95, 68), (25, 42), (137, 43),
    )
    links = ((0, 1), (0, 2), (0, 3), (0, 4), (0, 5), (0, 6),
             (1, 7), (3, 7), (2, 8), (4, 8), (1, 3), (2, 4), (5, 6))
    for link_index, (first_index, second_index) in enumerate(links):
        first = nodes[first_index]
        second = nodes[second_index]
        color = accent if (frame // 3 + link_index) % 4 == 0 else primary
        canvas.line(*first, *second, 0.8, color)
    for node_index, (x, y) in enumerate(nodes):
        radius = 2.0 + ((frame + node_index * 5) % 16) / 16.0 * 2.0
        canvas.disc(x, y, radius, light if node_index == 0 else accent)
        canvas.disc(x, y, max(1.0, radius - 1.5), primary)
    orbit_angle = frame * math.pi / 24.0
    canvas.disc(80 + math.cos(orbit_angle) * 19,
                44 + math.sin(orbit_angle) * 19, 2.2, light)
    canvas.text(campaign.slogan, 80, 80, 1, accent)
    return canvas


def future_frame(campaign: Campaign, frame: int) -> Canvas:
    canvas = background(campaign, frame)
    _, primary, accent, light = campaign.palette
    canvas.text(campaign.title, 80, 5, 2, light)
    horizon = 55
    canvas.rect(0, horizon, WIDTH, horizon + 2, accent)
    for x in range(-80, 241, 16):
        vanishing_x = 80
        canvas.line(vanishing_x, horizon, x + (frame % 16), HEIGHT, 0.6, primary)
    for row in range(6):
        y = horizon + row * row
        canvas.rect(0, y, WIDTH, y + 1, primary)
    for index, x in enumerate(range(5, WIDTH, 13)):
        building_height = 10 + (index * 11) % 25
        color = blend(campaign.palette[0], primary, 0.24 + (index % 3) * 0.08)
        canvas.rect(x, horizon - building_height, x + 9, horizon, color)
        for window_y in range(horizon - building_height + 3, horizon - 2, 5):
            window_color = light if (index + window_y + frame // 8) % 4 == 0 else accent
            canvas.rect(x + 2, window_y, x + 4, window_y + 2, window_color)
    transport_x = (frame * 3) % (WIDTH + 34) - 20
    canvas.rect(transport_x, 68, transport_x + 18, 72, light)
    canvas.rect(transport_x - 22, 70, transport_x, 71, accent)
    canvas.text(campaign.slogan, 80, 80, 1, light)
    return canvas


FRAME_BUILDERS = {
    "logo": logo_frame,
    "glasses": glasses_frame,
    "ai": ai_frame,
    "future": future_frame,
}


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))


def write_png(path: Path, width: int, height: int, pixels: bytearray) -> None:
    rows = bytearray()
    row_size = width * 3
    for y in range(height):
        rows.append(0)
        rows.extend(pixels[y * row_size:(y + 1) * row_size])
    payload = b"\x89PNG\r\n\x1a\n"
    payload += png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    payload += png_chunk(b"IDAT", zlib.compress(bytes(rows), 9))
    payload += png_chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def blit_frame(sheet: Canvas, frame: Canvas, slot: int) -> None:
    column = slot % SHEET_COLUMNS
    row = slot // SHEET_COLUMNS
    source_row_size = WIDTH * 3
    destination_x = column * WIDTH
    destination_y = row * HEIGHT
    for y in range(HEIGHT):
        source_offset = y * source_row_size
        destination_offset = ((destination_y + y) * SHEET_WIDTH + destination_x) * 3
        sheet.pixels[destination_offset:destination_offset + source_row_size] = (
            frame.pixels[source_offset:source_offset + source_row_size]
        )


def generate_campaign(clip_id: str, duration_seconds: int, output: Path) -> int:
    """Generate one catalog campaign into an empty output directory."""
    campaign = CAMPAIGN_BY_ID.get(clip_id)
    if campaign is None:
        raise ValueError(f"Unknown procedural Meta campaign: {clip_id}")
    if duration_seconds != campaign.duration_seconds:
        raise ValueError(
            f"{clip_id}: expected {campaign.duration_seconds}s, got {duration_seconds}s"
        )
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"Procedural output directory must be empty: {output}")
    output.mkdir(parents=True, exist_ok=True)

    frame_count = duration_seconds * FPS
    sheet_count = math.ceil(frame_count / FRAMES_PER_SHEET)
    frame_builder = FRAME_BUILDERS[campaign.style]
    for sheet_index in range(sheet_count):
        sheet = Canvas(SHEET_WIDTH, SHEET_HEIGHT, (0, 0, 0))
        first_frame = sheet_index * FRAMES_PER_SHEET
        for slot in range(FRAMES_PER_SHEET):
            frame_index = first_frame + slot
            if frame_index >= frame_count:
                break
            blit_frame(sheet, frame_builder(campaign, frame_index), slot)
        write_png(output / f"sheet_{sheet_index + 1:03d}.png",
                  SHEET_WIDTH, SHEET_HEIGHT, sheet.pixels)
    return sheet_count


def generate_all(output_root: Path) -> None:
    output_root.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="meta-ads-", dir=output_root.parent) as temporary:
        staging = Path(temporary)
        for campaign in CAMPAIGNS:
            generate_campaign(
                campaign.clip_id,
                campaign.duration_seconds,
                staging / campaign.clip_id,
            )
        output_root.mkdir(parents=True, exist_ok=True)
        for campaign in CAMPAIGNS:
            destination = output_root / campaign.clip_id
            if destination.exists():
                shutil.rmtree(destination)
            shutil.move(str(staging / campaign.clip_id), destination)


def main() -> None:
    repository = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=repository / "src/main/resources/assets/cyberdeck/textures/ads",
    )
    args = parser.parse_args()
    generate_all(args.output)
    print(
        f"generated {len(CAMPAIGNS)} silent procedural campaigns "
        f"({sum(c.duration_seconds * FPS for c in CAMPAIGNS)} frames) in {args.output}"
    )


if __name__ == "__main__":
    main()
