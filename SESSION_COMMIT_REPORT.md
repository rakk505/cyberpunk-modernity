# Cyberdeck Session Commit Report

Generated: 2026-08-01

## Branch And Repository State

- Current working branch: `codex/district-patrol-enemies`
- Remote tracking branch after publication: `origin/codex/district-patrol-enemies`
- Current implementation HEAD: `d7010d0d0a8f83a57f6380cfcc18ad834a023af9`
- Parent feature branch: `codex/npc-voicelines-lifepaths` at
  `4b381ce0af55f6785910fafa7fab14d3815d0fd6`
- Scanner/Trauma milestone branch: `codex/eye-implant-scanner-trauma` at `fb6633ba47fe5d893def098a690ecb2f91697435`
- Current `origin/main`: `3ad54be5d795c2a5d9e2274f5b51c01f0faabdb4`
- Relationship including this report update: 0 commits behind and 6 commits ahead of `origin/main`.
- Latest installed test JAR: `cyberdeck-1.5.0.jar`
- Installed JAR SHA-256 after the district-patrol overhaul:
  `822f2e7463c59308dd6c975c48c009ad5e5baa4902de0dce2a4628b777a7a08d`

The current branch descends from the scanner/Trauma branch, so it contains every scanner,
Trauma Team, mission-integration, Excision, voiceline, lifepath, currency, shard-icon, dialogue,
turret-orientation, and district-patrol change described below.

## Commit Map

The first eight commits below were existing feature-lineage commits explicitly preserved and
integrated while resolving the stale-copy concern. The remaining listed commits were the direct
feature, merge-resolution, and follow-up commits produced during this session.

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
| `c68e7c7` | Direct feature | District A, E, and N authored voiceline pools and routing |
| `ba4bf14` | Direct feature | District patrol spawning, tactical skins, district identity, and Bulletproof Vest overhaul |
| `4d762bf` | Direct follow-up | Exact 3/5 patrol squads, 30% reactive reinforcements, and groupwide skin diversity |
| `d7010d0` | Direct integration | Current mainline quest stack, schema-2 mission compatibility, and patrol reconciliation |

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

### `c68e7c7` - District A, E, And N Voiceline Expansion

- Added all 47 newly authored lines without changing the existing 123 lines.
- Expanded the bundled catalog from 10 location pools/30 role pools/123 lines to 13 location
  pools/39 role pools/170 lines.
- Added District A, The Archive:
  - 5 Resident lines.
  - 5 Corpo lines.
  - 5 Exec lines.
- Added District E, The Stage:
  - 5 Resident lines.
  - 5 Corpo lines.
  - 5 Exec lines.
- Added District N, The Campus:
  - 6 Resident lines.
  - 5 Corpo lines.
  - 6 Exec lines.
- Added `DISTRICT_A`, `DISTRICT_E`, and `DISTRICT_N` to the strict catalog enum, so all three role
  arrays are required at startup and unknown/missing data still fails validation.
- Routed `A_CORP`, `E_CORP`, and `N_CORP` urban samples to their authored pools.
- Preserved location precedence: Border Slums overrides Great Highway, Great Highway overrides the
  district pool, and unsupported districts such as C continue to use the generic pool.
- Extended `npc_voiceline_pools` to assert the exact 170-line total, A/E/N per-role counts, and all
  three new mappings.
- Full Gradle build passed.
- Failed suite attempts passed the new voiceline test but hit known unrelated tick-zero Lifepath
  drop-visibility/turret-placement timing failures; the final full run passed all 69 required tests.
- No rendering or subtitle-layout code changed, so no new visual capture was required.
- Installed the verified `cyberdeck-1.5.0.jar` in the local Minecraft client.

### `ba4bf14` + `4d762bf` - District Patrol Enemy Overhaul

#### Sparse public-area patrol generation

- Restricted ambient corporate patrol generation to the Neon Megacity overworld.
- Excluded dead, creative, and spectator players from driving ambient patrol population.
- Increased the spawn evaluation interval from 600 to 1,200 ticks.
- Added a one-in-four probability gate to each population-cell evaluation.
- This produces an average successful evaluation opportunity of roughly once every four minutes
  per continuously occupied cell before location, concealment, and population checks.
