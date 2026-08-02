# Cyberdeck Session Regression Ledger

Date: 2026-08-01
Repository: `rakk505/cyberpunk-modernity`
Final branch: `main`
Integration inputs: `origin/main` (`ae72a8b3`), `feature/mainline-questline`
(`161b00d8`), and `codex/npc-voicelines-lifepaths` (`4b381ce0`)
Mod version: `1.5.0`

## Scope And Attribution

This file records the behavior implemented, inspected, or verified during the
2026-08-01 Cyberdeck session. It is intended to be a regression baseline rather
than a raw diff.

The worktree was already substantially dirty when the session began, and there
was no clean checkpoint between every request. Git can show the current changes
relative to `6dc1a27`, but it cannot prove that every dirty mainline/UI/art file
was authored in this exact chat. The district, map, crate, mission-site, turret,
canister, atmosphere, test, build, and packaging changes below were directly
discussed and audited. Other co-shipped dirty-tree work is listed separately.

## Executive Summary

- The city now defines 35 districts and packages 70 Arnis atlases with 17,920
  structure tiles.
- Nine new districts have fixed edge positions and their own Nest and
  Backstreets atlases.
- The exact 12-district outer ring is Y, Yi, 王, X, Xi, Ui, U, Uang, Pak,
  Pok, Pon, and Æ. Its circular blobs overlap through normal dividers and connect
  inward into one continuous city footprint.
- City systems use canonical seed `50520260801`; five pre-analyzed mainline Arnis
  sites are bundled and persisted without a startup scan or remote chunk generation.
- Artificial tundra, land, extraction, and ocean-biome bands outside the city were
  removed. Vanilla generation owns terrain beyond the irregular perimeter.
- J Corp uses only Las Vegas sources and Q Corp now uses Fukuoka.
- D Corp has dense local fog. District Æ, Y Corp, and District Yi share winter
  weather.
- Sparse urban supply crates require a wall, clear overhead, and a sturdy floor.
  Every crate tier guarantees ammunition.
- Mission and gig planners create usable entrances, multi-floor circulation,
  furnishings, cover, guards, canisters, and Kang Tao turret placements, then
  validate the installed site with DFS.
- Completing a mission no longer immediately removes its generated site.
  Combat persists until the party is far enough away; generated geometry
  persists until the party leaves the district.
- Kang Tao turret rendering, muzzle origin, burst behavior, target filtering,
  mission spawning, and destruction explosion were corrected.
- Protocol-13 NPC voicelines and one-time Netrunner, Brawler, and Merc
  lifepaths are integrated with the mainline mission network packets.
- Emmies now use vanilla emeralds; generated crate loot no longer emits the
  hidden legacy currency item.

## Three-Branch Integration

The integration target was the current remote `main`, not the stale local
branch. The merge history is preserved:

- `444ba5c3` merges `codex/npc-voicelines-lifepaths` into `main`.
- `a9f7e7c1` merges the complete questline/megacity checkpoint from
  `feature/mainline-questline` into `main`.

Semantic merge repairs made after those merge commits:

- `CyberdeckNetwork` retains all 29 unique protocol-13 payloads, including
  `AcceptStoryMissionPacket` and the four lifepath/voiceline payloads.
- Brawlers receive shotgun ammunition and Mercs receive heavy ammunition for
  their actual starter weapons.
- The city actor compatibility listener cannot undo a mission-lifecycle actor
  rejection.
- Custom black/ammo caches and tiered supply barrels share a final placement
  phase, allow at most one generated container per chunk, require a wall and
  clear overhead, and guarantee ammunition.
- The old Cyberpunk City builder scans real building facades instead of trying
  to place caches in an open road lane.
- The lifepath overflow test uses a loaded chunk and waits for entity insertion,
  eliminating its inherited tick-zero race.

## Districts And Atlases

The new district enum values were appended after A-Z so persisted A-Z ordinals
remain unchanged. Command codes are ASCII even where display names are not.

