package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.NpcRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

/** Runtime orchestration for persistent mainline sites, nodes, and protected quest NPCs. */
final class MainlineQuestService {
    static final String NPC_CHARACTER = "cyberdeck_mainline_character";
    private static final long PLAN_SALT = 0x4D41494E4C494E45L;
    private static final int NPC_SEARCH_RADIUS = 48;
    private static final int MINIMUM_FALLBACK_ENEMIES = 2;
    private static final int STORY_STREET_SEARCH_RADIUS = 256;
    private static final int[][] CARDINAL_DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private MainlineQuestService() {
    }

    /** Hydrates exact pre-analyzed descriptors without loading or generating their chunks. */
    static int restoreFixedWorldPlans(ServerLevel level) {
        MainlineQuestData data = MainlineQuestData.get(level);
        List<MissionBuildingPlanner.Site> accepted = new ArrayList<>();
        for (StoryMissionCatalog.StoryMission mission : StoryMissionCatalog.definitions()) {
            MissionBuildingPlanner.Site existing = data.site(mission.id()).orElse(null);
            MissionBuildingPlanner.Site fixed = MainlineQuestData.fixedSite(
                    mission.id()).orElse(null);
            if (existing != null && fixed != null
                    && existing.id().startsWith("mainline:" + mission.id() + ":")
                    && !data.isCommittedRecovery(mission.id())
                    && !MissionSiteData.get(level).isReservedSite(existing)) {
                Cyberdeck.LOGGER.warn(
                        "[Mainline] replacing synthetic recovery site {} with bundled descriptor {}",
                        existing.id(), fixed.id());
                existing = null;
            }
            if (existing != null && fixed != null && existing.id().equals(fixed.id())
                    && existing.buildingId().equals(existing.id())) {
                try {
                    existing = MissionBuildingPlanner.withBuildingReservation(
                            existing, fixed.buildingId(), fixed.buildingBounds());
                } catch (IllegalArgumentException incompatibleLegacyPlan) {
                    Cyberdeck.LOGGER.warn(
                            "[Mainline] legacy descriptor for {} does not fit its bundled "
                                    + "building envelope; restoring the bundled plan",
                            mission.id());
                    existing = null;
                }
            }
            MissionBuildingPlanner.Site restored = existing;
            if (validSite(mission, restored)
                    && accepted.stream().noneMatch(site ->
                            MainlineQuestData.buildingConflicts(site, restored))) {
                data.putSite(
                        mission.id(), restored,
                        data.isCommittedRecovery(mission.id()));
                accepted.add(restored);
                continue;
            }
            data.removeSite(mission.id());
            if (!validSite(mission, fixed)
                    || accepted.stream().anyMatch(site ->
                            MainlineQuestData.buildingConflicts(site, fixed))) {
                Cyberdeck.LOGGER.warn(
                        "[Mainline] fixed descriptor missing, invalid, or conflicting for {}",
                        mission.id());
                continue;
            }
            data.putSite(mission.id(), fixed);
            accepted.add(fixed);
        }
        return validWorldPlanCount(level);
    }

    static int ensureWorldPlans(ServerLevel level) {
        restoreFixedWorldPlans(level);
        for (StoryMissionCatalog.StoryMission mission : StoryMissionCatalog.definitions()) {
            if (ensureWorldPlan(level, mission.id()).isEmpty()) break;
        }
        return validWorldPlanCount(level);
    }

    static int ensureNextWorldPlan(ServerLevel level) {
        MainlineQuestData data = MainlineQuestData.get(level);
        for (StoryMissionCatalog.StoryMission mission : StoryMissionCatalog.definitions()) {
            MissionBuildingPlanner.Site existing = data.site(mission.id()).orElse(null);
            if (validSite(mission, existing)) continue;
            ensureWorldPlan(level, mission.id());
            break;
        }
        return validWorldPlanCount(level);
    }

    /**
     * Resolves one fixed-seed building only when its story is accepted.
     * The selected descriptor is persisted, so restarts never repeat the atlas scan.
     */
    static synchronized Optional<MissionBuildingPlanner.Site> ensureWorldPlan(
            ServerLevel level, String missionId) {
        StoryMissionCatalog.StoryMission mission = StoryMissionCatalog.definition(missionId);
        restoreFixedWorldPlans(level);
        MainlineQuestData data = MainlineQuestData.get(level);
        MissionBuildingPlanner.Site existing = data.site(mission.id()).orElse(null);
        if (validSite(mission, existing)) return Optional.of(existing);
        return discoverWorldPlan(level, mission, data, Set.of(), false);
    }

    /** Replaces a repeatedly unsafe descriptor with one newly verified live-world plan. */
    static synchronized Optional<MissionBuildingPlanner.Site> recoverWorldPlan(
            ServerLevel level, String missionId, Set<String> rejectedSiteIds) {
        StoryMissionCatalog.StoryMission mission = StoryMissionCatalog.definition(missionId);
        return discoverWorldPlan(
                level, mission, MainlineQuestData.get(level), Set.copyOf(rejectedSiteIds), true);
    }

