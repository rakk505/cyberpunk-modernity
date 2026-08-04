#!/usr/bin/env python3
"""Build Minecraft-ready firearm sounds from the CC0 Free Firearm Sound Library.

The source archive is intentionally not checked in. Download the prepared archive documented in
THIRD_PARTY_AUDIO.md, extract it, and pass the extracted "Prepared SFX Library" directory here.
"""

from __future__ import annotations

import argparse
import array
import audioop
import hashlib
import math
import shutil
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path


ARCHIVE_SHA256 = "cc1ab5a99a0a365105c7c5dd783f4b0b1fe90938114d3ceec53856bfe005f7d6"
LEAD_IN_SECONDS = 0.035
TARGET_RATE = 48_000
TARGET_RMS_DB = -18.0
TARGET_PEAK_DB = -2.5


@dataclass(frozen=True)
class Clip:
    output: str
    source: str
    onset_seconds: float
    tail_seconds: float


CLIPS = (
    Clip("pistol_1", "Walther PPQ/X_39P.wav", 1.405, 0.90),
    Clip("pistol_2", "Bersa/F_47P.wav", 0.330, 0.90),
    Clip("pistol_3", "Ruger Mark III/R_35P.wav", 0.480, 0.90),
    Clip("heavy_pistol_1", "1911/A_42P.wav", 0.940, 1.15),
    Clip("heavy_pistol_2", "Smith & Wesson 642/V_27P.wav", 0.805, 1.15),
    Clip("heavy_pistol_3", "1911/A_42P.wav", 5.000, 1.15),
    Clip("smg_1", "Carl Gustav M45/G_31P.wav", 0.310, 0.55),
    Clip("smg_2", "Carl Gustav M45/G_31P.wav", 3.500, 0.55),
    Clip("smg_3", "PPSh/P_30P.wav", 0.965, 0.55),
    Clip("rifle_1", "AR-15/D_32P.wav", 0.700, 0.85),
    Clip("rifle_2", "AK-47/C_28P.wav", 0.610, 0.85),
    Clip("rifle_3", "SKS/U_14P.wav", 3.575, 0.85),
    Clip("shotgun_1", "Nova/O_21P.wav", 0.430, 1.25),
    Clip("shotgun_2", "CD/H_21P.wav", 0.465, 1.25),
    Clip("shotgun_3", "Model 12/K_22P.wav", 0.840, 1.25),
    Clip("sniper_1", "Mosin Nagant/M_21P.wav", 1.030, 1.60),
    Clip("sniper_2", "Tikka/W_29P.wav", 0.575, 1.60),
    Clip("sniper_3", "Savage 10 .300 Blackout/T_27P.wav", 0.950, 1.60),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apply_fades(pcm: bytes, rate: int, duration_seconds: float) -> bytes:
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()

    fade_in = min(len(samples), max(1, int(rate * 0.005)))
    fade_out = min(len(samples), max(1, int(rate * min(0.15, duration_seconds * 0.20))))
    for index in range(fade_in):
        samples[index] = round(samples[index] * index / fade_in)
    for offset in range(fade_out):
        index = len(samples) - fade_out + offset
        samples[index] = round(samples[index] * (fade_out - offset - 1) / fade_out)

    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def render_wav(source: Path, destination: Path, clip: Clip) -> None:
    with wave.open(str(source), "rb") as input_wave:
        channels = input_wave.getnchannels()
        sample_width = input_wave.getsampwidth()
        sample_rate = input_wave.getframerate()
        if channels != 2 or sample_width != 3 or sample_rate != 96_000:
            raise ValueError(
                f"Unexpected source format for {source}: "
                f"{channels} channels, {sample_width * 8}-bit, {sample_rate} Hz"
            )

        start_frame = max(0, round((clip.onset_seconds - LEAD_IN_SECONDS) * sample_rate))
        frame_count = round((LEAD_IN_SECONDS + clip.tail_seconds) * sample_rate)
        input_wave.setpos(start_frame)
        stereo = input_wave.readframes(frame_count)

    mono = audioop.tomono(stereo, 3, 0.5, 0.5)
    dc_offset = audioop.avg(mono, 3)
    if dc_offset:
        mono = audioop.bias(mono, 3, -dc_offset)
    mono, _ = audioop.ratecv(mono, 3, 1, sample_rate, TARGET_RATE, None)
    pcm = audioop.lin2lin(mono, 3, 2)

    active_start = round(LEAD_IN_SECONDS * TARGET_RATE) * 2
    active_end = min(len(pcm), active_start + round(0.30 * TARGET_RATE) * 2)
    active = pcm[active_start:active_end]
    peak = max(1, audioop.max(pcm, 2))
    rms = max(1, audioop.rms(active, 2))
    target_peak = 32767 * math.pow(10.0, TARGET_PEAK_DB / 20.0)
    target_rms = 32767 * math.pow(10.0, TARGET_RMS_DB / 20.0)
    gain = min(target_peak / peak, target_rms / rms)
    pcm = audioop.mul(pcm, 2, gain)
    pcm = apply_fades(pcm, TARGET_RATE, LEAD_IN_SECONDS + clip.tail_seconds)

    destination.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(destination), "wb") as output_wave:
        output_wave.setnchannels(1)
        output_wave.setsampwidth(2)
        output_wave.setframerate(TARGET_RATE)
        output_wave.writeframes(pcm)


def encode_ogg(source: Path, destination: Path, ffmpeg: str) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            ffmpeg,
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(source),
            "-c:a",
            "libvorbis",
            "-q:a",
            "5",
            str(destination),
        ],
        check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg") or "ffmpeg")
    args = parser.parse_args()

    if sha256(args.archive) != ARCHIVE_SHA256:
        raise SystemExit("Source archive SHA-256 does not match THIRD_PARTY_AUDIO.md")

    args.output_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="cyberdeck-firearm-sfx-") as temporary:
        temporary_root = Path(temporary)
        for clip in CLIPS:
            source = args.source_root / clip.source
            if not source.is_file():
                raise FileNotFoundError(source)
            wav = temporary_root / f"{clip.output}.wav"
            output = args.output_root / f"{clip.output}.ogg"
            render_wav(source, wav, clip)
            encode_ogg(wav, output, args.ffmpeg)
            print(f"{clip.output}.ogg  {sha256(output)}")


if __name__ == "__main__":
    main()