- Generates only intact three- or five-soldier squads; population limits never clip a deployment
  into a one-, two-, or four-member partial group.
- Selects between the authored three- and five-member formations after capacity checks; a remaining
  capacity of four falls back to an intact three-member squad.
- Set the local ambient base-population cap to five soldiers inside 96 blocks.
- Set the loaded-world ambient base-population cap to 10 soldiers.
- Added an explicit worst-case reactive ambient bound of 21 loaded soldiers: three base squads of
  three plus one four-member reinforcement wave from each squad.
- Added 24-block separation between ambient patrol anchors.
- Changed player population cells from 128 to 64 blocks so two nearby players cannot starve one
  another's local population budget while occupying the same oversized cell.
- Retained deterministic world/epoch/cell seeding and stable UUID-based player representatives.
- Moved candidate spawn distance from 26-46 blocks to 48-72 blocks from the driving player.
- Increased anchor search attempts to handle the stricter public-area policy.
- Required every candidate member to be at least 48 blocks from every non-spectator player.
- Required every candidate member to be outside every non-spectator player's line of sight.
- Kept collision, world-border, floor-support, headroom, and all-or-nothing formation validation.
- Rollback still discards every member if any insertion fails, preventing partial pseudo-squads.
- Allowed ambient patrol anchors only on central plazas, district boulevards, local streets, and
  parks.
- Explicitly excluded service alleys, interdistrict roads, bridges, elevated rail, and highway
  buffers.
- Explicitly excluded outskirts, walled borders, forest borders, cliff borders, and wilderness.
- Validated every member position against the same public-area policy rather than checking only
  the formation anchor.
- Logged successful ambient deployments by district rather than by hidden weapon faction.

#### Patrol behavior and lifecycle

- Retained a compact 12-block home patrol radius instead of creating roaming enemy clusters.
- Added patrol-destination validation for ambient enemies.
- Ambient patrol destinations must remain in the soldier's original district.
- Ambient patrol destinations must remain in an approved public road/park class.
- Authored mission patrol routes retain their existing behavior and are not constrained by the
  ambient destination predicate.
- Ambient patrols are intentionally not serialized into the world save.
- Ambient patrols retire after 600 ticks with no player inside 128 blocks.
- Mission, Trauma Team, Excision, and cyberpsycho actors remain persistent under their existing
  rules.
- Added legacy deployment inference so old naturally generated, nonpersistent soldiers migrate to
  ambient lifecycle behavior without misclassifying Trauma, Excision, or cyberpsycho actors.

#### District identity and tactical appearance

- Replaced the ordinary Steve fallback with eight original 64x64 wide-player tactical skins.
- Added olive, charcoal, and black uniforms with restrained yellow identification accents.
- Added distinct helmets, patrol caps, balaclavas, goggles, red optics, headsets, rolled sleeves,
  load-bearing webbing, gloves, cargo trousers, knee pads, and boots across the variants.
- Kept dedicated Cyberpsycho, Trauma Team, and Excision texture precedence intact.
- Added synchronized and persisted skin-variant state.
- Assigns tactical variants without replacement inside every generated three- or five-member
  patrol, so no base-squad members share a skin.
- Makes ordinary nearby soldiers prefer the least-used local variant instead of independent random
  duplication.
- Makes mission guards exhaust all eight variants before balanced reuse in larger deployments.
- Gives every four-member reinforcement wave four distinct variants.
- A three-member squad plus its wave uses seven unique skins; a five-member squad plus its wave
  exhausts all eight variants before the one unavoidable repeat.
- Added stable UUID-hash skin migration for old saved soldiers without a skin field.
- Added synchronized and persisted district identity derived from the entity's world position.
- Added district migration for old saves without a district field.
- Replaced generic `Corpo Soldier` naming with district naming such as `O Corp. Soldier`.
- Preserved custom mission names, named cyberpsychos, `Excision Agent`, and the dedicated
  `Trauma Team Responder` name.
- Updated scanner affiliation from hidden Arasaka/Militech/Kang Tao loadout data to the visible
  district identity.