    private static Optional<MissionBuildingPlanner.Site> discoverWorldPlan(
            ServerLevel level,
            StoryMissionCatalog.StoryMission mission,
            MainlineQuestData data,
            Set<String> rejectedSiteIds,
            boolean recovery) {
        MegacityLayout.Node center = NeonCityGenerator.layout().node(mission.primaryDistrict());
        BlockPos origin = new BlockPos(
                center.x(), NeonCityGenerator.CITY_GROUND_Y + 1, center.z());
        UUID reservationOwner = UUID.nameUUIDFromBytes(
                ("cyberdeck:mainline-site:" + mission.id())
                        .getBytes(StandardCharsets.UTF_8));
        long selectionSalt = PLAN_SALT ^ mission.id().hashCode()
                ^ NeonCityGenerator.contentSeed();
        if (mission.encounter().type()
                == MissionCatalog.MissionType.NEUTRALIZE_CYBERPSYCHO) {
            List<MissionBuildingPlanner.Site> excluded = data.sites();
            for (int attempt = 0; attempt < 16; attempt++) {
                long attemptSeed = MegacityLayout.mix(
                        selectionSalt ^ (recovery ? 0x5245434F56455259L : 0L),
                        attempt, rejectedSiteIds.size());
                MissionBuildingPlanner.Site selected = PublicEncounterPlanner.plan(
                                NeonCityGenerator.layout(), mission.primaryDistrict(),
                                attemptSeed, mission.id(), excluded)
                        .orElse(null);
                if (selected == null
                        || rejectedSiteIds.contains(selected.id())
                        || data.conflicts(selected, mission.id())
                        || MissionSiteData.get(level).isReservedByOther(
                                selected.id(), selected, reservationOwner)) {
                    continue;
                }
                if (!recovery) data.putSite(mission.id(), selected);
                Cyberdeck.LOGGER.info(
                        "[Mainline] selected {} public encounter at {} for {} in District {}",
                        recovery ? "recovery" : "on-demand",
                        selected.target(), mission.id(),
                        mission.primaryDistrict().commandCode());
                return Optional.of(selected);
            }
            Cyberdeck.LOGGER.warn(
                    "[Mainline] no non-highway public encounter area available for {} in "
                            + "District {}",
                    mission.id(), mission.primaryDistrict().commandCode());
            return Optional.empty();
        }
        MissionBuildingPlanner.Site selected = ArnisBuildingAtlas.findSite(
                level,
                mission.primaryDistrict(),
                origin,
                16,
                selectionSalt,
                mission.requestedFloors(),
                mission.requestedFloors(),
                candidate -> !rejectedSiteIds.contains(candidate.id())
                        && !data.conflicts(candidate, mission.id())
                        && !MissionSiteData.get(level).isReservedByOther(
                                candidate.id(), candidate, reservationOwner))
                .orElse(null);
        boolean imported = selected != null;
        if (selected == null) {
            selected = MainlineBuildingGenerator.generate(
                    level, mission, data,
                    candidate -> !rejectedSiteIds.contains(candidate.id()));
        }
        if (selected == null) {
            Cyberdeck.LOGGER.warn(
                    "[Mainline] no {}-floor building available for {} in District {}",
                    mission.requestedFloors(), mission.id(),
                    mission.primaryDistrict().commandCode());
            return Optional.empty();
        }
        selected = MissionBuildingPlanner.withoutMissionInteriorPlan(selected);
        if (!recovery) data.putSite(mission.id(), selected);
        Cyberdeck.LOGGER.info(
                "[Mainline] selected {} {} site with {} floors at {} for {} in District {}",
                recovery ? "recovery" : "on-demand",
                imported ? "Arnis" : "fallback",
                selected.floorYs().size(), selected.id(), mission.id(),
                mission.primaryDistrict().commandCode());
        return Optional.of(selected);
    }

    static int validWorldPlanCount(ServerLevel level) {
        MainlineQuestData data = MainlineQuestData.get(level);
        int planned = 0;
        for (StoryMissionCatalog.StoryMission mission : StoryMissionCatalog.definitions()) {
            MissionBuildingPlanner.Site existing = data.site(mission.id()).orElse(null);
            if (validSite(mission, existing)) {
                planned++;
            }
        }
        return planned;
    }

