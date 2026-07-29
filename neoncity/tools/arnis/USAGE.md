# Offline Arnis patch importer

`arnis_import.py` converts selected chunks from an unpacked Arnis 3.0.0 Java
world into deterministic, entity-free vanilla structure NBT patches. It uses
only Python 3's standard library and never contacts Arnis or a map provider.

Run from the `neoncity` directory:

```bash
python3 tools/arnis/arnis_import.py import ../ArnisWorld \
  --district H \
  --source-id hong-kong-core \
  --source-name "Hong Kong urban core" \
  --source-url "https://example.invalid/source-record" \
  --source-sha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  --geo-bbox 22.2780,114.1650,22.2840,114.1740 \
  --license ODbL-1.0 \
  --license-url "https://opendatacommons.org/licenses/odbl/1-0/" \
  --attribution "OpenStreetMap contributors" \
  --selection harbor=12,8:14,10 \
  --selection hillside=18,4:19,6 \
  --surface-y 68
```

Selection coordinates are inclusive chunk coordinates. Each reviewed selection
must be at most 3x3 chunks (48x48 blocks). A multi-chunk crop is automatically
split into runtime-safe one-chunk files named `<selection>_<x>_<z>`; the runtime
recognizes those names as one coherent mosaic. Negative coordinates are
supported. The default output is
`src/main/resources/data/neoncity/arnis/structures/<district>/`, and catalog
paths are always relative.

Inspect or verify all catalog entries and their NBT payloads with:

```bash
python3 tools/arnis/arnis_import.py list
python3 tools/arnis/arnis_import.py list --json
python3 tools/arnis/arnis_import.py validate
python3 -m unittest tools/arnis/test_arnis_import.py
```

The importer strips all entities, all block-entity payloads, air, command and
structure blocks, barriers, jigsaws, and light blocks. It records what was
stripped. Boundary runs of common vanilla road materials are emitted as
low-confidence connector hints for later road stitching; they are hints, not
semantic guarantees, and should be curated for production atlases.

`--surface-y` records the source world's street/deck elevation. Runtime aligns
that Y level to the megacity deck, and connector inference ignores apparent
"roads" far above it (for example, stone on a cropped skyscraper roof). When
omitted it defaults to two blocks above the imported minimum Y; pass it
explicitly for every curated production atlas.

Limitations:

- LZ4-compressed Anvil chunks are rejected because Python's standard library
  has no LZ4 decoder. Resave those worlds with zlib compression first.
- Block-entity data is deliberately not preserved, so signs, inventories,
  banners, and command content lose their payloads.
- Imported blocks are not rotated, terrain-blended, or biome-translated. Those
  operations belong to the district assembler.
- Arnis and upstream map-data licensing are not inferred. Every import requires
  an explicit license identifier and attribution, which are copied verbatim to
  the catalog.
- A source world is not bundled. Generate or obtain it separately and ensure
  its license permits redistribution of the resulting geometry.
