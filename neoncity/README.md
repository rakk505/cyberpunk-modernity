# Project Moon Megacity Generator

A finite, world-seeded cyberpunk megacity generator for Minecraft 26.2 and NeoForge. Each save lays out one enormous city of 26 irregular A–Z Corp districts, surrounded by normal Minecraft wilderness. Every district has a dense premium Nest and a lower-cost Backstreets band, both built primarily from that district's own provenance-audited Arnis atlas. Roads, bridges, parks, waterways, hills, and elevated rail connect the resulting patchwork without imposing one global grid.

The mod is inspired by the district structure of Project Moon's City and by the scale and visual variety of Night City. It does **not** bundle maps, buildings, textures, characters, logos, or other assets from either franchise. Runtime construction combines original procedural code, Minecraft's built-in blocks, and explicitly licensed, provenance-audited Arnis patches.

## World layout

The city is a finite graph rather than an infinite urban tiling:

- Exactly one blob-shaped node is created for every district from A Corp through Z Corp. A Corp remains at the origin; the world seed changes the placement and identity of the other districts, their connection graph, parcels, and architecture.
- District ellipses are rotated and rippled into irregular borders. The overall footprint has a nominal radius of about 4,900 blocks (roughly a 9,800-block diameter), although individual lobes and connections vary by seed.
- A connected spanning tree guarantees that every district is reachable. Nearest-neighbor chords add loops and alternate routes; curved graph edges become grand roads, bridges, scenic routes, or elevated rail.
- Closely competing district borders become rivers or raised green hills unless a graph connection crosses them. These barriers make travel between districts feel regional instead of like crossing a city block.
- Land outside every district and connection is left as the preset's vanilla-noise Overworld. City chunks are the only chunks queued for the stamping pass.

Each district has two generated urban zones:

| Zone | Urban character |
| --- | --- |
| **Nest** | Dense, expensive core with the tallest skyline, central plaza, and monumental boulevards |
| **Backstreets** | Lower and slightly less dense fabric with narrow local streets and connected 2–4-block service alleys |

There is no third Arnis “Outskirts” zone. Leaving the finite city footprint reaches ordinary vanilla wilderness. Hills, rivers, green space, and sparse connection corridors soften district and city boundaries without extending urban generation forever.

Crossing into a district displays a centered **“Now Entering District A–Z”** title once. Remaining inside the same district does not repeat it; entering another district or returning from wilderness displays the new title.

The exact 2.2.1 JAR and a Modernity client-rendered [district-entry capture](deliverable/v2.2.1/district_entry_title_a.png) are accompanied by a [machine-readable verification record](deliverable/verification-v2.2.1.json).

Roads are generated in district-local coordinates. Curved spokes, two uneven orbital boulevards, independently rotated parcel grids, coordinate warping, parks, and graph-scale connections keep the whole map from resolving into one global checkerboard. Every 96×96-block sector also contains a deterministic depth-first-search alley network with matching portals across sector and chunk boundaries.

## District cultures

Every Corp has a distinct combined culture signature. Architecture, palette, street grammar, roof silhouette, parcel grain, density, height, vegetation, and tree style are deliberately recombined; some individual parameters are shared where the real-world references call for similar urban fabric. The shared rings and graph roads make the city legible, while the culture layer adds district-specific campus loops, greenways, radial avenues, superblocks, merchant lanes, industrial spines, canals, prospects, or diagonal crossings. The result is mechanically different urban biomes rather than one building generator with 26 paint schemes.