    private static boolean validSite(
            StoryMissionCatalog.StoryMission mission, MissionBuildingPlanner.Site site) {
        if (site == null || site.district() != mission.primaryDistrict()) {
            return false;
        }
        boolean publicCyberpsycho = mission.encounter().type()
                == MissionCatalog.MissionType.NEUTRALIZE_CYBERPSYCHO;
        if (publicCyberpsycho) {
            int expectedY = NeonCityGenerator.topologySample(
                    NeonCityGenerator.layout(),
                    site.target().getX(), site.target().getZ()).groundY() + 1;
            BlockPos navigation = MissionBuildingPlanner.navigationTarget(site);
            if (!PublicEncounterPlanner.isPublicSite(site)
                    || site.floorYs().size() != 1
                    || site.floorYs().getFirst() != expectedY
                    || site.target().getY() != expectedY
                    || !PublicEncounterPlanner.isPublicTarget(
                            NeonCityGenerator.layout(), mission.primaryDistrict(),
                            site.target().getX(), site.target().getZ())
                    || !PublicEncounterPlanner.isPublicTarget(
                            NeonCityGenerator.layout(), mission.primaryDistrict(),
                            navigation.getX(), navigation.getZ())) {
                return false;
            }
        } else if (site.floorYs().size() < mission.requestedFloors()
                || site.floorYs().getFirst() != NeonCityGenerator.CITY_GROUND_Y + 1
                || !site.floorYs().contains(site.target().getY())
                || site.floorYs().size() >= 2
                        && site.target().getY() < site.floorYs().get(1)) {
            return false;
        }
        if (site.floorMasks().size() != site.floorYs().size()
                || site.stairs().size() != site.floorYs().size() - 1
                || site.patrolRoutes().size() != site.floorYs().size()
                || site.entrance().position().getY() != site.floorYs().getFirst()) {
            return false;
        }
        Set<Integer> floors = new HashSet<>(site.floorYs());
        if (floors.size() != site.floorYs().size()) return false;
        Set<Integer> masks = new HashSet<>();
        for (MissionBuildingPlanner.FloorMask mask : site.floorMasks()) {
            if (!floors.contains(mask.floorY())
                    || !masks.add(mask.floorY())
                    || mask.cells().isEmpty()
                    || mask.cells().stream().anyMatch(cell -> cell.getY() != mask.floorY())) {
                return false;
            }
        }
        Set<Integer> routes = new HashSet<>();
        for (MissionBuildingPlanner.PatrolRoute route : site.patrolRoutes()) {
            if (!floors.contains(route.floorY()) || !routes.add(route.floorY())) return false;
        }
        return masks.equals(floors) && routes.equals(floors);
    }

    static Optional<MissionBuildingPlanner.Site> reservedSite(
            ServerLevel level, String missionId) {
        return MainlineQuestData.get(level).site(missionId);
    }

    static Optional<MissionBuildingPlanner.Site> permanentInterior(
            ServerLevel level, String missionId) {
        return MainlineQuestData.get(level).permanentInterior(missionId);
    }

    /** Commits a recovered descriptor only after its complete deployment succeeds. */
    static void commitWorldPlan(
            ServerLevel level, String missionId, MissionBuildingPlanner.Site deployed) {
        StoryMissionCatalog.StoryMission mission = StoryMissionCatalog.definition(missionId);
        MissionBuildingPlanner.Site structural =
                MissionBuildingPlanner.withoutMissionInteriorPlan(deployed);
        MainlineQuestData data = MainlineQuestData.get(level);
        if (validSite(mission, structural) && !data.conflicts(structural, missionId)) {
            MissionBuildingPlanner.Site fixed = MainlineQuestData.fixedSite(missionId)
                    .orElse(null);
            boolean recovered = fixed == null
                    || !fixed.id().equals(structural.id())
                    || !fixed.buildingId().equals(structural.buildingId());
            data.commitSite(missionId, structural, recovered, deployed);
        }
    }

    static boolean conflictsReservedSite(
            ServerLevel level, MissionBuildingPlanner.Site site, String exceptMissionId) {
        return MainlineQuestData.get(level).conflicts(site, exceptMissionId);
    }

    static boolean isReservedMainlineSite(
            ServerLevel level, MissionBuildingPlanner.Site candidate) {
        if (level == null || candidate == null) return false;
        MainlineQuestData data = MainlineQuestData.get(level);
        return StoryMissionCatalog.definitions().stream()
                .map(mission -> data.site(mission.id()).orElse(null))
                .filter(site -> site != null)
                .anyMatch(site -> site.id().equals(candidate.id())
                        && site.buildingId().equals(candidate.buildingId()));
    }

    static void begin(ServerLevel level, MissionService.ContractContext context, String missionId) {
        MainlineQuestData.get(level).start(context.instanceId(), missionId);
    }

    static boolean ensureProgress(
        ServerLevel level, MissionService.ContractContext context, String missionId) {
        MainlineQuestData data = MainlineQuestData.get(level);
        StoryMissionCatalog.definition(missionId);
        if (data.progress(context.instanceId()).isPresent()) return false;
        data.start(context.instanceId(), missionId);
        return true;
    }

    static void end(ServerLevel level, UUID instanceId) {
        MainlineQuestData.get(level).removeProgress(instanceId);
    }