| District | Code | Fixed position | Nest atlas | Backstreets atlas | Identity |
|---|---|---|---|---|---|
| District Æ | `AE` | North-west, left of Y | Oslo / Bjorvika | Helsinki / Punavuori | Nordic maritime waterfronts |
| District Yi | `YI` | North-east, right of Y | Moscow / New Arbat | Yekaterinburg / Uralmash | Russian brutalist infrastructure |
| District 王 | `WANG` | East-north, above X | Boston / Newbury Street | Boston / Fort Point | Historic and industrial Boston |
| District Xi | `XI` | East-south, below X | Bangkok / Ratchaprasong | Bangkok / Yaowarat | Bangkok mixed-use density |
| District Ui | `UI` | South-east, right of U | Singapore / Raffles Place | Singapore / Tiong Bahru | Singapore biophilic superblocks |
| District Uang | `UANG` | South-west, left of U | Amsterdam / Nine Streets | Amsterdam / De Pijp | Canal houses and industrial docks |
| District Pon | `PON` | West-north | Madrid / Gran Via-Callao | Lisbon / Alfama | Madrid avenues and Lisbon backstreets |
| District Pok | `POK` | West-middle | Austin / Congress and Sixth | Austin / East Cesar Chavez | Austin technology and warehouse blocks |
| District Pak | `PAK` | West-south | Downtown Dubai | Dubai / Al Fahidi | Dubai towers and engineered desert infrastructure |

Each new district packages:

- 256 Nest `.nbt` tiles.
- 256 Backstreets `.nbt` tiles.
- 512 tiles total.
- Two independent coherent 16x16 atlases.

The nine districts therefore add 4,608 tiles. The runtime-wide contract is 35
districts, 70 atlases, and 17,920 tiles.

Primary files:

- [District.java](src/main/java/dev/modernity/neoncity/District.java)
- [catalog.json](src/main/resources/data/neoncity/arnis_districts/catalog.json)
- `src/main/resources/data/neoncity/arnis_districts/structures/{ae,yi,wang,xi,ui,uang,pon,pok,pak}/`
- [perimeter_manifest.json](provenance/arnis_districts/perimeter_manifest.json)
- [build_perimeter_atlases.py](tools/arnis/build_perimeter_atlases.py)
- [ASSET_SOURCES.md](ASSET_SOURCES.md)

### Replaced Existing Atlases

- J Corp is now Las Vegas only:
  - Nest: Las Vegas Strip / Bellagio-Paris area.
  - Backstreets: Downtown Fremont.
- Q Corp moved from Nagoya to Fukuoka:
  - Nest: Tenjin.
  - Backstreets: Daimyo.
  - Street grammar is `FUKUOKA_TRANSIT_LANES` to avoid the former malformed
    road placement and floating-tower behavior.

All new and replacement sources have checked-in OSM inputs, Arnis generation
settings, selection metadata, and SHA-256 provenance under
`provenance/arnis_districts/`.

## Circular Perimeter And One-Piece City

The outer ring contains exactly 12 districts in clockwise order:

```text
Y -> Yi -> 王 -> X -> Xi -> Ui -> U -> Uang -> Pak -> Pok -> Pon -> Æ -> Y
```

Their centers lie on a 5,000-block-radius circle. Each irregular blob has a
1,450-block base radius, so every neighboring pair overlaps enough to expose the
same `BORDER_WALLED`, `BORDER_FOREST`, or `BORDER_CLIFF` divider grammar used by
interior districts. Y, X, U, and Pok are the exact north, east, south, and west
cardinal anchors. The original low-discrepancy placement algorithm is preserved
for all 22 shuffled non-A interior districts, with A Corp at the origin.

A circular continuity hull joins the overlapping interior without forcing edge
ownership or replacing the nearest-district calculation. The full district land
flood-fills as one component, while locations outside the irregular outer blobs
taper directly into vanilla wilderness. The full-screen map uses the same lookup
and no longer renders detached edge islands or artificial terrain bands.

Primary files:

- [MegacityLayout.java](src/main/java/dev/modernity/neoncity/MegacityLayout.java)
- [NeonCityGenerator.java](src/main/java/dev/modernity/neoncity/NeonCityGenerator.java)
- [CityMapTextureCache.java](src/main/java/com/example/cyberdeck/client/screen/CityMapTextureCache.java)
- [CityMapProjection.java](src/main/java/dev/modernity/neoncity/CityMapProjection.java)

## Edge Terrain And Atmosphere

- Æ, Y, and Yi share one deterministic winter cycle:
  - 2,400 ticks of gentle snow.
  - 1,200 ticks of snowstorm.
- D Corp uses dense client-local fog with a 42-block far plane and smooth
  entry/exit blending.
- T Corp retains lighter smog with a 92-block far plane.
- No custom biome or land band is generated outside the city. North, west, east,
  and south all transition directly to the preset's vanilla world generation.
- X Corp extraction equipment remains restricted to its in-district eastern edge.
- U Corp retains a localized container terminal, cranes, harbor, ocean blocks,
  and Portships entirely within the U Corp district blob; no biome is replaced.

Primary files:

- [DistrictAtmosphere.java](src/main/java/dev/modernity/neoncity/DistrictAtmosphere.java)
- [ProjectMoonAtmosphereClient.java](src/main/java/dev/modernity/neoncity/client/ProjectMoonAtmosphereClient.java)
- [UCorpPortGeneration.java](src/main/java/dev/modernity/neoncity/UCorpPortGeneration.java)

## Urban Supply Crates

Crates are generated after structures and district decorations by
[UrbanCrateGeneration.java](src/main/java/dev/modernity/neoncity/UrbanCrateGeneration.java).

Placement contract:

- Approximately one in seven chunks is selected deterministically.
- At most one crate is placed in a selected chunk.
- Up to 24 ranked candidate positions are evaluated.
- Eligible terrain is limited to:
  - Backstreets service alleys.
  - Highway buffers.
  - Extraction sites.
  - Container ports.
- Wilderness and all border-zone types are rejected.
- The crate position must be air or replaceable.
- The block above must remain air.
- The floor must have a sturdy top face.
- A horizontally adjacent sturdy backing block is mandatory.
- The barrel faces away from its actual backing wall.
- A persistent managed marker prevents opened crates from being replaced or
  rerolled.

Loot uses deterministic Common, Tech, or Rare tables. Every tier has a separate
one-roll ammo pool that guarantees one of handgun, shotgun, or heavy ammo.

Loot files:

- [urban_supply_common.json](src/main/resources/data/neoncity/loot_table/chests/urban_supply_common.json)
- [urban_supply_tech.json](src/main/resources/data/neoncity/loot_table/chests/urban_supply_tech.json)
- [urban_supply_rare.json](src/main/resources/data/neoncity/loot_table/chests/urban_supply_rare.json)

Crates are deliberately sparse and geometry-dependent. There is no guarantee of
one crate per district or selected chunk when no valid wall-backed location
exists. The current visual container is a Minecraft barrel.

## Fixed Seed And Mainline Building Catalog

All authored megacity systems use canonical content seed `50520260801`. The
Minecraft seed can still control vanilla terrain outside the city; the demo
server should also set `level-seed=50520260801` when identical wilderness is
required.

The recovered fixed-site catalog is packaged at
`data/neoncity/missions/mainline_sites_50520260801.dat` with SHA-256
`5f447bc0acc4e61c82b92557e99b2e51190cb1bd1be6008bc76c9586d5ab4fd5`.

| Mission | District | Floors | Site ID | Bounds |
|---|---:|---:|---|---|
| `m01_deliver_datashards` | G | 3 | `g:71:12:e67adada6fea42bf` | `1133,72,215 .. 1150,92,230` |
| `m02_assassinate_g_exec` | G | 4 | `g:72:11:e7227c874cf5a54e` | `1133,72,156 .. 1150,96,171` |
| `m03_steal_weights` | O | 5 | `o:-76:192:9be67862fd808952` | `-1188,72,3103 .. -1170,100,3117` |
| `m04_assassinate_fixer` | D | 3 | `d:-197:-59:1cb4b96cfc3905f0` | `-3169,72,-969 .. -3162,92,-951` |
| `m05_kill_cyberpsycho` | D | 3 | `d:-196:-58:c8a7958c6b587fbf` | `-3149,72,-898 .. -3140,92,-888` |

