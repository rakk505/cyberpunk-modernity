#!/usr/bin/env python3
"""Validate MP4 advertisements and build bounded Minecraft video/audio resources."""

from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import struct
import subprocess
import tempfile
from pathlib import Path


FPS = 8
FRAME_WIDTH = 160
FRAME_HEIGHT = 90
SHEET_COLUMNS = 4
SHEET_ROWS = 4
FRAMES_PER_SHEET = SHEET_COLUMNS * SHEET_ROWS
MIN_DURATION = 30
MAX_DURATION = 45
MAX_TOTAL_FRAMES = 1_000
MAX_TOTAL_SHEETS = 64
VALID_ID = re.compile(r"^[a-z0-9_]+$")


def run(command: list[str], input_bytes: bytes | None = None) -> None:
    subprocess.run(command, input=input_bytes, check=True)


def write_frame_texture(path: Path, ffmpeg: str) -> None:
    pixels = bytearray()
    for y in range(16):
        for x in range(16):
            border = x < 2 or x > 13 or y < 2 or y > 13
            corner = (x < 4 or x > 11) and (y < 4 or y > 11)
            color = (15, 229, 210) if corner else ((18, 25, 34) if border else (5, 7, 10))
            pixels.extend(color)
    path.parent.mkdir(parents=True, exist_ok=True)
    run([
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
        "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", "16x16",
        "-i", "-", "-frames:v", "1", str(path),
    ], bytes(pixels))


def read_mp4_duration(path: Path) -> float:
    with path.open("rb") as source:
        source.seek(0, 2)
        file_size = source.tell()
        moov = find_box(source, 0, file_size, b"moov")
        if moov is None:
            raise ValueError(f"{path}: MP4 has no moov metadata box")
        mvhd = find_box(source, moov[0], moov[1], b"mvhd")
        if mvhd is None:
            raise ValueError(f"{path}: MP4 has no movie header")
        source.seek(mvhd[0])
        header = source.read(min(32, mvhd[1] - mvhd[0]))

    version = header[0]
    if version == 0 and len(header) >= 20:
        timescale, duration = struct.unpack_from(">II", header, 12)
    elif version == 1 and len(header) >= 32:
        timescale = struct.unpack_from(">I", header, 20)[0]
        duration = struct.unpack_from(">Q", header, 24)[0]
    else:
        raise ValueError(f"{path}: unsupported or truncated MP4 movie header")
    if timescale == 0:
        raise ValueError(f"{path}: MP4 movie timescale is zero")
    return duration / timescale


def find_box(source, start: int, end: int, wanted: bytes) -> tuple[int, int] | None:
    offset = start
    while offset + 8 <= end:
        source.seek(offset)
        size, box_type = struct.unpack(">I4s", source.read(8))
        header_size = 8
        if size == 1:
            extended = source.read(8)
            if len(extended) != 8:
                return None
            size = struct.unpack(">Q", extended)[0]
            header_size = 16
        elif size == 0:
            size = end - offset
        if size < header_size or offset + size > end:
            return None
        payload_start = offset + header_size
        payload_end = offset + size
        if box_type == wanted:
            return payload_start, payload_end
        offset = payload_end
    return None