    static Optional<StoryMissionCatalog.StoryNode> currentNode(
            ServerLevel level, MissionService.ContractContext context) {
        if (context == null || context.kind() != MissionService.ContractKind.STORY_MISSION) {
            return Optional.empty();
        }
        MainlineQuestData.Progress progress = MainlineQuestData.get(level)
                .progress(context.instanceId()).orElse(null);
        if (progress == null) return Optional.empty();
        StoryMissionCatalog.StoryMission mission;
        try {
            mission = StoryMissionCatalog.definition(progress.missionId());
        } catch (IllegalArgumentException removedDefinition) {
            return Optional.empty();
        }
        return mission.readyNodes(Set.copyOf(progress.completedNodes())).stream().findFirst();
    }

    static Set<String> completedNodes(
            ServerLevel level, MissionService.ContractContext context) {
        return MainlineQuestData.get(level).progress(context.instanceId())
                .map(MainlineQuestData.Progress::completedNodes)
                .map(Set::copyOf)
                .orElse(Set.of());
    }

    static boolean isActiveMainline(
            ServerLevel level, MissionService.ContractContext context, String missionId) {
        return context != null
                && context.kind() == MissionService.ContractKind.STORY_MISSION
                && MainlineQuestData.get(level).progress(context.instanceId())
                        .map(progress -> progress.missionId().equals(missionId))
                        .orElse(false);
    }

    static boolean completeNode(
            ServerLevel level,
            MissionService.ContractContext context,
            StoryMissionCatalog.StoryNode node) {
        return context != null && node != null
                && MainlineQuestData.get(level).completeNode(context.instanceId(), node.id());
    }

    static boolean isCompletionNode(
            MissionService.ActiveMission mission, StoryMissionCatalog.StoryNode node) {
        return mission != null && node != null
                && StoryMissionCatalog.definition(mission.definitionId())
                        .completionNodeId().equals(node.id());
    }

    static boolean buildingReady(
            ServerLevel level, MissionService.ContractContext context) {
        return currentNode(level, context).map(StoryMissionCatalog.StoryNode::type)
                .map(type -> type != StoryMissionCatalog.NodeType.TALK)
                .orElse(true);
    }

    static Optional<StoryMissionCatalog.StoryNode> automaticNode(
            ServerPlayer player,
            MissionService.ActiveMission mission,
            MissionService.ContractContext context) {
        ServerLevel level = (ServerLevel) player.level();
        StoryMissionCatalog.StoryNode node = currentNode(level, context).orElse(null);
        if (node == null) return Optional.empty();
        if (node.type() == StoryMissionCatalog.NodeType.TRAVEL) {
            District destination = node.destinationDistrict().orElse(null);
            MegacityLayout.Location location = NeonCityGenerator.effectiveLocation(
                    NeonCityGenerator.sample(player.getBlockX(), player.getBlockZ()));
            if (destination != null && location.insideCity()
                    && location.district() == destination) {
                return Optional.of(node);
            }
        }
        if (node.type() == StoryMissionCatalog.NodeType.INFILTRATE && context.deployed()) {
            MissionBuildingPlanner.Site site = reservedSite(level, mission.definitionId()).orElse(null);
            if (site != null && inside(site, player.blockPosition())) return Optional.of(node);
            if (site != null && player.blockPosition().closerThan(site.entrance().position(), 8.0)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    static MissionService.ActiveMission retarget(
            ServerLevel level,
            MissionService.ActiveMission mission,
            MissionService.ContractContext context) {
        StoryMissionCatalog.StoryNode node = currentNode(level, context).orElse(null);
        if (node == null) return mission;
        StoryMissionCatalog.StoryMission definition = StoryMissionCatalog.definition(
                mission.definitionId());
        District district = node.destinationDistrict().orElse(definition.primaryDistrict());
        BlockPos target = nodeTarget(level, definition, node);
        return new MissionService.ActiveMission(
                mission.definitionId(), mission.type(), mission.title(), mission.briefing(),
                objective(definition, node), district, target, mission.reward(), mission.actorUuid(),
                mission.cargoItem(), mission.cargoCount(), mission.acceptedTick());
    }

    static Optional<StoryMissionCatalog.StoryNode> matchingInteraction(
            ServerPlayer player, Entity entity) {
        if (!isQuestNpc(entity)) return Optional.empty();
        MissionService.ContractContext context = MissionService.contractContext(player).orElse(null);
        if (context == null || context.kind() != MissionService.ContractKind.STORY_MISSION) {
            return Optional.empty();
        }
        StoryMissionCatalog.StoryNode node = currentNode((ServerLevel) player.level(), context)
                .orElse(null);
        if (node == null || node.characterId().isBlank()
                || !node.characterId().equals(characterId(entity))) {
            return Optional.empty();
        }
        return node.type() == StoryMissionCatalog.NodeType.TALK
                        || node.type() == StoryMissionCatalog.NodeType.DELIVER
                ? Optional.of(node) : Optional.empty();
    }

    static boolean objectiveReady(
            ServerPlayer player, StoryMissionCatalog.NodeType... expectedTypes) {
        MissionService.ContractContext context = MissionService.contractContext(player).orElse(null);
        if (context == null || context.kind() != MissionService.ContractKind.STORY_MISSION) return true;
        if (MainlineQuestData.get((ServerLevel) player.level()).progress(context.instanceId())
                .isEmpty()) return true;
        StoryMissionCatalog.NodeType current = currentNode((ServerLevel) player.level(), context)
                .map(StoryMissionCatalog.StoryNode::type).orElse(null);
        for (StoryMissionCatalog.NodeType expected : expectedTypes) {
            if (current == expected) return true;
        }
        return false;
    }

    static void maintainQuestNpcs(ServerLevel level, District district) {
        if (district == null) return;
        Set<String> placed = new HashSet<>();
        for (StoryMissionCatalog.StoryMission mission : StoryMissionCatalog.definitions()) {
            for (StoryMissionCatalog.StoryNode node : mission.nodes()) {
                if (node.characterId().isBlank()
                        || node.type() != StoryMissionCatalog.NodeType.TALK
                                && node.type() != StoryMissionCatalog.NodeType.DELIVER
                        || node.destinationDistrict().orElse(null) != district) {
                    continue;
                }
                if (!questNodeNeeded(level, mission, node)) continue;
                BlockPos position = nodePosition(level, mission, node);
                String key = node.characterId() + ":" + position.getX() + ":" + position.getZ();
                if (!placed.add(key)) continue;
                ensureQuestNpc(level, node.characterId(), position);
            }
        }
        retireStaleQuestNpcs(level, district, placed);
    }

    static boolean questNodeNeeded(
            ServerLevel level,
            StoryMissionCatalog.StoryMission mission,
            StoryMissionCatalog.StoryNode node) {
        for (ServerPlayer player : level.players()) {
            MissionService.ContractContext context = MissionService.contractContext(player)
                    .orElse(null);
            if (context != null
                    && context.kind() == MissionService.ContractKind.STORY_MISSION
                    && MissionService.activeMission(player)
                            .map(active -> active.definitionId().equals(mission.id()))
                            .orElse(false)
                    && currentNode(level, context)
                            .map(current -> current.id().equals(node.id())).orElse(false)
                    && (node.type() != StoryMissionCatalog.NodeType.DELIVER
                            || context.deployed())) {
                return true;
            }
            Set<String> completed = MissionPlayerData.completedStory(player);
            if (node.type() == StoryMissionCatalog.NodeType.TALK
                    && node.dependsOn().isEmpty()
                    && !completed.contains(mission.id())
                    && mission.available(completed, PartyService.sharedStreetCred(player))) {
                return true;
            }
        }
        return false;
    }

    private static void retireStaleQuestNpcs(
            ServerLevel level, District district, Set<String> placed) {
        ArrayList<CityNpc> removals = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof CityNpc npc) || !isQuestNpc(npc)
                    || NeonCityGenerator.districtAt(npc.getBlockX(), npc.getBlockZ()) != district) {
                continue;
            }
            String key = characterId(npc) + ":" + npc.getBlockX() + ":" + npc.getBlockZ();
            if (!placed.contains(key)) removals.add(npc);
        }
        removals.forEach(Entity::discard);
    }