Each descriptor has complete floor masks, a ground-level generated entrance,
an upper-floor target, stairs, patrol routes, furnishings, and one explosive
canister. Startup copies these descriptors into save data without loading their
chunks or invoking the live atlas scanner. Deployment near the active site
installs the interior, canister, and validated turret plan. Repeated live
deployment failure can replace a damaged descriptor with a persisted emergency
atlas/tower plan instead of retrying forever.

## Mission And Gig Building Planning

[MissionBuildingPlanner.java](src/main/java/dev/modernity/neoncity/MissionBuildingPlanner.java)
now treats a mission site as an installed, reversible floor plan instead of a
small point encounter.

### Building And Entrance Selection

- Generated Arnis building geometry is inspected in the target district.
- Candidate entrances are ranked toward city ground level and by usable
  approach and floor count.
- Existing entrances must connect to an exposed exterior path.
- When no usable entrance exists, the planner can cut through up to four blocks
  of wall and install a closed, two-wide copper doorway.
- Door openings preserve three blocks of player clearance.
- Mission cells and the objective must remain within Manhattan distance 20 of
  the entrance.
- Runtime navigation points at the exterior side of the entrance, making the
  gig marker lead to a visible ground-level access point.

### Floors, Stairs, And Enemies

- A site can use one to five connected floors.
- Selected floor components must be enclosed and connected.
- Multi-floor objectives must be on floor two or higher; the planner normally
  uses the highest selected floor.
- Stairs are two blocks wide.
- Every stair step clears three blocks of headroom.
- Separate stair runs require at least three blocks of horizontal separation.
- Ambient multi-floor gigs receive at least two guards per floor.
- Mainline missions use authored per-floor guard quotas.
- Guard positions avoid entrances, objectives, stairs, and turret footprints.

### Walls, Cover, And Decoration

Large open floors can receive full-height partitions and corridors. Furnishing
types include:

- Stair-block couches.
- Cauldron and leaf-block planters.
- Wall-backed vending machines.
- Desks and computer-display paintings.
- Reception desks and cubicle pods.
- Conference tables.
- Server racks and filing cabinets.
- Water coolers.
- Explosive canisters.

Placements reserve circulation clearance and are rejected when they block a
required path. Vending machines require wall-adjacent placement.

### DFS And Transactionality

- Exact floor masks are persisted with the site.
- DFS checks the exterior entrance, every selected floor, stair landings,
  patrol waypoints, and an interaction-adjacent objective cell.
- DFS runs against the plan and again after real blocks and turret footprints
  are installed.
- Unsafe building candidates are rejected; deployment retries another candidate
  instead of leaving a partial encounter.
- Original block states are captured before editing.
- Installation rolls back transactionally if an edit or post-install
  circulation check fails.
- Sites reserve their horizontal bounds plus a 10-block exclusion buffer so
  another mission cannot occupy the same nearby building area.

### Mission Size Limit

Production planning enforces `MAX_MISSION_FLOOR_CELLS = 144` per selected
floor, with a minimum of 64 cells. This matches the requested 12x12 maximum
while still allowing non-square connected floor plans.

## Mission Completion And Cleanup

State is persisted in [MissionSiteData.java](src/main/java/dev/modernity/neoncity/MissionSiteData.java)
and advanced by [MissionService.java](src/main/java/dev/modernity/neoncity/MissionService.java).

- Completing a newly reserved mission retains its doorway, generated blocks,
  displays, explosive canisters, guards, and turrets instead of immediately
  removing them.
- Combat actors are removed only after every online participant is more than 96
  horizontal blocks beyond the site.
- Generated blocks and noncombat decoration entities are restored only after
  the whole party has conclusively left the retained district.
