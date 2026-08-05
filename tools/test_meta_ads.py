#!/usr/bin/env python3
"""Focused regression tests for the branded large-screen advertising assets."""

from __future__ import annotations

import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path

try:
    import generate_meta_ads
    import process_ads
except ModuleNotFoundError:  # Support `python -m unittest tools.test_meta_ads`.
    from tools import generate_meta_ads, process_ads


REPOSITORY = Path(__file__).resolve().parents[1]
RESOURCES = REPOSITORY / "src/main/resources/assets/cyberdeck"
META_IDS = ("meta_logo", "meta_glasses", "meta_ai", "meta_future")
SUPPLIED_IDS = ("misanthropic", "closed_ai")
SHEET_COUNTS = {
    "meta_logo": 15,
    "meta_glasses": 15,
    "meta_ai": 23,
    "meta_future": 23,
}


def hashes(root: Path) -> dict[str, str]:
    return {
        str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("sheet_*.png"))
    }


def png_dimensions(path: Path) -> tuple[int, int]:
    header = path.read_bytes()[:24]
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise AssertionError(f"not a PNG with an IHDR header: {path}")
    return struct.unpack(">II", header[16:24])


class MetaAdGeneratorTests(unittest.TestCase):
    def test_generated_assets_are_complete_deterministic_and_idempotent(self) -> None:
        checked_in = RESOURCES / "textures/ads"
        expected_files = sum(SHEET_COUNTS.values())
        checked_hashes = {
            name: digest
            for name, digest in hashes(checked_in).items()
            if name.split("/", 1)[0] in META_IDS
        }
        self.assertEqual(expected_files, len(checked_hashes))

        for clip_id, sheet_count in SHEET_COUNTS.items():
            sheets = sorted((checked_in / clip_id).glob("sheet_*.png"))
            self.assertEqual(sheet_count, len(sheets), clip_id)
            self.assertTrue(all(png_dimensions(sheet) == (640, 360) for sheet in sheets))

        with tempfile.TemporaryDirectory(prefix="meta-ad-test-") as temporary:
            generated = Path(temporary) / "ads"
            generate_meta_ads.generate_all(generated)
            first_hashes = hashes(generated)
            self.assertEqual(checked_hashes, first_hashes)
            generate_meta_ads.generate_all(generated)
            self.assertEqual(first_hashes, hashes(generated))

    def test_catalog_manifest_and_audio_are_consistent(self) -> None:
        catalog = json.loads((REPOSITORY / "ads/catalog.json").read_text())
        manifest = json.loads((RESOURCES / "ads/manifest.json").read_text())
        catalog_by_id = {clip["id"]: clip for clip in catalog["clips"]}
        manifest_by_id = {clip["id"]: clip for clip in manifest["clips"]}

        self.assertEqual(2_304, process_ads.MAX_TOTAL_FRAMES)
        self.assertEqual(146, process_ads.MAX_TOTAL_SHEETS)
        self.assertEqual(2_304, manifest["total_frames"])
        self.assertEqual(146, manifest["total_sheets"])
        self.assertEqual(set(catalog_by_id), set(manifest_by_id))

        sounds = json.loads((RESOURCES / "sounds.json").read_text())
        for clip_id, catalog_clip in catalog_by_id.items():
            manifest_clip = manifest_by_id[clip_id]
            self.assertEqual(catalog_clip["audio"], manifest_clip["audio"])
            self.assertEqual(catalog_clip["campaigns"], manifest_clip["campaigns"])
            if catalog_clip["audio"]:
                self.assertIn(f"ad.{clip_id}", sounds)
                self.assertTrue((RESOURCES / f"sounds/ads/{clip_id}.ogg").is_file())
            else:
                self.assertNotIn(f"ad.{clip_id}", sounds)
                self.assertFalse((RESOURCES / f"sounds/ads/{clip_id}.ogg").exists())

        for clip_id in META_IDS:
            catalog_clip = catalog_by_id[clip_id]
            manifest_clip = manifest_by_id[clip_id]
            self.assertEqual(process_ads.META_GENERATOR, catalog_clip["generator"])
            self.assertNotIn("file", catalog_clip)
            self.assertFalse(catalog_clip["audio"])
            self.assertFalse(manifest_clip["audio"])
            self.assertEqual(process_ads.META_GENERATOR, manifest_clip["generator"])
            self.assertEqual(8, manifest_clip["fps"])
            self.assertEqual([160, 90], manifest_clip["frame_size"])
            self.assertEqual([4, 4], manifest_clip["sheet_grid"])
            self.assertEqual(SHEET_COUNTS[clip_id], manifest_clip["sheet_count"])

        for clip_id in SUPPLIED_IDS:
            catalog_clip = catalog_by_id[clip_id]
            manifest_clip = manifest_by_id[clip_id]
            self.assertEqual(4, catalog_clip["fps"])
            self.assertEqual(4, manifest_clip["fps"])
            self.assertEqual(8, manifest_clip["sheet_count"])
            self.assertFalse(catalog_clip["audio"])
            self.assertTrue(catalog_clip["loop"])
            self.assertTrue((REPOSITORY / "ads" / catalog_clip["file"]).is_file())
            self.assertEqual(
                catalog_clip["source_sha256"],
                hashlib.sha256(
                    (REPOSITORY / "ads" / catalog_clip["file"]).read_bytes()
                ).hexdigest(),
            )

    def test_processor_generates_procedural_clip_without_ffmpeg_or_audio(self) -> None:
        catalog = json.loads((REPOSITORY / "ads/catalog.json").read_text())
        clip = next(entry for entry in catalog["clips"] if entry["id"] == "meta_logo")
        with tempfile.TemporaryDirectory(prefix="meta-process-test-") as temporary:
            output = Path(temporary)
            manifest = process_ads.process_clip(
                clip,
                REPOSITORY / "ads",
                output,
                "/missing/ffmpeg",
            )
            self.assertFalse(manifest["audio"])
            self.assertEqual(15, manifest["sheet_count"])
            self.assertEqual(15, len(list((output / "textures/meta_logo").glob("*.png"))))
            self.assertFalse((output / "sounds/meta_logo.ogg").exists())

    def test_atomic_install_rolls_back_all_prior_replacements(self) -> None:
        with tempfile.TemporaryDirectory(prefix="meta-install-test-") as temporary:
            root = Path(temporary)
            first_target = root / "target-one"
            second_target = root / "target-two"
            first_target.write_text("original one")
            second_target.write_text("original two")
            first_staged = root / "staged-one"
            missing_staged = root / "missing-two"
            first_staged.write_text("replacement one")

            with self.assertRaises(FileNotFoundError):
                process_ads.install_atomically([
                    (first_staged, first_target),
                    (missing_staged, second_target),
                ])
            self.assertEqual("original one", first_target.read_text())
            self.assertEqual("original two", second_target.read_text())

    def test_provenance_excludes_historical_third_party_media(self) -> None:
        ads_doc = (REPOSITORY / "ADS.md").read_text()
        sources_doc = (REPOSITORY / "ASSET_SOURCES.md").read_text()
        self.assertIn("tools/generate_meta_ads.py", ads_doc)
        self.assertIn("no historical", ads_doc.lower())
        self.assertIn("third-party Meta videos or audio are restored or distributed", ads_doc)
        self.assertIn("Historical YouTube-derived Meta advertisements", sources_doc)
        self.assertIn("redistribution rights were not established", sources_doc)
        for clip_id in META_IDS:
            self.assertFalse((REPOSITORY / f"ads/{clip_id}.mp4").exists())


if __name__ == "__main__":
    unittest.main()
