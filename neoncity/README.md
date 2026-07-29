# Project Moon Megacity Generator

A finite, world-seeded cyberpunk megacity generator for Minecraft 26.2 and NeoForge. Each save lays out one enormous city of 26 irregular A–Z Corp districts, surrounded by normal Minecraft wilderness. Districts have distinct architectural palettes and street scales, dense premium Nests, compressed Backstreets, sparse outskirts, and a connected network of roads, bridges, and elevated rail.

The mod is inspired by the district structure of Project Moon's City and by the scale and visual variety of Night City. It does **not** bundle maps, buildings, textures, characters, logos, or other assets from either franchise. Runtime construction combines original procedural code, Minecraft's built-in blocks, and explicitly licensed, provenance-audited Arnis patches.

## World layout

The city is a finite graph rather than an infinite urban tiling:

- Exactly one blob-shaped node is created for every district from A Corp through Z Corp. A Corp remains at the origin; the world seed changes the placement and identity of the other districts, their connection graph, parcels, and architecture.
- District ellipses are rotated and rippled into irregular borders. The overall footprint has a nominal radius of about 4,900 blocks (roughly a 9,800-block diameter), although individual lobes and connections vary by seed.
- A connected spanning tree guarantees that every district is reachable. Nearest-neighbor chords add loops and alternate routes; curved graph edges become grand roads, bridges, scenic routes, or elevated rail.
- Closely competing district borders become rivers or raised green hills unless a graph connection crosses them. These barriers make travel between districts feel regional instead of like crossing a city block.
- Land outside every district and connection is left as the preset's vanilla-noise Overworld. City chunks are the only chunks queued for the stamping pass.

Each district tapers through three gameplay zones:

| Zone | Urban character |
| --- | --- |
| **Nest** | Dense, expensive core with the tallest skyline, central plaza, and monumental boulevards |
| **Backstreets** | Lower and slightly less dense fabric with narrow local streets and connected 2–4-block service alleys |
| **Outskirts** | Sparse, short buildings, parks and trees transitioning toward wilderness |

Roads are generated in district-local coordinates. Curved spokes, two uneven orbital boulevards, independently rotated parcel grids, coordinate warping, parks, and graph-scale connections keep the whole map from resolving into one global checkerboard. Every 96×96-block sector also contains a deterministic depth-first-search alley network with matching portals across sector and chunk boundaries.

## District cultures

Every Corp has a distinct parcel grain, density, height range, façade rhythm, Minecraft palette, vegetation rate, and architectural massing rule.

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
| **S Corp** | Busan-like urban core, low Joseon-inspired outskirts, and broad wheat fields |
| **T Corp** | Victorian London steamworks in brick, tuff, iron, and weathered copper |
| **U Corp** | Container-port district with sinuous quays, water, docks, and cargo stacks |
| **V Corp** | Swiss-inspired blocks cut by two families of curving canals |
| **W Corp** | Shenzhen future skyline with tall, stepped glass towers |
| **X Corp** | Hanoi-inspired industrial fabric and extraction rigs in the outskirts |
| **Y Corp** | Nordic–Muscovite winter monumentality with snow cover, spruce, ice, and local snowflake particles |
| **Z Corp** | Tokyo electric crossroads blending Akihabara and Shibuya density |

S, U, V, and X have dedicated farm, harbor, canal, and extraction-site generators. Y receives snowy surfaces and a client-visible snowflake effect around players in the district; this is a localized treatment, not a custom biome-wide weather simulation.

## Arnis and offline city studies

Arnis is an **offline preparation and analysis tool**, not a runtime dependency. [`arnis_import.py`](tools/arnis/arnis_import.py) can convert explicitly selected chunks from an unpacked Arnis Java world into deterministic, entity-free vanilla structure NBT. A patch may cover at most 3×3 chunks. The standard-library-only importer strips entities, block-entity payloads, air, and dangerous utility blocks; then records a SHA-256, footprint, bounding box, source-region hash, explicit license/attribution, and heuristic edge-road hints in [`catalog.json`](src/main/resources/data/neoncity/arnis/catalog.json).

From the `neoncity/` directory, an import has this form:

```bash
python3 tools/arnis/arnis_import.py import /path/to/ArnisWorld \
  --district Z \
  --source-id tokyo-core \
  --source-name "Tokyo urban core" \
  --license ODbL-1.0 \
  --attribution "OpenStreetMap contributors" \
  --selection shibuya=12,8:14,10

python3 tools/arnis/arnis_import.py list
python3 tools/arnis/arnis_import.py validate
python3 -m unittest tools/arnis/test_arnis_import.py
```

Selection coordinates are inclusive chunk coordinates; see [`tools/arnis/USAGE.md`](tools/arnis/USAGE.md) for the complete interface and redistribution checklist. Source identity, license, and attribution are mandatory because the tool deliberately does not infer them.