- Offline participants conservatively prevent premature cleanup when their
  position or district cannot be proven.
- An active unfinished contract suspends and restores its site after the party
  has entered and then fully leaves the target district. The contract remains
  active and can deploy again later.
- Abandonment and mission failure still perform immediate cleanup.
- Late-loading mission actors are rejected after their completed or suspended
  lifecycle says they should no longer exist.

Legacy mission sites without a persisted reservation/restoration snapshot
cannot use completion retention and fall back to immediate cleanup.

## Kang Tao Turrets And Explosive Canisters

### Mission Placement

- A mission/gig is accepted only when the selected site can safely fit at least
  one turret. An unsuitable building is rejected and another candidate is tried.
- Up to two turrets can be planned per site.
- Positions three to nine blocks inside the entrance are preferred and face the
  doorway when that arc is valid.
- Fallback positions maximize forward and side firing space.
- Each position requires:
  - A solid floor.
  - A clear 3x3 volume three blocks high.
  - At least four clear blocks in the forward firing direction.
  - At least eight total clear blocks across the forward and side rays.
- DFS is repeated with turret footprints treated as occupied.
- Turrets are mission-instance tagged, persistent, and spawned idempotently.
- Managed-city actor join compatibility prevents another generator's ambient
  spawn cancellation from suppressing mission turrets.

### Firing And Destruction

[KangTaoTurret.java](src/main/java/com/example/cyberdeck/defense/KangTaoTurret.java)
and [KangTaoTurretRenderer.java](src/main/java/com/example/cyberdeck/client/render/KangTaoTurretRenderer.java)
now share the same forward convention.

- The model's authored `+Z` barrel direction matches yaw zero.
- Hitscan and tracer rays start at the modeled muzzle instead of the entity eye
  or rear of the model.
- The weapon profile uses assault-rifle cadence and heavy-ammo damage.
- Deterministic bursts last five or six seconds:
  - 20 rounds over five seconds.
  - 24 rounds over six seconds.
- Every burst is followed by the assault-rifle reload interval.
- Burst/reload state persists through entity save/load.
- The original 270-degree horizontal arc remains.
- Turret hitscan ignores:
  - City civilians.
  - Kang Tao allies.
  - Other Kang Tao turrets.
  - Actors tagged with the same mission instance.
- Destroying a turret causes a radius-2.5 explosion that damages entities but
  does not damage blocks, then leaves a temporary blackened wreck.

[GunFiring.java](src/main/java/com/example/cyberdeck/weapon/GunFiring.java)
contains the shared muzzle-origin and target-filter logic.

### Canisters

- Every accepted mission layout requires at least one real
  `cyberdeck:explosive_canister`; at most two are placed.
- Placement avoids the doorway, objective, stairs, and patrol routes.
- A wall-backed fallback is attempted when the primary furnishing pass cannot
  place one.
- Canister explosions damage nearby entities without destroying protected
  blocks.
- Canisters remain after mission completion until district-level geometry
  cleanup.

## Character And Trauma-Team Notes

- Cyberpsycho configured-health capacity increased from 90 to 180, allowing Fog
  Mother's authored 160 health.
- `FactionEnemy` now synchronizes and persists a skin variant, and the renderer
  can select the dedicated Fog Mother texture.
- Six additional corporate/quest NPC textures expand `CityNpc.SKIN_COUNT` from
  9 to 15.
- No core Trauma Team behavior change can be attributed to this session's dirty
  diff. Existing Trauma Team behavior remains covered: the native 23x9x11
  aerodyne, landing clearance, descent/lift-off, 4-5 responders, extraction,
  and cleanup.

## Other Co-Shipped Dirty-Tree Work

The following functionality is present in the current worktree and verified
artifact, but exact authorship within this chat cannot be proven because the
repository was dirty at session start:

- Mainline campaign schema v2 with five sequential mission DAGs and seven named
  characters.
- Journal `AVAILABLE MAINLINE` acceptance and the server-authoritative
  `AcceptStoryMissionPacket`.