- Added scanner-specific Cyberpsycho, Trauma Team, and Excision identity handling.
- Added `EXCISION AGENT` to the scanner TYPE row instead of falling through to Corporate Soldier.
- Added a deterministic standard-library generator for all tactical and vest textures.
- Recorded the supplied tactical image as broad visual direction only; no source pixels, logos,
  or character designs are redistributed.

#### Bulletproof Vest and retained combat stats

- Removed Arasaka, Militech, and Kang Tao helmet, leggings, boots, and full-body armor from every
  newly generated faction loadout.
- Kept all legacy branded armor items registered so old saves and inventories still deserialize.
- Removed legacy branded armor from the mod's creative-tab equipment listing.
- Added the player-facing `Bulletproof Vest` item.
- Applied the requested black leather dye value `#1D1D21`.
- Added a body-only worn equipment texture so the vest does not cover tactical sleeves or legs.
- Added an inventory model using the dyed leather chestplate presentation.
- Added carrier straps, MOLLE rows, pockets, utility belt, clasp, and back-panel detail.
- Added no enchantment and no enchantment glint.
- Added explicit 30% reduction for damage tagged as a projectile while the vest is worn.
- Applied vest mitigation server-side to any living wearer, including players and NPCs.
- Added a dedicated hitscan `cyberdeck:bullet` damage type.
- Tagged hitscan bullets as Minecraft projectile damage so the vest protects against guns as well
  as physical arrows and other tagged projectiles.
- Preserved Mantis Blade attacks as melee damage instead of misclassifying them as bullets.
- Added shooter-attributed bullet death messages, including the held-item variant.
- Moved the old four-piece armor values to invisible entity attribute modifiers.
- Preserved exact light totals: 15 armor, 8 armor toughness, and 0.20 knockback resistance.
- Preserved exact heavy totals: 20 armor, 12 armor toughness, and 0.60 knockback resistance.
- Made the hidden modifiers transient, ID-stable, and idempotent so reload/equip calls cannot stack
  duplicate bonuses.
- Persisted the selected light/heavy ballistic tier and reapplied it after entity load.
- Preserved existing cyberpsycho base defenses while adding the selected ballistic profile.
- Migrated complete legacy branded loadouts to the vest-only presentation on load.
- Preserved a damaged current vest as-is on reload rather than repairing or recreating it.
- A missing or broken vest is not silently recreated from a saved ballistic tier.
- Set the vest drop chance to 28%, approximately preserving the former chance that at least one of
  four independently droppable 8% armor pieces would drop.

#### Ambient-versus-mission awareness and multiplayer isolation

- Added explicit ambient-deployment state synchronized to clients and retained for server logic.
- Reduced ambient detection range to 14 blocks.
- Retained the stronger 24-block detection range for gigs, missions, and authored hostile actors.
- Reduced ambient ally-alert radius to 10 blocks.
- Retained the 20-block mission ally-alert radius.
- Added a shared UUID alert group for each generated ambient patrol.
- Restricted ally alerts to the same hidden loadout faction, deployment class, and alert-group UUID.
- Assigned mission actors their contract instance UUID as the alert group.
- Prevented nearby guards belonging to two players' separate contracts from cross-alerting.
- Added load-time recovery from the pre-overhaul `cyberdeck_mission_instance` field so already
  active two-player contracts also gain alert isolation after updating.
- Replaced the old Kang Tao-only, detection-count trigger with one 30% airborne reinforcement roll
  when a player first successfully attacks any ordinary equipped corporate squad.
- Kept the drop at four soldiers and applies it to both ambient patrols and mission guards.
- Consumes the roll for the entire alert group before random evaluation or entity insertion, so
  simultaneous hits, area damage, and the newly spawned soldiers cannot create duplicate waves.
- Persists resolved non-ambient alert-group UUIDs in overworld saved data, preventing a mission
  squad from rerolling after members unload or the server restarts.
- Stores the resolved state on loaded entities as a fast path and as the complete lifecycle state
  for deliberately unsaved ambient patrols.
- Excludes Cyberpsycho, Trauma Team, and Excision actors from ordinary corporate reinforcements.
- Makes each wave inherit the source squad's faction, district, home, alert group, ambient profile,
  and current player target.