def process_clip(
    clip: dict[str, object],
    ads_root: Path,
    temporary: Path,
    ffmpeg: str,
) -> dict[str, object]:
    clip_id = str(clip["id"])
    filename = str(clip["file"])
    duration = int(clip["duration_seconds"])
    if not VALID_ID.fullmatch(clip_id):
        raise ValueError(f"Invalid clip id: {clip_id}")
    if not MIN_DURATION <= duration <= MAX_DURATION:
        raise ValueError(f"{clip_id}: duration must be {MIN_DURATION}-{MAX_DURATION}s")

    source = ads_root / filename
    if source.suffix.lower() != ".mp4" or not source.is_file():
        raise FileNotFoundError(f"{clip_id}: missing MP4 source {source}")
    actual_duration = read_mp4_duration(source)
    if not MIN_DURATION <= actual_duration <= MAX_DURATION + 0.25:
        raise ValueError(
            f"{clip_id}: MP4 duration {actual_duration:.3f}s is outside "
            f"{MIN_DURATION}-{MAX_DURATION}s"
        )
    if abs(actual_duration - duration) > 0.25:
        raise ValueError(
            f"{clip_id}: catalog duration {duration}s does not match "
            f"MP4 duration {actual_duration:.3f}s"
        )

    frame_count = duration * FPS
    sheet_count = math.ceil(frame_count / FRAMES_PER_SHEET)
    clip_sheets = temporary / "textures" / clip_id
    clip_sheets.mkdir(parents=True)
    run([
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(source), "-t", str(duration), "-an",
        "-vf", f"fps={FPS},scale={FRAME_WIDTH}:{FRAME_HEIGHT}:flags=lanczos,"
               f"tile={SHEET_COLUMNS}x{SHEET_ROWS}",
        "-frames:v", str(sheet_count), "-fps_mode", "passthrough",
        str(clip_sheets / "sheet_%03d.png"),
    ])
    generated_sheets = sorted(clip_sheets.glob("sheet_*.png"))
    if len(generated_sheets) != sheet_count:
        raise RuntimeError(
            f"{clip_id}: expected {sheet_count} sheets, generated {len(generated_sheets)}"
        )

    audio = temporary / "sounds" / f"{clip_id}.ogg"
    audio.parent.mkdir(parents=True, exist_ok=True)
    run([
        ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(source), "-t", str(duration), "-map", "0:a:0", "-vn",
        "-c:a", "libvorbis", "-q:a", "4", "-ac", "1", "-ar", "48000",
        str(audio),
    ])

    return {
        "id": clip_id,
        "duration_ticks": duration * 20,
        "fps": FPS,
        "frame_count": frame_count,
        "sheet_count": sheet_count,
        "sheet_grid": [SHEET_COLUMNS, SHEET_ROWS],
        "frame_size": [FRAME_WIDTH, FRAME_HEIGHT],
    }


def main() -> None:
    repository = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=repository / "ads" / "catalog.json")
    parser.add_argument("--ads-root", type=Path, default=repository / "ads")
    parser.add_argument(
        "--resources",
        type=Path,
        default=repository / "src" / "main" / "resources" / "assets" / "cyberdeck",
    )
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg") or "ffmpeg")
    args = parser.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    clips = catalog.get("clips")
    if not isinstance(clips, list) or not clips:
        raise ValueError("ads/catalog.json must contain a non-empty clips array")
    ids = [str(clip.get("id", "")) for clip in clips]
    if len(ids) != len(set(ids)):
        raise ValueError("Advertisement clip ids must be unique")

    total_frames = sum(int(clip["duration_seconds"]) * FPS for clip in clips)
    total_sheets = sum(math.ceil(int(clip["duration_seconds"]) * FPS
                                 / FRAMES_PER_SHEET) for clip in clips)
    if total_frames > MAX_TOTAL_FRAMES or total_sheets > MAX_TOTAL_SHEETS:
        raise ValueError(
            f"Catalog exceeds asset budget: {total_frames}/{MAX_TOTAL_FRAMES} frames, "
            f"{total_sheets}/{MAX_TOTAL_SHEETS} sheets"
        )

    with tempfile.TemporaryDirectory(prefix="cyberdeck-ads-") as temporary_name:
        temporary = Path(temporary_name)
        manifest_clips = [
            process_clip(clip, args.ads_root, temporary, args.ffmpeg)
            for clip in clips
        ]
        write_frame_texture(temporary / "frame.png", args.ffmpeg)

        textures_root = args.resources / "textures" / "ads"
        sounds_root = args.resources / "sounds" / "ads"
        if textures_root.exists():
            shutil.rmtree(textures_root)
        if sounds_root.exists():
            shutil.rmtree(sounds_root)
        textures_root.mkdir(parents=True)
        sounds_root.mkdir(parents=True)
        shutil.copy2(temporary / "frame.png", textures_root / "frame.png")
        for clip_id in ids:
            shutil.copytree(temporary / "textures" / clip_id, textures_root / clip_id)
            shutil.copy2(temporary / "sounds" / f"{clip_id}.ogg",
                         sounds_root / f"{clip_id}.ogg")

        item_texture = args.resources / "textures" / "item" / "large_ad_display.png"
        item_texture.parent.mkdir(parents=True, exist_ok=True)
        run([
            args.ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(args.ads_root / str(clips[0]["file"])),
            "-frames:v", "1", "-vf", "scale=16:16:flags=lanczos", str(item_texture),
        ])

        manifest = {
            "format": 1,
            "surface": {"width": 8, "height": 4},
            "total_frames": total_frames,
            "total_sheets": total_sheets,
            "clips": manifest_clips,
        }
        manifest_path = args.resources / "ads" / "manifest.json"
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    print(
        f"validated {len(clips)} MP4 ads; generated {total_frames} frames in "
        f"{total_sheets} shared sheets"
    )


if __name__ == "__main__":
    main()
