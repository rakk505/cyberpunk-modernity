#!/usr/bin/env python3
"""Standard-library regression tests for the offline Arnis importer."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest


MODULE_PATH = Path(__file__).with_name("arnis_import.py")
SPEC = importlib.util.spec_from_file_location("arnis_import", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class ImporterTests(unittest.TestCase):
    def test_selection_parsing_and_limits(self) -> None:
        value = MODULE.parse_selection("plaza=-2,4:0,6")
        self.assertEqual((value.chunks_x, value.chunks_z), (3, 3))
        self.assertEqual((value.size_x, value.size_z), (48, 48))
        with self.assertRaises(Exception):
            MODULE.parse_selection("too_big=0,0:3,0")

    def test_structure_is_deterministic_and_safe(self) -> None:
        stone = MODULE.State("minecraft:stone", ())
        road = MODULE.State("minecraft:black_concrete", ())
        selection = MODULE.Selection("fixture", 0, 0, 0, 0)
        blocks = [
            (x, 0, 0, road) for x in range(3)
        ] + [(0, 1, 1, stone)]
        patch = MODULE.Patch(
            selection=selection,
            source_versions={4189},
            palette=sorted({stone, road}),
            blocks=sorted(blocks, key=lambda item: (item[1], item[2], item[0])),
            min_source_y=64,
            max_source_y=65,
            stripped_dangerous=MODULE.Counter(),
            stripped_block_entities=MODULE.Counter(),
            missing_chunks=[],
            top_surface={(x, 0): (64, road.name) for x in range(3)},
        )
        first = MODULE.structure_bytes(patch, 4189)
        second = MODULE.structure_bytes(patch, 4189)
        self.assertEqual(first, second)
        summary = MODULE.validate_structure(first)
        self.assertEqual(summary["blocks"], 4)
        self.assertEqual(summary["size"], [16, 2, 16])
        connectors = MODULE.road_connectors(patch)
        self.assertEqual(connectors[0]["edge"], "north")
        self.assertEqual(connectors[0]["width"], 3)


if __name__ == "__main__":
    unittest.main()