- Persistent mainline progress, party rewards, recovery, protected quest NPCs,
  contract cargo reissue, and reserved 3-5-floor mission towers.
- Arnis building-atlas selection plus generated fallback tower planning.
- `/neoncity buildings summary` and player-local building inspection overlays
  for floors, entrances, stairs, patrol routes, and objectives.
- Deterministic mainline character skin generation and provenance.

See [MISSIONS.md](MISSIONS.md) for the mainline campaign contract.

## Verification Record

The following checks ran against the final merged source:

| Check | Result |
|---|---|
| Static registration audit | 72 explicit unique GameTests; 29 unique network payloads |
| Standalone layout raster/continuity coverage | One blob; exact 12-member ring, 12 dividers, and 12 inward links |
| Modernity quick compile | Passed |
| Modernity full Gradle build | Passed |
| NeoForge GameTests | **73/73 passed** on the final v22 source |
| Fresh `neoncity:megacity` boot | Ready in 11.06s; five sites restored with no atlas scan |
| Clean restart | Minecraft ready in 2.07s; five saved sites and nine generated chunks restored with no rescan |
| `/neoncity status` | `enabled=true`, 35 districts, 64 edges, 9 generated chunks, v22 fingerprint |
| Packaged edge atlases | Nine districts x 512 NBTs = 4,608 tiles |
| Fixed-site catalog | Five exact G/G/O/D/D descriptors; restore left all five remote chunks unloaded |
| Package inspection | 20,332 entries; fixed-site NBT and both compatible world-preset IDs present |
| `git diff --check` | Passed |

Important registered regression tests include:

- `project_moon_district_coverage`
- `project_moon_urban_footprint_continuity`
- `project_moon_arnis_patch_selection`
- `project_moon_u_corp_port_generation`
- `project_moon_district_environment`
- `project_moon_district_atmosphere`
- `project_moon_urban_supply_crates`
- `project_moon_mission_building_planner`
- `project_moon_gig_board_lifecycle`
- `project_moon_story_mission_dag`
- `project_moon_party_rewards`
- Defense turret arc, destruction, canister, and placement tests.
- City actor join compatibility, including Kang Tao turrets.
- Existing Trauma Team lifecycle coverage.

Most mission-planner geometry tests use synthetic structures. The session did
not retain a screenshot or fly-through artifact for naturally selected Arnis
mission buildings.

## Artifact Baseline And Install

The verified v22 build artifact is:

```text
build/libs/cyberdeck-1.5.0.jar
SHA-256: 636cc03c996e89efac8f839460873fb661c5024e51988569a0006c319e6860a9
Size: 64,440,300 bytes
Entries: 20,332
```

The Minecraft-installed JAR is verified against this artifact after installation:

```text
~/Library/Application Support/minecraft/mods/cyberdeck-1.5.0.jar
SHA-256: 636cc03c996e89efac8f839460873fb661c5024e51988569a0006c319e6860a9
Status: installed and hash-verified twice
```

All multiplayer clients and the dedicated server must use this same
protocol-13 JAR.

Hash comparison:

```bash
shasum -a 256 build/libs/cyberdeck-1.5.0.jar \
  "$HOME/Library/Application Support/minecraft/mods/cyberdeck-1.5.0.jar"
```

## Compatibility And Known Gaps

1. **Fresh world requirement:** generator fingerprint
   `project-moon-megacity-v22-district-ring-fixed-seed-20260801` intentionally
   disables generation in older megacity saves. Already generated chunks are not
   rewritten. Use a fresh megacity world for the complete contiguous layout.
2. **Sparse crates:** crates are not guaranteed per district or selected chunk;
   valid geometry is required.
3. **Legacy completion sites:** sites without persisted rollback data clean up
   immediately on completion.
4. **Pre-limit development sites:** a version-2 active mission site generated
   by an earlier development JAR with more than 144 cells on one floor will be
   rejected by the new 144-cell contract. Use a fresh demo world or abandon the
   old active contract.
5. **Visual mission coverage:** synthetic GameTests are strong, but no durable
   naturally selected Arnis gig fly-through was saved.
