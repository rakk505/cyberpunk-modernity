# Cyberdeck Session Commit Report

Generated: 2026-08-01

## Branch And Repository State

- Current working branch: `codex/npc-voicelines-lifepaths`
- Remote tracking branch: `origin/codex/npc-voicelines-lifepaths`
- Implementation HEAD before this report: `782cc3427a77508bf9ca1164e98fa61e8f796ad9`
- Scanner/Trauma milestone branch: `codex/eye-implant-scanner-trauma` at `fb6633ba47fe5d893def098a690ecb2f91697435`
- Current `origin/main` integrated by this branch: `e178028ca7191ae421b8083a4fdf51923a22f07e`
- Relationship before this report: 0 commits behind and 15 commits ahead of `origin/main`
- Latest installed test JAR: `cyberdeck-1.5.0.jar`
- Installed JAR SHA-256 after the turret fix: `f1a228294d4a39aa61acc66efb755757011c3dea714d8c42f423bc34f7186408`

The current branch descends from the scanner/Trauma branch, so it contains every scanner,
Trauma Team, mission-integration, Excision, voiceline, lifepath, currency, shard-icon, dialogue,
and turret-orientation change described below.

## Commit Map

The first eight commits below were existing feature-lineage commits explicitly preserved and
integrated while resolving the stale-copy concern. The final seven commits were the direct feature,
merge-resolution, and follow-up commits produced during this session.

| Commit | Classification | Summary |
| --- | --- | --- |
| `dd42483` | Integrated lineage | Quickhack scanner and upload UI overhaul |
| `3548963` | Integrated lineage | Initial missions, gigs, parties, vendors, and mission buildings |
| `e5cc675` | Integrated lineage | Concurrent per-target quickhack uploads and state hardening |
| `b08fd57` | Integrated lineage | Mission journal, ambient gigs, delivery endpoints, and persistent vendor sites |
| `ba3b7d8` | Integrated lineage | Deterministic black loot caches and ammo caches |
| `fb40ea5` | Integrated lineage | Retry/recovery behavior for temporarily unavailable mission buildings |
| `974d3ee` | Integrated lineage | Structured mission interiors and entrance-focused navigation |
| `6dc1a27` | Integrated lineage | Kang Tao defense content, Wanted/Excision, Trauma command changes, and combat balance |
| `7e9a159` | Direct feature | Eye-implant scan-only mode, NPC scanner intel, and extended Trauma extraction |
| `c400bfd` | Direct integration | Merge missions/current features while retaining scanner and Trauma behavior |
| `fb6633b` | Direct integration | Merge current main and restore cross-branch functionality |
| `a80a1f4` | Direct feature | NPC voicelines, lifepaths, healing cooldown, and multiplayer hardening |
| `f76da14` | Direct feature | Emerald-backed Emmies and custom shard inventory icons |
| `925a1e2` | Direct correction | Resident dialogue moved from attack to right-click |
| `782cc34` | Direct correction | Thin turret barrel aligned with the firing direction |

## Integrated Feature Lineage

These features were not discarded when the stale working copy was reconciled. They were brought
forward through `c400bfd` and `fb6633b`, tested with the direct work, and remain in the current
branch.

### `dd42483` - Quickhack Scanner Upload Overhaul

- Reworked the scanner into a selection-first Cyberpunk-style interface.
- Added a full-screen scanner treatment with scanlines, edge framing, reticle, target lock state,
  responsive panels, and compact-layout handling.
- Added a segmented RAM rail showing available, consumed, and reserved RAM.
- Added a seven-entry quickhack list with item icons, RAM cost, affordability, selected state,
  upload state, queue position, and detail text.
- Added target intel with entity name, affiliation, current/max health, health bar, distance, and
  alert status.
- Added animated panel entrance/exit behavior when acquiring or losing a target.
- Added world-anchored upload markers projected above affected entities.
- Increased practical scan/quickhack reach to the tracked-entity range, bounded between 128 and
  192 blocks; the current implementation uses 160 blocks.
- Made uploads server-authoritative and allowed committed uploads to continue after the scanner UI
  closes.
- Retained cancellation for death, deck removal, invalid target, dimension mismatch, or excessive
  range.
- Added a long-range regression assertion and scanner/upload HUD registration.