| District | Procedural direction |
| --- | --- |
| **A Corp** | Monumental plazas and tall, sleek black corporate towers |
| **B Corp** | Bay Area garden campuses influenced by Palo Alto and San Francisco parks |
| **C Corp** | Portland-inspired brick and timber blocks |
| **D Corp** | Seattle–Bellevue glass, light concrete, and abundant evergreens |
| **E Corp** | Mexico City-inspired mid-rise courtyards and warm masonry |
| **F Corp** | Miami tropical art deco in bright pastel palettes |
| **G Corp** | Dense Jakarta-inspired tropical vertical center |
| **H Corp** | Hong Kong-inspired hyper-density, tight parcels, and tall towers |
| **I Corp** | Roman and Italian stone terraces with classical details |
| **J Corp** | Macau–Las Vegas spectacle, casino palettes, and gold accents |
| **K Corp** | Clean white and gray research arcologies |
| **L Corp** | Seoul metropolitan glass towers and neon accents |
| **M Corp** | Toronto-inspired metropolitan slabs and mixed skyline |
| **N Corp** | Parisian boulevards and pale Haussmann-style blocks |
| **O Corp** | Viennese grand blocks, stone, and aged copper roofs |
| **P Corp** | Dense New York art deco towers with stepped crowns |
| **Q Corp** | Nagoya manufacturing metro |
| **R Corp** | Osaka neon mercantile blocks |
| **S Corp** | Busan-like Nest, low Joseon-inspired Backstreets, and broad wheat corridors near its edge |
| **T Corp** | Victorian London steamworks in brick, tuff, iron, and weathered copper |
| **U Corp** | Container-port district with sinuous quays, water, docks, and cargo stacks |
| **V Corp** | Swiss-inspired blocks cut by two families of curving canals |
| **W Corp** | Shenzhen future skyline with tall, stepped glass towers |
| **X Corp** | Hanoi-inspired shop-house fabric with extraction sites along the district boundary |
| **Y Corp** | Nordic–Muscovite winter monumentality with snow cover, spruce, ice, and local snowflake particles |
| **Z Corp** | Tokyo electric crossroads blending Akihabara and Shibuya density |

S, U, V, and X have dedicated farm, harbor, canal, and extraction-site generators. Y receives snowy surfaces and a client-visible snowflake effect around players in the district; this is a localized treatment, not a custom biome-wide weather simulation.

## Arnis district atlases

Arnis is an **offline preparation tool**, not a runtime dependency. Version 2.2 regenerates an OSM-only Arnis 3.0.0 source world for every Corp from A through Z. The selected places include Canary Wharf, Palo Alto, Portland, Bellevue, Mexico City, Miami Beach, Jakarta, Hong Kong, Rome, Macau, Kendall Square, Seoul, Toronto, Paris, Vienna, Manhattan, Nagoya, Osaka, Busan, King's Cross, Rotterdam, Zurich, Shenzhen, Hanoi, Stockholm, and Tokyo.

Each district contributes two coherent 8×8-chunk source atlases:

- one **Nest** atlas selected for dense, tall, road-connected fabric;
- one **Backstreets** atlas selected for lower-scale secondary fabric.

That is **26 districts × 2 zones × 64 chunks = 3,328 one-chunk structure templates across 52 coherent atlases**. Neighboring source chunks stay neighboring after placement, including when the whole atlas is deterministically reflected. Arnis geometry is the normal developed-district fabric, not a rare decorative insert. Procedural generation is retained for graph roads, bridges, rail, border rivers and hills, parks, special farm/harbor/canal/extraction infrastructure, and the transition to wilderness.

The runtime loads [`catalog.json`](src/main/resources/data/neoncity/arnis_districts/catalog.json), maps destination chunks through the appropriate district-and-zone atlas, aligns the recorded source street level to the city deck, and applies the district's material treatment without replacing the source footprints or road topology. A/H/N/P and the other Corps therefore do not share one procedural tower shell merely painted different colors.

The complete pipeline is reproducible from the repository:

```bash
# Generate all 26 OSM-only source worlds and their hash records.
python3 tools/arnis/generate_district_worlds.py

# Score source chunks, select the two 8x8 atlases, and import 3,328 NBTs.
python3 tools/arnis/build_district_atlases.py --reset-output

# Validate the importer and final catalog.
python3 -m unittest tools/arnis/test_arnis_import.py
python3 tools/arnis/arnis_import.py validate

# Rebuild the labeled, hash-audited A-Z source-selection montage.
python3 tools/arnis/render_district_atlas_montage.py
```

