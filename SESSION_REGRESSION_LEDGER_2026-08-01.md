# Cyberdeck Session Regression Ledger

Date: 2026-08-01
Repository: `rakk505/cyberpunk-modernity`
Local branch: `feature/mainline-questline`
Git baseline: `6dc1a27`
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
- The fixed edge districts are folded into one filled octagonal urban mass;
  thin roads are no longer the only links between detached district islands.
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

## Fixed Perimeter And One-Piece City

The following centers are invariant across seeds:

| Edge | District centers |
|---|---|
| North | Æ `(-2200,-5200)`, Y `(0,-5200)`, Yi `(2200,-5200)` |
| East | 王 `(5200,-2200)`, X `(5200,0)`, Xi `(5200,2200)` |
| South | Uang `(-2200,5200)`, U `(0,5200)`, Ui `(2200,5200)` |
| West | Pon `(-5200,-2200)`, Pok `(-5200,0)`, Pak `(-5200,2200)` |

Only non-edge districts shuffle through the interior. A mandatory perimeter
ring preserves edge adjacency, including Yi to 王 at the north-east corner.

The original fixed anchors left large wilderness gaps. The runtime now unions
the irregular district blobs with a filled octagonal urban hull:

```text
abs(x) <= 5200
abs(z) <= 5200
abs(x) + abs(z) <= 7400
```

Behavior inside the hull:

- Existing authored blob interiors keep their Nest/Backstreets classification.
- Former gaps become the nearest district's Backstreets.
- The outer 1,050-block hull band is owned only by fixed perimeter districts,
  preventing a shuffled interior district from capturing an edge seam.
- Far diagonal corners remain wilderness.
- S Corp's farm rule is limited to its authored blob, preventing hull infill
  from becoming a giant farm.
- Highway bridge classification no longer treats all hull infill as a bridge.

The client city map performs the same exact hull and edge-owner lookup, so it
does not show black voids where the world now generates Backstreets.

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
- A 640-block tundra band remains north of the city and receives a
  `SNOWY_PLAINS` biome override.
- The west edge tapers into land.
- The east edge tapers into land. X Corp extraction equipment is restricted to
  the east-facing side and decreases in density away from the city.
- The south edge opens into ocean across Uang, U, and Ui and receives a
  `DEEP_OCEAN` biome override.
- U Corp retains its container terminal, cranes, harbor, and Portships.

Primary files:

- [DistrictAtmosphere.java](src/main/java/dev/modernity/neoncity/DistrictAtmosphere.java)
- [ProjectMoonAtmosphereClient.java](src/main/java/dev/modernity/neoncity/client/ProjectMoonAtmosphereClient.java)
- [PerimeterOutskirts.java](src/main/java/dev/modernity/neoncity/PerimeterOutskirts.java)
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

The following checks ran against the session source before this ledger was
created:

| Check | Result |
|---|---|
| Standalone `District` and `MegacityLayout` Java compile | Passed |
| 768x768 exact layout raster over five seeds | One component per seed; zero hull wilderness cells |
| Modernity quick compile | Passed |
| Modernity full Gradle build | Passed |
| NeoForge GameTests | **65/65 required tests passed** in 23.65 seconds |
| Dedicated server boot | Ready; `Done` matched after 1.374 seconds |
| `git diff --check` | Passed before ledger creation |
| Nine new source atlas directories | 512 `.nbt` files each |

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

## Artifact Baseline And Drift Warning

The verified build artifact is:

```text
build/libs/cyberdeck-1.5.0.jar
SHA-256: edf75e05a4a88cf27b58ec27a28213d8966d63f6480754c0d76515150c58143f
Entries: 20,280
```

At the time this ledger was written, the Minecraft-installed JAR did **not**
match the verified artifact:

```text
~/Library/Application Support/minecraft/mods/cyberdeck-1.5.0.jar
SHA-256: edac02f2b7a40bd9e9a2e6bb5626daea12e493124a724ad7e0624f49bb760401
Entries: 15,646
Status: stale / not the session build
```

The installed JAR currently lacks the crate generator, crate loot tables,
mainline classes, new edge atlas structures, new skins, and the latest
`MegacityLayout`/map implementation. A concurrent local process replaced the
JAR after the verified artifact was installed. Use the `build/libs` SHA above as
the regression baseline, not the currently installed JAR.

Hash comparison:

```bash
shasum -a 256 build/libs/cyberdeck-1.5.0.jar \
  "$HOME/Library/Application Support/minecraft/mods/cyberdeck-1.5.0.jar"
```

## Compatibility And Known Gaps

1. **Fresh world requirement:** generator fingerprint
   `project-moon-megacity-v20-fixed-perimeter-20260801` intentionally disables
   generation in pre-v20 megacity saves. Already generated chunks are not
   rewritten. Use a fresh megacity world for the complete contiguous layout.
2. **Sparse crates:** crates are not guaranteed per district or selected chunk;
   valid geometry is required.
3. **Legacy completion sites:** sites without persisted rollback data clean up
   immediately on completion.
4. **Visual mission coverage:** synthetic GameTests are strong, but no durable
   naturally selected Arnis gig fly-through was saved.
5. **Installed artifact drift:** the current Minecraft JAR is stale and must not
   be treated as the verified session build.

## Regression Checklist

### City And Districts

- [ ] All 35 district enum values load without ordinal changes to A-Z.
- [ ] Each of the nine new atlas directories contains exactly 512 NBTs.
- [ ] All 70 catalog atlases resolve to packaged structure templates.
- [ ] North remains Æ-Y-Yi; east 王-X-Xi; south Uang-U-Ui; west Pon-Pok-Pak.
- [ ] Yi and 王 directly own the north-east seam.
- [ ] `locateDistrict` finds no wilderness inside the octagonal hull.
- [ ] A district-only flood fill produces one city landmass across multiple seeds.
- [ ] The full-screen map shows the same filled footprint as world generation.
- [ ] J uses Las Vegas Strip/Fremont and Q uses Fukuoka Tenjin/Daimyo.

### Environment

- [ ] Æ, Y, and Yi show the same gentle-snow/snowstorm cycle.
- [ ] D Corp fog converges to a 42-block far plane and clears outside D.
- [ ] Northern tundra and southern deep-ocean biome overrides apply.
- [ ] X extraction equipment appears only on the east-facing edge and tapers out.

### Crates

- [ ] No generated crate appears without a sturdy adjacent backing block.
- [ ] The block above every generated crate is air.
- [ ] Opened managed crates are not rerolled or overwritten.
- [ ] Common, Tech, and Rare crate tiers each always yield ammo.

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
- [ ] Full GameTest count remains at least 65 with zero required failures.