    static boolean isQuestNpc(Entity entity) {
        return entity instanceof CityNpc && !characterId(entity).isBlank();
    }

    static String characterId(Entity entity) {
        return entity == null ? "" : entity.getPersistentData()
                .getString(NPC_CHARACTER).orElse("");
    }

    static void configureGuard(FactionEnemy guard, String missionId) {
        StoryMissionCatalog.StoryMission mission;
        try {
            mission = StoryMissionCatalog.definition(missionId);
        } catch (IllegalArgumentException unknownMission) {
            com.example.cyberdeck.faction.FactionSquads.equip(
                    guard, Faction.ARASAKA, guard.getRandom());
            return;
        }
        Faction faction = faction(mission.enemyFaction());
        com.example.cyberdeck.faction.FactionSquads.equip(guard, faction, guard.getRandom());
        double power = mission.enemyPower();
        guard.getAttribute(Attributes.MAX_HEALTH).setBaseValue(24.0 * power);
        guard.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(5.0 * Math.sqrt(power));
        if (guard.getAttribute(Attributes.ARMOR) != null) {
            guard.getAttribute(Attributes.ARMOR).setBaseValue(Math.max(
                    guard.getAttribute(Attributes.ARMOR).getBaseValue(), 2.0 + power * 2.0));
        }
        guard.setHealth(guard.getMaxHealth());
        if (power >= 1.5) guard.setGrenadeCount(Math.max(guard.getGrenadeCount(), 1));
    }

    static List<Integer> floorEnemyQuotas(String missionId, int floorCount) {
        List<Integer> configured;
        try {
            configured = StoryMissionCatalog.definition(missionId).enemiesPerFloor();
        } catch (IllegalArgumentException unknownMission) {
            return java.util.Collections.nCopies(floorCount, MINIMUM_FALLBACK_ENEMIES);
        }
        ArrayList<Integer> quotas = new ArrayList<>(floorCount);
        for (int floor = 0; floor < floorCount; floor++) {
            quotas.add(floor < configured.size() ? configured.get(floor) : 2);
        }
        return List.copyOf(quotas);
    }

