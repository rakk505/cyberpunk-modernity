# Mission Configuration

Fixer contracts are server authoritative and loaded from:

```text
config/cyberdeck/missions.json
```

The file is created from the bundled catalog on first server start. Edit it while the server is
running, then apply valid changes with `/neoncity mission reload`. Existing active contracts retain
their accepted snapshot; newly opened fixer boards use the reloaded definitions.

The source default lives at
`src/main/resources/data/neoncity/missions/catalog.json`.

## Schema

The root object has `schema_version: 1` and a `missions` array. Every mission requires:

| Field | Meaning |
| --- | --- |
| `id` | Unique lowercase identifier, up to 48 characters |
| `type` | One of the four types below |
| `title` | Player-facing contract name |
| `briefing` | Fixer-board description |
| `target_name` | Objective actor, terminal, or destination label |
| `target_districts` | District letters, or `["*"]` for all districts |
| `reward_emeralds` | Inclusive `min` and `max`, each from 1 through 256 |
| `guards` | Corpo guard count from 0 through 8 |
| `objective_radius` | Objective deployment and patrol radius from 8 through 128 blocks |

Supported `type` values:

- `assassinate_target`: spawns the gold-highlighted target and the configured guard detail.
- `neutralize_cyberpsycho`: requires a `cyberpsycho` object with `health`, `gun`, `grenades`, and
  `cyberware`. Implemented cyberware behavior IDs are `sandevistan`, `subdermal_armor`,
  `blood_pump`, and `optical_camo`.
- `steal_data`: installs an interactive secured-data terminal and the configured patrol detail.
- `ship_item`: requires a `cargo` object containing a namespaced item ID and stack `count`. Cargo
  is issued on acceptance, removed on arrival in the target district, and then paid.

The catalog is validated as one unit. Duplicate IDs, malformed ranges, unsupported guns, missing
mission types, or invalid type-specific fields reject the reload and leave the previous catalog
active.

## Operator Commands

```text
/neoncity mission reload
/neoncity mission start <definition_id> <A-Z>
/neoncity mission clear
```

`start` stages an exact configured definition for the executing player. It is intended for mission
authoring and visual verification; ordinary players accept the same definitions from black fixer
trucks.

## Mainline Campaign

Mainline missions are server authoritative and loaded from
`config/cyberdeck/story_missions.json`. Schema version 2 is seeded from
`src/main/resources/data/neoncity/missions/story.json`. A schema-version-1 config is backed up as
`story_missions.v1.backup.json` before the bundled campaign replaces it.

The Journal always lists the one unlocked mainline mission under `AVAILABLE MAINLINE`. Acceptance
uses the same active contract slot as a gig. Completing the configured DAG completion node settles
the shared party reward, records story completion, and exposes the next mission. The equivalent
commands are:

```text
/missions list
/missions status
/missions start <mission_id>
/missions abandon
```

Mainline generation uses canonical city seed `50520260801`. The mod bundles five pre-analyzed Arnis
building descriptors: two distinct G Corp buildings, one five-floor O Corp building, and two
distinct D Corp buildings. New saves persist those exact descriptors at startup without loading,
scanning, or rendering their chunks. Acceptance reuses the descriptor; deployment installs the
selected interior only when the party approaches the active site, and restarts reuse the saved
descriptor. The live atlas scanner and purpose-built tower generator remain emergency recovery
paths if the bundled catalog or a modified story configuration is incompatible. Reservations
compare complete building footprints with normal mission-site clearance and keep same-district
missions in different buildings. Rejected candidates are never modified; accepted edits are
snapshotted and restored transactionally.

Game masters can inspect the compiler without changing the world:

```text
/neoncity buildings summary [radius]
/neoncity buildings inspect [radius]
/neoncity buildings inspect off
```

`summary` reports stable building IDs, bounds, inferred floor heights and cell counts, readiness,
and rejection reasons. `inspect` adds a player-local, ten-second particle overlay for the nearest
label, including exact mission masks, entrance, stairs, patrol points, and objective when accepted.
The radius is bounded to zero through two chunks.

Talk and delivery characters are persistent, invulnerable NPCs with a visible `!` name marker.
Left-click advances only the currently ready node. Cargo is contract-tagged, party-counted, and can
be reissued by its source NPC if lost. Combat targets stay invulnerable until their assassination or
cyberpsycho node becomes current.

Save recovery is conservative: missing DAG progress restarts a valid active mainline at its opening
node after deployed actors are cleaned up; a removed mission definition fails the stale contract so
it cannot occupy the player's contract slot permanently. Party acceptance requires all snapshotted
members online, node completion is idempotent, and early or duplicate interactions do not advance
the DAG.
