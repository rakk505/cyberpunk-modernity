#!/usr/bin/env python3
"""Validate advertisement sources and build bounded Minecraft video/audio resources."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import shutil
import struct
import subprocess
import tempfile
from pathlib import Path

try:
    from generate_meta_ads import generate_campaign as generate_meta_campaign
except ModuleNotFoundError:  # Support `python -m tools.process_ads` and test imports.
    from tools.generate_meta_ads import generate_campaign as generate_meta_campaign


FPS = 8
FRAME_WIDTH = 160
FRAME_HEIGHT = 90
SHEET_COLUMNS = 4
SHEET_ROWS = 4
FRAMES_PER_SHEET = SHEET_COLUMNS * SHEET_ROWS
MIN_DURATION = 30
MAX_DURATION = 45
MAX_TOTAL_FRAMES = 4_464
MAX_TOTAL_SHEETS = 280
VALID_ID = re.compile(r"^[a-z0-9_]+$")
VALID_CAMPAIGNS = frozenset({"general", "meta", "closed_ai", "highway"})
VALID_PAD_COLORS = frozenset({"black", "white"})
META_GENERATOR = "meta_ads_v1"


def run(command: list[str], input_bytes: bytes | None = None) -> None:
    subprocess.run(command, input=input_bytes, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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
    duration = int(clip["duration_seconds"])
    fps = int(clip.get("fps", FPS))
    campaigns_value = clip.get("campaigns", ["general"])
    generator = clip.get("generator")
    audio_enabled = clip.get("audio", True)
    loop = clip.get("loop", False)
    pad_color = str(clip.get("pad_color", "black"))

    if not VALID_ID.fullmatch(clip_id):
        raise ValueError(f"Invalid clip id: {clip_id}")
    if not MIN_DURATION <= duration <= MAX_DURATION:
        raise ValueError(f"{clip_id}: duration must be {MIN_DURATION}-{MAX_DURATION}s")
    if fps not in {4, 8}:
        raise ValueError(f"{clip_id}: fps must be 4 or 8")
    if not isinstance(campaigns_value, list) or not campaigns_value:
        raise ValueError(f"{clip_id}: campaigns must be a non-empty array")
    campaigns = [str(campaign) for campaign in campaigns_value]
    invalid_campaigns = set(campaigns) - VALID_CAMPAIGNS
    if invalid_campaigns or len(campaigns) != len(set(campaigns)):
        raise ValueError(f"{clip_id}: invalid or duplicate campaigns {campaigns}")
    if not isinstance(audio_enabled, bool) or not isinstance(loop, bool):
        raise ValueError(f"{clip_id}: audio and loop must be booleans")
    if pad_color not in VALID_PAD_COLORS:
        raise ValueError(f"{clip_id}: invalid pad color {pad_color}")
    if generator is not None and generator != META_GENERATOR:
        raise ValueError(f"{clip_id}: unknown procedural generator {generator!r}")
    if generator is not None and (audio_enabled or "file" in clip or fps != FPS):
        raise ValueError(f"{clip_id}: procedural campaigns must be silent 8 FPS assets")

    frame_count = duration * fps
    sheet_count = math.ceil(frame_count / FRAMES_PER_SHEET)
    clip_sheets = temporary / "textures" / clip_id
    clip_sheets.mkdir(parents=True)
    source: Path | None = None

    if generator == META_GENERATOR:
        generated_count = generate_meta_campaign(clip_id, duration, clip_sheets)
        if generated_count != sheet_count:
            raise RuntimeError(
                f"{clip_id}: generator returned {generated_count}/{sheet_count} sheets"
            )
    else:
        filename = clip.get("file")
        if not isinstance(filename, str):
            raise ValueError(f"{clip_id}: video campaigns require a file")
        source = ads_root / filename
        if source.suffix.lower() not in {".mp4", ".mov"} or not source.is_file():
            raise FileNotFoundError(f"{clip_id}: missing MP4/MOV source {source}")
        expected_sha256 = clip.get("source_sha256")
        if expected_sha256 is not None and sha256(source) != str(expected_sha256).lower():
            raise ValueError(f"{clip_id}: source SHA-256 does not match the catalog")
        actual_duration = read_mp4_duration(source)
        if actual_duration <= 0.0:
            raise ValueError(f"{clip_id}: source duration must be positive")
        if not loop and (not MIN_DURATION <= actual_duration <= MAX_DURATION + 0.25
                         or abs(actual_duration - duration) > 0.25):
            raise ValueError(
                f"{clip_id}: source duration {actual_duration:.3f}s does not match {duration}s"
            )
        input_options = ["-stream_loop", "-1"] if loop else []
        if "pad_color" in clip:
            scale = (
                f"scale={FRAME_WIDTH}:{FRAME_HEIGHT}:"
                "force_original_aspect_ratio=decrease:flags=lanczos,"
                f"pad={FRAME_WIDTH}:{FRAME_HEIGHT}:(ow-iw)/2:(oh-ih)/2:color={pad_color}"
            )
        else:
            scale = f"scale={FRAME_WIDTH}:{FRAME_HEIGHT}:flags=lanczos"
        run([
            ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
            *input_options, "-i", str(source), "-t", str(duration), "-an",
            "-vf", f"fps={fps},{scale},tile={SHEET_COLUMNS}x{SHEET_ROWS}",
            "-frames:v", str(sheet_count), "-fps_mode", "passthrough",
            str(clip_sheets / "sheet_%03d.png"),
        ])

    generated_sheets = sorted(clip_sheets.glob("sheet_*.png"))
    if len(generated_sheets) != sheet_count:
        raise RuntimeError(
            f"{clip_id}: expected {sheet_count} sheets, generated {len(generated_sheets)}"
        )

    if audio_enabled:
        if source is None:
            raise ValueError(f"{clip_id}: procedural campaigns cannot provide audio")
        audio = temporary / "sounds" / f"{clip_id}.ogg"
        audio.parent.mkdir(parents=True, exist_ok=True)
        run([
            ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(source), "-t", str(duration), "-map", "0:a:0", "-vn",
            "-c:a", "libvorbis", "-q:a", "4", "-ac", "1", "-ar", "48000",
            str(audio),
        ])

    manifest: dict[str, object] = {
        "id": clip_id,
        "campaigns": campaigns,
        "audio": audio_enabled,
        "duration_ticks": duration * 20,
        "fps": fps,
        "frame_count": frame_count,
        "sheet_count": sheet_count,
        "sheet_grid": [SHEET_COLUMNS, SHEET_ROWS],
        "frame_size": [FRAME_WIDTH, FRAME_HEIGHT],
    }
    if generator is not None:
        manifest["generator"] = generator
    return manifest


def install_atomically(replacements: list[tuple[Path, Path]]) -> None:
    """Install staged files/directories as one rollback-safe resource transaction."""
    backups: list[tuple[Path, Path]] = []
    installed: list[Path] = []
    try:
        for index, (staged, destination) in enumerate(replacements):
            destination.parent.mkdir(parents=True, exist_ok=True)
            backup = staged.parent / f".backup-{index}-{destination.name}"
            if destination.exists():
                destination.replace(backup)
                backups.append((backup, destination))
            staged.replace(destination)
            installed.append(destination)
    except Exception:
        for destination in reversed(installed):
            if destination.is_dir():
                shutil.rmtree(destination)
            else:
                destination.unlink(missing_ok=True)
        for backup, destination in reversed(backups):
            if backup.exists():
                backup.replace(destination)
        raise


def clip_fps(clip: dict[str, object]) -> int:
    return int(clip.get("fps", FPS))


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

    total_frames = sum(int(clip["duration_seconds"]) * clip_fps(clip) for clip in clips)
    total_sheets = sum(math.ceil(
        int(clip["duration_seconds"]) * clip_fps(clip) / FRAMES_PER_SHEET
    ) for clip in clips)
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
        manifest = {
            "format": 3,
            "surface": {"width": 8, "height": 4},
            "total_frames": total_frames,
            "total_sheets": total_sheets,
            "clips": manifest_clips,
        }

        args.resources.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(
                prefix=".cyberdeck-ads-install-", dir=args.resources) as staging_name:
            staging = Path(staging_name)
            staged_textures = staging / "textures_ads"
            staged_sounds = staging / "sounds_ads"
            staged_item = staging / "large_ad_display.png"
            staged_manifest = staging / "manifest.json"
            staged_textures.mkdir()
            staged_sounds.mkdir()
            shutil.copy2(temporary / "frame.png", staged_textures / "frame.png")
            for clip_id, manifest_clip in zip(ids, manifest_clips):
                shutil.copytree(temporary / "textures" / clip_id, staged_textures / clip_id)
                if manifest_clip["audio"]:
                    shutil.copy2(
                        temporary / "sounds" / f"{clip_id}.ogg",
                        staged_sounds / f"{clip_id}.ogg",
                    )
            run([
                args.ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-i", str(args.ads_root / str(clips[0]["file"])),
                "-frames:v", "1", "-vf", "scale=16:16:flags=lanczos", str(staged_item),
            ])
            staged_manifest.write_text(
                json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            install_atomically([
                (staged_textures, args.resources / "textures" / "ads"),
                (staged_sounds, args.resources / "sounds" / "ads"),
                (staged_item, args.resources / "textures" / "item" / "large_ad_display.png"),
                (staged_manifest, args.resources / "ads" / "manifest.json"),
            ])

    print(
        f"validated {len(clips)} ads; generated {total_frames} frames in "
        f"{total_sheets} shared sheets"
    )


if __name__ == "__main__":
    main()