- Makes mission waves inherit actor owner, definition, instance, guard role, and persistence tags,
  so contract success, failure, and abandonment remove them with the source guards.
- Covers direct player melee, gun, and projectile damage plus damaging Overheat, Contagion,
  Cyberpsychosis, Detonate, incendiary-grenade, and poison-grenade paths.
- Ignores creative/spectator attackers and enemy-owned grenade effects.
- Kept all mutable patrol, detection, target, and retirement state on the server.

#### Regression and visual verification

- Expanded the deterministic patrol-plan GameTest for the 1,200-tick interval, one-in-four gate,
  exact three/five sizes, intact capacity fallback, local cap, loaded-world cap, worst-case reactive
  bound, and unique formations.
- Added public-road acceptance assertions.
- Added service-alley, interdistrict-road, bridge, elevated-rail, and highway-buffer rejection
  assertions.
- Added exact light/heavy armor, toughness, and knockback assertions.
- Added a modifier idempotency assertion.
- Added vest-only equipment and empty head/legs/feet assertions.
- Added exact black dye and no-foil assertions.
- Added exact `O Corp. Soldier` name coverage.
- Added ambient/mission detection-range and persistence assertions.
- Added separate-contract alert-group isolation coverage.
- Added exact 30% boundary assertions and unrelated-null-group isolation coverage.
- Added deterministic four-member reinforcement deployment coverage.
- Added faction, district, alert-group, persistence, and mission-cleanup inheritance assertions.
- Added entity-state and saved-data rejection of a second group roll.
- Added direct-player-damage and damaging-quickhack retaliation-path coverage.
- Added deterministic skin uniqueness for three- and five-member base squads, reinforcement waves,
  combined encounters, and two full mission-guard skin cycles.
- Added a real registered bullet damage-type projectile-tag assertion.
- Added a server damage comparison proving vest mitigation.
- Converted the new entity fixtures to GameTest-relative absolute positions to avoid cross-test
  contamination.
- Increased the complete suite from 69 to 70 required tests.
- Final full Gradle build passed.
- Stabilized pre-existing Lifepath overflow-drop and turret-placement tests by waiting for the
  GameTest structure/entity manager instead of asserting at tick zero; production behavior was not
  changed by those test-only adjustments.
- Final full NeoForge run passed all 70 required GameTests in 11.66 seconds.
- Modernity client captures verified tactical variants 0-3, variants 4-7, headgear/optic UVs,
  visible tactical sleeves, and body-only vest geometry.
- Modernity RCON staging resolved generated names as `O Corp. Soldier` and `N Corp. Soldier`.
- Capture 0-3 SHA-256:
  `17af5671a1bab37de01bbde69e9e8a4c6de5191d4a31a4040ed6b96cc7dbfab6`.
- Capture 4-7 SHA-256:
  `9feef7d3949ad1125a58fc9e6d89a0694163b78fa2b203281033fdb6fe47aa05`.
- Final five-member O Corp squad capture SHA-256:
  `a07ebc764f99e7bc42f785ae056605f9398c947b92fdb4c6a4cc84ed99cbb0c6`.
- Installed artifact and sandbox artifact both hash to
  `368d5ec4d200ce2a6fc06511e82a06ab81f5cfebf59e203a21c062d190a756b6`.

### `d7010d0` - Current Mainline Mission Integration And Crash Fix

- Diagnosed the integrated-server crash as a code/config version mismatch rather than malformed
  user data.
- Confirmed the installed `story_missions.json` is schema 2 and byte-identical to the current
  `origin/main` bundled story catalog, SHA-256
  `a21dbe320d7119acad8be02e1f13e52e21515004ba4c72e1298bf852008c1c4c`.
- Confirmed the prior branch JAR accepted only schema 1 and failed before parsing any mission or
  character entry with `unsupported story mission schema`.
- Merged all seven commits previously missing from `origin/main` instead of deleting or
  downgrading the valid configuration.
- Restored the schema-2 story catalog, mainline characters, node DAG, generated mainline sites,
  mission-specific guard scaling, story acceptance packet, urban systems, and fixed-seed startup
  stabilization.
