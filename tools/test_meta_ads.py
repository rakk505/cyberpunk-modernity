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
HIGHWAY_IDS = ("vater", "gojo", "horizon", "meta_logo_2", "petrochem")
HIGHWAY_SOURCE_SHA256 = {
    "vater": "d2ece481ebd62dd45e1c90842d23644d523d08e51c3317087275278e27cfb8cf",
    "gojo": "36d3fea88b23aad8efdcbf990387f555492cdfecb887c0f96fe96a5d861d04ef",
    "horizon": "c7420259b558af7e7a1c86384e1990986f20de4c3cfad1cafc19aab8684b1bfc",
    "meta_logo_2": "32a0d9c2df0c41762351205de85148e2e1c489eee6d50006c33a73a488e276e1",
    "petrochem": "4ffb04f95bdea2e3537bdb8dd648a2a77595a449f57819600098cc3dc582af0a",
}
SHEET_COUNTS = {
    "meta_logo": 15,
    "meta_glasses": 15,
    "meta_ai": 23,
    "meta_future": 23,
}
META_SOURCE_SHA256 = {
    "meta_logo": "e865d609eec6d85f107f345c39da06c40e89b297cc1937d2ba67aaa3ea64cf66",
    "meta_glasses": "14125e81121180a7c42b540eabc813d3ecf63f22d1e0860119fe323ac5d87911",
    "meta_ai": "4f7d6ab6c866d5e2023397a05f9c0d06077f67dcd47fda8add7fbb9f0070ecf2",
    "meta_future": "3c25d6ecbd46567b8a25f6ae412bbe87b930c7a6c0f5049bc483df698a93a3b8",
}


def hashes(root: Path) -> dict[str, str]:
    # as_posix keeps the "<clip>/sheet_nnn.png" keys stable on Windows checkouts too.
    return {
        path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("sheet_*.png"))
    }


def png_dimensions(path: Path) -> tuple[int, int]:
    header = path.read_bytes()[:24]
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise AssertionError(f"not a PNG with an IHDR header: {path}")
    return struct.unpack(">II", header[16:24])