### `3548963` - Missions And Gigs Foundation

- Added server-authoritative fixer contracts driven by a validated JSON catalog.
- Added four mission types:
  - Assassinate a named target with a configured guard detail.
  - Neutralize a configurable cyberpsycho with gun, grenade, health, and cyberware settings.
  - Steal data from an installed secured terminal.
  - Ship tagged cargo to a destination district.
- Added catalog validation for IDs, mission types, districts, reward ranges, guard counts,
  objective radii, cargo, guns, and cyberware behavior IDs.
- Added operator mission reload/start/clear commands without mutating already accepted snapshots.
- Added persistent active-mission state, mission actors, objective locations, rewards, and HUD sync.
- Added assassination targets, cyberpsychos, guards, data objectives, cargo issuance, district
  delivery completion, and server-side death/interaction completion checks.
- Added persistent mission-site reservations so contracts do not claim the same building.
- Added deterministic mission-building selection inside the megacity.
- Added persistent vendor anchors and vendor recreation/locking behavior.
- Added dedicated fixer/vendor stalls and deterministic district specialty coverage.
- Added party persistence, invitations, membership, leadership, disband/leave behavior, participant
  snapshots, shared Street Cred, reward distribution, offline pending rewards, and contract terminal
  state.
- Added story-mission catalog support and prerequisite-DAG validation.
- Added map/HUD mission markers, navigation integration, mission tracking, and fixer interaction.
- Added patrol-route support for mission guards.
- Added mission, party, vendor, story-DAG, and world-placement GameTest coverage.

### `e5cc675` - Concurrent Quickhack Uploads And State Hardening

- Replaced the single upload queue with per-caster, per-target upload queues.
- Different targets upload concurrently while hacks against the same target remain FIFO.
- Added a global four-entry cap across active and pending hacks.
- Reserved RAM across every target to prevent cross-target overcommit.
- Added independent target-head start/end ticks and per-target queue promotion.
- Pruned invalid targets without corrupting the remaining queues.
- Synchronized all active target queues to the owning client in one bounded packet snapshot.
- Updated the scanner list to show whether a skill is uploading or queued for the selected target.
- Updated the world overlay to render every independently uploading target.
- Added a durable nine-slot hotbar snapshot with serialization and `copyOnDeath()`.
- Restored real hotbar contents on toggle-off, login recovery, death, respawn, logout, and deck
  removal; legacy saves without a snapshot remove only synthetic quickhack items.
- Added server-side double-jump validation:
  - Requires at least two trusted airborne ticks.
  - Allows one use per airborne cycle.
  - Adds a ten-tick packet cooldown.
  - Rejects supported, dead, spectator, passenger, swimming, lava, flying, and fall-flying states.
- Added regression tests for hotbar recovery, concurrent uploads, RAM reservations, and double-jump
  packet abuse.

### `b08fd57` - Mission, Gig, Journal, Delivery, And Vendor Refinement

- Added a persistent mission journal and client journal screen.
- Added shared Journal/Cyberware/City Map navigation tabs and resource counters.
- Added ambient gig discovery with deterministic boards and five offers per inhabited district.
- Added server-side gig acceptance, completion history, refreshes, and owner/party-scoped offers.
- Added journal packets for request/sync, discovered-gig acceptance, and contract abandonment.
- Added contract abandonment rules and terminal-state cleanup.
- Added tagged contract cargo so ordinary matching items cannot satisfy or be removed as mission
  cargo.
- Added a physical delivery terminal block and interaction-driven delivery completion.
- Added persistent journal status for active, complete, and abandoned contracts.
- Added exact vendor anchors, immovable/invulnerable vendor entities, stale/duplicate vendor
  retirement, and missing-vendor recreation.
- Replaced transient merchant-truck assumptions with persistent building stalls.
- Added map signals for gigs, fixers, vendors, active objectives, and delivery endpoints.
- Added full-map and minimap route/marker refinements.
- Added multiplayer-safe party participation and journal synchronization for contracts.

### `ba3b7d8` - Generated City Loot Caches

- Added deterministic, seed/chunk-based cache generation in city streets, service alleys, and
  central plazas while excluding wilderness.