- Retained schema-1 migration: older files are backed up and replaced by the bundled schema-2
  catalog on first load.
- Reconciled mainline guards with district tactical skins, hidden ballistic stats, mission-instance
  alert groups, the one-time reinforcement ledger, and contract cleanup inheritance.
- Preserved Fog Mother rendering precedence ahead of ordinary tactical skin selection.
- Preserved the Lifepath overflow timing stabilization while accepting mainline's current Merc ammo
  definition.
- Full Gradle build passed.
- All 74 required NeoForge GameTests passed in 23.82 seconds.
- A dedicated server booted to ready state in 9.13 seconds using the exact schema-2 configuration
  that caused the client crash.
- Startup logged five loaded story missions with no schema or configuration error.
- The managed Minecraft client loaded the merged mod, connected to that server, and rendered the
  existing five-member tactical patrol successfully.
- Schema-2 client-load capture SHA-256:
  `dbb9c45650ee77258678aa8e067213b7d92d31ed10457d188f5308946dd96ddf`.
- Corrected packaged artifact SHA-256:
  `822f2e7463c59308dd6c975c48c009ad5e5baa4902de0dce2a4628b777a7a08d`.

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
- Mission enemy alerts now use those instance IDs and recover them from older active-contract
  saves, preventing nearby two-player contracts from sharing aggro.
- Vendor anchors deduplicate and recreate one authoritative vendor entity.
- Emerald and starter-item overflow drops reserve pickup for the intended recipient.
- Trauma and Excision responders retain assigned target UUIDs and persistence metadata.
- Ambient base population is deduplicated in 64-block player cells, capped at five locally and 10
  globally, and kept separate from persistent mission populations.
- Exact three/five deployment rules and all-or-nothing insertion prevent partial squads when two
  players consume population capacity concurrently.
- Ambient patrols share alerts only inside their own three- or five-member patrol group.
- Each group resolves at most one 30% reinforcement chance even when both players attack at once.
- Mission reinforcement resolution survives unload/restart and remains isolated by contract
  instance; ambient waves remain bounded by the 21-soldier worst-case reactive population.
- Spawn concealment is checked against every non-spectator player, not just the player whose cell
  initiated the attempt.
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
- District-dialogue expansion: all 47 supplied lines matched the source text, full build passed,
  final full run passed 69/69 required GameTests, and the verified JAR was installed.
- District patrol overhaul: final full build passed; all 70 required GameTests passed; two real
  client-rendered capture groups verified all eight skins and vest geometry; the installed JAR
  hash matched the validated sandbox artifact.
- Reactive-squad follow-up: full build passed; all 70 required GameTests passed; a real
  client-rendered five-member O Corp formation verified five distinct tactical skins and vests;
  the replacement JAR was installed and hash-matched.
- Mainline compatibility fix: merged current `origin/main`, passed all 74 required GameTests, and
  booted successfully with the exact persisted schema-2 story catalog that previously crashed.
- Each completed implementation commit was pushed to
  its named remote feature branch.
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

- The Lifepath overflow and turret-placement fixtures now defer their assertions until their test
  structure and spawned entities are visible to the server entity manager.
- One unrelated detection-decay fixture remained timing-sensitive during an intermediate aggregate
  attempt; the subsequent complete 70-test run passed.

### District patrol compatibility and runtime coverage

- A legacy soldier with only part of an old branded armor set remaining is migrated using the
  detected tier and may regain that tier's complete hidden defense profile; complete generated
  sets migrate exactly.
- Patrol spawn concealment, retirement, and the loaded-world cap are server-authoritative but do
  not yet have a two-real-client automated integration test.
- Patrol destination validation constrains chosen endpoints; Minecraft pathfinding may still route
  across a narrow excluded road edge between two otherwise valid public endpoints.
- Ambient patrols are deliberately unsaved and will be regenerated after chunk unload or restart.
- A squad gets one 30% reinforcement roll, not a fresh roll on every hit; this prevents automatic
  weapons and area damage from multiplying airborne drops.
- The persistent non-ambient reinforcement ledger retains the newest 8,192 group UUIDs; extremely
  old completed group entries are evicted to keep world-save growth bounded.
