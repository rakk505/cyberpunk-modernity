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
- **Cyberware** — installable body augmentations with active skills.
- **Neon city** — a procedurally generated city to explore.

## Building

```bash
./gradlew build
```

The built mod jar is written to `build/libs/`.

## Neon City Worldgen v1

This branch also contains the standalone [Neon City world generator](neoncity/README.md).
It installs alongside Cyberdeck and provides the `neoncity:megacity` world preset,
infinite cultural districts, organic roads, elevated transit, and DFS service alleys.
Its verified release JAR is available under `neoncity/deliverable/`.

## Running in a dev environment

```bash
./gradlew runClient   # launch the client
./gradlew runServer   # launch a dedicated server
```

## Mapping names

By default the MDK uses the official Mojang mapping names for methods and fields in the
Minecraft codebase. These names are covered by a specific license — see the reference
copy here: https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

## Additional resources

- Community Documentation: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
