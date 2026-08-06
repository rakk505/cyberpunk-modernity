"""Tests for strip_ogg_video, plus a standing guard over the shipped sounds.

The guard is the important half. A multiplexed Ogg looks entirely healthy from
the outside -- correct extension, plays in every desktop audio player -- but
Minecraft's stb_vorbis rejects it and the sound goes silent with nothing logged.
That failure already shipped once; this keeps it from shipping twice.
"""

from __future__ import annotations

import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from strip_ogg_video import (  # noqa: E402
    FLAG_BOS,
    HEADER_FIXED_LEN,
    codec_of,
    iter_pages,
    ogg_crc,
    strip_to_vorbis,
)

SOUNDS = (Path(__file__).resolve().parent.parent
          / "src/main/resources/assets/cyberdeck/sounds")

VORBIS_ID_HEADER = (b"\x01vorbis" + b"\x00" * 4 + bytes([2])
                    + struct.pack("<I", 44100) + b"\x00" * 16)
THEORA_ID_HEADER = b"\x80theora" + b"\x00" * 32


def make_page(serial: int, sequence: int, body: bytes,
              flags: int = 0, granule: int = 0) -> bytes:
    """Assemble one well-formed Ogg page, checksum included."""
    segments = []
    remaining = len(body)
    while remaining >= 255:
        segments.append(255)
        remaining -= 255
    segments.append(remaining)
    page = bytearray()
    page += b"OggS"
    page += bytes([0, flags])
    page += struct.pack("<q", granule)
    page += struct.pack("<I", serial)
    page += struct.pack("<I", sequence)
    page += struct.pack("<I", 0)          # checksum, filled in below
    page += bytes([len(segments)])
    page += bytes(segments)
    page += body
    struct.pack_into("<I", page, 22, ogg_crc(bytes(page)))
    return bytes(page)


def multiplexed_sample() -> tuple[bytes, list[bytes]]:
    """An Ogg carrying cover art as Theora alongside the Vorbis audio.

    This is exactly what ffmpeg produces from an mp3 with embedded artwork:
    both beginning-of-stream pages come first, the video one leading.
    """
    audio_bodies = [VORBIS_ID_HEADER, b"audio-setup", b"audio-frame-0",
                    b"audio-frame-1"]
    pages = [
        make_page(0xAAAA, 0, THEORA_ID_HEADER, flags=FLAG_BOS),
        make_page(0xBBBB, 0, audio_bodies[0], flags=FLAG_BOS),
        make_page(0xAAAA, 1, b"cover-art-frame"),
        make_page(0xBBBB, 1, audio_bodies[1]),
        make_page(0xAAAA, 2, b"cover-art-tail"),
        make_page(0xBBBB, 2, audio_bodies[2], granule=44100),
        make_page(0xBBBB, 3, audio_bodies[3], granule=88200),
    ]
    return b"".join(pages), audio_bodies


def page_bodies(data: bytes) -> list[bytes]:
    out = []
    for page in iter_pages(data):
        body_start = HEADER_FIXED_LEN + page.raw[26]
        out.append(page.raw[body_start:])
    return out