- Added black loot caches at a 1-in-16 chunk decision density.
- Added ammo caches at a 2-in-16 chunk decision density.
- Added footprint, world-border, floor-support, headroom, and obstruction checks.
- Added a persistent 54-slot black-cache block entity and six-row inventory UI.
- Guaranteed every black cache contains one firearm and one cyberware item.
- Added one to three extra random firearm/cyberware rewards.
- Added one-shot ammo caches claimed by attacking the block.
- Ammo rewards select a random ammo type and grant 100-250 rounds in 25-round increments.
- Removed the ammo cache before granting rewards to prevent duplicate packet claims.
- Added 500-round ammo item stack limits and codec/container compatibility mixins.
- Added item/block models, blockstates, localization, registration, and deterministic tests.

### `fb40ea5` - Mission Building Retry And Recovery

- Changed temporarily unavailable objective buildings from contract failure into delayed deployment.
- Added a 15-second retry delay with expanding search radius and new deterministic selection salts.
- Retained the accepted contract, party registration, and active journal status while deployment is
  delayed.
- Added per-instance retry state and cleanup on completion/abandonment.
- Added site rejection and reservation ownership checks so unsafe or occupied candidates can be
  skipped without losing the contract.
- Added safe release of legacy/current mission-site reservations.
- Added canonical deployed-objective recovery for players who were offline during deployment.
- Preserved the latest deployed target and journal timestamp over stale participant state.
- Added safe single-floor building fallback when no multi-floor site is available.
- Rejected planned edit cells containing block entities while preserving unrelated block entities
  elsewhere in the building.
- Added tests for unavailable chunks, offline recovery, reservation cleanup, block-entity safety,
  and compact fallback buildings.

### `974d3ee` - Structured Mission Interiors And Focused Navigation

- Expanded mission sites into one-to-four-floor office interiors.
- Added exterior entrances connected to walkable street approaches.
- Added interior stairs and clear landings between floors.
- Added deterministic patrol routes per floor.
- Added reception desks, planters, couches, partitions, cubicles, conference tables, server racks,
  water coolers, filing cabinets, desks, and limited explosive canisters.
- Reserved circulation spines between entrances, stairs, patrol points, and objectives.
- Rejected decoration layouts that break floor connectivity or player-sized objective access.
- Added rollback when installation would make circulation unsafe.
- Added safe objective approach cells and separated the interior objective from the exterior road
  navigation target.
- Updated mission packets, journal, HUD, minimap, and full map to track the correct focused
  navigation endpoint while preserving the real objective coordinate.
- Added structural and path-accessibility regression tests.

### `6dc1a27` - Defense, Wanted/Excision, Trauma Dispatch, And Balance

#### Kang Tao defense content

- Added a deployable Kang Tao automatic turret item/entity.
- Added solid-floor and clear-volume placement validation.
- Added a 270-degree horizontal firing arc with a 90-degree rear blind spot.
- Added 32-block target acquisition, line-of-sight checks, assault-rifle cadence, pitch limits, and
  an idle scanning sweep.
- Added persistent base yaw, target yaw/pitch articulation, and a supplied Bedrock model/texture.
- Added 40 health, armor, knockback resistance, stationary movement, persistence, and no XP.
- Added lethal explosion, blackened destroyed rendering, smoke, and a timed wreck lifetime.
- Added placement, firing-arc, animation, and destruction tests.

#### Explosive canister

- Added placeable explosive canister block/item, model, loot table, and localization.
- Added gun-hit detonation for conventional and tech-penetrating shot paths.
- Preserved reached-block ordering so a canister behind an earlier entity/impact is not incorrectly
  detonated.
- Added non-block-damaging explosion behavior and regression coverage.

#### Wanted and Excision

- Added persistent, owner-synchronized Wanted state and HUD.
- The third city-civilian kill inside a valid megacity district starts one-star Excision.
- One star maintains up to three assigned ground agents spawned outside the player's view.
- Killing two assigned agents escalates directly to three stars.
- Three stars dispatch black aerodynes every 20 seconds, with one active AV and a 12-nearby-agent
  cap.
- Each AV deploys four persistent assigned Excision agents, holds for four seconds, and ascends.
- Pursuit clears on player death, spectator state, or escape at least 55 blocks beyond the bound
  district edge.