class MetaAdGeneratorTests(unittest.TestCase):
    def test_meta_video_sheets_are_complete_with_expected_dimensions(self) -> None:
        checked_in = RESOURCES / "textures/ads"
        expected_files = sum(SHEET_COUNTS.values())
        checked_sheets = {
            name
            for name in hashes(checked_in)
            if name.split("/", 1)[0] in META_IDS
        }
        self.assertEqual(expected_files, len(checked_sheets))

        for clip_id, sheet_count in SHEET_COUNTS.items():
            sheets = sorted((checked_in / clip_id).glob("sheet_*.png"))
            self.assertEqual(sheet_count, len(sheets), clip_id)
            self.assertTrue(all(png_dimensions(sheet) == (640, 360) for sheet in sheets))

    def test_catalog_manifest_and_audio_are_consistent(self) -> None:
        catalog = json.loads((REPOSITORY / "ads/catalog.json").read_text())
        manifest = json.loads((RESOURCES / "ads/manifest.json").read_text())
        catalog_by_id = {clip["id"]: clip for clip in catalog["clips"]}
        manifest_by_id = {clip["id"]: clip for clip in manifest["clips"]}

        self.assertEqual(3_744, process_ads.MAX_TOTAL_FRAMES)
        self.assertEqual(235, process_ads.MAX_TOTAL_SHEETS)
        self.assertEqual(3_744, manifest["total_frames"])
        self.assertEqual(235, manifest["total_sheets"])
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
            self.assertNotIn("generator", catalog_clip)
            self.assertNotIn("generator", manifest_clip)
            self.assertEqual(f"{clip_id}.mp4", catalog_clip["file"])
            self.assertFalse(catalog_clip["audio"])
            self.assertFalse(manifest_clip["audio"])
            self.assertEqual(8, manifest_clip["fps"])
            self.assertEqual([160, 90], manifest_clip["frame_size"])
            self.assertEqual([4, 4], manifest_clip["sheet_grid"])
            self.assertEqual(SHEET_COUNTS[clip_id], manifest_clip["sheet_count"])
            source = REPOSITORY / "ads" / catalog_clip["file"]
            self.assertTrue(source.is_file())
            self.assertEqual(META_SOURCE_SHA256[clip_id], catalog_clip["source_sha256"])
            self.assertEqual(
                catalog_clip["source_sha256"],
                hashlib.sha256(source.read_bytes()).hexdigest(),
            )

        for clip_id in SUPPLIED_IDS:
            catalog_clip = catalog_by_id[clip_id]
            manifest_clip = manifest_by_id[clip_id]
            self.assertEqual(8, catalog_clip["fps"])
            self.assertEqual(8, manifest_clip["fps"])
            self.assertEqual(15, manifest_clip["sheet_count"])
            self.assertFalse(catalog_clip["audio"])
            self.assertTrue(catalog_clip["loop"])
            self.assertTrue((REPOSITORY / "ads" / catalog_clip["file"]).is_file())
            self.assertEqual(
                catalog_clip["source_sha256"],
                hashlib.sha256(
                    (REPOSITORY / "ads" / catalog_clip["file"]).read_bytes()
                ).hexdigest(),
            )

    def test_highway_campaign_clips_are_silent_thirty_second_loops(self) -> None:
        catalog = json.loads((REPOSITORY / "ads/catalog.json").read_text())
        manifest = json.loads((RESOURCES / "ads/manifest.json").read_text())
        catalog_by_id = {clip["id"]: clip for clip in catalog["clips"]}
        manifest_by_id = {clip["id"]: clip for clip in manifest["clips"]}

        self.assertIn("highway", process_ads.VALID_CAMPAIGNS)
        for clip_id in HIGHWAY_IDS:
            catalog_clip = catalog_by_id[clip_id]
            manifest_clip = manifest_by_id[clip_id]
            self.assertEqual(["highway"], catalog_clip["campaigns"])
            self.assertEqual(["highway"], manifest_clip["campaigns"])
            self.assertEqual(30, catalog_clip["duration_seconds"])
            self.assertEqual(8, catalog_clip["fps"])
            self.assertEqual(8, manifest_clip["fps"])
            self.assertEqual(240, manifest_clip["frame_count"])
            self.assertEqual(15, manifest_clip["sheet_count"])
            self.assertEqual([160, 90], manifest_clip["frame_size"])
            self.assertFalse(catalog_clip["audio"])
            self.assertFalse(manifest_clip["audio"])
            self.assertTrue(catalog_clip["loop"])
            self.assertEqual("black", catalog_clip["pad_color"])

            source = REPOSITORY / "ads" / catalog_clip["file"]
            self.assertTrue(source.is_file())
            self.assertEqual(HIGHWAY_SOURCE_SHA256[clip_id], catalog_clip["source_sha256"])
            self.assertEqual(
                catalog_clip["source_sha256"],
                hashlib.sha256(source.read_bytes()).hexdigest(),
            )

            sheets = sorted((RESOURCES / "textures/ads" / clip_id).glob("sheet_*.png"))
            self.assertEqual(15, len(sheets), clip_id)
            self.assertTrue(all(png_dimensions(sheet) == (640, 360) for sheet in sheets))

    def test_provenance_records_highway_campaign_sources(self) -> None:
        sources_doc = (REPOSITORY / "ASSET_SOURCES.md").read_text()
        ads_doc = (REPOSITORY / "ADS.md").read_text()
        self.assertIn("highway", ads_doc)
        for clip_id in HIGHWAY_IDS:
            self.assertIn(HIGHWAY_SOURCE_SHA256[clip_id], sources_doc)

    def test_meta_source_duration_is_read_without_ffmpeg(self) -> None:
        catalog = json.loads((REPOSITORY / "ads/catalog.json").read_text())
        for clip_id in META_IDS:
            clip = next(entry for entry in catalog["clips"] if entry["id"] == clip_id)
            source = REPOSITORY / "ads" / clip["file"]
            duration = process_ads.read_mp4_duration(source)
            self.assertAlmostEqual(clip["duration_seconds"], duration, delta=0.25)

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

    def test_provenance_records_restored_meta_video_sources(self) -> None:
        ads_doc = (REPOSITORY / "ADS.md").read_text()
        sources_doc = (REPOSITORY / "ASSET_SOURCES.md").read_text()
        self.assertIn("real Meta advertisement videos", ads_doc)
        self.assertIn("project owner", sources_doc)
        for clip_id in META_IDS:
            self.assertTrue((REPOSITORY / f"ads/{clip_id}.mp4").is_file())
            self.assertIn(META_SOURCE_SHA256[clip_id], sources_doc)


if __name__ == "__main__":
    unittest.main()
