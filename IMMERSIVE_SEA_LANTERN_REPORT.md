# Immersive Arnis Sea-Lantern Lighting Report

Date: 2026-08-04

Feature branch: `immersive-sea-lanterns`

Implementation commit: `4482e8dd` (`feat: add immersive Arnis floor lighting`)

Focused report commit: `27e34942` (`docs: report immersive Arnis lighting`)

Current-main integration merges: `6b9ae44b`, `6fed34f6`

## Requested Behavior

- Replace glowstone imported from Arnis buildings with sea-lantern lighting.
- If the light has a block above it, use an ordinary sea lantern because its top face is hidden.
- If its top is exposed, make the top match the surrounding floor while retaining sea-lantern
  sides, bottom, light level, collision, and durability behavior.
- Perform material selection during city import/generation rather than through a per-tick or
  per-frame neighbor lookup.

## Implemented Behavior

### Covered lights

Imported glowstone with any occupied block immediately above becomes
`minecraft:sea_lantern`.

### Exposed floor lights

Exposed imported glowstone becomes `cyberdeck:camouflaged_sea_lantern`. Its `surface` block-state
property is selected by a deterministic cardinal-neighbor majority vote at the same height.

The ten supported floor finishes are:

1. Blackstone
2. Gray concrete
3. Light-gray concrete
4. Mud bricks
5. Nether bricks
6. Oak planks
7. Polished andesite
8. Smooth stone
9. Stone bricks
10. White concrete

Matching slab, stair, cracked, and chiseled variants vote for the corresponding full-block top
texture. Transparent blocks, unsupported materials, and positions without a usable neighbor fall
back to an ordinary sea lantern.

Ties are resolved by a stable finish order. Runtime selection is bounded to the current 16x16
Arnis tile, so adjacent chunk generation order cannot alter the chosen texture or force-load a
neighboring chunk.

## Content And Registration

- `CamouflagedSeaLanternBlock` defines the ten-value `surface` property.
- `ArnisLightingBlocks` registers one generated-city block with a full copy of vanilla sea-lantern
  behavior.
- The block intentionally has no creative-tab item and no block entity.
- One blockstate file maps the ten states to ten ordinary baked models.
- Models reuse vanilla Minecraft textures directly. No duplicate PNG textures were added.
- The top face uses the selected floor texture; the sides and bottom use the vanilla sea-lantern
  texture.
- Light emission remains block-state light level 15 through the copied sea-lantern properties.

## Arnis Integration

### New imports

`tools/arnis/arnis_import.py` now normalizes embedded lighting before building the canonical
structure palette. Future Arnis imports therefore contain sea lanterns or camouflaged sea lanterns
directly and need no runtime conversion.

### Existing checked-in templates

The active archive already contains 17,920 compressed, chunk-sized NBT templates. Replacing every
affected binary in this feature commit would create about 86 MB of new Git blobs across 10,858
files. Instead, ungenerated city chunks use a bounded one-time placement pass:

- `ArnisColumnMaskProcessor` records glowstone positions that actually survive the district mask.
- After successful template placement, only those retained positions are inspected.
- Covered and exposed replacements are written before the generated chunk is sent to clients.
- The pass is server-owned, deterministic, and runs once when a city chunk is first built.

It does not use a tick handler, dynamic client model, block entity, renderer callback, or shared
mutable player state.

## Offline Migration Script

`tools/arnis/rewrite_embedded_lighting.py` supports three modes:

```bash
# Read-only inventory and deterministic rewrite validation
python3 tools/arnis/rewrite_embedded_lighting.py

# Rewrite all affected NBTs and their metadata
python3 tools/arnis/rewrite_embedded_lighting.py --apply

# CI check: fail when an archive still contains glowstone
python3 tools/arnis/rewrite_embedded_lighting.py --check
```

`--apply` performs structured NBT migration rather than byte replacement. It:

- Parses palette entries and block-position/state references.
- Classifies every glowstone using its local neighbors.
- Rebuilds canonical palettes and block indices.
- Preserves structure size, block count, data version, ordering, and empty entity list.
- Updates each catalog entry's SHA-256, compressed byte count, and palette size.
- Validates every rewritten structure and the complete catalog.
- Rebuilds the catalog-bound `open_park_tiles.json` audit.
- Stages every output before mutation and writes a resumable transaction journal.
- Detects unexpected structure or catalog edits rather than overwriting them.

## Why A Find And Replace Is Insufficient

A literal replacement can change every palette occurrence of `minecraft:glowstone` to
`minecraft:sea_lantern`, but it cannot implement the requested floor camouflage:

- NBT files are compressed binary data, not plain-text block lists.
- One glowstone palette entry can be referenced by many positions with different surrounding
  floors. Those positions must be split into different block states.
- The correct top material depends on neighboring blocks at each position.
- Adding the custom states changes palette ordering and every affected block's palette index.
- Any modified file requires new catalog SHA-256, compressed-size, and palette-size metadata.
- The open-park audit embeds the catalog hash and must be regenerated.
- Blind byte replacement risks corrupting compressed NBT or leaving runtime integrity checks stale.

The migration script is effectively the safe, structure-aware equivalent of find-and-replace. It
can rewrite the existing template archive with `--apply`; the branch does not commit those 10,858
binary deltas because the one-time placement fallback provides the behavior with much smaller
source-control and review cost.