- Added dedicated Excision uniforms, renderer state, black aerodyne structure, catalog metadata,
  asset generation tool, and automated agent/AV/rule tests.

#### Combat and cyberpsycho balance

- Rebalanced default cyberpsycho health to 75 and clamped configured health to 40-90.
- Reduced armor/toughness, including subdermal-armor variants.
- Changed Blood Pump to heal one health every 400 ticks.
- Preserved optional Sandevistan variants instead of forcing it on every natural spawn.
- Reapplied balanced bounds and armor after entity load so old saves cannot restore out-of-band
  values.

#### Trauma command/landing source behavior

- Added `/cyberdeck trauma` for the command sender and `/cyberdeck trauma <player>` for a named
  target.
- Required game-master permission for both command forms.
- Allowed administrative Trauma dispatch against living non-spectator creative players.
- Kept automatic low-health dispatch restricted to non-creative, non-spectator targets.
- Command dispatch creates a mission-skin Exec and searches for a landing site about 18 blocks in
  the target's look direction.
- Added localized invalid-target, no-landing-site, and successful-dispatch feedback.
- Added three-block aerodyne hover clearance.
- Separated landing-site acceptance from the entire descent column.
- Adapted descent height to available overhead clearance instead of rejecting otherwise valid
  landing sites.

## Direct Session Work

### `7e9a159` - Eye-Implant Scanner And Extended Trauma Extraction

#### Eye-implant scan-only mode

- Tab now opens a scanner for players with either a cyberdeck or eligible ocular cyberware.
- Any Face-slot implant grants scanning except `behavioral_imprint_synced_faceplate`.
- A cyberdeck takes precedence when both capabilities are present and opens full quickhacking.
- Optics-only use sets a separate synchronized `SCANNING` attachment and never sets
  `QUICKHACKING`.
- Scan-only mode never stashes, replaces, or synchronizes the hotbar.
- Full quickhacking retains durable nine-slot stash/recovery behavior.
- Removing an active deck closes quickhacking, restores the hotbar, and cancels uploads.
- Removing the final eligible ocular implant closes scan-only mode.
- Login/death/respawn/logout recovery handles stale scanner sessions.
- `/cyberdeck scanner on|off|toggle` accepts either capability and reports the implant requirement
  when neither exists.
- The toggle packet remains server-authoritative; clients render from synchronized attachments.

#### Scanner input and UI separation

- Scan-only reuses the scanner backdrop, reticle, lock animation, outline behavior, and right-side
  intel panel.
- Scan-only hides the RAM rail and entire quickhack list.
- Quickhack upload indicators, quickhack cycling, F queueing, and right-click queueing require full
  quickhacking.
- Scan-only does not reserve F, so stealth takedowns remain available.
- Quickhack actions require a current direct lock on a hostile `Enemy`; city civilians can be
  scanned but cannot be hacked.
- Client targeting is block-clipped and accepts hostile enemies or `CityNpc` entities.
- Target locking uses four confirmation ticks and tolerates three missed ticks before release.
- Server outlining covers enemies and city NPCs inside the scanner cone.

#### NPC scanner intel

- Expanded the right-side panel with:
  - NPC type: Resident, Corpo, Exec, faction soldier, or entity type.
  - Expected money-drop range.
  - Armed yes/no status.
  - Exec yes/no status.
- Resident drop range: `E$ 5-15`.
- Corpo drop range: `E$ 20-40`.
- Exec drop range: `E$ 80-150`.
- Non-city entities display `E$ 0`.
- `ARMED` is true when either hand contains any item.
- Existing name, affiliation, health, distance, and status information remains.

#### Trauma lifecycle extension

- Retained the 23x9x11 aerodyne structure and one-block-per-three-ticks movement cadence.
- Kept the AV hovering three blocks above the landing anchor.
- Deployed four or five armed, killable responders on landing.
- Assigned responders permanently aggro the selected player and persist after deployment.
- Added explicit `DESCENDING`, `LANDED`, `BOARDING`, and `ASCENDING` phases.
- Added a 2.5-minute Exec approach timeout (`3000` ticks).
- Added a 2.5-minute stationary boarding wait (`3000` ticks).
- Bounded maximum landed lifecycle at five minutes (`6000` ticks).
- The Exec runs to the pickup point and enters boarding within 3.5 blocks.
- During boarding the Exec clears evacuation navigation, stops horizontal movement, remains visible,
  and remains killable beside the AV.
