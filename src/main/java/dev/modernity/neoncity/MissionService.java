package dev.modernity.neoncity;

import com.example.cyberdeck.faction.CyberpsychoEntity;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSquads;
import com.example.cyberdeck.network.MissionSyncPacket;
import com.example.cyberdeck.network.OpenCityMapPacket;
import com.example.cyberdeck.network.OpenMerchantQuestPacket;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.weapon.WeaponItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Server-authoritative lifecycle shared by storyline missions and procedural/fixer gigs. */
public final class MissionService {
    private static final String PREFIX = "cyberdeck_mission_";
    private static final String ACTIVE = PREFIX + "active";
    private static final String DEFINITION = PREFIX + "definition";
    private static final String TYPE = PREFIX + "type";
    private static final String TITLE = PREFIX + "title";
    private static final String BRIEFING = PREFIX + "briefing";
    private static final String OBJECTIVE = PREFIX + "objective";
    private static final String DISTRICT = PREFIX + "district";
    private static final String TARGET_X = PREFIX + "target_x";
    private static final String TARGET_Y = PREFIX + "target_y";
    private static final String TARGET_Z = PREFIX + "target_z";
    private static final String NAVIGATION_X = PREFIX + "navigation_x";
    private static final String NAVIGATION_Z = PREFIX + "navigation_z";
    private static final String REWARD = PREFIX + "reward";
    private static final String ACTOR_UUID = PREFIX + "actor_uuid";
    private static final String CARGO_ITEM = PREFIX + "cargo_item";
    private static final String CARGO_COUNT = PREFIX + "cargo_count";
    private static final String ACCEPTED_TICK = PREFIX + "accepted_tick";
    private static final String CONTRACT_KIND = PREFIX + "kind";
    private static final String STREET_CRED = PREFIX + "street_cred";
    private static final String INSTANCE_ID = PREFIX + "instance_id";
    private static final String PARTY_ID = PREFIX + "party_id";
    private static final String PARTICIPANTS = PREFIX + "participants";
    private static final String DEPLOYED = PREFIX + "deployed";
    private static final String COMPLETING = PREFIX + "completing";
    private static final String SITE_PLAN = PREFIX + "site";

    private static final String ACTOR_TAG = "cyberdeck_mission_actor";
    private static final String ACTOR_OWNER = "cyberdeck_mission_owner";
    private static final String ACTOR_DEFINITION = "cyberdeck_mission_definition";
    private static final String ACTOR_ROLE = "cyberdeck_mission_role";
    private static final String ACTOR_INSTANCE = "cyberdeck_mission_instance";
    private static final String ROLE_TARGET = "target";
    private static final String ROLE_GUARD = "guard";
    private static final String ROLE_DATA_TERMINAL = "data_terminal";
    private static final String ROLE_DELIVERY_TERMINAL = "delivery_terminal";
    private static final String CARGO_INSTANCE = "cyberdeck_contract_cargo";

    private static final long OFFER_SALT = 0x4D495353494F4E53L;
    private static final int TARGET_OFFSET = 144;
    private static final int DELIVERY_HANDOFF_RADIUS = 8;
    private static final long DEPLOY_RETRY_DELAY_TICKS = 20L * 15L;
    private static final int MAX_SITE_SEARCH_RADIUS_CHUNKS = 16;
    private static final Map<UUID, Integer> LAST_SYNC = new HashMap<>();
    private static final Map<UUID, DeploymentRetry> DEPLOYMENT_RETRIES = new HashMap<>();

    private MissionService() {
    }

    public enum ContractKind {
        STORY_MISSION("MISSION"),
        GIG("GIG");

        private final String displayName;

