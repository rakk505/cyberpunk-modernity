# Cyberpunk 2027

A Cyberpunk-themed NeoForge mod for Minecraft that adds hitscan firearms, throwable
grenades, cyberware, faction soldiers, and a procedurally generated neon city.

- **Minecraft:** 26.2
- **NeoForge:** 26.2.0.7-beta
- **Java:** 25
- **Mod ID:** `cyberdeck`

## Features

- **Firearms** — hitscan guns with custom ammo and reloading mechanics, plus animated
  first-person 3D gun models (Overture, Unity, 3516, Saratoga, Yukimura, G58 Dian,
  M2038, Carnage, Ajax, Copperhead, Grad, and more).
- **Grenades** — throwable incendiary and poison grenades with area effects.
- **Faction soldiers** — Arasaka, Militech, and Kang Tao corpo enemies that patrol,
  detect the player, alert their squad, and fight with cyberpunk weapons. They dash and
  slide while firing, with synchronized full-body movement, recoil, reload, and malfunction
  poses. Grenade-armed soldiers lob grenades at you.
- **Tactical movement** — server-authoritative directional dashes (`Left Alt`) and grounded
  sprint slides (`C`) with low collision, eased momentum, first-person weapon motion, camera
  response, and third-person procedural animation.
- **Cyberware** — 121 wiki-sourced implant families across all ten body systems, represented by
  1,025 distinct Tier 1 through Tier 5++ variants. The ripperdoc screen selects a physical socket,
  implant family, and exact tier while showing that tier's capacity, armor, and source effect text.
- **Entity quickhacks** — scanned cars expose Take Control, Speed, Brake, and Detonate; Kang Tao
  turrets expose Take Control, Detonate, and Deactivate with security levels gated against the
  installed cyberdeck tier.
- **Project Moon Megacity** — a finite, irregular 35-district city with separate Nest and Backstreets
  zones, an exact 12-district circular perimeter, district roads/bridges/rail, and 70 coherent 16x16
  Arnis
  atlases containing 17,920 literal source chunks in their original materials. Each district adds
  its own three-color emblem banners without recoloring the source buildings. Parks use 68 curated
  Exsilit tree structures with district-appropriate foliage plus bounded merchant-truck clusters. Gray,
  yellow, cyan, brown, and black trucks provide weapons, sub-Tier-4 cyberware, armor, food/Slop,
  and waypoint-driven fixer deliveries respectively. Ordinary city buildings come from the Arnis
  atlases;
  procedural column overlays add infrastructure and open space without discarding unaffected
  imported buildings or synthesizing routine towers. A purpose-built mission tower remains an
  emergency fallback if a modified story catalog has no compatible predefined site. Sparse seeded
  supply crates add ammunition, currency,
  consumables, and technical salvage after structures are placed. It is built directly into the
  Cyberdeck JAR.
- **U Corp container coast** — U Corp contains a localized port without replacing the vanilla biome
  beyond the city, including a colored container terminal, working-scale cranes, harbor basins, and
  two or three 75x75 Portships. Each
  Portship is a floating settlement of staggered container homes, decks, lights, and a crane; the
  first ship also carries a central harbor tower.
- **City civilians** — eight corporate-worker variants populate only the Cyberpunk City and Neon
  City presets. They follow street-level paths, never fight back, and scatter away from gunshots.
- **Coherent enemy squads** — faction soldiers arrive as deterministic four-person formations with
  a shared faction and patrol anchor instead of inconsistent singleton spawns.
- **Fixer missions** — black merchant trucks offer configurable assassination, cyberpsycho,
  data-theft, and district-delivery contracts with persistent HUD and city-map tracking. Mission
  definitions, actors, patrol size, cargo, and emerald rewards are driven by JSON.

## Building

```bash
./gradlew build
```

The built mod jar is written to `build/libs/`.

## Project Moon Megacity