## Inventory Results

The complete read-only archive audit reported:

| Metric | Count |
| --- | ---: |
| Active Arnis templates | 17,920 |
| Templates containing glowstone | 10,858 |
| Total imported glowstone | 1,300,095 |
| Covered lights | 33,332 |
| Camouflaged exposed lights | 1,265,868 |
| Unsupported/ambiguous fallbacks | 895 |
| Exposed lights resolved by camouflage | 99.93% |

## Performance

### Runtime

- Normal gameplay cost after generation: effectively the same as ordinary full-cube blocks.
- No ticking blocks, block entities, dynamic neighbor models, or per-frame work.
- Light-engine behavior matches vanilla sea lanterns.
- The legacy-template fallback examines only retained glowstone positions, averaging about 72
  candidates across all active tiles and about 120 in affected tiles.
- The cost occurs once per newly generated city chunk, not once per player or server tick forever.

### Import and migration

- New imports pay one linear neighbor-classification pass before NBT serialization.
- A full archive audit/rewrite is intentionally slower because it parses and validates 17,920
  compressed files.
- Applying the migration removes the one-time runtime conversion but creates a large binary Git
  change. This is an operational/source-control tradeoff, not a gameplay requirement.

### Multiplayer

- Selection and replacement are server-authoritative.
- There is no per-player lighting state and no cross-player mutable cache.
- Two players approaching generation borders cannot choose different finishes because voting is
  tile-bounded and deterministic.

## Existing World Limitation

The city generator records completed chunks. Already-generated chunks are not restamped, because
doing so could overwrite player construction and containers. This feature affects newly generated
or previously ungenerated chunks.

Updating glowstone already present in a save requires a separate world-edit/admin migration that
operates on world chunks. The Arnis `--apply` command migrates source templates for future chunk
generation; it does not edit an existing save.

## Verification

- Full Gradle build: passed.
- Pre-integration NeoForge GameTests: all 76 required tests passed.
- Post-current-main NeoForge GameTests: all 82 required tests passed.
- Covered, exposed, unsupported, light-level, material-majority, masked-column ownership, and
  tile-edge isolation cases are covered.
- Full 17,920-template read-only migration audit: passed.
- Pre-integration managed Minecraft client load/connect: passed.
- Day capture verified all ten top textures blend with their floor pads.
- Night capture verified the same ten states retain light emission.
- Post-integration managed Minecraft client load/connect: passed after installing current main's
  mandatory local `vehicle_mod` runtime in the isolated verification client.
- Post-integration night capture verified all ten camouflaged surfaces render and retain light
  emission. Capture SHA-256:
  `2640394c71ea429b36683d28225072f5323b2fa3f51c9ed12a634a07522c2ba5`.
- The integrated dedicated server booted successfully with both Cyberdeck and `vehicle_mod` loaded.
- Final integrated JAR SHA-256:
  `77d0606fcc797940a0d7b98310b257926693b10320a00d4bdf74face0490d5c6`.
- Required `vehicle_mod` JAR SHA-256:
  `d2f94af30268d045631c1daa67ad0538cc44886401d9d7f332e3e290af109700`.

## Current-Main Merge Resolution

Current `origin/main` was merged into the feature before publication to main. Three overlapping
combat files required additive resolution:

- Player death/logout/respawn/server-stop cleanup retains hostile netrunner state cleanup,
  Weapon Glitch cleanup, and current main's remote-entity-control cleanup.
- Gun use and charged shots retain both hostile Weapon Glitch rejection and current main's
  cyberware-based ranged-weapon lockout.
- Reload completion retains hostile Weapon Glitch cancellation and current main's Microgenerator
  arming behavior.
- Hitscan filtering retains R Corp/faction ally protection and current main's remotely controlled
  Kang Tao turret override.
- The subsequent current-main traffic update retained the Arnis lighting transformation before
  the generator's light-engine completion and client-refresh phase.
- Turret, single-canister, and chained-canister explosion tests now wait one server tick after
  inserting their stationary damage target, eliminating intermittent same-tick entity-index misses
  without changing production explosion behavior.

The complete 82-test suite passed after these resolutions.

## Files Added

- `src/main/java/dev/modernity/neoncity/ArnisEmbeddedLighting.java`
- `src/main/java/dev/modernity/neoncity/ArnisLightingBlocks.java`
- `src/main/java/dev/modernity/neoncity/CamouflagedSeaLanternBlock.java`
- `src/main/resources/assets/cyberdeck/blockstates/camouflaged_sea_lantern.json`
- `src/main/resources/assets/cyberdeck/models/block/camouflaged_sea_lantern_base.json`
- Ten surface-specific block models under `assets/cyberdeck/models/block/`
- `tools/arnis/rewrite_embedded_lighting.py`

## Files Updated

- `src/main/java/com/example/cyberdeck/Cyberdeck.java`
- `src/main/java/dev/modernity/neoncity/NeonCityGenerator.java`
- `src/main/java/dev/modernity/neoncity/ProjectMoonCityModule.java`
- `src/main/java/dev/modernity/neoncity/UrbanSystemsGameTests.java`
- `src/main/resources/assets/cyberdeck/lang/en_us.json`
- `tools/arnis/arnis_import.py`
- `SESSION_COMMIT_REPORT.md`