The catalog currently contains one real Arnis 3.0.0 patch: a 16×162×16 Shinjuku study assigned to Z Corp, with 2,446 blocks, 14 palette states, two road connectors, and complete ODbL attribution and hashes. The runtime selects it deterministically in compatible Z Corp chunks, aligns its source street level with the city deck, clears conflicting procedural massing, and extends its west/east connectors into neighbouring streets. The importer does not support LZ4-compressed Anvil chunks, block-entity payloads such as signs or inventories, rotation, automatic terrain blending, or biome translation, and its road-connection hints require human review.

Separately, the repository contains reproducible Tokyo/Shinjuku, Seoul/Gangnam, and Shanghai/Lujiazui OSM studies plus `tools/compile_cultural_profiles.py`. Their distributions are recorded in [`cultural_profiles.json`](src/main/resources/data/neoncity/cultural_profiles.json) and `provenance/`. They inform baked procedural parameters; changing the JSON alone does not retune Java generation. Source Arnis worlds remain external and must be obtained or generated under appropriate terms.

The saved OSM extracts contain OpenStreetMap data, © OpenStreetMap contributors, made available under the [Open Database License](https://www.openstreetmap.org/copyright).

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
/neoncity generate <chunkX> <chunkZ> <radius>
```

Queues the city chunks in an inclusive square around the supplied **chunk** coordinates, with `radius` from 0 through 12. For example, `/neoncity generate 0 0 3` examines a 7×7 area. Wilderness-only and already generated/queued chunks are skipped. The command does not force-load terrain; a queued city chunk is stamped only after Minecraft loads it.

## Persistence and performance

- Player positions are sampled every 10 server ticks, and city chunks within a 15×15 window (radius 7) are queued.
- The transient queue is capped at 768 chunks. Recent exploration can displace stale unloaded requests.
- Normal background work stamps at most one already-loaded city chunk per server tick. Wilderness columns are never stamped.
- The initial 3×3 spawn prewarm is synchronous. First startup can pause, and a very tall or dense city chunk can still cause a visible tick spike because block placement runs on the server thread.
- Buildings are hollow architectural shells with five-block floor spacing, not solid volumes. This reduces placement cost but does not make generation free.
- Completed chunks are recorded in Overworld `SavedData`; the queue is transient and rebuilt around players after restart. An interrupted, uncommitted chunk is deterministically replayed.
- The ledger stores a generator fingerprint. A layout-changing update disables city generation in that save instead of silently overwriting player construction. There is currently no migration or regeneration command.

Back up saves before changing mod versions or testing large manual queues. Initial exploration across the full roughly 10,000-block city is a large server-thread workload and has not been presented as a pre-generated, production-soak-tested world download.

## Build and test

Requirements: Java 25 and the included Gradle wrapper. The project pins Minecraft 26.2, NeoForge 26.2.0.7-beta, and Gradle 9.2.1.

From the `neoncity/` directory:

```bash
# Fast compile and resource validation
./gradlew --no-daemon compileJava processResources

# Build build/libs/neoncity-2.0.0.jar
./gradlew --no-daemon build

# Run the registered pure NeoForge GameTests
./gradlew --no-daemon runGameTestServer

# Launch a development client for fresh-world validation
./gradlew --no-daemon runClient
```

The regression suite exercises the 26-culture seeded layout, connected graph and alternate routes, finite wilderness boundary, Nest/Backstreets/outskirts coverage, roads/bridges/rail, border rivers and hills, special S/U/V/X/Y behavior, skyline tapering, deterministic negative coordinates, and connected cross-sector service alleys.

## Current limitations

- **Post-load stamping:** this is deterministic server-tick construction over a vanilla noise Overworld, not a custom terrain `ChunkGenerator`. A newly loaded city chunk can briefly show natural terrain before the city pass finishes.
- **Procedural shells:** buildings have district-specific massing, façades, floor plates, roof tiers, and lights, but no authored interiors, doors, utilities, NPCs, traffic, or functional trains.
- **Local parcel grids remain:** the global plan is organic, but individual districts still use warped, rotated parcel grids beneath their curved boulevards and irregular borders.
- **Mob ban is city-scoped:** spawning and world-join attempts inside the city are rejected; vanilla wilderness outside it retains normal ecology. A mob created outside the footprint is not currently culled merely for later walking across the border.
- **Curated Arnis allowlist:** runtime never invokes Arnis and only places patches represented in the audited catalog and the compact Java allowlist. The first Z Corp patch is integrated; broader A–Y coverage, rotation, and richer terrain blending require additional reviewed imports.
- **No save migration:** a fingerprint change requires a new world until an explicit migration tool exists.
- **Legacy previews:** images and v1 audit artifacts under `deliverable/` describe the previous six-district generator and should not be treated as verification of this finite v2 layout.

## License

The mod is currently marked **All Rights Reserved**. The NeoForge template terms are retained in [`TEMPLATE_LICENSE.txt`](TEMPLATE_LICENSE.txt).