The Project Moon generator is part of the root Cyberdeck mod. Install only the Cyberdeck JAR,
create a fresh world, and select **Project Moon Megacity**. The data namespace remains
`neoncity:` for compatibility with the original world preset and saved generator state, but there
is no separate runtime mod dependency. The authored city always uses seed `50520260801`; enter the
same Minecraft seed (or set `level-seed=50520260801`) when the surrounding vanilla wilderness must
also be identical between demo clients and servers.

The city contains the original A-Z districts plus District Æ, Yi, 王, Xi, Ui, Uang, Pon,
Pok, and Pak. Its exact clockwise perimeter is Y, Yi, 王, X, Xi, Ui, U, Uang, Pak, Pok, Pon,
and Æ. Y is northernmost, X easternmost, U southernmost, and Pok westernmost. Each adjacent pair
shares a standard district divider and connects inward to the seeded interior, producing one
continuous city footprint. Terrain outside the perimeter is left to vanilla world generation.
Every district has a Nest and
Backstreets atlas sourced from an audited Arnis city study. Operators can use `/neoncity teleport
<code>` to jump to a central plaza; the new ASCII command codes are `AE`, `YI`, `WANG`, `XI`, `UI`,
`UANG`, `PON`, `POK`, and `PAK`. `/neoncity port` reports the U Corp terminal, shoreline, ocean
bounds, and Portship coordinates for inspection.

City content uses canonical seed `50520260801`. Five pre-analyzed G/G/O/D/D Arnis building
descriptors are bundled for the mainline campaign and persisted into new saves without scanning or
generating those chunks at startup. The v22 layout changes the generator fingerprint; older
megacity saves are left untouched, so use a fresh world for this layout.

Mission configuration and authoring commands are documented in [MISSIONS.md](MISSIONS.md).

## City map controls

- Press `M` to open or close the full Project Moon city map.
- Drag to pan and use the mouse wheel to zoom between the city overview and building detail.
- Left-click the map or a signal marker to set a waypoint. The city network calculates the shortest
  route across district roads and displays it on both the full map and the live top-left minimap.
- Right-click, press `Delete`, or use the `X` map tool to clear the waypoint. The target button
  recenters on the player.
- `/cyberdeck map <x> <z>` opens the same map with a coordinate waypoint;
  `/cyberdeck waypoint <x> <z>` updates navigation without opening it.

## Tactical movement controls

- Hold a movement key and tap `Left Alt` to dash in that direction.
- Sprint forward and tap `C` to slide.
- Both moves have short server-enforced recovery windows. Gun handling, recoil, and reload
  animation continue while moving.

## Vehicle quickhack integration

This repository does not ship a car entity. A vehicle provider can opt in an entity type through
the `cyberdeck:quickhack_cars` entity-type tag, mark an individual entity with
`VehicleQuickhackService.markCompatibleCar`, or implement `QuickhackCar`. The interface supplies
native throttle, steering, braking, destruction, and camera-anchor hooks; tagged entities use the
generic server-authoritative movement fallback.

## Running in a dev environment

```bash
./gradlew runClient   # launch the client
./gradlew runServer   # launch a dedicated server
```

## Cyberware catalog and slot integration

`tools/import_cyberware_wiki.py` regenerates the checked-in catalog, tier item definitions, model
links, and source-image manifest from the Cyberpunk Wiki. The 16×16 item textures are generated from
the downloaded references with the `pixelart-downsample` pipeline; see
`.modernity/art/references/cyberware/sources.json` for per-icon provenance.

The three optional sockets are persisted and owner-synced. A quest/perk integration can call
`CyberwareInstaller.unlock(...)` directly, or set these persistent player-data booleans:

- `cyberdeck.quest.birds_with_broken_wings` — second Face socket
- `cyberdeck.perk.license_to_chrome` — third Skeleton socket
- `cyberdeck.perk.ambidextrous` — second Hands socket

## Mapping names

By default the MDK uses the official Mojang mapping names for methods and fields in the
Minecraft codebase. These names are covered by a specific license — see the reference
copy here: https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

## Additional resources

- Community Documentation: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
