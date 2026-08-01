package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Bounded accepted-contract journal that remains correct for offline party participants. */
final class MissionJournalData extends SavedData {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES_PER_PLAYER = 64;

    private static final Codec<StoredEntry> ENTRY_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("player").forGetter(StoredEntry::playerId),
                    UUIDUtil.CODEC.fieldOf("instance").forGetter(StoredEntry::instanceId),
                    Codec.STRING.fieldOf("kind").forGetter(StoredEntry::kind),
                    Codec.STRING.optionalFieldOf("type", "").forGetter(StoredEntry::type),
                    Codec.STRING.fieldOf("definition").forGetter(StoredEntry::definitionId),
                    Codec.STRING.fieldOf("title").forGetter(StoredEntry::title),
                    Codec.STRING.optionalFieldOf("briefing", "").forGetter(StoredEntry::briefing),
                    Codec.STRING.fieldOf("objective").forGetter(StoredEntry::objective),
                    Codec.STRING.fieldOf("district").forGetter(StoredEntry::district),
                    Codec.INT.fieldOf("x").forGetter(StoredEntry::targetX),
                    Codec.INT.fieldOf("z").forGetter(StoredEntry::targetZ),
                    Codec.INT.fieldOf("reward").forGetter(StoredEntry::reward),
                    Codec.INT.fieldOf("street_cred").forGetter(StoredEntry::streetCred),
                    Codec.LONG.fieldOf("accepted_tick").forGetter(StoredEntry::acceptedTick),
                    Codec.STRING.fieldOf("status").forGetter(StoredEntry::status),
                    Codec.LONG.fieldOf("updated_tick").forGetter(StoredEntry::updatedTick))
                    .apply(instance, StoredEntry::new));

    private static final Codec<MissionJournalData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("format_version", FORMAT_VERSION)
                            .forGetter(ignored -> FORMAT_VERSION),
                    ENTRY_CODEC.listOf().optionalFieldOf("entries", List.of())
                            .forGetter(MissionJournalData::serializedEntries),
                    Deployment.CODEC.listOf().optionalFieldOf("deployments", List.of())
                            .forGetter(MissionJournalData::serializedDeployments))
                    .apply(instance, MissionJournalData::new));

    static final SavedDataType<MissionJournalData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "mission_journal_v1"),
            MissionJournalData::new,
            CODEC);

    private record StoredEntry(
            UUID playerId,
            UUID instanceId,
            String kind,
            String type,
            String definitionId,
            String title,
            String briefing,
            String objective,
            String district,
            int targetX,
            int targetZ,
            int reward,
            int streetCred,
            long acceptedTick,
            String status,
            long updatedTick) {
    }

    private record Deployment(
            UUID instanceId,
            int targetX,
            int targetY,
            int targetZ,
            int navigationX,
            int navigationZ) {
        private static final Codec<Deployment> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.CODEC.fieldOf("instance").forGetter(Deployment::instanceId),
                        Codec.INT.fieldOf("x").forGetter(Deployment::targetX),
                        Codec.INT.fieldOf("y").forGetter(Deployment::targetY),
                        Codec.INT.fieldOf("z").forGetter(Deployment::targetZ),
                        Codec.INT.optionalFieldOf("navigation_x", Integer.MIN_VALUE)
                                .forGetter(Deployment::navigationX),
                        Codec.INT.optionalFieldOf("navigation_z", Integer.MIN_VALUE)
                                .forGetter(Deployment::navigationZ))
                        .apply(instance, Deployment::new));
    }

    private final Map<UUID, List<MissionService.JournalEntry>> entriesByPlayer = new HashMap<>();

    private MissionJournalData() {
        this(FORMAT_VERSION, List.of(), List.of());
    }

    private MissionJournalData(
            int ignoredVersion, List<StoredEntry> entries, List<Deployment> deployments) {
        Map<UUID, Deployment> deploymentsByInstance = new HashMap<>();
        for (Deployment deployment : deployments) {
            deploymentsByInstance.putIfAbsent(deployment.instanceId(), deployment);
        }
        for (StoredEntry stored : entries) {
            decode(stored, deploymentsByInstance.get(stored.instanceId()))
                    .ifPresent(entry -> put(stored.playerId(), entry, false));
        }
    }

    static MissionJournalData get(ServerLevel context) {
        return context.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    List<MissionService.JournalEntry> entries(UUID playerId) {
        return entriesByPlayer.getOrDefault(playerId, List.of()).stream()
                .sorted(Comparator.comparingLong(MissionService.JournalEntry::updatedTick)
                        .reversed()
                        .thenComparing(MissionService.JournalEntry::instanceId))
                .toList();
    }

    void accept(
            PartyService.ParticipantSnapshot participants,
            MissionService.ContractContext context,
            MissionService.ActiveMission mission,
            long updatedTick) {
        accept(participants, context, mission, mission.target(), false, updatedTick);
    }

    void accept(
            PartyService.ParticipantSnapshot participants,
            MissionService.ContractContext context,
            MissionService.ActiveMission mission,
            BlockPos navigation,
            long updatedTick) {
        accept(participants, context, mission, navigation, true, updatedTick);
    }

    private void accept(
            PartyService.ParticipantSnapshot participants,
            MissionService.ContractContext context,
            MissionService.ActiveMission mission,
            BlockPos navigation,
            boolean navigationKnown,
            long updatedTick) {
        MissionService.JournalEntry entry = new MissionService.JournalEntry(
                context.instanceId(), context.kind(), mission.type(),
                mission.definitionId(), mission.title(),
                mission.briefing(), mission.objective(), mission.targetDistrict(),
                mission.target().getX(), mission.target().getY(), mission.target().getZ(),
                navigation.getX(), navigation.getZ(), context.deployed(), mission.reward(),
                context.streetCred(), mission.acceptedTick(), MissionService.JournalStatus.ACTIVE,
                updatedTick);
        for (UUID participant : participants.playerIds()) {
            MissionService.JournalEntry current = entriesByPlayer
                    .getOrDefault(participant, List.of()).stream()
                    .filter(value -> value.instanceId().equals(context.instanceId()))
                    .findFirst().orElse(null);
            boolean terminal = current != null
                    && current.status() != MissionService.JournalStatus.ACTIVE;
            MissionService.JournalEntry candidate;
            if (current != null && current.deployed() && context.deployed()) {
                if (!navigationKnown) {
                    candidate = entry.withNavigation(
                            current.navigationX(), current.navigationZ());
                } else if (current.updatedTick() > entry.updatedTick()) {
                    candidate = current.withNavigation(navigation.getX(), navigation.getZ());
                } else {
                    candidate = entry;
                }
            } else {
                candidate = entry;
            }
            if (!terminal) put(participant, candidate, true);
        }
    }

    void status(
            UUID instanceId,
            MissionService.JournalStatus status,
            long updatedTick) {
        boolean changed = false;
        for (Map.Entry<UUID, List<MissionService.JournalEntry>> playerEntries
                : entriesByPlayer.entrySet()) {
            ArrayList<MissionService.JournalEntry> updated = new ArrayList<>(playerEntries.getValue());
            for (int index = 0; index < updated.size(); index++) {
                MissionService.JournalEntry current = updated.get(index);
                if (!current.instanceId().equals(instanceId)) continue;
                updated.set(index, current.withStatus(status, updatedTick));
                changed = true;
            }
            playerEntries.setValue(trim(updated));
        }
        if (changed) setDirty();
    }

    private void put(UUID playerId, MissionService.JournalEntry entry, boolean dirty) {
        ArrayList<MissionService.JournalEntry> entries = new ArrayList<>(
                entriesByPlayer.getOrDefault(playerId, List.of()));
        UUID instanceId = entry.instanceId();
        MissionService.JournalEntry current = entries.stream()
                .filter(value -> value.instanceId().equals(instanceId))
                .findFirst().orElse(null);
        boolean deploymentUpgrade = current != null && !current.deployed() && entry.deployed();
        if (current != null && (current.status() != MissionService.JournalStatus.ACTIVE
                || current.deployed() && !entry.deployed()
                || current.updatedTick() > entry.updatedTick() && !deploymentUpgrade)) {
            return;
        }
        if (deploymentUpgrade && current.updatedTick() > entry.updatedTick()) {
            entry = entry.withUpdatedTick(current.updatedTick());
        }
        entries.removeIf(value -> value.instanceId().equals(instanceId));
        entries.add(entry);
        entriesByPlayer.put(playerId, trim(entries));
        if (dirty) setDirty();
    }

    private static List<MissionService.JournalEntry> trim(
            List<MissionService.JournalEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparingLong(MissionService.JournalEntry::updatedTick)
                        .reversed()
                        .thenComparing(MissionService.JournalEntry::instanceId))
                .limit(MAX_ENTRIES_PER_PLAYER)
                .toList();
    }

    private List<StoredEntry> serializedEntries() {
        ArrayList<StoredEntry> stored = new ArrayList<>();
        entriesByPlayer.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(player -> player.getValue().forEach(entry -> stored.add(new StoredEntry(
                        player.getKey(), entry.instanceId(), entry.kind().name(),
                        entry.type().name(), entry.definitionId(), entry.title(),
                        entry.briefing(), entry.objective(),
                        entry.targetDistrict().name(), entry.targetX(), entry.targetZ(),
                        entry.reward(), entry.streetCred(), entry.acceptedTick(),
                        entry.status().name(), entry.updatedTick()))));
        return List.copyOf(stored);
    }

    private List<Deployment> serializedDeployments() {
        Map<UUID, Deployment> deployments = new HashMap<>();
        for (List<MissionService.JournalEntry> entries : entriesByPlayer.values()) {
            for (MissionService.JournalEntry entry : entries) {
                if (entry.deployed()) {
                    deployments.putIfAbsent(
                            entry.instanceId(),
                            new Deployment(
                                    entry.instanceId(), entry.targetX(), entry.targetY(),
                                    entry.targetZ(), entry.navigationX(), entry.navigationZ()));
                }
            }
        }
        return deployments.values().stream()
                .sorted(Comparator.comparing(Deployment::instanceId))
                .toList();
    }

    private static java.util.Optional<MissionService.JournalEntry> decode(
            StoredEntry stored, Deployment deployment) {
        try {
            int targetX = deployment == null ? stored.targetX() : deployment.targetX();
            int targetY = deployment == null
                    ? NeonCityGenerator.CITY_GROUND_Y + 1 : deployment.targetY();
            int targetZ = deployment == null ? stored.targetZ() : deployment.targetZ();
            int navigationX = deployment == null
                    || deployment.navigationX() == Integer.MIN_VALUE
                    ? targetX : deployment.navigationX();
            int navigationZ = deployment == null
                    || deployment.navigationZ() == Integer.MIN_VALUE
                    ? targetZ : deployment.navigationZ();
            return java.util.Optional.of(new MissionService.JournalEntry(
                    stored.instanceId(), MissionService.ContractKind.valueOf(stored.kind()),
                    missionType(stored.type(), stored.definitionId()), stored.definitionId(),
                    stored.title(), stored.briefing(), stored.objective(),
                    District.valueOf(stored.district()), targetX, targetY, targetZ,
                    navigationX, navigationZ, deployment != null, Math.max(1, stored.reward()),
                    Math.max(0, stored.streetCred()),
                    stored.acceptedTick(), MissionService.JournalStatus.valueOf(stored.status()),
                    stored.updatedTick()));
        } catch (RuntimeException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static MissionCatalog.MissionType missionType(String encoded, String definitionId) {
        if (encoded != null && !encoded.isBlank()) {
            try {
                return MissionCatalog.MissionType.valueOf(encoded);
            } catch (IllegalArgumentException ignored) {
                // Fall through to the current catalogs for older or renamed enum values.
            }
        }
        try {
            return StoryMissionCatalog.definition(definitionId).encounter().type();
        } catch (IllegalArgumentException missingStory) {
            try {
                return MissionCatalog.definition(definitionId).type();
            } catch (IllegalArgumentException missingGig) {
                return MissionCatalog.MissionType.ASSASSINATE_TARGET;
            }
        }
    }
}