[`manifest.json`](provenance/arnis_districts/manifest.json) records each district's source place, culture intent, geographic bounding box, Arnis binary hash, and generation settings. Per-district `generation.json` records preserve the OSM, world metadata, preview, `level.dat`, and Anvil region hashes; `selection.json` records the exact inclusive chunk coordinates and measured selection scores. Source identity, license, and attribution are mandatory.

The labeled [A–Z atlas montage](provenance/arnis_districts/atlas_montage.png) is cropped directly from those hash-checked Arnis previews. Its [machine-readable audit](provenance/arnis_districts/atlas_montage.audit.json) records every pixel rectangle and source digest. It is selection evidence, not a Minecraft client screenshot.

Fresh Modernity client captures from the exact `2.2.0` JAR show the runtime result in [A Corp](deliverable/v2.2/a_corp_arnis_runtime.png), [H Corp](deliverable/v2.2/h_corp_arnis_runtime.png), [N Corp](deliverable/v2.2/n_corp_arnis_runtime.png), and [Z Corp](deliverable/v2.2/z_corp_arnis_runtime.png), plus the [vanilla wilderness beyond the city](deliverable/v2.2/exterior_wilderness_runtime.png). Their coordinates, artifact hash, image hashes, GameTest result, and runtime tour are recorded in [`verification-v2.2.json`](deliverable/verification-v2.2.json).

The importer is standard-library-only. It strips entities, block-entity payloads, air, and dangerous utility blocks, then writes deterministic vanilla structure NBT with SHA-256 catalog records and edge-road hints. It does not support LZ4-compressed Anvil chunks, signs or inventories, automatic terrain blending, or biome translation; inferred connector hints still require review.

