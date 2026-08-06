"""Reduce an Ogg file to its Vorbis audio stream alone.

Minecraft decodes sounds with stb_vorbis, which handles exactly one logical
bitstream per file. Converting an mp3 that carries embedded cover art produces
an Ogg with *two* multiplexed streams -- a Theora video stream holding the
artwork, plus the Vorbis audio -- and stb_vorbis rejects the file outright. The
sound then fails to load and the game simply plays nothing.

This drops every page that does not belong to the Vorbis stream. It is a remux,
not a re-encode: the audio pages are copied byte for byte, so the result is
bit-identical audio with no generation loss.

Usage:
    python tools/strip_ogg_video.py <file-or-directory> [...] [--check]

--check reports what would change without writing anything.
"""

from __future__ import annotations

import argparse
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

CAPTURE = b"OggS"
HEADER_FIXED_LEN = 27
FLAG_CONTINUED = 0x01
FLAG_BOS = 0x02

# Ogg uses a plain (non-reflected) CRC-32, polynomial 0x04c11db7, with no
# initial or final inversion -- deliberately unlike the zlib CRC, so the two are
# not interchangeable.
_CRC_POLY = 0x04C11DB7


def _build_crc_table() -> list[int]:
    table = []
    for i in range(256):
        value = i << 24
        for _ in range(8):
            value = ((value << 1) ^ _CRC_POLY) & 0xFFFFFFFF if value & 0x80000000 \
                else (value << 1) & 0xFFFFFFFF
        table.append(value)
    return table


_CRC_TABLE = _build_crc_table()


def ogg_crc(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ _CRC_TABLE[((crc >> 24) & 0xFF) ^ byte]
    return crc


@dataclass
class Page:
    serial: int
    sequence: int
    flags: int
    raw: bytes

    @property
    def is_bos(self) -> bool:
        return bool(self.flags & FLAG_BOS)


def iter_pages(data: bytes):
    """Walk the Ogg pages in a buffer, skipping any leading or trailing junk."""
    offset = 0
    length = len(data)
    while offset < length:
        if data[offset:offset + 4] != CAPTURE:
            nxt = data.find(CAPTURE, offset + 1)
            if nxt < 0:
                return
            offset = nxt
            continue
        if offset + HEADER_FIXED_LEN > length:
            return
        flags = data[offset + 5]
        serial = struct.unpack_from("<I", data, offset + 14)[0]
        sequence = struct.unpack_from("<I", data, offset + 18)[0]
        segment_count = data[offset + 26]
        table_end = offset + HEADER_FIXED_LEN + segment_count
        if table_end > length:
            return
        body_len = sum(data[offset + HEADER_FIXED_LEN:table_end])
        page_end = table_end + body_len
        if page_end > length:
            return
        yield Page(serial, sequence, flags, data[offset:page_end])
        offset = page_end


def codec_of(page: Page) -> str:
    """Identify a logical stream from its beginning-of-stream page."""
    body_start = HEADER_FIXED_LEN + page.raw[26]
    body = page.raw[body_start:]
    if body[1:7] == b"vorbis":
        return "vorbis"
    if body[1:7] == b"theora":
        return "theora"
    if body[:8] == b"OpusHead":
        return "opus"
    if body[:5] == b"\x7fFLAC":
        return "flac"
    return "unknown"


def _renumber(raw: bytes, sequence: int) -> bytes:
    """Rewrite a page's sequence number and repair its checksum."""
    page = bytearray(raw)
    struct.pack_into("<I", page, 18, sequence)
    struct.pack_into("<I", page, 22, 0)
    struct.pack_into("<I", page, 22, ogg_crc(bytes(page)))
    return bytes(page)


def strip_to_vorbis(data: bytes) -> tuple[bytes, dict]:
    """Return the file reduced to its Vorbis stream, plus a summary of the edit.

    Raises ValueError if there is no Vorbis stream, or more than one, since
    guessing which audio the caller wanted would be worse than refusing.
    """
    pages = list(iter_pages(data))
    if not pages:
        raise ValueError("not an Ogg file: no pages found")

    codecs: dict[int, str] = {}
    for page in pages:
        if page.is_bos:
            codecs[page.serial] = codec_of(page)

    vorbis_serials = [s for s, c in codecs.items() if c == "vorbis"]
    if not vorbis_serials:
        raise ValueError(f"no Vorbis stream; found {sorted(codecs.values())}")
    if len(vorbis_serials) > 1:
        raise ValueError(f"{len(vorbis_serials)} Vorbis streams; refusing to guess")

    keep = vorbis_serials[0]
    dropped = {c for s, c in codecs.items() if s != keep}

    out = bytearray()
    kept_pages = 0
    renumbered = 0
    for page in pages:
        if page.serial != keep:
            continue
        # Sequence numbers count per logical stream, so they usually survive
        # demuxing untouched; renumber only if this file disagrees.
        if page.sequence == kept_pages:
            out += page.raw
        else:
            out += _renumber(page.raw, kept_pages)
            renumbered += 1
        kept_pages += 1

    summary = {
        "streams": dict(sorted(codecs.items())),
        "dropped_codecs": sorted(dropped),
        "pages_total": len(pages),
        "pages_kept": kept_pages,
        "pages_renumbered": renumbered,
    }
    return bytes(out), summary


def targets(paths: list[str]) -> list[Path]:
    found: list[Path] = []
    for raw in paths:
        path = Path(raw)
        if path.is_dir():
            found.extend(sorted(path.rglob("*.ogg")))
        else:
            found.append(path)
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", help="ogg files, or directories to search")
    parser.add_argument("--check", action="store_true",
                        help="report without writing")
    args = parser.parse_args(argv)

    changed = 0
    failed = 0
    for path in targets(args.paths):
        data = path.read_bytes()
        try:
            stripped, summary = strip_to_vorbis(data)
        except ValueError as exc:
            print(f"{path.name}: SKIP ({exc})")
            failed += 1
            continue
        if len(stripped) == len(data) and not summary["dropped_codecs"]:
            print(f"{path.name}: already audio-only")
            continue
        saved = len(data) - len(stripped)
        print(f"{path.name}: drop {'+'.join(summary['dropped_codecs'])} "
              f"({summary['pages_total'] - summary['pages_kept']} pages, "
              f"{saved / 1024:.1f} KB)")
        changed += 1
        if not args.check:
            path.write_bytes(stripped)

    verb = "would change" if args.check else "rewrote"
    print(f"\n{verb} {changed} file(s); {failed} skipped")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