    static Optional<StoryMissionCatalog.CharacterDefinition> targetCharacter(String missionId) {
        try {
            StoryMissionCatalog.StoryMission mission = StoryMissionCatalog.definition(missionId);
            return StoryMissionCatalog.findCharacter(mission.targetCharacterId());
        } catch (IllegalArgumentException unknownMission) {
            return Optional.empty();
        }
    }

    static boolean ensureQuestNpc(
            ServerLevel level, String characterId, BlockPos position) {
        if (level == null || position == null
                || !CityWorlds.hasFullyLoadedChunk(level, position)
                || !level.getWorldBorder().isWithinBounds(position)
                || !level.getBlockState(position.below()).blocksMotion()
                || level.getBlockState(position).blocksMotion()
                || level.getBlockState(position.above()).blocksMotion()) {
            return false;
        }
        AABB search = new AABB(position).inflate(NPC_SEARCH_RADIUS);
        CityNpc existing = level.getEntitiesOfClass(
                        CityNpc.class, search,
                        npc -> characterId.equals(characterId(npc)) && !npc.isRemoved())
                .stream().findFirst().orElse(null);
        StoryMissionCatalog.CharacterDefinition character = StoryMissionCatalog.character(characterId);
        if (existing != null) {
            protect(existing, character);
            if (existing.blockPosition().equals(position)) return true;
            existing.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                    existing.getYRot(), existing.getXRot());
            if (level.noCollision(existing)) return true;
            // Never preserve an obsolete exterior copy when its new authored anchor is blocked.
            existing.discard();
        }
        CityNpc npc = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.EVENT);
        if (npc == null) return false;
        npc.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        npc.finalizeSpawn(level, level.getCurrentDifficultyAt(position),
                EntitySpawnReason.EVENT, null);
        npc.getPersistentData().putString(NPC_CHARACTER, characterId);
        protect(npc, character);
        if (!level.noCollision(npc) || !level.addFreshEntity(npc)) {
            npc.discard();
            return false;
        }
        return true;
    }

    static boolean ensureDeliveryNpc(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return false;
        MissionService.ActiveMission active = MissionService.activeMission(player).orElse(null);
        MissionService.ContractContext context = MissionService.contractContext(player).orElse(null);
        if (active == null || context == null
                || context.kind() != MissionService.ContractKind.STORY_MISSION) {
            return false;
        }
        StoryMissionCatalog.StoryMission mission = StoryMissionCatalog.definition(
                active.definitionId());
        StoryMissionCatalog.StoryNode node = currentNode(level, context).orElse(null);
        if (node == null || node.type() != StoryMissionCatalog.NodeType.DELIVER
                || node.characterId().isBlank()) {
            return false;
        }
        return ensureQuestNpc(level, node.characterId(), nodePosition(level, mission, node));
    }

    static void removeDeliveryNpc(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        MissionService.ActiveMission active = MissionService.activeMission(player).orElse(null);
        MissionService.ContractContext context = MissionService.contractContext(player).orElse(null);
        StoryMissionCatalog.StoryNode node = currentNode(level, context).orElse(null);
        if (active == null || node == null || node.type() != StoryMissionCatalog.NodeType.DELIVER
                || node.characterId().isBlank()) {
            return;
        }
        StoryMissionCatalog.StoryMission mission = StoryMissionCatalog.definition(
                active.definitionId());
        BlockPos position = nodePosition(level, mission, node);
        level.getEntitiesOfClass(
                        CityNpc.class,
                        new AABB(position).inflate(4.0),
                        npc -> node.characterId().equals(characterId(npc)))
                .forEach(Entity::discard);
    }

    private static void protect(
            CityNpc npc, StoryMissionCatalog.CharacterDefinition character) {
        npc.setRole(NpcRole.RESIDENT);
        npc.setSkinVariant(character.skinVariant());
        npc.setNoAi(true);
        npc.setInvulnerable(true);
        npc.setPersistenceRequired();
        npc.setCustomName(Component.literal("! ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(character.name()).withStyle(ChatFormatting.AQUA)));
        npc.setCustomNameVisible(true);
    }

    private static BlockPos nodeTarget(
            ServerLevel level,
            StoryMissionCatalog.StoryMission mission,
            StoryMissionCatalog.StoryNode node) {
        if (node.type() == StoryMissionCatalog.NodeType.TALK
                || node.type() == StoryMissionCatalog.NodeType.DELIVER) {
            return nodePosition(level, mission, node);
        }
        MissionBuildingPlanner.Site site = reservedSite(level, mission.id()).orElse(null);
        if (site == null) {
            MegacityLayout.Node center = NeonCityGenerator.layout().node(mission.primaryDistrict());
            return new BlockPos(center.x(), NeonCityGenerator.CITY_GROUND_Y + 1, center.z());
        }
        return node.type() == StoryMissionCatalog.NodeType.TRAVEL
                        || node.type() == StoryMissionCatalog.NodeType.INFILTRATE
                ? MissionBuildingPlanner.navigationTarget(site)
                : site.target();
    }

    static BlockPos nodePosition(
            ServerLevel level,
            StoryMissionCatalog.StoryMission mission,
            StoryMissionCatalog.StoryNode node) {
        StoryMissionCatalog.StoryMission deliveryHome = StoryMissionCatalog.definitions().stream()
                .filter(candidate -> candidate.nodes().stream().anyMatch(candidateNode ->
                        candidateNode.type() == StoryMissionCatalog.NodeType.DELIVER
                                && candidateNode.characterId().equals(node.characterId())))
                .findFirst().orElse(null);
        if (deliveryHome != null) {
            StoryMissionCatalog.StoryNode deliveryNode = deliveryHome.nodes().stream()
                    .filter(candidate -> candidate.type() == StoryMissionCatalog.NodeType.DELIVER
                            && candidate.characterId().equals(node.characterId()))
                    .findFirst().orElseThrow();
            Optional<MissionBuildingPlanner.Site> active = activeSiteForNode(
                    level, deliveryHome.id(), deliveryNode.id());
            Optional<MissionBuildingPlanner.Site> permanent = permanentInterior(
                    level, deliveryHome.id());
            MissionBuildingPlanner.Site site = active
                    .or(() -> permanent)
                    .or(() -> reservedSite(level, deliveryHome.id())).orElse(null);
            if (site != null) {
                if (active.isPresent() || permanent.isPresent()) {
                    return floorPosition(
                            level, site, deliveryNode.floor(), node.characterId());
                }
                if (siteChunksLoaded(level, site)) {
                    return floorPosition(
                            level, site, deliveryNode.floor(), node.characterId());
                }
                return MissionBuildingPlanner.navigationTarget(site);
            }
        }
        return streetPosition(level, mission, node);
    }

    private static boolean siteChunksLoaded(
            ServerLevel level, MissionBuildingPlanner.Site site) {
        int minChunkX = Math.floorDiv(site.bounds().minX(), 16);
        int maxChunkX = Math.floorDiv(site.bounds().maxX(), 16);
        int minChunkZ = Math.floorDiv(site.bounds().minZ(), 16);
        int maxChunkZ = Math.floorDiv(site.bounds().maxZ(), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return false;
            }
        }
        return true;
    }

    private static Optional<MissionBuildingPlanner.Site> activeSiteForNode(
            ServerLevel level, String missionId, String nodeId) {
        return level.players().stream()
                .sorted(Comparator.comparing(ServerPlayer::getUUID))
                .filter(player -> MissionService.activeMission(player)
                        .map(active -> active.definitionId().equals(missionId)).orElse(false))
                .filter(player -> MissionService.contractContext(player)
                        .filter(context -> context.kind()
                                        == MissionService.ContractKind.STORY_MISSION
                                && (context.deployed()
                                        || MissionService.isDeploymentInProgress(
                                                context.instanceId()))
                                && currentNode(level, context)
                                        .map(node -> node.id().equals(nodeId)).orElse(false))
                        .isPresent())
                .map(MissionService::site)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static BlockPos floorPosition(
            ServerLevel level,
            MissionBuildingPlanner.Site site,
            int requestedFloor,
            String salt) {
        int index = Math.max(0, Math.min(site.floorYs().size() - 1, requestedFloor - 1));
        int y = site.floorYs().get(index);
        List<BlockPos> cells = site.missionCells(y).stream()
                .filter(level::isLoaded)
                .filter(position -> level.getBlockState(position.below()).blocksMotion()
                        && !level.getBlockState(position).blocksMotion()
                        && !level.getBlockState(position.above()).blocksMotion())
                .filter(position -> !position.closerThan(site.target(), 3.0))
                .filter(position -> site.decorations().stream().noneMatch(decoration ->
                        position.closerThan(decoration.position(), 2.0)))
                .filter(position -> site.stairs().stream().noneMatch(stair ->
                        position.closerThan(stair.start().atY(y), 4.0)))
                .filter(position -> site.patrolRoutes().stream()
                        .filter(route -> route.floorY() == y)
                        .flatMap(route -> route.waypoints().stream())
                        .noneMatch(waypoint -> position.closerThan(waypoint, 3.0)))
                .sorted(Comparator.comparingLong(position -> MegacityLayout.mix(
                        site.planSeed() ^ salt.hashCode(), position.getX(), position.getZ())))
                .toList();
        if (!cells.isEmpty()) return cells.getFirst();
        return site.entrance().position().atY(y);
    }

    static BlockPos streetPosition(
            ServerLevel level,
            StoryMissionCatalog.StoryMission mission,
            StoryMissionCatalog.StoryNode node) {
        District district = node.destinationDistrict().orElse(mission.primaryDistrict());
        MegacityLayout.Node center = NeonCityGenerator.layout().node(district);
        long hash = MegacityLayout.mix(
                NeonCityGenerator.layout().seed() ^ PLAN_SALT ^ node.id().hashCode(),
                district.ordinal(), mission.id().hashCode());
        int x = center.x() - 96 + Math.floorMod((int) hash, 193);
        int z = center.z() - 96 + Math.floorMod((int) Long.rotateLeft(hash, 29), 193);
        BlockPos street = findExteriorStoryStreet(
                level, district, x, z, STORY_STREET_SEARCH_RADIUS);
        if (street == null) {
            street = findExteriorStoryStreet(
                    level, district, center.x(), center.z(), STORY_STREET_SEARCH_RADIUS * 2);
        }
        if (street != null) return street;
        throw new IllegalStateException(
                "No connected exterior story-NPC location in District " + district.commandCode());
    }

    private static BlockPos findExteriorStoryStreet(
            ServerLevel level,
            District district,
            int originX,
            int originZ,
            int maximumRadius) {
        for (int radius = 0; radius <= maximumRadius; radius += 2) {
            if (radius == 0) {
                BlockPos exact = exteriorStoryStreet(level, district, originX, originZ);
                if (exact != null) return exact;
                continue;
            }
            for (int offset = -radius; offset <= radius; offset += 2) {
                BlockPos north = exteriorStoryStreet(
                        level, district, originX + offset, originZ - radius);
                if (north != null) return north;
                BlockPos south = exteriorStoryStreet(
                        level, district, originX - offset, originZ + radius);
                if (south != null) return south;
                if (Math.abs(offset) == radius) continue;
                BlockPos west = exteriorStoryStreet(
                        level, district, originX - radius, originZ - offset);
                if (west != null) return west;
                BlockPos east = exteriorStoryStreet(
                        level, district, originX + radius, originZ + offset);
                if (east != null) return east;
            }
        }
        return null;
    }

    private static BlockPos exteriorStoryStreet(
            ServerLevel level, District district, int x, int z) {
        MegacityLayout.Location location = NeonCityGenerator.layout().locateDistrict(x, z);
        NeonCityGenerator.RoadClass road = NeonCityGenerator.roadAt(x, z);
        if (!location.insideCity() || location.district() != district || !publicStoryRoad(road)) {
            return null;
        }
        NeonCityGenerator.generateNow(level, Math.floorDiv(x, 16), Math.floorDiv(z, 16), 1);
        BlockPos feet = CityWorlds.resolveStreetFeet(
                level, x, z, NeonCityGenerator.CITY_GROUND_Y + 1);
        if (feet == null || feet.getY() != NeonCityGenerator.CITY_GROUND_Y + 1
                || !level.canSeeSky(feet.above())) {
            return null;
        }
        int connected = 0;
        for (int[] direction : CARDINAL_DIRECTIONS) {
            int neighborX = x + direction[0];
            int neighborZ = z + direction[1];
            if (!publicStoryRoad(NeonCityGenerator.roadAt(neighborX, neighborZ))) continue;
            BlockPos neighbor = CityWorlds.resolveStreetFeet(
                    level, neighborX, neighborZ, NeonCityGenerator.CITY_GROUND_Y + 1);
            if (neighbor != null && Math.abs(neighbor.getY() - feet.getY()) <= 1) connected++;
        }
        return connected >= 2 ? feet : null;
    }

    private static boolean publicStoryRoad(NeonCityGenerator.RoadClass road) {
        return switch (road) {
            case CENTRAL_PLAZA, DISTRICT_BOULEVARD, LOCAL_STREET, SERVICE_ALLEY, PARK -> true;
            default -> false;
        };
    }

    private static String objective(
            StoryMissionCatalog.StoryMission mission,
            StoryMissionCatalog.StoryNode node) {
        String character = StoryMissionCatalog.findCharacter(node.characterId())
                .map(StoryMissionCatalog.CharacterDefinition::name).orElse("");
        return switch (node.type()) {
            case TALK -> "Talk to " + character + " // " + node.location();
            case TRAVEL -> "Travel to " + node.location();
            case DELIVER -> "Deliver the sealed package to " + character + " // " + node.location();
            case INFILTRATE -> "Infiltrate " + node.location();
            case ASSASSINATE -> "Eliminate " + character + " // " + node.location();
            case STEAL -> "Steal and erase the data // " + node.location();
            case KILL_CYBERPSYCHO -> "Neutralize " + character + " // " + node.location();
        };
    }

    private static Faction faction(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        for (Faction faction : Faction.values()) {
            if (faction.id().equals(normalized)) return faction;
        }
        throw new IllegalArgumentException("unknown mainline enemy faction " + id);
    }

    private static boolean inside(MissionBuildingPlanner.Site site, BlockPos position) {
        return position.getX() >= site.bounds().minX() && position.getX() <= site.bounds().maxX()
                && position.getY() >= site.bounds().minY() && position.getY() <= site.bounds().maxY()
                && position.getZ() >= site.bounds().minZ() && position.getZ() <= site.bounds().maxZ();
    }
}
