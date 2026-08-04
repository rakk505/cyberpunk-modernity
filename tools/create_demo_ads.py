#!/usr/bin/env python3
"""Create three original MP4 advertisements used by the large display demo catalog."""

from __future__ import annotations

import argparse
import math
import shutil
import struct
import subprocess
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path


WIDTH = 160
HEIGHT = 90
FPS = 8
SAMPLE_RATE = 48_000


@dataclass(frozen=True)
class DemoAd:
    clip_id: str
    duration: int
    title: str
    slogan: str
    palette: tuple[tuple[int, int, int], ...]
    notes: tuple[float, ...]


ADS = (
    DemoAd(
        "neon_skyline",
        30,
        "NEON SKYLINE",
        "LIVE ABOVE THE GRID",
        ((4, 18, 28), (0, 229, 208), (255, 54, 122), (246, 233, 92)),
        (220.0, 277.18, 329.63, 440.0),
    ),
    DemoAd(
        "chrome_cola",
        36,
        "CHROME COLA",
        "TASTE THE CURRENT",
        ((26, 6, 19), (255, 45, 75), (0, 224, 255), (248, 245, 230)),
        (164.81, 220.0, 246.94, 329.63),
    ),
    DemoAd(
        "orbital_air",
        42,
        "ORBITAL AIR",
        "TOMORROW DEPARTS TONIGHT",
        ((3, 8, 25), (54, 95, 255), (255, 185, 40), (223, 245, 255)),
        (196.0, 246.94, 293.66, 392.0),
    ),
)


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


def set_pixel(canvas: bytearray, x: int, y: int, color: tuple[int, int, int]) -> None:
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT):
        return
    index = (y * WIDTH + x) * 3
    canvas[index:index + 3] = bytes(color)


def fill_rect(
    canvas: bytearray,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    color: tuple[int, int, int],
) -> None:
    x0, x1 = max(0, x0), min(WIDTH, x1)
    y0, y1 = max(0, y0), min(HEIGHT, y1)
    row = bytes(color) * max(0, x1 - x0)
    for y in range(y0, y1):
        start = (y * WIDTH + x0) * 3
        canvas[start:start + len(row)] = row


def fill_circle(
    canvas: bytearray,
    cx: int,
    cy: int,
    radius: int,
    color: tuple[int, int, int],
) -> None:
    radius_squared = radius * radius
    for y in range(cy - radius, cy + radius + 1):
        extent = int(math.sqrt(max(0, radius_squared - (y - cy) ** 2)))
        fill_rect(canvas, cx - extent, y, cx + extent + 1, y + 1, color)


def draw_text(
    canvas: bytearray,
    text: str,
    center_x: int,
    y: int,
    scale: int,
    color: tuple[int, int, int],
) -> None:
    advance = 6 * scale
    x0 = center_x - (len(text) * advance - scale) // 2
    for char_index, character in enumerate(text):
        glyph = FONT.get(character, FONT[" "])
        for row_index, row in enumerate(glyph):
            for column_index, value in enumerate(row):
                if value == "1":
                    fill_rect(
                        canvas,
                        x0 + char_index * advance + column_index * scale,
                        y + row_index * scale,
                        x0 + char_index * advance + (column_index + 1) * scale,
                        y + (row_index + 1) * scale,
                        color,
                    )


def background(ad: DemoAd, frame: int) -> bytearray:
    base, primary, accent, light = ad.palette
    canvas = bytearray(WIDTH * HEIGHT * 3)
    for y in range(HEIGHT):
        blend = y / max(1, HEIGHT - 1)
        color = tuple(int(base[channel] * (1.0 - blend * 0.45)
                          + primary[channel] * blend * 0.18) for channel in range(3))
        fill_rect(canvas, 0, y, WIDTH, y + 1, color)
    scan = frame % 12
    for y in range(scan, HEIGHT, 12):
        fill_rect(canvas, 0, y, WIDTH, y + 1,
                  tuple(min(255, value + 16) for value in base))
    return canvas


def skyline_frame(ad: DemoAd, frame: int) -> bytes:
    canvas = background(ad, frame)
    _, primary, accent, light = ad.palette
    fill_circle(canvas, 132, 24, 13, accent)
    for index, x in enumerate(range(0, WIDTH, 10)):
        height = 15 + ((index * 13 + 7) % 31)
        fill_rect(canvas, x, HEIGHT - height, x + 8, HEIGHT, (6, 12, 22))
        window = primary if index % 2 == 0 else light
        for wy in range(HEIGHT - height + 4, HEIGHT - 2, 6):
            fill_rect(canvas, x + 2, wy, x + 4, wy + 2, window)
    streak_x = (frame * 5) % (WIDTH + 35) - 35
    fill_rect(canvas, streak_x, 72, streak_x + 32, 74, light)
    draw_text(canvas, ad.title, 80, 10, 2, light)
    draw_text(canvas, ad.slogan, 80, 31, 1, primary)
    return bytes(canvas)


