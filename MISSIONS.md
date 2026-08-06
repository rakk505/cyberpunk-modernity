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
  `blood_pump`, `optical_camo`, `mantis_blades`, and `arm_cannon`. A `sandevistan` blinks toward
  its target on a cooldown using the same dash, particle trail, and afterimage the player's does;
  `mantis_blades` replace the held weapon with the melee blades and add reach and damage;
  `arm_cannon` lobs a periodic explosive shot at a target it can see, never at point-blank range.
- `steal_data`: installs an interactive secured-data terminal and the configured patrol detail.
- `ship_item`: requires a `cargo` object containing a namespaced item ID and stack `count`. Cargo
  is issued on acceptance, removed on arrival in the target district, and then paid.

The catalog is validated as one unit. Duplicate IDs, malformed ranges, unsupported guns, missing
mission types, or invalid type-specific fields reject the reload and leave the previous catalog
active.

## Weapons

The firearm roster is 76 `GunType` entries: five original weapons, eleven Cyber Armorer base
frames, twenty-two Cyber Armorer iconic variants, and a Tech counterpart for each. An iconic keeps
its own ballistics but declares the frame it is built on, so its firing sound, alert radius and
in-hand size come from that family rather than being restated per variant. Re-importing from a
newer pack is `tools/import_cyber_armorer.py`; the `weapon_asset_coverage` game test then proves
every registered weapon ships the geometry, clips, atlas and item model its renderer reads.

`cyberdeck:mantis_blade` and `cyberdeck:mantis_blade_maxtac` are melee weapons, not firearms. They
are not `GunType`s, take no ammunition, have no magazine or reload, and are swung rather than
fired: heavy per-hit damage, one extra block of reach, and a slow recovery between swings. In first
person they render on their own Blockbench blade rig and cycle the authored `melee_stock_1..3`
slashes on each swing. An enemy that installs the `mantis_blades` augment is given the standard
blade and closes to melee with it.

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

Each mainline mission authors its own detail through optional `scale` fields, so a contract is
recognisable by who defends it rather than by a uniform corporate squad:

| Field | Meaning |
| --- | --- |
| `defender_kind` | `soldier` (default), `elite`, or `cyberpsycho` |
| `elite_fraction` | For a `soldier` detail, the 0.0-1.0 share upgraded to elites |
| `defenders_roam` | Defenders hunt the whole building instead of holding their spawn floor |
| `target_loadout` | Optional `gun`, `cyberware`, and `health` for an assassination target that fights back |

Elites are spread deterministically across the detail rather than rolled per spawn, so redeploying
a contract meets the same fight. An elite draws one `EnemyCyberware` loadout (sandevistan, mantis
blades, arm cannon, subdermal armour, blood pump in combination). Outside missions, a minority of
ordinary corporate soldiers spawn chromed the same way. A configured `target_loadout` replaces the
inert civilian model with an armed soldier that stays invulnerable and inert until its kill node
opens, exactly as the passive target did.

Mainline generation uses canonical city seed `50520260801`. The mod bundles five pre-analyzed Arnis
building descriptors: two distinct G Corp buildings, one five-floor O Corp building, and two
distinct D Corp buildings. New saves persist those exact descriptors at startup without loading,
scanning, or rendering their chunks. Acceptance reuses the descriptor; deployment installs the
selected interior only when the party approaches the active site, and restarts reuse the saved
descriptor. Each descriptor retains both its selected mission floors and the complete segmented
building identity, so separate floor windows in one tower still conflict. Components touching a
scan boundary are rejected until a wider authoring scan closes the complete structure. The live
atlas scanner and purpose-built tower generator remain emergency recovery paths if the bundled
catalog or a modified story configuration is incompatible. Atlas-backed reservations compare exact
building IDs and physical envelopes, while legacy descriptors retain normal mission-site clearance.
Rejected candidates are never modified; accepted edits are snapshotted and restored
transactionally.

Two mainline actors must never land in what a player reads as one building, which is stricter than
"different reserved volumes". A candidate is rejected when it shares a structure, when its facade
is within 32 blocks of an already-reserved mainline building, or when it was stamped from the same
Arnis source geometry. The last case is the subtle one: the district atlas repeats by reflection,
so a building just past a mirror line is a pixel-perfect copy of its neighbour at different world
coordinates. Source identity is recovered by mapping a footprint's corners back through the tile
reflection, which makes the two copies compare equal. A bundled descriptor that fails this rule is
dropped at restore and its building is selected on demand instead, so the restored plan count can
legitimately be lower than the number of missions.

Interior dressing uses objective-specific floor programs: assassination sites progress through
office/operations space to an executive floor, data sites culminate in operations space, delivery
sites use lounge and storage roles, and cyberpsycho sites use storage/operations roles. Generated
floors require one canonical role anchor before optional dressing, keep at most four furnishing
anchors below 120 walkable cells or five on larger floors, and cap their occupied footprint.
Partition bases scale from six on compact floors to twelve on the largest mission masks. Repair may
remove unsafe optional props but cannot remove a floor's last role anchor. A reserved circulation
spine and post-install traversal audit keep entrances, stairs, patrol routes, and objectives
connected. Guards use a bounded whole-floor search, prefer four blocks of horizontal spacing, and
retry around an occupied spawn cell before relaxing spacing or failing deployment.

The bundled gig catalog contains 262 validated descriptors across all 35 districts. D was rescanned
with full physical-building exclusions; conflicting G and O markers were removed while both
districts remained above the five-site minimum. Administrator catalog compilation may inspect up to
24 chunks from a district center, but player board refreshes remain read-only and never invoke the
scanner.

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

Street placement for a talk character requires ground the player can actually walk to. The layout's
street classes that do not override imported geometry keep whatever the Arnis atlas stamped there,
so a column the layout calls a local street can be building interior; a retained atlas column only
counts when the imported map also puts a road ribbon on it. On top of that, a candidate must reach
twelve blocks of connected walkable ground, which is what separates a street from a sealed
courtyard or light well that a story NPC could only be reached in by breaking a wall.

Imported Arnis ground floors that have no door get one. After the facade passes seal a chunk's cut
cross-sections, a bounded per-chunk pass floods the chunk's street columns across connected
walkable ground, groups whatever it never reached into rooms, and punches at most two doorways
through the thinnest wall between a sealed room of ten or more cells and the street it already
faces. It reads and writes only inside the chunk being generated and classifies streets from the
layout rather than the world, so the result does not depend on which neighbours exist yet. Blocks
holding state, such as chests or signs, are never carved through.

Talk and delivery characters are persistent, invulnerable NPCs with a visible `!` name marker.
Left-click or use advances only the currently ready node. Cargo is contract-tagged, party-counted,
and can be reissued by its source NPC if lost. Combat targets stay invulnerable until their
assassination or cyberpsycho node becomes current. A delivery character is not exposed at an
exterior placeholder while its building is staged: deployment installs and validates access first,
then Kaito appears on his authored level-two stall. Stale placeholder NPCs are retired when an
anchor changes. Completed mainline interiors remain installed in their permanently reserved
buildings, so Kaito's stall remains an accessible interior hub for the next mission; completed gig
sites still restore normally after their cleanup gates pass.

Save recovery is conservative: missing DAG progress restarts a valid active mainline at its opening
node after deployed actors are cleaned up; a removed mission definition fails the stale contract so
it cannot occupy the player's contract slot permanently. Party acceptance requires all snapshotted
members online, node completion is idempotent, and early or duplicate interactions do not advance
the DAG.
