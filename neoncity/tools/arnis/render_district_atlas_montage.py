#!/usr/bin/env python3
"""Render an auditable A-Z montage from Arnis' saved source-map previews.

The script is intentionally dependency-free. It reads the 26 generation and
selection records, crops each selected 8x8-chunk Nest and Backstreets atlas
from the corresponding ``arnis_world_map.png``, labels the result, and emits a
JSON sidecar containing the source hashes and exact pixel rectangles.

This is selection evidence, not a Minecraft client screenshot. The source
worlds and full previews remain reproducible build artifacts and are not
bundled in the mod JAR.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import zlib


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ZONES = ("NEST", "BACKSTREETS")
ATLAS_CHUNKS = 8
BLOCKS_PER_CHUNK = 16


FONT = {
    " ": ("000", "000", "000", "000", "000", "000", "000"),
    "A": ("010", "101", "101", "111", "101", "101", "101"),
    "B": ("110", "101", "101", "110", "101", "101", "110"),
    "C": ("011", "100", "100", "100", "100", "100", "011"),
    "D": ("110", "101", "101", "101", "101", "101", "110"),
    "E": ("111", "100", "100", "110", "100", "100", "111"),
    "F": ("111", "100", "100", "110", "100", "100", "100"),
    "G": ("011", "100", "100", "101", "101", "101", "011"),
    "H": ("101", "101", "101", "111", "101", "101", "101"),
    "I": ("111", "010", "010", "010", "010", "010", "111"),
    "J": ("001", "001", "001", "001", "101", "101", "010"),
    "K": ("101", "101", "110", "100", "110", "101", "101"),
    "L": ("100", "100", "100", "100", "100", "100", "111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("1001", "1101", "1101", "1011", "1011", "1001", "1001"),
    "O": ("010", "101", "101", "101", "101", "101", "010"),
    "P": ("110", "101", "101", "110", "100", "100", "100"),
    "Q": ("010", "101", "101", "101", "101", "011", "001"),
    "R": ("110", "101", "101", "110", "110", "101", "101"),
    "S": ("011", "100", "100", "010", "001", "001", "110"),
    "T": ("111", "010", "010", "010", "010", "010", "010"),
    "U": ("101", "101", "101", "101", "101", "101", "010"),
    "V": ("101", "101", "101", "101", "101", "010", "010"),
    "W": ("10001", "10001", "10001", "10101", "10101", "11011", "10001"),
    "X": ("101", "101", "010", "010", "010", "101", "101"),
    "Y": ("101", "101", "010", "010", "010", "010", "010"),
    "Z": ("111", "001", "001", "010", "100", "100", "111"),
    "0": ("111", "101", "101", "101", "101", "101", "111"),
    "1": ("010", "110", "010", "010", "010", "010", "111"),
    "2": ("110", "001", "001", "010", "100", "100", "111"),
    "3": ("110", "001", "001", "010", "001", "001", "110"),
    "4": ("101", "101", "101", "111", "001", "001", "001"),
    "5": ("111", "100", "100", "110", "001", "001", "110"),
    "6": ("011", "100", "100", "110", "101", "101", "010"),
    "7": ("111", "001", "001", "010", "010", "010", "010"),
    "8": ("010", "101", "101", "010", "101", "101", "010"),
    "9": ("010", "101", "101", "011", "001", "001", "110"),
    "/": ("001", "001", "010", "010", "100", "100", "000"),
    "-": ("000", "000", "000", "111", "000", "000", "000"),
    ".": ("000", "000", "000", "000", "000", "000", "010"),
    "'": ("010", "010", "010", "000", "000", "000", "000"),
    "&": ("010", "101", "100", "010", "101", "101", "011"),
    "+": ("000", "010", "010", "111", "010", "010", "000"),
    "=": ("000", "000", "111", "000", "111", "000", "000"),
    "(": ("001", "010", "100", "100", "100", "010", "001"),
    ")": ("100", "010", "001", "001", "001", "010", "100"),
    ":": ("000", "010", "010", "000", "010", "010", "000"),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def paeth(a: int, b: int, c: int) -> int:
    estimate = a + b - c
    da, db, dc = abs(estimate - a), abs(estimate - b), abs(estimate - c)
    if da <= db and da <= dc:
        return a
    if db <= dc:
        return b
    return c


def read_png(path: Path) -> tuple[int, int, bytearray]:
    data = path.read_bytes()
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError(f"not a PNG: {path}")

    offset = len(PNG_SIGNATURE)
    width = height = channels = 0
    compressed = bytearray()
    while offset < len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        kind = data[offset + 4 : offset + 8]
        payload = data[offset + 8 : offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length : offset + 12 + length])[0]
        actual_crc = zlib.crc32(kind + payload) & 0xFFFFFFFF
        if expected_crc != actual_crc:
            raise ValueError(f"bad PNG CRC in {path} ({kind!r})")
        offset += length + 12
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
            if depth != 8 or color not in (2, 6) or compression or filtering or interlace:
                raise ValueError(
                    f"unsupported PNG format in {path}: depth={depth}, color={color}, "
                    f"compression={compression}, filter={filtering}, interlace={interlace}"
                )
            channels = 3 if color == 2 else 4
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break

    if width <= 0 or height <= 0 or not compressed:
        raise ValueError(f"incomplete PNG: {path}")
    inflated = zlib.decompress(bytes(compressed))
    stride = width * channels
    if len(inflated) != height * (stride + 1):
        raise ValueError(f"unexpected decompressed size in {path}")

    rows: list[bytearray] = []
    cursor = 0
    previous = bytearray(stride)
    for _ in range(height):
        filter_kind = inflated[cursor]
        cursor += 1
        encoded = inflated[cursor : cursor + stride]
        cursor += stride
        decoded = bytearray(stride)
        for index, value in enumerate(encoded):
            left = decoded[index - channels] if index >= channels else 0
            above = previous[index]
            upper_left = previous[index - channels] if index >= channels else 0
            if filter_kind == 0:
                predictor = 0
            elif filter_kind == 1:
                predictor = left
            elif filter_kind == 2:
                predictor = above
            elif filter_kind == 3:
                predictor = (left + above) // 2
            elif filter_kind == 4:
                predictor = paeth(left, above, upper_left)
            else:
                raise ValueError(f"unsupported PNG row filter {filter_kind} in {path}")
            decoded[index] = (value + predictor) & 0xFF
        rows.append(decoded)
        previous = decoded

    rgb = bytearray(width * height * 3)
    for y, row in enumerate(rows):
        for x in range(width):
            source = x * channels
            target = (y * width + x) * 3
            if channels == 3:
                rgb[target : target + 3] = row[source : source + 3]
            else:
                alpha = row[source + 3]
                for channel in range(3):
                    rgb[target + channel] = (row[source + channel] * alpha) // 255
    return width, height, rgb


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)


def write_png(path: Path, width: int, height: int, pixels: bytearray) -> None:
    rows = bytearray()
    stride = width * 3
    for y in range(height):
        rows.append(0)
        rows.extend(pixels[y * stride : (y + 1) * stride])
    payload = (
        PNG_SIGNATURE
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + png_chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def fill(pixels: bytearray, width: int, x: int, y: int, w: int, h: int, color: tuple[int, int, int]) -> None:
    for py in range(max(0, y), max(0, y + h)):
        if py < 0 or py >= len(pixels) // (width * 3):
            continue
        for px in range(max(0, x), max(0, x + w)):
            if 0 <= px < width:
                offset = (py * width + px) * 3
                pixels[offset : offset + 3] = bytes(color)


def blit(
    destination: bytearray,
    destination_width: int,
    source: bytearray,
    source_width: int,
    source_height: int,
    source_box: tuple[int, int, int, int],
    target_x: int,
    target_y: int,
) -> None:
    left, top, right, bottom = source_box
    if left < 0 or top < 0 or right > source_width or bottom > source_height:
        raise ValueError(
            f"crop {source_box} exceeds preview {source_width}x{source_height}"
        )
    for source_y in range(top, bottom):
        source_start = (source_y * source_width + left) * 3
        source_end = source_start + (right - left) * 3
        target_start = ((target_y + source_y - top) * destination_width + target_x) * 3
        destination[target_start : target_start + (right - left) * 3] = source[
            source_start:source_end
        ]


def text_width(value: str, scale: int) -> int:
    return sum(len(FONT.get(char, FONT[" "])[0]) + 1 for char in value) * scale


def draw_text(
    pixels: bytearray,
    width: int,
    x: int,
    y: int,
    value: str,
    color: tuple[int, int, int],
    scale: int = 1,
) -> None:
    cursor = x
    for character in value.upper():
        glyph = FONT.get(character, FONT[" "])
        for gy, row in enumerate(glyph):
            for gx, bit in enumerate(row):
                if bit == "1":
                    fill(pixels, width, cursor + gx * scale, y + gy * scale, scale, scale, color)
        cursor += (len(glyph[0]) + 1) * scale


def parse_selection(value: str) -> tuple[int, int, int, int]:
    _, coordinates = value.split("=", 1)
    start, end = coordinates.split(":", 1)
    min_x, min_z = (int(part) for part in start.split(",", 1))
    max_x, max_z = (int(part) for part in end.split(",", 1))
    if max_x - min_x + 1 != ATLAS_CHUNKS or max_z - min_z + 1 != ATLAS_CHUNKS:
        raise ValueError(f"selection is not an {ATLAS_CHUNKS}x{ATLAS_CHUNKS} atlas: {value}")
    return min_x, min_z, max_x, max_z


def relative(repo_root: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(repo_root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def render(repo_root: Path, output: Path, audit_output: Path) -> None:
    provenance = repo_root / "provenance" / "arnis_districts"
    manifest = json.loads((provenance / "manifest.json").read_text())
    districts = manifest["districts"]
    if [entry["district"] for entry in districts] != [chr(ord("A") + index) for index in range(26)]:
        raise ValueError("manifest must contain exactly one ordered A-Z district entry")

    columns = 4
    rows = math.ceil(len(districts) / columns)
    crop_size = ATLAS_CHUNKS * BLOCKS_PER_CHUNK
    margin = 16
    title_height = 52
    panel_gap = 10
    zone_gap = 6
    panel_width = crop_size * len(ZONES) + zone_gap + 16
    panel_height = 173
    footer_height = 34
    width = margin * 2 + columns * panel_width + (columns - 1) * panel_gap
    height = title_height + rows * panel_height + (rows - 1) * panel_gap + footer_height
    canvas = bytearray((12, 16, 22)) * (width * height)

    draw_text(canvas, width, margin, 10, "A-Z ARNIS DISTRICT SOURCE ATLAS SELECTIONS", (235, 241, 247), 2)
    draw_text(
        canvas,
        width,
        margin,
        34,
        "NEST + BACKSTREETS / 52 X 8X8 ATLASES / 3328 SOURCE CHUNKS",
        (134, 184, 207),
        1,
    )

    audit_districts: list[dict[str, object]] = []
    crop_hashes: list[str] = []
    for index, district_entry in enumerate(districts):
        district = district_entry["district"]
        generation_path = provenance / district.lower() / "generation.json"
        selection_path = provenance / district.lower() / "selection.json"
        generation = json.loads(generation_path.read_text())
        selection_record = json.loads(selection_path.read_text())
        preview = repo_root / generation["world"]["build_path"] / "arnis_world_map.png"
        if not preview.is_file():
            raise FileNotFoundError(f"missing reproducible Arnis preview: {preview}")
        preview_hash = sha256(preview)
        recorded_hash = generation["world"]["preview_sha256"]
        if preview_hash != recorded_hash:
            raise ValueError(f"preview hash mismatch for {district}: {preview_hash} != {recorded_hash}")
        preview_width, preview_height, preview_pixels = read_png(preview)
        metadata = generation["world"]["metadata"]
        min_mc_x = int(metadata["minMcX"])
        min_mc_z = int(metadata["minMcZ"])

        row, column = divmod(index, columns)
        panel_x = margin + column * (panel_width + panel_gap)
        panel_y = title_height + row * (panel_height + panel_gap)
        fill(canvas, width, panel_x, panel_y, panel_width, panel_height, (24, 31, 41))
        draw_text(canvas, width, panel_x + 8, panel_y + 7, f"{district} CORP", (241, 246, 250), 2)
        city = district_entry["city"].upper()
        while text_width(city, 1) > panel_width - 16 and city:
            city = city[:-1]
        draw_text(canvas, width, panel_x + 8, panel_y + 24, city, (151, 164, 178), 1)

        zone_audits: dict[str, object] = {}
        for zone_index, zone in enumerate(ZONES):
            zone_record = selection_record["zones"].get(zone)
            if zone_record is None:
                raise ValueError(f"{district} selection has no {zone} atlas")
            min_x, min_z, max_x, max_z = parse_selection(zone_record["selection"])
            left = min_x * BLOCKS_PER_CHUNK - min_mc_x
            top = min_z * BLOCKS_PER_CHUNK - min_mc_z
            right = (max_x + 1) * BLOCKS_PER_CHUNK - min_mc_x
            bottom = (max_z + 1) * BLOCKS_PER_CHUNK - min_mc_z
            crop_digest = hashlib.sha256()
            for source_y in range(top, bottom):
                start = (source_y * preview_width + left) * 3
                end = start + (right - left) * 3
                crop_digest.update(preview_pixels[start:end])
            crop_hash = crop_digest.hexdigest()
            crop_hashes.append(crop_hash)
            crop_x = panel_x + 8 + zone_index * (crop_size + zone_gap)
            crop_y = panel_y + 37
            blit(
                canvas,
                width,
                preview_pixels,
                preview_width,
                preview_height,
                (left, top, right, bottom),
                crop_x,
                crop_y,
            )
            border = (65, 221, 229) if zone == "NEST" else (255, 167, 76)
            fill(canvas, width, crop_x, crop_y, crop_size, 2, border)
            fill(canvas, width, crop_x, crop_y + crop_size - 2, crop_size, 2, border)
            fill(canvas, width, crop_x, crop_y, 2, crop_size, border)
            fill(canvas, width, crop_x + crop_size - 2, crop_y, 2, crop_size, border)
            draw_text(canvas, width, crop_x, crop_y + crop_size + 3, zone, border, 1)
            zone_audits[zone] = {
                "selection": zone_record["selection"],
                "chunk_bounds_inclusive": [min_x, min_z, max_x, max_z],
                "source_preview_pixel_box_exclusive": [left, top, right, bottom],
                "source_preview_crop_rgb_sha256": crop_hash,
                "source_metrics": {
                    key: value
                    for key, value in zone_record.items()
                    if key != "selection"
                },
            }

        audit_districts.append(
            {
                "district": district,
                "city": district_entry["city"],
                "culture": district_entry["culture"],
                "preview": relative(repo_root, preview),
                "preview_sha256": preview_hash,
                "selection_record": relative(repo_root, selection_path),
                "zones": zone_audits,
            }
        )

    footer_y = height - footer_height + 10
    draw_text(
        canvas,
        width,
        margin,
        footer_y,
        "SOURCE PREVIEW EVIDENCE / OUTSIDE THE FINITE CITY = VANILLA WILDERNESS",
        (151, 164, 178),
        1,
    )
    write_png(output, width, height, canvas)

    audit = {
        "schema_version": 1,
        "kind": "neoncity:arnis_district_atlas_selection_montage",
        "generated_by": "tools/arnis/render_district_atlas_montage.py",
        "description": (
            "Labeled crops from provenance-audited Arnis source previews; "
            "this is selection evidence, not a Minecraft client screenshot."
        ),
        "arnis_version": manifest["arnis_version"],
        "arnis_binary_sha256": manifest["arnis_binary_sha256"],
        "zones": list(ZONES),
        "district_count": len(districts),
        "atlas_count": len(districts) * len(ZONES),
        "distinct_source_preview_crop_count": len(set(crop_hashes)),
        "chunks_per_atlas": ATLAS_CHUNKS * ATLAS_CHUNKS,
        "chunk_template_count": len(districts) * len(ZONES) * ATLAS_CHUNKS * ATLAS_CHUNKS,
        "outside_city": "vanilla wilderness",
        "districts": audit_districts,
        "output": {
            "path": relative(repo_root, output),
            "width": width,
            "height": height,
            "sha256": sha256(output),
        },
    }
    audit_output.parent.mkdir(parents=True, exist_ok=True)
    audit_output.write_text(json.dumps(audit, indent=2, sort_keys=True) + "\n")
    print(
        f"rendered {len(districts)} districts / {len(districts) * len(ZONES)} atlases "
        f"to {relative(repo_root, output)} ({width}x{height})"
    )
    print(f"audit: {relative(repo_root, audit_output)}")


def main() -> None:
    default_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=default_root)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("provenance/arnis_districts/atlas_montage.png"),
    )
    parser.add_argument(
        "--audit-output",
        type=Path,
        default=Path("provenance/arnis_districts/atlas_montage.audit.json"),
    )
    args = parser.parse_args()
    root = args.repo_root.resolve()
    output = args.output if args.output.is_absolute() else root / args.output
    audit_output = (
        args.audit_output if args.audit_output.is_absolute() else root / args.audit_output
    )
    render(root, output, audit_output)


if __name__ == "__main__":
    main()