- A normally boarded Exec is discarded only when the boarding timer expires and ascent starts.
- If the Exec never reaches the pickup point, the AV leaves after the approach timeout and leaves
  the Exec behind.
- Exec death during descent, landing, or boarding immediately starts the normal ascent animation.
- Killing every successfully deployed Trauma responder immediately starts ascent.
- If all responders die while the Exec lives, the Exec is left behind with evacuation cleared.
- Individual responder deaths do not make the AV depart.
- Deaths are captured from `LivingDeathEvent` so removed bodies still count.
- Surviving responders remain behind and hostile after successful extraction.
- Zero successfully spawned responders do not count as an eliminated full team; timers apply.
- One Exec cannot own two simultaneous Trauma events.
- Descent/ascent obstruction terminates the event after removing placed AV blocks.
- Level unload clears runtime event structures; active event state is intentionally not persisted
  through a server restart.

#### Tests added/expanded

- Scanner capability and hotbar recovery tests cover deck mode, optics-only mode, identity-faceplate
  exclusion, no scan-only stash, and item preservation.
- Trauma tests cover timing constants, creative command eligibility, automatic creative exclusion,
  responder count/armament/killability, boarding visibility, no premature extraction, timed
  extraction, responder persistence, all-responder death, Exec death in boarding/descent,
  obstruction handling, and AV cleanup.

### `c400bfd` - Mission/Feature Integration Merge

- Merged the mission/gig line through `6dc1a27` into the scanner/Trauma branch.
- Resolved conflicts in `CyberdeckGameTests`, `TraumaTeamEvents`, and localization.
- Preserved scan-only mode and extended Trauma boarding rather than accepting the older lifecycle.
- Preserved creative-compatible `/cyberdeck trauma` dispatch and adaptive hover/landing behavior.
- Integrated missions, ambient gigs, parties, journal/map navigation, vendor sites, Wanted/Excision,
  Kang Tao defenses, cyberpsycho balance, and their assets/tests.
- Restored the Excision feature and its black aerodyne/agent assets in the combined branch.

### `fb6633b` - Current Main Merge And Cross-Branch Restoration

- Merged current main `e178028` into the combined feature branch.
- Preserved the newer mainline Kang Tao placement/animation fixes.
- Retained extended Trauma boarding, kill-triggered ascent, and adaptive descent.
- Added a persisted `traumaAllowsCreative` responder flag.
- Command-created responders can target their assigned creative player after deployment and after
  save/load.
- Automatic Trauma targeting remains survival/adventure-only.
- Restored deterministic merchant markers on the rotating minimap while retaining exact
  server-owned vendor/fixer/gig markers on the full map.
- Preserved City actor join compatibility and Excision-assigned actors.
- Corrected Excision catalog source paths to the dedicated Excision skin and black aerodyne rather
  than Trauma Team paths.
- Confirmed the branch was no longer a stale copy of the mod and retained the creative Trauma
  command and Excision behavior.

### `a80a1f4` - NPC Voicelines, Lifepaths, Healing, And Multiplayer Hardening

#### Voiceline catalog

- Added `ambient.json` schema version 1 with 123 authored lines in 30 pools.
- Added location pools for Great Highway, O, P, D, G, K, B, M, Border Slums, and a generic fallback.
- Added Resident, Corpo, and Exec role pools for every location.
- Exact pool counts:
  - Great Highway: 3 Resident, 3 Corpo, 2 Exec.
  - District O: 4/4/4.
  - District P: 3/3/3.
  - District D: 3/3/3.
  - District G: 3/3/3.
  - District K: 5/4/4.
  - District B: 4/5/4.
  - District M: 4/5/5.
  - Border Slums: 4/2/2.
  - Generic fallback: 10/10/8.
- Added eager UTF-8 catalog loading and validation for schema version, required pools, unknown keys,
  empty/non-string lines, duplicates, and 1-512 code-point line length.
- Location precedence is Border Slums, then Great Highway road classes, then supported district,
  then generic fallback.
- Non-megacity, disabled-generator, null-district, and unsupported-district contexts use generic.