6. **Live multiplayer coverage:** dedicated boot and per-player FakePlayer tests
   passed, but no automated two-real-client handshake, subtitle render, or party
   reconnect test was available.

## Regression Checklist

### City And Districts

- [ ] All 35 district enum values load without ordinal changes to A-Z.
- [ ] Each of the nine new atlas directories contains exactly 512 NBTs.
- [ ] All 70 catalog atlases resolve to packaged structure templates.
- [ ] The exact clockwise ring is Y-Yi-王-X-Xi-Ui-U-Uang-Pak-Pok-Pon-Æ.
- [ ] Y, X, U, and Pok remain the north/east/south/west cardinal anchors.
- [ ] Every adjacent outer pair exposes a normal district divider.
- [ ] No ordinary interior district captures an outer-ring slot.
- [ ] A district-only flood fill produces one city landmass across multiple seeds.
- [ ] The full-screen map shows the same filled footprint as world generation.
- [ ] J uses Las Vegas Strip/Fremont and Q uses Fukuoka Tenjin/Daimyo.

### Environment

- [ ] Æ, Y, and Yi show the same gentle-snow/snowstorm cycle.
- [ ] D Corp fog converges to a 42-block far plane and clears outside D.
- [ ] Exterior terrain is vanilla wilderness with no custom biome bands.
- [ ] X extraction equipment appears only inside its east-facing district edge.
- [ ] U's port and Portships remain inside the U Corp district blob.

### Fixed Seed And Mainline Sites

- [ ] City layout and content seed are always `50520260801`.
- [ ] Startup restores exactly five G/G/O/D/D pre-analyzed site descriptors.
- [ ] Descriptor restoration does not generate or load remote mission chunks.
- [ ] Restart reuses persisted descriptors without an Arnis atlas scan.
- [ ] Mission activation, not startup, installs interior decorations and actors.

### Crates

- [ ] No generated crate appears without a sturdy adjacent backing block.
- [ ] The block above every generated crate is air.
- [ ] Opened managed crates are not rerolled or overwritten.
- [ ] Common, Tech, and Rare crate tiers each always yield ammo.
- [ ] Black caches always include a gun, cyberware, and ammunition.
- [ ] Generated currency is `minecraft:emerald`, never the legacy item.

### Missions And Gigs

- [ ] Navigation markers lead to a visible, ground-accessible doorway.
- [ ] A missing entrance causes a closed two-wide doorway to be installed.
- [ ] Entrance-to-objective distance remains at most 20 blocks.
- [ ] Every stair step has three blocks of headroom and separate runs do not collide.
- [ ] Multi-floor objectives are on floor two or higher.
- [ ] Every used floor receives its required guards and patrol route.
- [ ] DFS reaches the entrance, stairs, patrol points, and objective after decoration.
- [ ] Vending machines are wall-backed and cover does not block circulation.
- [ ] Site reservations reject another site within the 10-block buffer.

### Turrets And Canisters

- [ ] Every deployed mission/gig has at least one turret and one canister.
- [ ] Turrets prefer entrance coverage when sufficient space exists.
- [ ] The visible barrel, muzzle tracer, and hitscan all point forward.
- [ ] Bursts last five or six seconds and use heavy-ammo assault-rifle behavior.
- [ ] Turrets do not shoot civilians, Kang Tao allies, or same-mission actors.
- [ ] Destroying a turret causes entity damage without block damage.
- [ ] Canisters detonate, damage nearby entities, and do not disappear on completion.

### Lifecycle And Packaging

- [ ] Completion leaves site geometry and combat in place immediately afterward.
- [ ] Combat actors persist until every participant is more than 96 blocks away.
- [ ] Generated geometry persists until all participants leave the district.
- [ ] The built and installed JAR hashes match the verified baseline.
- [ ] Full GameTest count remains 73 with zero required failures.
- [ ] Every multiplayer client and the server uses protocol-13 build hash
      `5ea444ba6a176bc394456db496bbb5dcd7375a7cc143b233e172da6251080392`.