def cola_frame(ad: DemoAd, frame: int) -> bytes:
    canvas = background(ad, frame)
    _, primary, accent, light = ad.palette
    pulse = 2 + int((math.sin(frame * 0.35) + 1.0) * 2)
    fill_circle(canvas, 80, 52, 28 + pulse, primary)
    fill_circle(canvas, 80, 52, 22, (25, 8, 20))
    fill_rect(canvas, 72, 30, 88, 72, light)
    fill_rect(canvas, 74, 34, 86, 68, primary)
    fill_rect(canvas, 74, 48, 86, 53, accent)
    for index in range(9):
        x = (index * 23 + frame * (index % 3 + 1)) % WIDTH
        y = 27 + (index * 17 + frame * 2) % 52
        fill_circle(canvas, x, y, 1 + index % 3, accent)
    draw_text(canvas, ad.title, 80, 7, 2, light)
    draw_text(canvas, ad.slogan, 80, 80, 1, accent)
    return bytes(canvas)


def orbital_frame(ad: DemoAd, frame: int) -> bytes:
    canvas = background(ad, frame)
    _, primary, accent, light = ad.palette
    for index in range(42):
        x = (index * 47 + 13) % WIDTH
        y = (index * 29 + frame // 3) % HEIGHT
        set_pixel(canvas, x, y, light if index % 5 == 0 else primary)
    fill_circle(canvas, 110, 54, 22, primary)
    fill_circle(canvas, 104, 48, 17, (7, 16, 45))
    ring_offset = int(math.sin(frame * 0.2) * 4)
    fill_rect(canvas, 76, 54 + ring_offset, 145, 56 + ring_offset, accent)
    ship_x = 18 + (frame * 2) % 55
    fill_rect(canvas, ship_x - 15, 61, ship_x, 62, accent)
    fill_rect(canvas, ship_x, 58, ship_x + 8, 64, light)
    draw_text(canvas, ad.title, 60, 8, 2, light)
    draw_text(canvas, ad.slogan, 80, 78, 1, accent)
    return bytes(canvas)


def write_jingle(path: Path, ad: DemoAd) -> None:
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        block = bytearray()
        for sample_index in range(ad.duration * SAMPLE_RATE):
            time = sample_index / SAMPLE_RATE
            beat = time % 0.5
            note = ad.notes[int(time / 0.5) % len(ad.notes)]
            envelope = 0.35 + 0.65 * math.exp(-beat * 7.0)
            value = (
                math.sin(2.0 * math.pi * note * time)
                + 0.28 * math.sin(2.0 * math.pi * note * 2.0 * time)
                + 0.20 * math.sin(2.0 * math.pi * 55.0 * time)
            )
            sample = int(4_800 * envelope * value)
            block.extend(struct.pack("<h", max(-32_768, min(32_767, sample))))
            if len(block) >= 65_536:
                output.writeframesraw(block)
                block.clear()
        output.writeframes(block)


def render_ad(ad: DemoAd, output: Path, ffmpeg: str) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f"{ad.clip_id}-") as temporary:
        audio = Path(temporary) / "jingle.wav"
        write_jingle(audio, ad)
        command = [
            ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo", "-pixel_format", "rgb24",
            "-video_size", f"{WIDTH}x{HEIGHT}", "-framerate", str(FPS),
            "-i", "-", "-i", str(audio), "-t", str(ad.duration),
            "-c:v", "libx264", "-preset", "slow", "-crf", "24",
            "-pix_fmt", "yuv420p", "-profile:v", "baseline",
            "-c:a", "aac", "-b:a", "64k", "-ac", "1", "-ar", str(SAMPLE_RATE),
            "-movflags", "+faststart", str(output),
        ]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
        assert process.stdin is not None
        frame_builder = {
            "neon_skyline": skyline_frame,
            "chrome_cola": cola_frame,
            "orbital_air": orbital_frame,
        }[ad.clip_id]
        try:
            for frame in range(ad.duration * FPS):
                process.stdin.write(frame_builder(ad, frame))
            process.stdin.close()
            stderr = process.stderr.read() if process.stderr is not None else b""
            return_code = process.wait()
        except BrokenPipeError:
            stderr = process.stderr.read() if process.stderr is not None else b""
            process.wait()
            raise RuntimeError(stderr.decode("utf-8", errors="replace")) from None
        if return_code != 0:
            raise RuntimeError(stderr.decode("utf-8", errors="replace"))


def main() -> None:
    repository = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=repository / "ads")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg") or "ffmpeg")
    args = parser.parse_args()

    for ad in ADS:
        destination = args.output / f"{ad.clip_id}.mp4"
        render_ad(ad, destination, args.ffmpeg)
        print(f"created {destination} ({ad.duration}s, {FPS} fps, with AAC audio)")


if __name__ == "__main__":
    main()