#### Voiceline server behavior and network

- Added server-authoritative NPC dialogue selection with six-block distance and line-of-sight checks.
- Added a per-player 60-tick cooldown.
- Added per-player previous-line memory to avoid immediate repetition when alternatives exist.
- Added display duration `clamp(50 + 2 * codepoints, 70, 140)` ticks.
- Added per-player packet delivery so two players do not overwrite each other's active subtitle.
- Cleared player dialogue state on logout and all dialogue state on server stop.
- Added bounded speaker/line packet decoding and client-thread delivery.
- Bumped the network protocol from 12 to 13 for voiceline and lifepath payloads.
- At this commit, all City NPCs and story actors spoke on attack. Commit `925a1e2` later superseded
  the ordinary Resident trigger without changing other roles.

#### Voiceline HUD

- Added a compact centered subtitle above survival health/armor/hunger bars.
- Added a translucent dark background, bottom border, amber speaker, and white wrapped dialogue.
- Limited wrap width to 280 pixels and shrank it for narrow viewports.
- Offset the subtitle for the armor row.
- Hid subtitles when the HUD is hidden, a screen/debug overlay is open, or the scanner is active.
- Added text presentation only; no spoken audio assets were added.

#### Healing

- Set Bounce Back cooldown to 300 ticks (15 seconds), down from 25 seconds.
- Set MaxDoc cooldown to 300 ticks (15 seconds), down from 30 seconds.
- Left healing amounts/profiles unchanged.
- Added normalized, serialized, owner-synchronized healing timestamps with `copyOnDeath()`.
- Healing cooldown and Bounce Back regeneration state now survive death and relog.

#### Lifepath command, state, and UI

- Added unrestricted `/cyberdeck lifepath` command.
- Added automatic picker opening on login until a selection is committed.
- Added stable path IDs: `netrunner`, `brawler`, and `merc`.
- Added serialized, synchronized, copy-on-death state containing path ID and rolled starting leg ID.
- Added a non-pausing cyberpunk full-screen picker with three color-coded profiles.
- Added desktop three-card layout.
- Added compact selector/detail layout below 620 pixels wide or 330 pixels high.
- Added hover state, pointer cursor, scanlines, descriptions, tags, and loadout summaries.
- Added keyboard Up/Down selection and confirmation keys.
- Added click-to-select, double-click-to-submit, and explicit Lock In behavior.
- Added pending state to suppress duplicate client submissions.
- Added 80-tick rejection display and server-acknowledged close behavior.
- Added server-owned validation/RNG through open, select, and result packets.
- Added 34 localization entries for paths, messages, common UI, tags, descriptions, and loadouts.

#### Exact starter loadouts

- Every path receives `basic_kiroshi_optics_t2`.
- Netrunner receives:
  - `jenkins_tendons_t2`.
  - `smart_link_t1`.
  - `paraline_mk_1_5_t1` cyberdeck.
  - Yukimura smart pistol.
  - 250 handgun rounds.
- Brawler receives:
  - One server-random leg from Fortified Ankles T1, Jenkins Tendons T2, Leeroy Ligament System T2,
    Lynx Paws T2, or Reinforced Tendons T2.
  - `nano_plating_t2`.
  - `gorilla_arms_t2`.
  - Tech Shotgun.
- Merc receives:
  - One server-random leg from the same five-entry pool.
  - `mantis_blades_t2`.
  - Assault Rifle.
  - 300 handgun rounds, matching the supplied requirement even though the weapon is an assault
    rifle.

#### Atomic grants and two-player safeguards

- Added loadout preflight on a copy before mutating installed cyberware.
- Rejected null/empty grants, occupied sockets, and capacity violations atomically.
- Accepted an exact already-installed implant without duplicating it.
- Enforced absolute 450 cyberware capacity.
- Granted only the starter-capacity deficit required by the selected package.
- Rejected attempts to use lifepath selection to subsidize unrelated veteran cyberware.
- Installed cyberware before item rewards and persisted the selected path after granting.
- Made same-path duplicate selection idempotent without regranting rewards.
- Rejected attempts to switch paths.
- Serialized selection packet work on the server thread.
- Kept state and RNG per player; no global selection state exists.
- Dropped full-inventory starter rewards at the recipient with the recipient UUID as pickup owner,
  preventing the second private-server player from taking overflow.

