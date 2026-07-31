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