The saved OSM extracts contain OpenStreetMap data, © OpenStreetMap contributors, made available under the [Open Database License](https://www.openstreetmap.org/copyright). Source Arnis worlds are reproducible local build artifacts and are not bundled in the mod JAR.

## Strict mob-spawn ban

No Minecraft `Mob` may spawn or join the world from a position inside the finite city footprint. The mod rejects natural spawn placement and also cancels city-side joins attempted through spawners, commands, other mods, or saved entity data. Players and non-mob entities are unaffected. Normal spawning resumes in the vanilla wilderness outside the city.

This is deliberately stricter than setting a peaceful difficulty or changing biome spawn lists. It also means the generated city currently has no NPC population; “living” refers to its varied urban form, infrastructure, parks, waterfronts, and traversal space rather than simulated citizens.

## Create a world

Generation is opt-in and activates only when the Overworld uses the dedicated `neoncity:megacity` preset. The preset uses the normal `minecraft:overworld` noise generator and biomes with a custom `neoncity:megacity_overworld` dimension-type marker. The Nether and End retain their vanilla generators.

1. Install the built JAR in a Minecraft 26.2 + NeoForge 26.2.0.7-beta instance.
2. Choose **Create New World**, open the world settings, and select **Project Moon Megacity** (`neoncity:megacity`) before creating the save.
3. Enter the world and run `/neoncity status` to confirm `enabled=true`.

Use a fresh save. Adding the mod to an existing world does not convert it, and selecting an unrelated world type does not activate city stamping. At first server start the mod synchronously prepares the 3×3-chunk spawn window and moves spawn to `(0, 74, 0)`, inside A Corp.

## Commands

All commands require game-master/operator permission, or cheats in single-player.

```text
/neoncity status
```

Reports whether generation is enabled; the district and graph-edge counts; the mixed layout seed; the persistent generated-chunk count; the transient queue size; and the generator fingerprint. `enabled=false` normally means the Overworld does not use the dedicated preset marker or the save ledger was created by an incompatible generator fingerprint.

```text
/neoncity locate <x> <z>
```

Samples block coordinates without generating or loading terrain. It reports the owning district, zone, infrastructure class, district-node center, and normalized distance from that node. Wilderness points still report their nearest district for orientation while showing `zone=WILDERNESS` and `infrastructure=WILDERNESS`.

```text
/neoncity atlas <A-Z>
```

Reports a deterministic Arnis tile for the requested district, including its zone, catalog ID, source tile, transform, and destination origin. It succeeds for every district A–Z. This is an operator diagnostic; it does not load or generate the reported chunk.

```text
/neoncity generate <chunkX> <chunkZ> <radius>
```

Queues the city chunks in an inclusive square around the supplied **chunk** coordinates, with `radius` from 0 through 12. For example, `/neoncity generate 0 0 3` examines a 7×7 area. Wilderness-only and already generated/queued chunks are skipped. The command does not force-load terrain; a queued city chunk is stamped only after Minecraft loads it.

## Persistence and performance

- Player positions are sampled every 10 server ticks, and city chunks within a 15×15 window (radius 7) are queued.
- The transient queue is capped at 768 chunks. Recent exploration can displace stale unloaded requests.
- Normal background work stamps at most one already-loaded city chunk per server tick. Wilderness columns are never stamped.
- The initial 3×3 spawn prewarm is synchronous. First startup can pause, and a very tall or dense city chunk can still cause a visible tick spike because block placement runs on the server thread.
- Most imported and fallback buildings are architectural shells rather than authored interiors. This reduces placement cost but does not make generation free.
- Completed chunks are recorded in Overworld `SavedData`; the queue is transient and rebuilt around players after restart. An interrupted, uncommitted chunk is deterministically replayed.
- The ledger stores a generator fingerprint. A layout-changing update disables city generation in that save instead of silently overwriting player construction. There is currently no migration or regeneration command.

Back up saves before changing mod versions or testing large manual queues. Initial exploration across the full roughly 10,000-block city is a large server-thread workload and has not been presented as a pre-generated, production-soak-tested world download.

## Build and test

Requirements: Java 25 and the included Gradle wrapper. The project pins Minecraft 26.2, NeoForge 26.2.0.7-beta, and Gradle 9.2.1.

From the `neoncity/` directory:

```bash
# Fast compile and resource processing
./gradlew --no-daemon compileJava processResources

# Build build/libs/neoncity-2.2.1.jar
./gradlew --no-daemon build

# Run the registered pure NeoForge GameTests
./gradlew --no-daemon runGameTestServer

# Launch a development client for fresh-world validation
./gradlew --no-daemon runClient
```

The regression suite exercises the 26 unique combined culture signatures, seeded layout, connected graph and alternate routes, finite wilderness boundary, sampled urban-zone coverage, roads/bridges/rail, border rivers and hills, special S/U/V/X culture contracts, Y's winter contract, skyline tapering, deterministic negative coordinates, connected cross-sector service alleys, and all 52 deterministic 8×8 Arnis atlases. Compile/GameTest success does not by itself prove client visuals, every placed decorative block, or long multiplayer soak; those remain separate runtime and visual checks.

## Current limitations

- **Post-load stamping:** this is deterministic server-tick construction over a vanilla noise Overworld, not a custom terrain `ChunkGenerator`. A newly loaded city chunk can briefly show natural terrain before the city pass finishes.
- **No authored interiors:** imported geometry and procedural infrastructure provide exterior urban form, not complete interiors, utilities, NPCs, traffic, or functional trains.
- **Source repetition:** each urban zone maps through one coherent 8×8 source atlas. Seeded reflections and district material treatments vary its presentation, but repeated traversal within one zone will eventually reveal the source atlas period.
- **Mob ban is city-scoped:** spawning and world-join attempts inside the city are rejected; vanilla wilderness outside it retains normal ecology. A mob created outside the footprint is not currently culled merely for later walking across the border.
- **Offline Arnis:** runtime never invokes Arnis and only places templates represented in the audited A–Z catalog. Source-world updates require rebuilding and reviewing the catalog; richer terrain blending remains future work.
- **No save migration:** a fingerprint change requires a new world until an explicit migration tool exists.
- **Legacy previews:** images and v1 audit artifacts under `deliverable/` describe the previous six-district generator. Use the v2.2 atlas montage and fresh runtime captures for this release.

## License

The mod is currently marked **All Rights Reserved**. The NeoForge template terms are retained in [`TEMPLATE_LICENSE.txt`](TEMPLATE_LICENSE.txt).