#### Verification at this milestone

- Added catalog mapping/non-repeat GameTests.
- Added exact healing cooldown assertions.
- Added five-player isolated lifepath tests for all loadouts, random-leg membership, capacity,
  idempotence, path-switch rejection, unrelated-cyberware rejection, full-inventory ownership, and
  cross-player isolation.
- Full suite passed 68/68 GameTests.
- Desktop and compact picker captures were visually checked.

### `f76da14` - Emerald Emmies And Shard Inventory Art

#### Emerald-backed economy

- Replaced spendable custom Emmies with vanilla emeralds for rewards, HUD balance, missions,
  parties, and merchant prices.
- Counted spendable non-equipment inventory slots, matching merchant-accessible currency.
- Issued rewards in legal emerald stack sizes and clamped negative grants to zero.
- Money shards now pay vanilla emeralds through the shared reward helper.
- Owner-targeted overflow emerald entities to the receiving player's UUID.

#### Old-save compatibility

- Kept registry ID `cyberdeck:emmies` registered but hidden from the creative inventory.
- Converted player-held legacy stacks server-side one-for-one.
- Removed the legacy stack before issuing emeralds to avoid duplication.
- Excluded legacy stacks from balances and merchant payment.
- Added persisted vendor offer schema version 2.
- Refreshed stale serialized legacy-currency offers once when a vendor loads.
- Made repeated vendor refresh checks idempotent.
- The one-time refresh resets old offer usage/demand to current catalog defaults.

#### Custom shard assets

- Added Minecraft 26.2 item-definition JSON for legacy Emmies, Money Shard, and Cyberware Shard.
- Replaced Money Shard's prismarine-shard fallback with an original 16x16 RGBA dark teal/gold
  circuit-credit icon.
- Replaced Cyberware Shard's prismarine-crystal fallback with an original 16x16 RGBA cyan/pink
  neural-capacity icon.
- Kept legacy Emmies visually mapped to the vanilla emerald while conversion occurs.

#### Verification

- Added `emmies_use_emeralds` coverage for a 70-emerald reward, 17-item legacy conversion, no dual
  balance, full-inventory overflow, and recipient ownership.
- Added merchant catalog and one-time persisted-offer migration assertions.
- Updated mission and party payout tests to emeralds.
- Full suite passed 69/69 required GameTests.
- Modernity capture `custom-shard-inventory-icons` at 1280x720 verified both custom icons beside a
  vanilla emerald with no missing-model fallback.

### `925a1e2` - Resident Dialogue Trigger Correction

- Ordinary ambient Residents now speak only on main-hand right-click.
- Attacking an ordinary Resident no longer triggers dialogue.
- Existing Resident damage response remains: the Resident immediately enters flee behavior.
- Corpos and Execs retain attack-triggered dialogue.
- Story mission actors retain attack-triggered dialogue even when represented by a `CityNpc` whose
  base role is Resident.
- Story actors and non-Resident City NPCs do not gain right-click dialogue.
- Non-city, non-story living entities remain excluded.
- Main-hand filtering prevents duplicate offhand interaction events.
- Right-click dialogue does not cancel the entity interaction, preserving normal item/entity use.
- Existing server authority, range/LOS checks, cooldown, non-repeat selection, per-player delivery,
  logout cleanup, and server-stop cleanup remain unchanged.
- Added routing assertions for ordinary Resident, story-Resident override, Exec behavior, and the
  existing Resident flee path.
- Full suite passed 69/69 required GameTests after this correction.

### `782cc34` - Thin Turret Barrel/Firing Direction Alignment

- Audited the Bedrock geometry and established:
  - Thin `barrel` bone extends along model-local `+Z` to `z=24.5`.
  - Rear drum occupies model-local `-Z` around `z=-15`.
  - Authored front legs are also model-local `+Z`.
- Removed the renderer's unintended extra 180-degree root rotation.
- Changed root model rotation from `180 - baseYaw` to `-baseYaw`.
- Aligned the visible thin barrel with entity yaw, target-facing math, idle sweep, 270-degree arc,
  hitscan direction, and tracer origin.
- Left server targeting, relative yaw articulation, pitch articulation, projectile mechanics,
  destroyed tint, geometry, and placement policy unchanged.
