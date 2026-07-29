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
  detect the player, alert their squad, and fight with cyberpunk weapons. Grenade-armed
  soldiers lob grenades at you.
- **Cyberware** — 121 wiki-sourced implant families across all ten body systems, represented by
  1,025 distinct Tier 1 through Tier 5++ variants. The ripperdoc screen selects a physical socket,
  implant family, and exact tier while showing that tier's capacity, armor, and source effect text.
- **Neon city** — a procedurally generated city to explore.

## Building

```bash
./gradlew build
```

The built mod jar is written to `build/libs/`.

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