class StripOggVideoTest(unittest.TestCase):

    def test_drops_the_video_stream(self):
        data, _ = multiplexed_sample()
        stripped, summary = strip_to_vorbis(data)
        serials = {page.serial for page in iter_pages(stripped)}
        self.assertEqual({0xBBBB}, serials)
        self.assertEqual(["theora"], summary["dropped_codecs"])
        self.assertEqual(4, summary["pages_kept"])

    def test_audio_payload_survives_byte_for_byte(self):
        """A remux must not touch the audio; any change here is a re-encode."""
        data, audio_bodies = multiplexed_sample()
        stripped, _ = strip_to_vorbis(data)
        self.assertEqual(audio_bodies, page_bodies(stripped))

    def test_result_is_a_single_stream_starting_with_vorbis(self):
        data, _ = multiplexed_sample()
        stripped, _ = strip_to_vorbis(data)
        bos = [page for page in iter_pages(stripped) if page.is_bos]
        self.assertEqual(1, len(bos), "exactly one beginning-of-stream page")
        self.assertEqual("vorbis", codec_of(bos[0]))
        self.assertEqual(bos[0], next(iter(iter_pages(stripped))),
                         "the Vorbis page must come first")

    def test_every_page_keeps_a_valid_checksum(self):
        data, _ = multiplexed_sample()
        stripped, _ = strip_to_vorbis(data)
        for page in iter_pages(stripped):
            stated = struct.unpack_from("<I", page.raw, 22)[0]
            zeroed = bytearray(page.raw)
            struct.pack_into("<I", zeroed, 22, 0)
            self.assertEqual(stated, ogg_crc(bytes(zeroed)))

    def test_sequence_numbers_are_contiguous(self):
        data, _ = multiplexed_sample()
        stripped, _ = strip_to_vorbis(data)
        self.assertEqual([0, 1, 2, 3],
                         [page.sequence for page in iter_pages(stripped)])

    def test_renumbers_and_repairs_crc_when_sequence_has_gaps(self):
        """Guards the fallback path: a rewritten header needs a fresh checksum."""
        pages = [
            make_page(0xAAAA, 0, THEORA_ID_HEADER, flags=FLAG_BOS),
            make_page(0xBBBB, 7, VORBIS_ID_HEADER, flags=FLAG_BOS),
            make_page(0xBBBB, 9, b"audio"),
        ]
        stripped, summary = strip_to_vorbis(b"".join(pages))
        self.assertEqual(2, summary["pages_renumbered"])
        self.assertEqual([0, 1], [p.sequence for p in iter_pages(stripped)])
        for page in iter_pages(stripped):
            zeroed = bytearray(page.raw)
            struct.pack_into("<I", zeroed, 22, 0)
            self.assertEqual(struct.unpack_from("<I", page.raw, 22)[0],
                             ogg_crc(bytes(zeroed)))

    def test_already_clean_file_is_left_alone(self):
        pages = [make_page(0xBBBB, 0, VORBIS_ID_HEADER, flags=FLAG_BOS),
                 make_page(0xBBBB, 1, b"audio")]
        data = b"".join(pages)
        stripped, summary = strip_to_vorbis(data)
        self.assertEqual(data, stripped)
        self.assertEqual([], summary["dropped_codecs"])

    def test_refuses_a_file_with_no_audio(self):
        data = make_page(0xAAAA, 0, THEORA_ID_HEADER, flags=FLAG_BOS)
        with self.assertRaisesRegex(ValueError, "no Vorbis stream"):
            strip_to_vorbis(data)

    def test_refuses_to_guess_between_two_audio_streams(self):
        data = b"".join([
            make_page(0xAAAA, 0, VORBIS_ID_HEADER, flags=FLAG_BOS),
            make_page(0xBBBB, 0, VORBIS_ID_HEADER, flags=FLAG_BOS),
        ])
        with self.assertRaisesRegex(ValueError, "refusing to guess"):
            strip_to_vorbis(data)


class ShippedSoundsTest(unittest.TestCase):
    """Every sound we ship must be something stb_vorbis can actually open."""

    def test_sound_assets_exist(self):
        self.assertTrue(SOUNDS.is_dir(), f"missing {SOUNDS}")
        self.assertTrue(list(SOUNDS.rglob("*.ogg")), "no sounds found")

    def test_every_sound_is_a_lone_vorbis_stream(self):
        offenders = []
        for path in sorted(SOUNDS.rglob("*.ogg")):
            pages = list(iter_pages(path.read_bytes()))
            starts = [page for page in pages if page.is_bos]
            codecs = [codec_of(page) for page in starts]
            if codecs != ["vorbis"] or not (pages and pages[0].is_bos
                                            and codec_of(pages[0]) == "vorbis"):
                offenders.append(f"{path.relative_to(SOUNDS)}: {codecs}")
        self.assertEqual([], offenders,
                         "stb_vorbis reads one stream per file and rejects the "
                         "rest outright; re-run tools/strip_ogg_video.py")


if __name__ == "__main__":
    unittest.main(verbosity=2)