- Full Gradle build passed.
- All four turret GameTests passed on every post-change suite attempt.
- Modernity packaged-client captures at 1280x720 verified the thin muzzle facing the target and
  combat particles traveling from that side.
- The whole 69-test runner remained nondeterministic after this client-only change: four attempts
  each failed one unrelated tick-zero/tick-one assertion in Lifepath, merchant-anchor, or mission
  actor registration. This was not addressed in the renderer-only commit.

## Multiplayer And Private Two-Player Review Summary

- Scanner mode is per-player and synchronized from server-owned attachments.
- Quickhack queues, RAM reservations, hotbar recovery, and target uploads are keyed per caster.
- NPC voiceline cooldown/history and packets are per player.
- Lifepath state, random leg choice, grants, result packet, and overflow ownership are per player.
- Same-path duplicate requests are idempotent; cross-path requests reject without mutation.
- Party contracts use persistent participant snapshots rather than mutable nearby-player lists.
- Rewards split across the accepted participant snapshot, with offline shares queued for login.
- Contract terminal state prevents duplicate settlement.
- Tagged cargo prevents one player's ordinary matching items from satisfying contract cargo.
- Mission actors/objectives use durable instance IDs and owner/party context.
- Vendor anchors deduplicate and recreate one authoritative vendor entity.
- Emerald and starter-item overflow drops reserve pickup for the intended recipient.
- Trauma and Excision responders retain assigned target UUIDs and persistence metadata.
- No shared global lifepath, dialogue, scanner, or reward state can be overwritten by the second
  player.

## Verification History

- Scanner/Trauma milestone: compile and focused lifecycle/capability GameTests passed.
- Integrated branch after stale-copy reconciliation: full feature set compiled and Excision,
  creative Trauma dispatch, scanner, mission, and turret coverage remained registered.
- Voiceline/lifepath milestone: 68/68 required GameTests passed; desktop and compact picker captures
  passed.
- Emerald/icon milestone: full build and 69/69 required GameTests passed; real inventory capture
  passed.
- Resident-trigger milestone: full build and 69/69 required GameTests passed.
- Turret-orientation milestone: full build, real client load, server boot, combat capture, and all
  turret tests passed. The aggregate runner showed unrelated nondeterministic timing failures as
  documented above.
- Each completed implementation commit was pushed to
  `origin/codex/npc-voicelines-lifepaths`.
- The final packaged JAR was installed in the local Minecraft `mods` directory and hash-matched to
  the verified sandbox artifact.

## Known Tradeoffs And Remaining Gaps

### Scanner and NPC intel

- `ARMED` currently means either hand holds any item, not specifically a weapon.
- Money intel displays the role's possible range, not a pre-rolled exact drop.
- Scanner strings/layout do not have pixel-level automated assertions.

### Trauma Team

- Active AV lifecycle state is runtime-only and is cleared by level unload/server restart.
- A responder unloaded/discarded without a death event is not counted as killed.
- If no responder successfully spawns, the empty list does not trigger immediate departure.
- If the assigned target disconnects before deployment, no responders spawn, but the Exec/AV timer
  can continue.

### Lifepaths and voicelines

- Login picker opening has no delayed retry if the channel is unavailable at that exact event;
  `/cyberdeck lifepath` or relog recovers it.
- Escape can dismiss the picker before selection; it reopens on later login or command.
- Cyberware preflight is atomic, but item grants/state persistence are not wrapped in a formal
  rollback transaction for unexpected runtime exceptions.
- There is no automated two-real-client UI test, packet roundtrip UI test, or subtitle render test.
- The bundled voiceline catalog is static classpath data rather than runtime reloadable data.

### Emerald economy and compatibility

- Vanilla-earned emeralds now fund the mod economy.
- Emeralds in offhand/equipment slots are not counted until moved into normal inventory.
- Legacy Emmies in unopened containers convert only after entering a player inventory.
- One-time vendor offer migration resets existing usage/demand values.

### Test infrastructure

- Several integration tests assert entity/drop availability on tick zero or tick one and are
  intermittently sensitive to GameTest entity-manager scheduling.
- Those unrelated tests were left unchanged during the client-only turret orientation correction.