        ContractKind(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum JournalStatus {
        ACTIVE,
        COMPLETED,
        FAILED,
        ABANDONED
    }

    private record DeploymentRetry(long nextTick, int cycle) {
    }

    private record RecoveredDeployment(BlockPos target, String actorUuid) {
    }

    /** Immutable accepted-contract history entry used by the Journal UI. */
    public record JournalEntry(
            UUID instanceId,
            ContractKind kind,
            MissionCatalog.MissionType type,
            String definitionId,
            String title,
            String briefing,
            String objective,
            District targetDistrict,
            int targetX,
            int targetY,
            int targetZ,
            int navigationX,
            int navigationZ,
            boolean deployed,
            int reward,
            int streetCred,
            long acceptedTick,
            JournalStatus status,
            long updatedTick) {
        public JournalEntry {
            if (instanceId == null || kind == null || type == null
                    || targetDistrict == null || status == null) {
                throw new IllegalArgumentException("Journal contract fields are required");
            }
            definitionId = definitionId == null ? "" : definitionId;
            title = title == null ? "" : title;
            briefing = briefing == null ? "" : briefing;
            objective = objective == null ? "" : objective;
            reward = Math.max(1, reward);
            streetCred = Math.max(0, streetCred);
        }

        JournalEntry withStatus(JournalStatus nextStatus, long tick) {
            return new JournalEntry(
                    instanceId, kind, type, definitionId, title, briefing, objective, targetDistrict,
                    targetX, targetY, targetZ, navigationX, navigationZ, deployed,
                    reward, streetCred, acceptedTick, nextStatus, tick);
        }

        JournalEntry withUpdatedTick(long tick) {
            return new JournalEntry(
                    instanceId, kind, type, definitionId, title, briefing, objective, targetDistrict,
                    targetX, targetY, targetZ, navigationX, navigationZ, deployed,
                    reward, streetCred, acceptedTick, status, tick);
        }

        JournalEntry withNavigation(int x, int z) {
            return new JournalEntry(
                    instanceId, kind, type, definitionId, title, briefing, objective, targetDistrict,
                    targetX, targetY, targetZ, x, z, deployed, reward, streetCred,
                    acceptedTick, status, updatedTick);
        }
    }

    public record MissionOffer(
            String definitionId,
            MissionCatalog.MissionType type,
            String title,
            String briefing,
            String objective,
            int targetDistrictOrdinal,
            int targetX,
            int targetZ,
            int reward,
            int streetCred) {
        public MissionOffer(
                String definitionId,
                MissionCatalog.MissionType type,
                String title,
                String briefing,
                String objective,
                int targetDistrictOrdinal,
                int targetX,
                int targetZ,
                int reward) {
            this(definitionId, type, title, briefing, objective,
                    targetDistrictOrdinal, targetX, targetZ, reward, 10);
        }
    }

    public record ContractContext(
            ContractKind kind,
            int streetCred,
            UUID instanceId,
            PartyService.ParticipantSnapshot participants,
            boolean deployed,
            boolean completing) {
        public ContractContext withDeployed(boolean value) {
            return new ContractContext(
                    kind, streetCred, instanceId, participants, value, completing);
        }

        public ContractContext withCompleting(boolean value) {
            return new ContractContext(
                    kind, streetCred, instanceId, participants, deployed, value);
        }
    }

    public record ActiveMission(
            String definitionId,
            MissionCatalog.MissionType type,
            String title,
            String briefing,
            String objective,
            District targetDistrict,
            BlockPos target,
            int reward,
            String actorUuid,
            String cargoItem,
            int cargoCount,
            long acceptedTick) {
        String clientObjective(ServerPlayer player) {
            if (type != MissionCatalog.MissionType.SHIP_ITEM || cargoItem.isBlank()) {
                return objective;
            }
            Item item = MissionBlocks.CONTRACT_CARGO.get();
            ContractContext context = contractContext(player).orElse(null);
            int carried = item == null ? 0 : context == null
                    ? count(player, item)
                    : count(
                            context.participants(), player.level().getServer(), item,
                            context.instanceId());
            return objective + "  [" + Math.min(carried, cargoCount) + "/" + cargoCount + "]";
        }
    }

    public static void open(ServerPlayer player, Entity merchant) {
        if (!isValidFixer(player, merchant)) return;
        District source = MerchantTruckLibrary.merchantDistrict(merchant).orElse(null);
        BlockPos anchor = MerchantTruckLibrary.merchantAnchor(merchant).orElse(null);
        if (source == null || anchor == null) return;
        PacketDistributor.sendToPlayer(player, new OpenMerchantQuestPacket(
                merchant.getId(), source.ordinal(), offers((ServerLevel) player.level(), anchor, source)));
    }

    public static boolean accept(ServerPlayer player, int merchantEntityId, int offerIndex) {
        ServerLevel level = (ServerLevel) player.level();
        Entity merchant = level.getEntity(merchantEntityId);
        if (!isValidFixer(player, merchant)) return false;
        District source = MerchantTruckLibrary.merchantDistrict(merchant).orElse(null);
        BlockPos anchor = MerchantTruckLibrary.merchantAnchor(merchant).orElse(null);
        if (source == null || anchor == null) return false;
        List<MissionOffer> available = offers(level, anchor, source);
        if (offerIndex < 0 || offerIndex >= available.size()) return false;

        MissionOffer offer = available.get(offerIndex);
        MissionCatalog.MissionDefinition definition = MissionCatalog.definition(offer.definitionId());
        return deploy(player, definition, offer, ContractKind.GIG);
    }

    /** Starts a configured contract directly for operator testing and mission authoring. */
    public static boolean startConfigured(
            ServerPlayer player,
            String definitionId,
            District targetDistrict) {
        ServerLevel level = (ServerLevel) player.level();
        MissionCatalog.MissionDefinition definition;
        try {
            definition = MissionCatalog.definition(definitionId);
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.literal("Unknown mission: " + definitionId)
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!definition.targetDistricts().contains(targetDistrict)) {
            player.sendSystemMessage(Component.literal(
                            definition.title() + " is not configured for " + targetDistrict.label() + ".")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        MegacityLayout layout = NeonCityGenerator.layout();
        MegacityLayout.Node node = layout.node(targetDistrict);
        UUID playerId = player.getUUID();
        long hash = MegacityLayout.mix(
                level.getSeed() ^ layout.seed() ^ definition.id().hashCode(),
                (int) playerId.getMostSignificantBits(),
                (int) playerId.getLeastSignificantBits());
        int targetX = node.x() - 96 + Math.floorMod((int) hash, 193);
        int targetZ = node.z() - 96 + Math.floorMod((int) Long.rotateLeft(hash, 29), 193);
        int reward = definition.rewardMin() + Math.floorMod(
                (int) Long.rotateRight(hash, 37),
                definition.rewardMax() - definition.rewardMin() + 1);
        MissionOffer offer = new MissionOffer(
                definition.id(), definition.type(), definition.title(), definition.briefing(),
                definition.objectiveText(), targetDistrict.ordinal(), targetX, targetZ, reward,
                definition.streetCred());
        return deploy(player, definition, offer, ContractKind.GIG);
    }

    /** Starts one currently unlocked storyline mission for the player's party. */
    public static boolean startStory(ServerPlayer player, String definitionId) {
        if (!(player.level() instanceof ServerLevel level)
                || !NeonCityGenerator.isEnabled()
                || !NeonCityGenerator.isMegacityWorld(level)) {
            player.sendSystemMessage(Component.literal(
                            "Story missions require a Project Moon Megacity world.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        StoryMissionCatalog.StoryMission story;
        try {
            story = StoryMissionCatalog.definition(definitionId);
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.literal("Unknown story mission: " + definitionId)
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        Set<String> completed = MissionPlayerData.completedStory(player);
        int streetCred = PartyService.sharedStreetCred(player);
        if (completed.contains(story.id())) {
            player.sendSystemMessage(Component.literal("Story mission already completed: " + story.id())
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }
        if (!story.available(completed, streetCred)) {
            player.sendSystemMessage(Component.literal(
                            "Story mission locked. Requires prior missions and "
                                    + story.requiredStreetCred() + " Street Cred.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        PartyService.ParticipantSnapshot storyParticipants = PartyService.participantSnapshot(player);
        List<ServerPlayer> onlineStoryParticipants = PartyService.onlineMembers(
                level.getServer(), storyParticipants);
        if (onlineStoryParticipants.size() != storyParticipants.playerIds().size()) {
            player.sendSystemMessage(Component.literal(
                            "All party members must be online to start a story mission.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        for (ServerPlayer member : onlineStoryParticipants) {
            Set<String> memberCompleted = MissionPlayerData.completedStory(member);
            if (memberCompleted.contains(story.id())
                    || !story.available(memberCompleted, PartyService.sharedStreetCred(member))) {
                player.sendSystemMessage(Component.literal(
                                member.getGameProfile().name()
                                        + " has not unlocked this story mission.")
                        .withStyle(ChatFormatting.RED));
                return false;
            }
        }

        MissionCatalog.MissionDefinition definition = story.encounter();
        UUID playerId = player.getUUID();
        long hash = MegacityLayout.mix(
                level.getSeed() ^ NeonCityGenerator.layout().seed() ^ definition.id().hashCode(),
                (int) playerId.getMostSignificantBits(),
                (int) playerId.getLeastSignificantBits());
        List<District> choices = definition.targetDistricts();
        District district = choices.get(Math.floorMod((int) hash, choices.size()));
        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        int targetX = node.x() - 112 + Math.floorMod((int) Long.rotateLeft(hash, 19), 225);
        int targetZ = node.z() - 112 + Math.floorMod((int) Long.rotateRight(hash, 27), 225);
        int reward = definition.rewardMin() + Math.floorMod(
                (int) Long.rotateRight(hash, 41),
                definition.rewardMax() - definition.rewardMin() + 1);
        MissionOffer offer = new MissionOffer(
                definition.id(), definition.type(), definition.title(), definition.briefing(),
                definition.objectiveText(), district.ordinal(), targetX, targetZ, reward,
                definition.streetCred());
        return deploy(player, definition, offer, ContractKind.STORY_MISSION);
    }

    static boolean acceptDiscovered(
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            MissionOffer offer) {
        if (!definition.id().equals(offer.definitionId())
                || offer.targetDistrictOrdinal() < 0
                || offer.targetDistrictOrdinal() >= District.values().length
                || !definition.targetDistricts().contains(
                        District.values()[offer.targetDistrictOrdinal()])) {
            return false;
        }
        return deploy(player, definition, offer, ContractKind.GIG);
    }

    public static List<StoryMissionCatalog.StoryMission> availableStoryMissions(
            ServerPlayer player) {
        return StoryMissionCatalog.available(
                MissionPlayerData.completedStory(player), PartyService.sharedStreetCred(player));
    }

    public static List<JournalEntry> journalEntries(ServerPlayer player) {
        return MissionJournalData.get((ServerLevel) player.level()).entries(player.getUUID());
    }

    /** Leader-authorized contract cancellation without payment. */
    public static boolean abandon(ServerPlayer player) {
        ActiveMission mission = activeMission(player).orElse(null);
        if (mission == null) return false;
        ServerLevel level = (ServerLevel) player.level();
        ContractContext context = contractContext(player).orElse(null);
        if (context == null) {
            player.sendSystemMessage(Component.literal(
                            "Contract state is incomplete; reconnect before abandoning it.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!canAbandon(level, player.getUUID(), context)) {
            player.sendSystemMessage(Component.literal(
                            "Only the contract party leader can abandon this shared contract.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!PartyService.markContractCompleted(level, context.instanceId())) {
            player.sendSystemMessage(Component.literal("This contract is already closed.")
                    .withStyle(ChatFormatting.YELLOW));
            return false;
        }

        MissionJournalData.get(level).status(
                context.instanceId(), JournalStatus.ABANDONED, level.getGameTime());
        clearDeploymentRetry(context.instanceId());
        cleanup(level, player, mission);
        clearParticipants(level, context);
        for (ServerPlayer member : PartyService.onlineMembers(
                level.getServer(), context.participants())) {
            member.sendSystemMessage(Component.literal(
                            context.kind().displayName() + " abandoned: " + mission.title())
                    .withStyle(ChatFormatting.YELLOW));
        }
        syncParticipants(level, context);
        return true;
    }

    static boolean canAbandon(
            ServerLevel level, UUID playerId, ContractContext context) {
        List<UUID> participants = context.participants().playerIds();
        if (!participants.contains(playerId)) return false;
        UUID currentLeader = context.participants().partyId()
                .flatMap(partyId -> PartySavedData.get(level).party(partyId))
                .map(PartySavedData.PartySnapshot::leader)
                .orElse(null);
        // A disbanded party or leadership transferred outside the accepted cohort must not leave
        // the snapshotted participants with an uncloseable contract.
        return currentLeader == null
                || !participants.contains(currentLeader)
                || playerId.equals(currentLeader);
    }

    private static boolean deploy(
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            MissionOffer offer,
            ContractKind kind) {
        ServerLevel level = (ServerLevel) player.level();
        PartyService.ParticipantSnapshot party = PartyService.participantSnapshot(player);
        List<ServerPlayer> onlineParticipants = PartyService.onlineMembers(
                player.level().getServer(), party);
        PartySavedData.PartySnapshot currentParty = party.partyId()
                .flatMap(partyId -> PartySavedData.get(level).party(partyId))
                .orElse(null);
        if (currentParty != null && onlineParticipants.stream().noneMatch(
                member -> member.getUUID().equals(currentParty.leader()))) {
            player.sendSystemMessage(Component.literal(
                            "The party leader must be online before accepting a shared contract.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        PartyService.ParticipantSnapshot participants = new PartyService.ParticipantSnapshot(
                party.partyId(), onlineParticipants.stream().map(ServerPlayer::getUUID).toList());
        if (onlineParticipants.stream().anyMatch(member -> activeMission(member).isPresent())) {
            player.sendSystemMessage(Component.literal("A party member already has an active contract.")
                    .withStyle(ChatFormatting.RED));
            forceSync(player);
            return false;
        }
        BlockPos target = new BlockPos(
                offer.targetX(), NeonCityGenerator.CITY_GROUND_Y + 1, offer.targetZ());
        ActiveMission active = new ActiveMission(
                definition.id(), definition.type(), definition.title(), definition.briefing(),
                definition.objectiveText(), District.values()[offer.targetDistrictOrdinal()],
                target, offer.reward(), "",
                definition.cargoItem() == null ? "" : definition.cargoItem().toString(),
                definition.cargoCount(), level.getGameTime());
        ContractContext context = new ContractContext(
                kind, definition.streetCred(), UUID.randomUUID(), participants, false, false);
        for (ServerPlayer member : onlineParticipants) {
            save(member, active);
            saveContext(member, context);
        }
        if (definition.type() == MissionCatalog.MissionType.SHIP_ITEM
                && issueCargo(level, player, definition, active) == null) {
            clearParticipants(level, context);
            player.sendSystemMessage(Component.literal("Cargo deployment failed; no contract was consumed.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        ActiveMission spawned = active;
        if (kind == ContractKind.STORY_MISSION) {
            ActiveMission activated = activate(player, definition, active, context);
            if (activated != null) {
                spawned = activated;
            } else {
                scheduleDeploymentRetry(level, context);
            }
        }
        PartyService.registerContract(level, context.instanceId(), participants);
        MissionJournalData.get(level).accept(
                participants, context, spawned, level.getGameTime());
        player.sendSystemMessage(Component.literal(
                        kind.displayName() + " accepted: " + spawned.title()
                                + " // " + spawned.clientObjective(player))
                .withStyle(ChatFormatting.AQUA));
        syncParticipants(level, context);
        return true;
    }

    static List<MissionOffer> offers(ServerLevel level, BlockPos anchor, District source) {
        return offers(NeonCityGenerator.layout(), level.getSeed(), anchor, source);
    }

    static List<MissionOffer> offers(
            MegacityLayout layout, long worldSeed, BlockPos anchor, District source) {
        List<MissionOffer> offers = new ArrayList<>();
        int index = 0;
        for (MissionCatalog.MissionDefinition definition : MissionCatalog.definitions()) {
            long hash = MegacityLayout.mix(
                    worldSeed ^ layout.seed() ^ OFFER_SALT ^ definition.id().hashCode(),
                    anchor.getX(), anchor.getZ() + index++);
            List<District> choices = definition.targetDistricts();
            District targetDistrict = choices.contains(source)
                    ? source : choices.get(Math.floorMod((int) hash, choices.size()));
            MegacityLayout.Node node = layout.node(targetDistrict);
            int targetX = node.x() - TARGET_OFFSET
                    + Math.floorMod((int) Long.rotateLeft(hash, 17), TARGET_OFFSET * 2 + 1);
            int targetZ = node.z() - TARGET_OFFSET
                    + Math.floorMod((int) Long.rotateRight(hash, 23), TARGET_OFFSET * 2 + 1);
            int reward = definition.rewardMin() + Math.floorMod(
                    (int) Long.rotateRight(hash, 39),
                    definition.rewardMax() - definition.rewardMin() + 1);
            offers.add(new MissionOffer(
                    definition.id(), definition.type(), definition.title(), definition.briefing(),
                    definition.objectiveText(), targetDistrict.ordinal(), targetX, targetZ, reward,
                    definition.streetCred()));
        }
        return List.copyOf(offers);
    }

    public static Optional<ActiveMission> activeMission(ServerPlayer player) {
        MissionPlayerData.migrateLegacyKeys(player, persistentKeys());
        CompoundTag data = MissionPlayerData.persisted(player);
        if (!data.getBoolean(ACTIVE).orElse(false)) return Optional.empty();
        try {
            MissionCatalog.MissionType type = MissionCatalog.MissionType.valueOf(
                    data.getString(TYPE).orElseThrow());
            int district = data.getInt(DISTRICT).orElseThrow();
            if (district < 0 || district >= District.values().length) throw new IllegalStateException();
            return Optional.of(new ActiveMission(
                    data.getString(DEFINITION).orElseThrow(),
                    type,
                    data.getString(TITLE).orElse("Mission"),
                    data.getString(BRIEFING).orElse(""),
                    data.getString(OBJECTIVE).orElse("Complete the objective"),
                    District.values()[district],
                    new BlockPos(
                            data.getInt(TARGET_X).orElse(0),
                            data.getInt(TARGET_Y).orElse(NeonCityGenerator.CITY_GROUND_Y + 1),
                            data.getInt(TARGET_Z).orElse(0)),
                    Math.max(1, data.getInt(REWARD).orElse(1)),
                    data.getString(ACTOR_UUID).orElse(""),
                    data.getString(CARGO_ITEM).orElse(""),
                    Math.max(0, data.getInt(CARGO_COUNT).orElse(0)),
                    data.getLong(ACCEPTED_TICK).orElse(0L)));
        } catch (RuntimeException exception) {
            clear(player);
            return Optional.empty();
        }
    }

    public static Optional<ContractContext> contractContext(ServerPlayer player) {
        if (activeMission(player).isEmpty()) return Optional.empty();
        CompoundTag data = MissionPlayerData.persisted(player);
        try {
            ContractKind kind = ContractKind.valueOf(
                    data.getString(CONTRACT_KIND).orElse(ContractKind.GIG.name()));
            int streetCred = Math.max(0, data.getInt(STREET_CRED).orElse(0));
            UUID instanceId = data.getString(INSTANCE_ID)
                    .filter(value -> !value.isBlank())
                    .map(UUID::fromString)
                    .orElseGet(() -> UUID.nameUUIDFromBytes((
                            player.getUUID() + ":"
                                    + data.getString(DEFINITION).orElse("legacy") + ":"
                                    + data.getLong(ACCEPTED_TICK).orElse(0L)).getBytes(
                                            java.nio.charset.StandardCharsets.UTF_8)));
            Optional<UUID> partyId = data.getString(PARTY_ID)
                    .filter(value -> !value.isBlank()).map(UUID::fromString);
            List<UUID> participants = new ArrayList<>();
            for (Tag entry : data.getListOrEmpty(PARTICIPANTS)) {
                entry.asString().filter(value -> !value.isBlank())
                        .map(UUID::fromString).ifPresent(participants::add);
            }
            if (participants.isEmpty()) participants.add(player.getUUID());
            return Optional.of(new ContractContext(
                    kind,
                    streetCred,
                    instanceId,
                    new PartyService.ParticipantSnapshot(partyId, participants),
                    data.getBoolean(DEPLOYED).orElse(true),
                    data.getBoolean(COMPLETING).orElse(false)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static void tickPlayer(ServerPlayer player, MegacityLayout.Location location) {
        Optional<ActiveMission> optional = activeMission(player);
        if (optional.isEmpty()) {
            syncIfChanged(player, null);
            return;
        }
        ActiveMission mission = optional.get();
        ServerLevel level = (ServerLevel) player.level();
        ContractContext context = contractContext(player).orElse(null);
        if (context == null) {
            syncIfChanged(player, mission);
            return;
        }
        if (PartyService.isContractCompleted(level, context.instanceId())
                || PartyService.requiresContractClear(
                        level, context.instanceId(), player.getUUID())) {
            clearDeploymentRetry(context.instanceId());
            if (context.completing()) {
                MissionJournalData.get(level).status(
                        context.instanceId(), JournalStatus.COMPLETED, level.getGameTime());
                AmbientGigService.recordCompletion(
                        level, context.instanceId(), context.participants(),
                        mission.targetDistrict());
            }
            cleanup(level.getServer().overworld(), player, mission);
            clear(player);
            PartyService.acknowledgeContractClear(
                    level, context.instanceId(), player.getUUID());
            forceSync(player);
            return;
        }
        MissionCatalog.MissionDefinition definition = definition(context, mission.definitionId());
        if (definition == null) {
            player.sendSystemMessage(Component.literal(
                            "Contract definition is no longer available: " + mission.definitionId())
                    .withStyle(ChatFormatting.RED), true);
            syncIfChanged(player, mission);
            return;
        }
        if (!context.deployed()
                && MissionSiteData.get(level).hasReservation(context.instanceId())) {
            syncIfChanged(player, mission);
            return;
        }
        if (!context.deployed() && participantNear(
                level, context.participants(), mission.target(),
                definition.activationRadius())
                && deploymentAttemptReady(level, context)) {
            ActiveMission activated = activate(player, definition, mission, context);
            if (activated == null) {
                scheduleDeploymentRetry(level, context);
                player.sendSystemMessage(Component.literal(
                                "Objective setup delayed; contract retained while another "
                                        + "building is located.")
                        .withStyle(ChatFormatting.YELLOW), true);
                syncIfChanged(player, mission);
                return;
            }
            clearDeploymentRetry(context.instanceId());
            mission = activated;
            context = contractContext(player).orElse(context.withDeployed(true));
        }
        syncIfChanged(player, mission);
    }

    private static ActiveMission activate(
            ServerPlayer owner,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission,
            ContractContext context) {
        ServerLevel level = (ServerLevel) owner.level();
        MissionSiteData siteData = MissionSiteData.get(level);
        DeploymentRetry retry = DEPLOYMENT_RETRIES.get(context.instanceId());
        int retryCycle = retry == null ? 0 : retry.cycle();
        long selectionSalt = context.instanceId().getMostSignificantBits()
                ^ context.instanceId().getLeastSignificantBits()
                ^ (long) retryCycle * 0xD1B54A32D192ED03L;
        int baseRadius = context.kind() == ContractKind.STORY_MISSION ? 16 : 10;
        int searchRadius = Math.min(
                MAX_SITE_SEARCH_RADIUS_CHUNKS, baseRadius + retryCycle * 2);
        Set<String> rejectedSites = new HashSet<>();
        for (int attempt = 0; attempt < 8; attempt++) {
            MissionBuildingPlanner.Site candidate = MissionBuildingPlanner.findSite(
                    level,
                    mission.targetDistrict(),
                    mission.target(),
                    searchRadius,
                    selectionSalt + attempt * 0x9E3779B97F4A7C15L,
                    1,
                    site -> {
                        String key = siteReservationKey(site);
                        return !rejectedSites.contains(key)
                                && !siteData.isReservedByOther(key, context.instanceId());
                    }).orElse(null);
            if (candidate == null) {
                continue;
            }
            String reservationKey = siteReservationKey(candidate);
            if (!rejectedSites.add(reservationKey)
                    || !siteData.reserve(reservationKey, context.instanceId())) {
                continue;
            }
            MissionBuildingPlanner.InstallationResult installation =
                    MissionBuildingPlanner.install(level, candidate);
            if (installation == MissionBuildingPlanner.InstallationResult.UNSAFE) {
                siteData.releaseIfOwned(reservationKey, context.instanceId());
                continue;
            }
            BlockPos target = mission.type() == MissionCatalog.MissionType.STEAL_DATA
                    ? candidate.target().above()
                    : candidate.target();
            ActiveMission prepared = new ActiveMission(
                    mission.definitionId(), mission.type(), mission.title(), mission.briefing(),
                    mission.objective(), mission.targetDistrict(), target, mission.reward(), "",
                    mission.cargoItem(), mission.cargoCount(), mission.acceptedTick());
            for (ServerPlayer member : PartyService.onlineMembers(
                    level.getServer(), context.participants())) {
                saveSite(member, candidate);
                saveNavigationTarget(member, MissionBuildingPlanner.navigationTarget(candidate));
            }
            ActiveMission spawned = switch (prepared.type()) {
                case ASSASSINATE_TARGET -> spawnAssassination(level, owner, definition, prepared);
                case NEUTRALIZE_CYBERPSYCHO -> spawnCyberpsycho(level, owner, definition, prepared);
                case STEAL_DATA -> installDataObjective(level, owner, definition, prepared);
                case SHIP_ITEM -> installDeliveryObjective(level, owner, definition, prepared);
            };
            if (spawned == null) {
                for (ServerPlayer member : PartyService.onlineMembers(
                        level.getServer(), context.participants())) {
                    clearSite(member);
                    clearNavigationTarget(member);
                }
                siteData.releaseIfOwned(reservationKey, context.instanceId());
                continue;
            }
            ContractContext deployed = context.withDeployed(true);
            for (ServerPlayer member : PartyService.onlineMembers(
                    level.getServer(), context.participants())) {
                save(member, spawned);
                saveContext(member, deployed);
            }
            clearDeploymentRetry(context.instanceId());
            MissionJournalData.get(level).accept(
                    context.participants(), deployed, spawned,
                    MissionBuildingPlanner.navigationTarget(candidate), level.getGameTime());
            syncParticipants(level, deployed);
            return spawned;
        }
        return null;
    }

    private static boolean deploymentAttemptReady(
            ServerLevel level, ContractContext context) {
        DeploymentRetry retry = DEPLOYMENT_RETRIES.get(context.instanceId());
        return retry == null || level.getGameTime() >= retry.nextTick();
    }

    private static void scheduleDeploymentRetry(
            ServerLevel level, ContractContext context) {
        DeploymentRetry previous = DEPLOYMENT_RETRIES.get(context.instanceId());
        int cycle = previous == null ? 1 : Math.min(64, previous.cycle() + 1);
        DEPLOYMENT_RETRIES.put(
                context.instanceId(),
                new DeploymentRetry(level.getGameTime() + DEPLOY_RETRY_DELAY_TICKS, cycle));
    }

    private static void clearDeploymentRetry(UUID instanceId) {
        DEPLOYMENT_RETRIES.remove(instanceId);
    }

    public static boolean activateDataTerminal(ServerPlayer player, BlockPos position) {
        ActiveMission mission = activeMission(player).orElse(null);
        ContractContext context = contractContext(player).orElse(null);
        if (mission == null || mission.type() != MissionCatalog.MissionType.STEAL_DATA
                || context == null || !context.deployed() || context.completing()
                || player.level() != player.level().getServer().overworld()
                || !mission.target().equals(position)
                || !player.level().getBlockState(position).is(MissionBlocks.DATA_TERMINAL.get())) {
            player.sendSystemMessage(Component.literal("ACCESS DENIED // NO MATCHING CONTRACT")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        player.level().playSound(null, position, SoundEvents.VAULT_OPEN_SHUTTER,
                SoundSource.BLOCKS, 0.8F, 1.4F);
        complete(player, mission);
        return true;
    }

    public static boolean activateDeliveryTerminal(ServerPlayer player, BlockPos position) {
        ActiveMission mission = activeMission(player).orElse(null);
        ContractContext context = contractContext(player).orElse(null);
        if (mission == null || mission.type() != MissionCatalog.MissionType.SHIP_ITEM
                || context == null || !context.deployed() || context.completing()
                || player.level() != player.level().getServer().overworld()
                || !mission.target().equals(position)
                || !player.level().getBlockState(position)
                        .is(MissionBlocks.DELIVERY_TERMINAL.get())) {
            player.sendSystemMessage(Component.literal("DELIVERY REJECTED // NO MATCHING CONTRACT")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        Item cargo = MissionBlocks.CONTRACT_CARGO.get();
        int carried = countNearby(
                (ServerLevel) player.level(), context.participants(), position,
                DELIVERY_HANDOFF_RADIUS, cargo, context.instanceId());
        if (carried < mission.cargoCount()) {
            player.sendSystemMessage(Component.literal(
                            "DELIVERY REJECTED // CONTRACT CARGO "
                                    + Math.min(carried, mission.cargoCount()) + "/"
                                    + mission.cargoCount())
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        player.level().playSound(null, position, SoundEvents.VAULT_OPEN_SHUTTER,
                SoundSource.BLOCKS, 0.8F, 1.2F);
        complete(player, mission);
        return true;
    }

    public static void onEntityDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        CompoundTag tags = entity.getPersistentData();
        if (!tags.getBoolean(ACTOR_TAG).orElse(false)
                || !ROLE_TARGET.equals(tags.getString(ACTOR_ROLE).orElse(""))) {
            return;
        }
        UUID instanceId;
        try {
            String instance = tags.getString(ACTOR_INSTANCE).orElse("");
            if (instance.isBlank()) {
                instance = tags.getString(ACTOR_OWNER).orElseThrow();
            }
            instanceId = UUID.fromString(instance);
        } catch (RuntimeException exception) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel objectiveLevel)) return;
        ServerPlayer player = findRepresentative(objectiveLevel, instanceId);
        if (player == null) {
            MissionJournalData.get(objectiveLevel).status(
                    instanceId, JournalStatus.FAILED, objectiveLevel.getGameTime());
            PartyService.markContractCompleted(objectiveLevel, instanceId);
            cleanupContractWorld(objectiveLevel.getServer(), instanceId);
            return;
        }
        ActiveMission mission = activeMission(player).orElse(null);
        ContractContext context = contractContext(player).orElse(null);
        if (mission == null
                || context == null
                || !mission.definitionId().equals(tags.getString(ACTOR_DEFINITION).orElse(""))) {
            return;
        }
        if (event.getSource().getEntity() instanceof ServerPlayer killer
                && context.participants().playerIds().contains(killer.getUUID())) {
            complete(player, mission);
        } else {
            fail(player, mission, "Target lost before your party neutralized it.");
        }
    }

    public static Optional<OpenCityMapPacket.Marker> activeMarker(ServerPlayer player) {
        return activeMission(player).map(mission -> {
            BlockPos navigation = navigationTarget(player, mission);
            String referenceId = contractContext(player)
                    .map(context -> context.instanceId().toString()).orElse("");
            return new OpenCityMapPacket.Marker(
                    OpenCityMapPacket.MarkerKind.ACTIVE_MISSION,
                    navigation.getX(), navigation.getZ(),
                    mission.targetDistrict().ordinal(), "literal:" + mission.title(),
                    referenceId);
        });
    }

    public static boolean isMissionActor(Entity entity) {
        return entity.getPersistentData().getBoolean(ACTOR_TAG).orElse(false);
    }

    /** Returns whether an entity belongs to an active main-story encounter. */
    public static boolean isStoryMissionActor(Entity entity) {
        if (!isMissionActor(entity)) {
            return false;
        }
        String definitionId = entity.getPersistentData()
                .getString(ACTOR_DEFINITION).orElse("");
        return !definitionId.isBlank() && StoryMissionCatalog.definitions().stream()
                .anyMatch(definition -> definition.id().equals(definitionId));
    }

    /** Rejects a persisted mission actor when its terminal contract's chunk loads later. */
    public static boolean removeIfTerminal(ServerLevel level, Entity entity) {
        if (!isMissionActor(entity)) return false;
        UUID instanceId;
        try {
            instanceId = entity.getPersistentData().getString(ACTOR_INSTANCE)
                    .filter(value -> !value.isBlank()).map(UUID::fromString).orElse(null);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (instanceId == null || !PartyService.isContractTerminal(level, instanceId)) {
            return false;
        }
        String role = entity.getPersistentData().getString(ACTOR_ROLE).orElse("");
        if (isObjectiveBlock(level, entity.blockPosition(), role)) {
            level.setBlock(entity.blockPosition(), Blocks.AIR.defaultBlockState(), 3);
        }
        MissionSiteData.get(level).releaseOwned(instanceId);
        return true;
    }

    public static void forceSync(ServerPlayer player) {
        LAST_SYNC.remove(player.getUUID());
        syncIfChanged(player, activeMission(player).orElse(null));
        AmbientGigService.syncJournal(player);
        if (NetworkRegistry.hasChannel(player.connection, OpenCityMapPacket.TYPE.id())) {
            CityMapService.open(player, false);
        }
    }

    public static void forgetPlayer(ServerPlayer player) {
        LAST_SYNC.remove(player.getUUID());
        AmbientGigService.forgetPlayer(player);
    }

    /** Reconciles deferred progression and projects a party member's active contract on login. */
    public static void onPlayerLogin(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        PartyService.onPlayerLogin(player);
        for (String storyId : PartyService.claimPendingStoryIds(player)) {
            MissionPlayerData.completeStory(player, storyId);
        }
        ActiveMission existingMission = activeMission(player).orElse(null);
        boolean hadActiveData = existingMission != null;
        ContractContext existing = contractContext(player).orElse(null);
        if (hadActiveData && existing == null) {
            clear(player);
        }
        if (existing != null && (PartyService.isContractCompleted(level, existing.instanceId())
                || PartyService.requiresContractClear(
                        level, existing.instanceId(), player.getUUID()))) {
            if (existing.completing()) {
                MissionJournalData.get(level).status(
                        existing.instanceId(), JournalStatus.COMPLETED, level.getGameTime());
                if (existingMission != null) {
                    AmbientGigService.recordCompletion(
                            level, existing.instanceId(), existing.participants(),
                            existingMission.targetDistrict());
                }
            }
            if (existingMission != null) {
                cleanup(level.getServer().overworld(), player, existingMission);
            } else {
                cleanupContractWorld(level.getServer(), existing.instanceId());
            }
            purgeCargo(player, existing.instanceId());
            clear(player);
            PartyService.acknowledgeContractClear(
                    level, existing.instanceId(), player.getUUID());
        }
        ActiveMission localMission = activeMission(player).orElse(null);
        ContractContext localContext = contractContext(player).orElse(null);
        if (localMission != null && localContext != null && !localContext.deployed()) {
            UUID localInstanceId = localContext.instanceId();
            String localDefinitionId = localMission.definitionId();
            JournalEntry canonical = MissionJournalData.get(level).entries(player.getUUID()).stream()
                    .filter(entry -> entry.instanceId().equals(localInstanceId))
                    .filter(entry -> entry.status() == JournalStatus.ACTIVE)
                    .filter(entry -> entry.definitionId().equals(localDefinitionId))
                    .findFirst().orElse(null);
            boolean legacyReservation = MissionSiteData.get(level).hasReservation(localInstanceId);
            if (canonical != null && (canonical.deployed() || legacyReservation)) {
                RecoveredDeployment recovered = canonical.deployed()
                        ? new RecoveredDeployment(
                                new BlockPos(
                                        canonical.targetX(), canonical.targetY(),
                                        canonical.targetZ()),
                                localMission.actorUuid())
                        : recoverLegacyDeployment(level, localContext, localMission, canonical);
                localMission = new ActiveMission(
                        canonical.definitionId(), canonical.type(), canonical.title(),
                        canonical.briefing(), canonical.objective(), canonical.targetDistrict(),
                        recovered.target(), canonical.reward(), recovered.actorUuid(),
                        localMission.cargoItem(),
                        localMission.cargoCount(), canonical.acceptedTick());
                localContext = localContext.withDeployed(true);
                save(player, localMission);
                saveContext(player, localContext);
                clearSite(player);
                saveNavigationTarget(player, new BlockPos(
                        canonical.navigationX(), recovered.target().getY(),
                        canonical.navigationZ()));
                clearDeploymentRetry(localContext.instanceId());
                MissionJournalData.get(level).accept(
                        localContext.participants(), localContext, localMission,
                        new BlockPos(canonical.navigationX(), recovered.target().getY(),
                                canonical.navigationZ()),
                        level.getGameTime());
            }
        }
        PartyService.ParticipantSnapshot searchParticipants = localContext == null
                ? PartyService.participantSnapshot(player) : localContext.participants();
        for (ServerPlayer member : PartyService.onlineMembers(
                level.getServer(), searchParticipants)) {
            if (member == player) continue;
            ActiveMission remoteMission = activeMission(member).orElse(null);
            ContractContext remoteContext = contractContext(member).orElse(null);
            if (remoteMission == null || remoteContext == null
                    || !remoteContext.participants().playerIds().contains(player.getUUID())
                    || localContext != null
                            && !remoteContext.instanceId().equals(localContext.instanceId())) {
                continue;
            }
            boolean adopted = localContext == null
                    || !localContext.deployed() && remoteContext.deployed();
            if (adopted) {
                save(player, remoteMission);
                saveContext(player, remoteContext);
                clearSite(player);
                clearNavigationTarget(player);
                localMission = remoteMission;
                localContext = remoteContext;
            }
            boolean copiedSite = false;
            if (localContext != null && localContext.deployed() && remoteContext.deployed()
                    && site(player).isEmpty()) {
                MissionBuildingPlanner.Site remoteSite = site(member).orElse(null);
                if (remoteSite != null) {
                    saveSite(player, remoteSite);
                    saveNavigationTarget(
                            player, MissionBuildingPlanner.navigationTarget(remoteSite));
                    copiedSite = true;
                }
            }
            if (localContext != null && localContext.deployed()
                    && persistedNavigationTarget(player).isEmpty()) {
                persistedNavigationTarget(member).ifPresent(
                        navigation -> saveNavigationTarget(player, navigation));
            }
            if (adopted || copiedSite) break;
        }
        if (activeMission(player).isEmpty()) {
            PartyService.acknowledgeMissingContracts(player);
        }
        ActiveMission reconciledMission = activeMission(player).orElse(null);
        ContractContext reconciledContext = contractContext(player).orElse(null);
        if (reconciledMission != null && reconciledContext != null) {
            if (reconciledContext.deployed()) {
                if (persistedNavigationTarget(player).isEmpty()) {
                    MissionJournalData.get(level).entries(player.getUUID()).stream()
                            .filter(entry -> entry.instanceId().equals(reconciledContext.instanceId()))
                            .filter(JournalEntry::deployed)
                            .findFirst()
                            .ifPresent(entry -> saveNavigationTarget(player, new BlockPos(
                                    entry.navigationX(), reconciledMission.target().getY(),
                                    entry.navigationZ())));
                }
                BlockPos reconciledNavigation = navigationTarget(player, reconciledMission);
                saveNavigationTarget(player, reconciledNavigation);
                MissionJournalData.get(level).accept(
                        reconciledContext.participants(), reconciledContext, reconciledMission,
                        reconciledNavigation, reconciledMission.acceptedTick());
            } else {
                MissionJournalData.get(level).accept(
                        reconciledContext.participants(), reconciledContext, reconciledMission,
                        reconciledMission.acceptedTick());
            }
        }
        forceSync(player);
    }

    private static RecoveredDeployment recoverLegacyDeployment(
            ServerLevel level,
            ContractContext context,
            ActiveMission localMission,
            JournalEntry journal) {
        BlockPos horizontalTarget = new BlockPos(
                journal.targetX(), localMission.target().getY(), journal.targetZ());
        level.getChunkAt(horizontalTarget);
        String instanceId = context.instanceId().toString();
        for (Entity entity : level.getAllEntities()) {
            CompoundTag data = entity.getPersistentData();
            String role = data.getString(ACTOR_ROLE).orElse("");
            if (!entity.isRemoved()
                    && instanceId.equals(data.getString(ACTOR_INSTANCE).orElse(""))
                    && (ROLE_TARGET.equals(role)
                            || ROLE_DATA_TERMINAL.equals(role)
                            || ROLE_DELIVERY_TERMINAL.equals(role))) {
                return new RecoveredDeployment(
                        entity.blockPosition(), entity.getUUID().toString());
            }
        }
        if (localMission.type() == MissionCatalog.MissionType.STEAL_DATA
                || localMission.type() == MissionCatalog.MissionType.SHIP_ITEM) {
            for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                BlockPos position = new BlockPos(journal.targetX(), y, journal.targetZ());
                if ((localMission.type() == MissionCatalog.MissionType.STEAL_DATA
                                && level.getBlockState(position)
                                        .is(MissionBlocks.DATA_TERMINAL.get()))
                        || (localMission.type() == MissionCatalog.MissionType.SHIP_ITEM
                                && level.getBlockState(position)
                                        .is(MissionBlocks.DELIVERY_TERMINAL.get()))) {
                    return new RecoveredDeployment(position, localMission.actorUuid());
                }
            }
        }
        return new RecoveredDeployment(horizontalTarget, localMission.actorUuid());
    }

    public static void onPlayerClone(
            net.minecraft.world.entity.player.Player original,
            net.minecraft.world.entity.player.Player replacement) {
        MissionPlayerData.copyOnClone(original, replacement);
    }

    public static void reset() {
        LAST_SYNC.clear();
        DEPLOYMENT_RETRIES.clear();
        AmbientGigService.reset();
    }

    static ActiveMission spawnAssassination(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        CityNpc target = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.EVENT);
        if (target == null) return null;
        target.snapTo(mission.target().getX() + 0.5, mission.target().getY(),
                mission.target().getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        target.finalizeSpawn(level, level.getCurrentDifficultyAt(mission.target()),
                EntitySpawnReason.EVENT, null);
        target.setSkinVariant(CityNpc.MISSION_TARGET_SKIN);
        target.setNoAi(true);
        target.setCustomName(Component.literal(definition.targetName()).withStyle(ChatFormatting.GOLD));
        target.setCustomNameVisible(true);
        target.setPersistenceRequired();
        tagActor(target, player, definition, ROLE_TARGET);
        if (!level.noCollision(target) || !level.addFreshEntity(target)) return null;
        spawnGuards(level, player, definition, mission.target());
        return withActor(mission, target.getUUID());
    }

    static ActiveMission spawnCyberpsycho(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        CyberpsychoEntity psycho = FactionEntities.CYBERPSYCHO.get().create(
                level, EntitySpawnReason.EVENT);
        if (psycho == null) return null;
        psycho.snapTo(mission.target().getX() + 0.5, mission.target().getY(),
                mission.target().getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        psycho.finalizeSpawn(level, level.getCurrentDifficultyAt(mission.target()),
                EntitySpawnReason.EVENT, null);
        FactionSquads.equip(psycho, Faction.MILITECH, level.getRandom());
        psycho.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(WeaponItems.gun(definition.cyberpsychoGun()).get()));
        psycho.configure(definition.cyberpsychoHealth(), definition.cyberware());
        psycho.setGrenadeCount(definition.cyberpsychoGrenades());
        psycho.setHome(mission.target());
        psycho.setCustomName(Component.literal(definition.targetName()).withStyle(ChatFormatting.RED));
        psycho.setCustomNameVisible(true);
        psycho.setPersistenceRequired();
        tagActor(psycho, player, definition, ROLE_TARGET);
        if (!level.noCollision(psycho) || !level.addFreshEntity(psycho)) return null;
        spawnGuards(level, player, definition, mission.target());
        return withActor(mission, psycho.getUUID());
    }

    static ActiveMission installDataObjective(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        BlockPos support = mission.target().below();
        BlockState originalSupport = level.getBlockState(support);
        BlockState originalTerminal = level.getBlockState(mission.target());
        if ((!originalSupport.isAir() && !originalSupport.canBeReplaced())
                || (!originalTerminal.isAir() && !originalTerminal.canBeReplaced())
                || !level.setBlock(
                        support, Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3)
                || !level.setBlock(
                        mission.target(), MissionBlocks.DATA_TERMINAL.get().defaultBlockState(), 3)) {
            level.setBlock(support, originalSupport, 3);
            level.setBlock(mission.target(), originalTerminal, 3);
            return null;
        }
        Entity marker = EntityTypes.MARKER.create(level, EntitySpawnReason.EVENT);
        if (marker == null) {
            level.setBlock(support, originalSupport, 3);
            level.setBlock(mission.target(), originalTerminal, 3);
            return null;
        }
        marker.snapTo(
                mission.target().getX() + 0.5,
                mission.target().getY() + 0.5,
                mission.target().getZ() + 0.5,
                0.0F,
                0.0F);
        marker.setInvulnerable(true);
        tagActor(marker, player, definition, ROLE_DATA_TERMINAL);
        if (!level.addFreshEntity(marker)) {
            level.setBlock(support, originalSupport, 3);
            level.setBlock(mission.target(), originalTerminal, 3);
            return null;
        }
        MissionBuildingPlanner.Site site = site(player).orElse(null);
        if (site != null && !MissionBuildingPlanner.hasAccessibleObjectivePath(level, site)) {
            marker.discard();
            level.setBlock(support, originalSupport, 3);
            level.setBlock(mission.target(), originalTerminal, 3);
            return null;
        }
        spawnGuards(level, player, definition, nearestStreet(level, mission.target()));
        return withActor(mission, marker.getUUID());
    }

    static ActiveMission installDeliveryObjective(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        BlockState original = level.getBlockState(mission.target());
        if ((!original.isAir() && !original.canBeReplaced())
                || !level.setBlock(
                        mission.target(),
                        MissionBlocks.DELIVERY_TERMINAL.get().defaultBlockState(), 3)) {
            return null;
        }
        Entity marker = EntityTypes.MARKER.create(level, EntitySpawnReason.EVENT);
        if (marker == null) {
            level.setBlock(mission.target(), original, 3);
            return null;
        }
        marker.snapTo(
                mission.target().getX() + 0.5,
                mission.target().getY() + 0.5,
                mission.target().getZ() + 0.5,
                0.0F,
                0.0F);
        marker.setInvulnerable(true);
        tagActor(marker, player, definition, ROLE_DELIVERY_TERMINAL);
        if (!level.addFreshEntity(marker)) {
            level.setBlock(mission.target(), original, 3);
            return null;
        }
        MissionBuildingPlanner.Site site = site(player).orElse(null);
        if (site != null && !MissionBuildingPlanner.hasAccessibleObjectivePath(level, site)) {
            marker.discard();
            level.setBlock(mission.target(), original, 3);
            return null;
        }
        spawnGuards(level, player, definition, mission.target());
        return withActor(mission, marker.getUUID());
    }

    static ActiveMission issueCargo(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        Item item = item(definition.cargoItem());
        if (item == null || item == Items.AIR) return null;
        ItemStack cargo = new ItemStack(
                MissionBlocks.CONTRACT_CARGO.get(), definition.cargoCount());
        ContractContext context = contractContext(player).orElse(null);
        if (context == null) return null;
        CustomData.update(DataComponents.CUSTOM_DATA, cargo,
                tag -> tag.putString(CARGO_INSTANCE, context.instanceId().toString()));
        if (!player.addItem(cargo) && !cargo.isEmpty()) player.drop(cargo, false);
        level.playSound(null, player.blockPosition(), SoundEvents.BUNDLE_INSERT,
                SoundSource.PLAYERS, 0.8F, 1.1F);
        return mission;
    }

    private static BlockPos prepareTargetArea(
            ServerLevel level,
            MissionCatalog.MissionDefinition definition,
            MissionOffer offer) {
        int chunkX = Math.floorDiv(offer.targetX(), 16);
        int chunkZ = Math.floorDiv(offer.targetZ(), 16);
        if (definition.type() != MissionCatalog.MissionType.SHIP_ITEM) {
            NeonCityGenerator.generateNow(level, chunkX, chunkZ, 1);
        }
        BlockPos approximate = new BlockPos(
                offer.targetX(), NeonCityGenerator.CITY_GROUND_Y + 1, offer.targetZ());
        if (definition.type() == MissionCatalog.MissionType.SHIP_ITEM) return approximate;
        if (definition.type() == MissionCatalog.MissionType.STEAL_DATA) {
            BlockPos interior = findInterior(level, approximate);
            return interior != null ? interior : buildDataSafehouse(level, approximate);
        }
        return nearestStreet(level, approximate);
    }

    private static BlockPos nearestStreet(ServerLevel level, BlockPos approximate) {
        BlockPos direct = com.example.cyberdeck.city.CityWorlds.resolveStreetFeet(
                level, approximate.getX(), approximate.getZ(), approximate.getY());
        if (direct != null) return direct;
        for (int radius = 2; radius <= 56; radius += 2) {
            for (int offset = -radius; offset <= radius; offset += 2) {
                int[][] points = {
                        {approximate.getX() + offset, approximate.getZ() - radius},
                        {approximate.getX() + offset, approximate.getZ() + radius},
                        {approximate.getX() - radius, approximate.getZ() + offset},
                        {approximate.getX() + radius, approximate.getZ() + offset}
                };
                for (int[] point : points) {
                    BlockPos found = com.example.cyberdeck.city.CityWorlds.resolveStreetFeet(
                            level, point[0], point[1], approximate.getY());
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static BlockPos findInterior(ServerLevel level, BlockPos approximate) {
        for (int radius = 0; radius <= 48; radius += 2) {
            int min = Math.max(1, radius);
            for (int dx = -radius; dx <= radius; dx += min) {
                for (int dz = -radius; dz <= radius; dz += min) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = approximate.getX() + dx;
                    int z = approximate.getZ() + dz;
                    BlockPos surface = level.getHeightmapPos(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                    int maxY = Math.min(surface.getY() - 2, NeonCityGenerator.CITY_GROUND_Y + 28);
                    for (int y = NeonCityGenerator.CITY_GROUND_Y + 1; y <= maxY; y++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (interiorCandidate(level, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean interiorCandidate(ServerLevel level, BlockPos position) {
        if (!level.isEmptyBlock(position) || !level.isEmptyBlock(position.above())
                || !level.getBlockState(position.below()).blocksMotion()) return false;
        boolean ceiling = false;
        for (int y = 2; y <= 6; y++) {
            if (level.getBlockState(position.above(y)).blocksMotion()) {
                ceiling = true;
                break;
            }
        }
        if (!ceiling) return false;
        int enclosure = 0;
        int access = 0;
        for (BlockPos direction : List.of(
                new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                new BlockPos(0, 0, 1), new BlockPos(0, 0, -1))) {
            BlockPos adjacent = position.offset(direction);
            if (level.getBlockState(adjacent).blocksMotion()) enclosure++;
            if (level.isEmptyBlock(adjacent) && level.isEmptyBlock(adjacent.above())) access++;
        }
        return enclosure >= 1 && access >= 1;
    }

    private static BlockPos buildDataSafehouse(ServerLevel level, BlockPos approximate) {
        BlockPos street = nearestStreet(level, approximate);
        if (street == null) return null;
        BlockPos center = street.offset(5, 0, 5);
        int floorY = center.getY() - 1;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlock(new BlockPos(center.getX() + dx, floorY, center.getZ() + dz),
                        Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos position = new BlockPos(
                            center.getX() + dx, floorY + dy, center.getZ() + dz);
                    boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    boolean entrance = dz == -3 && dx == 0 && dy <= 2;
                    level.setBlock(position, wall && !entrance
                            ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(new BlockPos(center.getX() + dx, floorY + 4, center.getZ() + dz),
                        Blocks.DEEPSLATE_TILES.defaultBlockState(), 3);
            }
        }
        return center;
    }

    private static void spawnGuards(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            BlockPos home) {
        if (home == null) return;
        RandomSource random = level.getRandom();
        int spawned = 0;
        MissionBuildingPlanner.Site site = site(player).orElse(null);
        if (site != null && !site.patrolRoutes().isEmpty()) {
            for (int index = 0; index < definition.guards(); index++) {
                MissionBuildingPlanner.PatrolRoute route = site.patrolRoutes().get(
                        index % site.patrolRoutes().size());
                BlockPos position = route.waypoints().get(
                        Math.floorMod(index / site.patrolRoutes().size(),
                                route.waypoints().size()));
                FactionEnemy guard = createGuard(
                        level, player, definition, position, route.waypoints(), random);
                if (guard != null) spawned++;
            }
            if (spawned >= definition.guards()) return;
        }
        int deploymentRadius = Math.min(32, definition.objectiveRadius());
        for (int radius = 2; radius <= deploymentRadius
                && spawned < definition.guards(); radius += 2) {
            for (int index = 0; index < 8 && spawned < definition.guards(); index++) {
                double angle = index * Math.PI / 4.0;
                BlockPos probe = home.offset(
                        (int) Math.round(Math.cos(angle) * radius), 0,
                        (int) Math.round(Math.sin(angle) * radius));
                BlockPos position = nearestStreet(level, probe);
                if (position == null
                        || position.distSqr(home) > deploymentRadius * deploymentRadius) continue;
                if (createGuard(level, player, definition, position, List.of(), random) != null) {
                    spawned++;
                }
            }
        }
    }

    private static FactionEnemy createGuard(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            BlockPos position,
            List<BlockPos> patrolRoute,
            RandomSource random) {
        FactionEnemy guard = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        if (guard == null) return null;
        guard.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                random.nextFloat() * 360.0F, 0.0F);
        guard.finalizeSpawn(level, level.getCurrentDifficultyAt(position),
                EntitySpawnReason.EVENT, null);
        guard.setHome(position);
        guard.setPatrolRoute(patrolRoute);
        guard.setPersistenceRequired();
        FactionSquads.equip(guard, Faction.ARASAKA, random);
        tagActor(guard, player, definition, ROLE_GUARD);
        return level.noCollision(guard) && level.addFreshEntity(guard) ? guard : null;
    }

    private static void tagActor(
            Entity entity,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            String role) {
        ContractContext context = contractContext(player).orElseGet(() -> {
            ContractContext legacy = new ContractContext(
                    ContractKind.GIG,
                    definition.streetCred(),
                    UUID.nameUUIDFromBytes((player.getUUID() + ":" + definition.id())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new PartyService.ParticipantSnapshot(
                            Optional.empty(), List.of(player.getUUID())),
                    true,
                    false);
            saveContext(player, legacy);
            return legacy;
        });
        CompoundTag data = entity.getPersistentData();
        data.putBoolean(ACTOR_TAG, true);
        data.putString(ACTOR_OWNER, player.getUUID().toString());
        data.putString(ACTOR_DEFINITION, definition.id());
        data.putString(ACTOR_ROLE, role);
        data.putString(ACTOR_INSTANCE, context.instanceId().toString());
    }

    private static ActiveMission withActor(ActiveMission mission, UUID actor) {
        return new ActiveMission(
                mission.definitionId(), mission.type(), mission.title(), mission.briefing(),
                mission.objective(), mission.targetDistrict(), mission.target(), mission.reward(),
                actor.toString(), mission.cargoItem(), mission.cargoCount(), mission.acceptedTick());
    }

    private static void complete(ServerPlayer player, ActiveMission mission) {
        ContractContext context = contractContext(player).orElse(null);
        if (context == null || context.completing()) return;
        ServerLevel level = (ServerLevel) player.level();
        ContractContext completing = context.withCompleting(true);
        for (ServerPlayer member : PartyService.onlineMembers(
                level.getServer(), context.participants())) {
            if (contractContext(member).map(value -> value.instanceId().equals(context.instanceId()))
                    .orElse(false)) {
                saveContext(member, completing);
            }
        }
        String storyId = context.kind() == ContractKind.STORY_MISSION
                ? mission.definitionId() : "";
        if (!PartyService.settleContract(
                level,
                context.instanceId(),
                context.participants(),
                mission.reward(),
                context.streetCred(),
                storyId)) return;

        MissionJournalData.get(level).status(
                context.instanceId(), JournalStatus.COMPLETED, level.getGameTime());
        clearDeploymentRetry(context.instanceId());
        AmbientGigService.recordCompletion(
                level, context.instanceId(), context.participants(), mission.targetDistrict());

        cleanup(level, player, mission);
        clearParticipants(level, context);
        for (ServerPlayer member : PartyService.onlineMembers(
                level.getServer(), context.participants())) {
            member.sendSystemMessage(Component.literal(
                            context.kind().displayName() + " complete: " + mission.title()
                                    + ". Party paid " + mission.reward() + " emmies and earned "
                                    + context.streetCred() + " Street Cred.")
                    .withStyle(ChatFormatting.GREEN));
        }
        level.playSound(null, player.blockPosition(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.65F, 1.35F);
        syncParticipants(level, context);
    }

    private static void fail(ServerPlayer player, ActiveMission mission, String reason) {
        ContractContext context = contractContext(player).orElse(null);
        ServerLevel level = (ServerLevel) player.level();
        cleanup(level, player, mission);
        if (context == null) {
            clear(player);
            forceSync(player);
            return;
        }
        MissionJournalData.get(level).status(
                context.instanceId(), JournalStatus.FAILED, level.getGameTime());
        clearDeploymentRetry(context.instanceId());
        PartyService.markContractCompleted(level, context.instanceId());
        for (ServerPlayer member : PartyService.onlineMembers(
                level.getServer(), context.participants())) {
            member.sendSystemMessage(Component.literal(
                            context.kind().displayName() + " failed: " + reason)
                    .withStyle(ChatFormatting.RED));
        }
        clearParticipants(level, context);
        syncParticipants(level, context);
    }

    private static void cleanup(ServerLevel level, ServerPlayer player, ActiveMission mission) {
        ServerLevel objectiveLevel = level.getServer().overworld();
        objectiveLevel.getChunkAt(mission.target());
        clearObjectiveBlock(objectiveLevel, mission);
        ContractContext context = contractContext(player).orElse(null);
        if (context != null) {
            cleanupContractWorld(objectiveLevel.getServer(), context.instanceId());
        }
        MissionBuildingPlanner.Site site = site(player).orElse(null);
        AABB area = site == null
                ? new AABB(mission.target()).inflate(48.0, 24.0, 48.0)
                : new AABB(
                        site.bounds().minX(), site.bounds().minY(), site.bounds().minZ(),
                        site.bounds().maxX() + 1.0, site.bounds().maxY() + 1.0,
                        site.bounds().maxZ() + 1.0).inflate(4.0);
        for (CityNpc npc : objectiveLevel.getEntitiesOfClass(CityNpc.class, area,
                entity -> ownedBy(entity, player, mission))) npc.discard();
        for (FactionEnemy enemy : objectiveLevel.getEntitiesOfClass(FactionEnemy.class, area,
                entity -> ownedBy(entity, player, mission))) enemy.discard();
    }

    private static void clearObjectiveBlock(ServerLevel level, ActiveMission mission) {
        boolean expectedObjective = switch (mission.type()) {
            case STEAL_DATA -> level.getBlockState(mission.target())
                    .is(MissionBlocks.DATA_TERMINAL.get());
            case SHIP_ITEM -> level.getBlockState(mission.target())
                    .is(MissionBlocks.DELIVERY_TERMINAL.get());
            default -> false;
        };
        if (expectedObjective) {
            level.setBlock(mission.target(), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static boolean ownedBy(Entity entity, ServerPlayer player, ActiveMission mission) {
        CompoundTag data = entity.getPersistentData();
        ContractContext context = contractContext(player).orElse(null);
        return data.getBoolean(ACTOR_TAG).orElse(false)
                && (context == null
                        ? player.getUUID().toString().equals(
                                data.getString(ACTOR_OWNER).orElse(""))
                        : context.instanceId().toString().equals(
                                data.getString(ACTOR_INSTANCE).orElse("")))
                && mission.definitionId().equals(data.getString(ACTOR_DEFINITION).orElse(""));
    }

    private static void syncIfChanged(ServerPlayer player, ActiveMission mission) {
        ContractContext context = contractContext(player).orElse(null);
        BlockPos navigation = mission == null ? null : navigationTarget(player, mission);
        MissionSyncPacket packet = mission == null
                ? MissionSyncPacket.inactive()
                : MissionSyncPacket.active(
                        context == null ? ContractKind.GIG : context.kind(),
                        mission.type(), mission.title(), mission.clientObjective(player),
                        mission.targetDistrict().ordinal(), mission.target().getX(),
                        mission.target().getZ(), navigation.getX(), navigation.getZ(),
                        mission.reward(),
                        context == null ? 0 : context.streetCred(),
                        context == null || context.deployed());
        int hash = packet.hashCode();
        Integer previous = LAST_SYNC.get(player.getUUID());
        if ((previous == null || previous != hash)
                && NetworkRegistry.hasChannel(player.connection, MissionSyncPacket.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, packet);
            LAST_SYNC.put(player.getUUID(), hash);
        }
    }

    private static BlockPos navigationTarget(ServerPlayer player, ActiveMission mission) {
        return site(player).map(MissionBuildingPlanner::navigationTarget)
                .or(() -> persistedNavigationTarget(player))
                .orElse(mission.target());
    }

    static void save(ServerPlayer player, ActiveMission mission) {
        CompoundTag data = MissionPlayerData.persisted(player);
        data.putBoolean(ACTIVE, true);
        data.putString(DEFINITION, mission.definitionId());
        data.putString(TYPE, mission.type().name());
        data.putString(TITLE, mission.title());
        data.putString(BRIEFING, mission.briefing());
        data.putString(OBJECTIVE, mission.objective());
        data.putInt(DISTRICT, mission.targetDistrict().ordinal());
        data.putInt(TARGET_X, mission.target().getX());
        data.putInt(TARGET_Y, mission.target().getY());
        data.putInt(TARGET_Z, mission.target().getZ());
        data.putInt(REWARD, mission.reward());
        data.putString(ACTOR_UUID, mission.actorUuid());
        data.putString(CARGO_ITEM, mission.cargoItem());
        data.putInt(CARGO_COUNT, mission.cargoCount());
        data.putLong(ACCEPTED_TICK, mission.acceptedTick());
    }

    static void saveContext(ServerPlayer player, ContractContext context) {
        CompoundTag data = MissionPlayerData.persisted(player);
        data.putString(CONTRACT_KIND, context.kind().name());
        data.putInt(STREET_CRED, Math.max(0, context.streetCred()));
        data.putString(INSTANCE_ID, context.instanceId().toString());
        data.putString(PARTY_ID, context.participants().partyId()
                .map(UUID::toString).orElse(""));
        ListTag participants = new ListTag();
        context.participants().playerIds().stream()
                .map(UUID::toString).map(StringTag::valueOf).forEach(participants::add);
        data.put(PARTICIPANTS, participants);
        data.putBoolean(DEPLOYED, context.deployed());
        data.putBoolean(COMPLETING, context.completing());
    }

    private static void clear(ServerPlayer player) {
        CompoundTag data = MissionPlayerData.persisted(player);
        for (String key : persistentKeys()) {
            data.remove(key);
        }
    }

    private static List<String> persistentKeys() {
        return List.of(
                ACTIVE, DEFINITION, TYPE, TITLE, BRIEFING, OBJECTIVE, DISTRICT,
                TARGET_X, TARGET_Y, TARGET_Z, NAVIGATION_X, NAVIGATION_Z, REWARD, ACTOR_UUID,
                CARGO_ITEM, CARGO_COUNT, ACCEPTED_TICK, CONTRACT_KIND, STREET_CRED,
                INSTANCE_ID, PARTY_ID, PARTICIPANTS, DEPLOYED, COMPLETING, SITE_PLAN);
    }

    static Optional<MissionBuildingPlanner.Site> site(ServerPlayer player) {
        return MissionPlayerData.persisted(player).getCompound(SITE_PLAN)
                .flatMap(MissionBuildingPlanner.Site::load);
    }

    private static void saveSite(ServerPlayer player, MissionBuildingPlanner.Site site) {
        MissionPlayerData.persisted(player).put(SITE_PLAN, site.save());
    }

    private static void clearSite(ServerPlayer player) {
        MissionPlayerData.persisted(player).remove(SITE_PLAN);
    }

    private static void saveNavigationTarget(ServerPlayer player, BlockPos navigation) {
        CompoundTag data = MissionPlayerData.persisted(player);
        data.putInt(NAVIGATION_X, navigation.getX());
        data.putInt(NAVIGATION_Z, navigation.getZ());
    }

    private static Optional<BlockPos> persistedNavigationTarget(ServerPlayer player) {
        CompoundTag data = MissionPlayerData.persisted(player);
        return data.getInt(NAVIGATION_X).flatMap(x -> data.getInt(NAVIGATION_Z)
                .map(z -> new BlockPos(x, player.blockPosition().getY(), z)));
    }

    private static void clearNavigationTarget(ServerPlayer player) {
        CompoundTag data = MissionPlayerData.persisted(player);
        data.remove(NAVIGATION_X);
        data.remove(NAVIGATION_Z);
    }

    private static void clearParticipants(ServerLevel level, ContractContext context) {
        for (ServerPlayer member : PartyService.onlineMembers(
                level.getServer(), context.participants())) {
            ContractContext memberContext = contractContext(member).orElse(null);
            if (memberContext != null && memberContext.instanceId().equals(context.instanceId())) {
                clear(member);
                PartyService.acknowledgeContractClear(
                        level, context.instanceId(), member.getUUID());
            }
        }
    }

    private static void syncParticipants(ServerLevel level, ContractContext context) {
        for (ServerPlayer member : PartyService.onlineMembers(
                level.getServer(), context.participants())) {
            forceSync(member);
        }
    }

    private static ServerPlayer findRepresentative(ServerLevel level, UUID instanceId) {
        for (ServerPlayer player : level.players()) {
            ContractContext context = contractContext(player).orElse(null);
            if (context != null && context.instanceId().equals(instanceId)) return player;
        }
        return null;
    }

    private static void cleanupContractWorld(
            net.minecraft.server.MinecraftServer server, UUID instanceId) {
        String encodedInstance = instanceId.toString();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            purgeCargo(player, instanceId);
        }
        for (ServerLevel level : server.getAllLevels()) {
            ArrayList<Entity> removals = new ArrayList<>();
            for (Entity candidate : level.getAllEntities()) {
                if (candidate instanceof ItemEntity itemEntity
                        && cargoInstance(itemEntity.getItem()).filter(instanceId::equals).isPresent()) {
                    removals.add(candidate);
                    continue;
                }
                CompoundTag data = candidate.getPersistentData();
                if (!encodedInstance.equals(data.getString(ACTOR_INSTANCE).orElse(""))) continue;
                if (isObjectiveBlock(level, candidate.blockPosition(),
                        data.getString(ACTOR_ROLE).orElse(""))) {
                    level.setBlock(candidate.blockPosition(), Blocks.AIR.defaultBlockState(), 3);
                }
                removals.add(candidate);
            }
            removals.forEach(Entity::discard);
        }
        MissionSiteData.get(server.overworld()).releaseOwned(instanceId);
    }

    private static boolean isObjectiveBlock(
            ServerLevel level, BlockPos position, String role) {
        if (ROLE_DATA_TERMINAL.equals(role)) {
            return level.getBlockState(position).is(MissionBlocks.DATA_TERMINAL.get());
        }
        return ROLE_DELIVERY_TERMINAL.equals(role)
                && level.getBlockState(position).is(MissionBlocks.DELIVERY_TERMINAL.get());
    }

    private static boolean participantNear(
            ServerLevel level,
            PartyService.ParticipantSnapshot participants,
            BlockPos target,
            int radius) {
        double radiusSquared = (double) radius * radius;
        for (ServerPlayer player : PartyService.onlineMembers(level.getServer(), participants)) {
            if (player.level() == level && player.isAlive() && !player.isSpectator()
                    && player.blockPosition().distSqr(target) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private static MissionCatalog.MissionDefinition definition(
            ContractContext context, String definitionId) {
        try {
            return context.kind() == ContractKind.STORY_MISSION
                    ? StoryMissionCatalog.definition(definitionId).encounter()
                    : MissionCatalog.definition(definitionId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String siteReservationKey(MissionBuildingPlanner.Site site) {
        return site.district().code() + ":"
                + site.bounds().minX() + ":" + site.bounds().minZ() + ":"
                + site.bounds().maxX() + ":" + site.bounds().maxZ();
    }

    private static Item item(Identifier id) {
        return BuiltInRegistries.ITEM.getValue(id);
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int count(ServerPlayer player, Item item, UUID instanceId) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isCargo(stack, item, instanceId)) total += stack.getCount();
        }
        return total;
    }

    private static int count(
            PartyService.ParticipantSnapshot participants,
            net.minecraft.server.MinecraftServer server,
            Item item,
            UUID instanceId) {
        long total = 0L;
        for (ServerPlayer player : PartyService.onlineMembers(server, participants)) {
            total += count(player, item, instanceId);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static int countNearby(
            ServerLevel level,
            PartyService.ParticipantSnapshot participants,
            BlockPos target,
            int radius,
            Item item,
            UUID instanceId) {
        long total = 0L;
        double radiusSquared = (double) radius * radius;
        for (ServerPlayer player : PartyService.onlineMembers(level.getServer(), participants)) {
            if (player.level() == level && player.isAlive() && !player.isSpectator()
                    && player.blockPosition().distSqr(target) <= radiusSquared) {
                total += count(player, item, instanceId);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static boolean isCargo(ItemStack stack, Item item, UUID instanceId) {
        return instanceId != null && stack.is(item)
                && cargoInstance(stack).filter(instanceId::equals).isPresent();
    }

    private static Optional<UUID> cargoInstance(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        try {
            return data.copyTag().getString(CARGO_INSTANCE)
                    .filter(value -> !value.isBlank()).map(UUID::fromString);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static void purgeCargo(ServerPlayer player, UUID instanceId) {
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (cargoInstance(stack).filter(instanceId::equals).isPresent()) {
                stack.setCount(0);
                changed = true;
            }
        }
        if (changed) player.getInventory().setChanged();
    }

    static boolean isExpiredCargo(ServerLevel level, ItemStack stack) {
        return cargoInstance(stack)
                .filter(instance -> PartyService.isContractTerminal(level, instance))
                .isPresent();
    }

    private static boolean isValidFixer(ServerPlayer player, Entity merchant) {
        return merchant != null && merchant.isAlive() && merchant.level() == player.level()
                && player.distanceToSqr(merchant) <= 64.0
                && MerchantTruckLibrary.merchantRole(merchant).orElse(null)
                == MerchantTruckLibrary.MerchantRole.QUEST;
    }
}
