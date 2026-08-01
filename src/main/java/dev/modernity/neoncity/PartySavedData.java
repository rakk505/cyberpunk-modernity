package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent server-wide party membership, shared progression, and deferred rewards. */
public final class PartySavedData extends SavedData {
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_COMPLETED_CONTRACTS = 8_192;

    private static final Codec<PartySavedData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("format_version", FORMAT_VERSION)
                            .forGetter(ignored -> FORMAT_VERSION),
                    PartyEntry.CODEC.listOf().optionalFieldOf("parties", List.of())
                            .forGetter(PartySavedData::serializedParties),
                    PlayerAmount.CODEC.listOf().optionalFieldOf("pending_emmies", List.of())
                            .forGetter(PartySavedData::serializedPendingEmmies),
                    PlayerAmount.CODEC.listOf().optionalFieldOf(
                                    "pending_street_cred", List.of())
                            .forGetter(PartySavedData::serializedPendingStreetCred),
                    PlayerAmount.CODEC.listOf().optionalFieldOf("street_cred_floors", List.of())
                            .forGetter(PartySavedData::serializedStreetCredFloors),
                    PlayerStoryIds.CODEC.listOf().optionalFieldOf(
                                    "pending_story_completions", List.of())
                            .forGetter(PartySavedData::serializedStoryCompletions),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("completed_contracts", List.of())
                            .forGetter(PartySavedData::serializedCompletedContracts),
                    ContractParticipants.CODEC.listOf().optionalFieldOf(
                                    "active_contract_participants", List.of())
                            .forGetter(PartySavedData::serializedActiveContracts),
                    ContractParticipants.CODEC.listOf().optionalFieldOf(
                                    "pending_contract_clears", List.of())
                            .forGetter(PartySavedData::serializedPendingContractClears))
                    .apply(instance, PartySavedData::new));

    public static final SavedDataType<PartySavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "parties_v1"),
            PartySavedData::new,
            CODEC);

    private final Map<UUID, PartySnapshot> parties = new HashMap<>();
    private final Map<UUID, UUID> partyByMember = new HashMap<>();
    private final Map<UUID, Integer> pendingEmmies = new HashMap<>();
    private final Map<UUID, Integer> pendingStreetCred = new HashMap<>();
    private final Map<UUID, Integer> streetCredFloors = new HashMap<>();
    private final Map<UUID, LinkedHashSet<String>> pendingStoryCompletions = new HashMap<>();
    private final LinkedHashSet<UUID> completedContracts = new LinkedHashSet<>();
    private final Map<UUID, LinkedHashSet<UUID>> activeContractParticipants = new HashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> pendingContractClears = new HashMap<>();

    public PartySavedData() {
    }

    private PartySavedData(
            int ignoredFormatVersion,
            List<PartyEntry> parties,
            List<PlayerAmount> pendingEmmies,
            List<PlayerAmount> pendingStreetCred,
            List<PlayerAmount> streetCredFloors,
            List<PlayerStoryIds> pendingStoryCompletions,
            List<UUID> completedContracts,
            List<ContractParticipants> activeContracts,
            List<ContractParticipants> pendingContractClears) {
        for (PartyEntry entry : parties) {
            PartySnapshot party = entry.snapshot();
            if (this.parties.putIfAbsent(party.id(), party) != null) {
                throw new IllegalArgumentException("Duplicate party id " + party.id());
            }
            for (UUID member : party.members()) {
                UUID previous = partyByMember.putIfAbsent(member, party.id());
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "Player " + member + " belongs to multiple parties");
                }
            }
        }
        loadAmounts(this.pendingEmmies, pendingEmmies);
        loadAmounts(this.pendingStreetCred, pendingStreetCred);
        loadAmounts(this.streetCredFloors, streetCredFloors);
        for (PlayerStoryIds entry : pendingStoryCompletions) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            entry.storyIds().stream()
                    .filter(PartySavedData::validStoryId)
                    .sorted()
                    .forEach(ids::add);
            if (!ids.isEmpty()) {
                this.pendingStoryCompletions.put(entry.playerId(), ids);
            }
        }
        int firstRetained = Math.max(0, completedContracts.size() - MAX_COMPLETED_CONTRACTS);
        for (int index = firstRetained; index < completedContracts.size(); index++) {
            this.completedContracts.add(completedContracts.get(index));
        }
        loadContractParticipants(this.activeContractParticipants, activeContracts);
        loadContractParticipants(this.pendingContractClears, pendingContractClears);
    }

    /** Returns the ledger stored in the overworld, regardless of the caller's current dimension. */
    public static PartySavedData get(ServerLevel context) {
        ServerLevel overworld = context.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<PartySnapshot> party(UUID partyId) {
        return Optional.ofNullable(parties.get(partyId));
    }

    public Optional<PartySnapshot> partyFor(UUID playerId) {
        UUID partyId = partyByMember.get(playerId);
        return partyId == null ? Optional.empty() : party(partyId);
    }

    PartySnapshot create(UUID partyId, UUID leader, int streetCred) {
        if (partyByMember.containsKey(leader) || parties.containsKey(partyId)) {
            throw new IllegalStateException("Party or leader is already registered");
        }
        PartySnapshot party = new PartySnapshot(
                partyId, leader, List.of(leader), clampStreetCred(streetCred));
        parties.put(partyId, party);
        partyByMember.put(leader, partyId);
        streetCredFloors.remove(leader);
        setDirty();
        return party;
    }

    Optional<PartySnapshot> addMember(UUID partyId, UUID member, int memberStreetCred) {
        PartySnapshot current = parties.get(partyId);
        if (current == null || partyByMember.containsKey(member)) {
            return Optional.empty();
        }
        ArrayList<UUID> members = new ArrayList<>(current.members());
        members.add(member);
        PartySnapshot updated = new PartySnapshot(
                current.id(),
                current.leader(),
                members,
                Math.max(current.streetCred(), clampStreetCred(memberStreetCred)));
        parties.put(partyId, updated);
        partyByMember.put(member, partyId);
        streetCredFloors.remove(member);
        setDirty();
        return Optional.of(updated);
    }

    Optional<MemberRemoval> removeMember(UUID member) {
        UUID partyId = partyByMember.remove(member);
        if (partyId == null) {
            return Optional.empty();
        }
        PartySnapshot previous = parties.get(partyId);
        ArrayList<UUID> remainingMembers = new ArrayList<>(previous.members());
        remainingMembers.remove(member);
        PartySnapshot remaining = null;
        if (remainingMembers.isEmpty()) {
            parties.remove(partyId);
        } else {
            UUID leader = previous.leader().equals(member)
                    ? remainingMembers.get(0) : previous.leader();
            remaining = new PartySnapshot(
                    partyId, leader, remainingMembers, previous.streetCred());
            parties.put(partyId, remaining);
        }
        setDirty();
        return Optional.of(new MemberRemoval(previous, Optional.ofNullable(remaining)));
    }

    Optional<PartySnapshot> disband(UUID partyId) {
        PartySnapshot removed = parties.remove(partyId);
        if (removed == null) {
            return Optional.empty();
        }
        for (UUID member : removed.members()) {
            partyByMember.remove(member);
        }
        setDirty();
        return Optional.of(removed);
    }

    Optional<PartySnapshot> addStreetCred(UUID partyId, int amount) {
        PartySnapshot current = parties.get(partyId);
        if (current == null) {
            return Optional.empty();
        }
        int updatedCred = saturatingAdd(current.streetCred(), Math.max(0, amount));
        if (updatedCred == current.streetCred()) {
            return Optional.of(current);
        }
        PartySnapshot updated = new PartySnapshot(
                current.id(), current.leader(), current.members(), updatedCred);
        parties.put(partyId, updated);
        setDirty();
        return Optional.of(updated);
    }

    int addPendingEmmies(UUID playerId, int amount) {
        if (amount <= 0) {
            return pendingEmmies.getOrDefault(playerId, 0);
        }
        int updated = saturatingAdd(pendingEmmies.getOrDefault(playerId, 0), amount);
        pendingEmmies.put(playerId, updated);
        setDirty();
        return updated;
    }

    public int pendingEmmies(UUID playerId) {
        return pendingEmmies.getOrDefault(playerId, 0);
    }

    int takePendingEmmies(UUID playerId) {
        Integer amount = pendingEmmies.remove(playerId);
        if (amount != null) {
            setDirty();
        }
        return amount == null ? 0 : amount;
    }

    void addPendingStreetCred(UUID playerId, int amount) {
        if (amount <= 0) {
            return;
        }
        pendingStreetCred.merge(playerId, amount, PartySavedData::saturatingAdd);
        setDirty();
    }

    int takePendingStreetCred(UUID playerId) {
        Integer amount = pendingStreetCred.remove(playerId);
        if (amount != null) {
            setDirty();
        }
        return amount == null ? 0 : amount;
    }

    void queueStreetCredFloor(UUID playerId, int streetCred) {
        int floor = clampStreetCred(streetCred);
        if (floor <= streetCredFloors.getOrDefault(playerId, 0)) {
            return;
        }
        streetCredFloors.put(playerId, floor);
        setDirty();
    }

    int takeStreetCredFloor(UUID playerId) {
        Integer floor = streetCredFloors.remove(playerId);
        if (floor != null) {
            setDirty();
        }
        return floor == null ? 0 : floor;
    }

    void clearStreetCredFloor(UUID playerId) {
        if (streetCredFloors.remove(playerId) != null) {
            setDirty();
        }
    }

    void queueStoryCompletion(UUID playerId, String storyId) {
        if (!validStoryId(storyId)) {
            throw new IllegalArgumentException("Invalid story completion id");
        }
        if (pendingStoryCompletions
                .computeIfAbsent(playerId, ignored -> new LinkedHashSet<>())
                .add(storyId)) {
            setDirty();
        }
    }

    List<String> takeStoryCompletions(UUID playerId) {
        LinkedHashSet<String> ids = pendingStoryCompletions.remove(playerId);
        if (ids == null) {
            return List.of();
        }
        setDirty();
        return ids.stream().sorted().toList();
    }

    void registerContract(UUID instanceId, List<UUID> participantIds) {
        LinkedHashSet<UUID> participants = normalizedParticipants(participantIds);
        if (instanceId == null || completedContracts.contains(instanceId)) {
            throw new IllegalArgumentException("Contract instance is already terminal");
        }
        LinkedHashSet<UUID> existing = activeContractParticipants.get(instanceId);
        if (existing != null) {
            if (!existing.equals(participants)) {
                throw new IllegalStateException("Contract participant snapshot changed");
            }
            return;
        }
        activeContractParticipants.put(instanceId, participants);
        setDirty();
    }

    boolean markContractCompleted(UUID instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("Contract instance id is required");
        }
        if (!completedContracts.add(instanceId)) {
            return false;
        }
        moveContractToPendingClear(instanceId, null);
        while (completedContracts.size() > MAX_COMPLETED_CONTRACTS) {
            completedContracts.remove(completedContracts.iterator().next());
        }
        setDirty();
        return true;
    }

    /** Atomically prepares every durable grant before a contract is considered terminal. */
    boolean settleContract(
            UUID instanceId,
            Optional<UUID> partyId,
            List<UUID> participantIds,
            int totalEmmies,
            int streetCred,
            String storyId) {
        if (instanceId == null || participantIds == null || participantIds.isEmpty()
                || totalEmmies < 0 || streetCred < 0
                || storyId != null && !storyId.isBlank() && !validStoryId(storyId)) {
            throw new IllegalArgumentException("Invalid contract settlement");
        }
        if (completedContracts.contains(instanceId)) return false;
        ArrayList<UUID> participants = new ArrayList<>(normalizedParticipants(participantIds));
        int quotient = totalEmmies / participants.size();
        int remainder = totalEmmies % participants.size();
        for (int index = 0; index < participants.size(); index++) {
            UUID participant = participants.get(index);
            int share = quotient + (index < remainder ? 1 : 0);
            if (share > 0) {
                pendingEmmies.merge(participant, share, PartySavedData::saturatingAdd);
            }
            if (storyId != null && !storyId.isBlank()) {
                pendingStoryCompletions
                        .computeIfAbsent(participant, ignored -> new LinkedHashSet<>())
                        .add(storyId);
            }
        }

        PartySnapshot currentParty = partyId.flatMap(this::party).orElse(null);
        if (currentParty != null) {
            PartySnapshot updated = new PartySnapshot(
                    currentParty.id(), currentParty.leader(), currentParty.members(),
                    saturatingAdd(currentParty.streetCred(), streetCred));
            parties.put(updated.id(), updated);
        } else if (streetCred > 0) {
            for (UUID participant : participants) {
                pendingStreetCred.merge(
                        participant, streetCred, PartySavedData::saturatingAdd);
            }
        }

        completedContracts.add(instanceId);
        moveContractToPendingClear(instanceId, new LinkedHashSet<>(participants));
        while (completedContracts.size() > MAX_COMPLETED_CONTRACTS) {
            completedContracts.remove(completedContracts.iterator().next());
        }
        setDirty();
        return true;
    }

    boolean isContractCompleted(UUID instanceId) {
        return completedContracts.contains(instanceId);
    }

    boolean isContractTerminal(UUID instanceId) {
        return completedContracts.contains(instanceId)
                || pendingContractClears.containsKey(instanceId);
    }

    boolean requiresContractClear(UUID instanceId, UUID playerId) {
        LinkedHashSet<UUID> pending = pendingContractClears.get(instanceId);
        return pending != null && pending.contains(playerId);
    }

    void acknowledgeContractClear(UUID instanceId, UUID playerId) {
        LinkedHashSet<UUID> pending = pendingContractClears.get(instanceId);
        if (pending == null || !pending.remove(playerId)) return;
        if (pending.isEmpty()) pendingContractClears.remove(instanceId);
        setDirty();
    }

    void acknowledgeMissingContracts(UUID playerId) {
        boolean changed = false;
        var iterator = pendingContractClears.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, LinkedHashSet<UUID>> entry = iterator.next();
            if (entry.getValue().remove(playerId)) changed = true;
            if (entry.getValue().isEmpty()) iterator.remove();
        }
        if (changed) setDirty();
    }

    private void moveContractToPendingClear(
            UUID instanceId, LinkedHashSet<UUID> fallbackParticipants) {
        LinkedHashSet<UUID> participants = activeContractParticipants.remove(instanceId);
        if (participants == null) participants = fallbackParticipants;
        if (participants != null && !participants.isEmpty()) {
            pendingContractClears.computeIfAbsent(instanceId, ignored -> new LinkedHashSet<>())
                    .addAll(participants);
        }
    }

    private List<PartyEntry> serializedParties() {
        return parties.values().stream()
                .sorted((first, second) -> first.id().compareTo(second.id()))
                .map(PartyEntry::from)
                .toList();
    }

    private List<PlayerAmount> serializedPendingEmmies() {
        return serializedAmounts(pendingEmmies);
    }

    private List<PlayerAmount> serializedPendingStreetCred() {
        return serializedAmounts(pendingStreetCred);
    }

    private List<PlayerAmount> serializedStreetCredFloors() {
        return serializedAmounts(streetCredFloors);
    }

    private List<PlayerStoryIds> serializedStoryCompletions() {
        return pendingStoryCompletions.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PlayerStoryIds(
                        entry.getKey(), entry.getValue().stream().sorted().toList()))
                .toList();
    }

    private List<UUID> serializedCompletedContracts() {
        return List.copyOf(completedContracts);
    }

    private List<ContractParticipants> serializedActiveContracts() {
        return serializedContractParticipants(activeContractParticipants);
    }

    private List<ContractParticipants> serializedPendingContractClears() {
        return serializedContractParticipants(pendingContractClears);
    }

    private static List<PlayerAmount> serializedAmounts(Map<UUID, Integer> values) {
        return values.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PlayerAmount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void loadAmounts(Map<UUID, Integer> destination, List<PlayerAmount> entries) {
        for (PlayerAmount entry : entries) {
            if (entry.amount() > 0) {
                destination.merge(entry.playerId(), entry.amount(), PartySavedData::saturatingAdd);
            }
        }
    }

    private static List<ContractParticipants> serializedContractParticipants(
            Map<UUID, LinkedHashSet<UUID>> contracts) {
        return contracts.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ContractParticipants(
                        entry.getKey(), entry.getValue().stream().sorted().toList()))
                .toList();
    }

    private static void loadContractParticipants(
            Map<UUID, LinkedHashSet<UUID>> destination,
            List<ContractParticipants> contracts) {
        for (ContractParticipants contract : contracts) {
            LinkedHashSet<UUID> participants = normalizedParticipants(contract.participants());
            destination.computeIfAbsent(contract.instanceId(), ignored -> new LinkedHashSet<>())
                    .addAll(participants);
        }
    }

    private static LinkedHashSet<UUID> normalizedParticipants(List<UUID> participantIds) {
        if (participantIds == null || participantIds.isEmpty()
                || participantIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Contract participants are required");
        }
        ArrayList<UUID> sorted = new ArrayList<>(new LinkedHashSet<>(participantIds));
        sorted.sort(UUID::compareTo);
        return new LinkedHashSet<>(sorted);
    }

    private static int clampStreetCred(int value) {
        return Math.max(0, value);
    }

    private static int saturatingAdd(int current, int amount) {
        long total = (long) current + amount;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, total));
    }

    private static boolean validStoryId(String storyId) {
        return storyId != null && !storyId.isBlank() && storyId.length() <= 128;
    }

    /** Immutable public view of one party. Member UUIDs are unique and deterministically sorted. */
    public record PartySnapshot(UUID id, UUID leader, List<UUID> members, int streetCred) {
        public PartySnapshot {
            if (id == null || leader == null) {
                throw new IllegalArgumentException("Party id and leader are required");
            }
            LinkedHashSet<UUID> unique = new LinkedHashSet<>();
            unique.add(leader);
            if (members != null) {
                unique.addAll(members);
            }
            ArrayList<UUID> sorted = new ArrayList<>(unique);
            sorted.sort(UUID::compareTo);
            members = List.copyOf(sorted);
            streetCred = clampStreetCred(streetCred);
        }

        public boolean contains(UUID playerId) {
            return members.contains(playerId);
        }
    }

    record MemberRemoval(PartySnapshot previous, Optional<PartySnapshot> remaining) {
    }

    private record PartyEntry(
            UUID id, UUID leader, List<UUID> members, int streetCred) {
        private static final Codec<PartyEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.CODEC.fieldOf("id").forGetter(PartyEntry::id),
                        UUIDUtil.CODEC.fieldOf("leader").forGetter(PartyEntry::leader),
                        UUIDUtil.CODEC.listOf().fieldOf("members").forGetter(PartyEntry::members),
                        Codec.INT.optionalFieldOf("street_cred", 0)
                                .forGetter(PartyEntry::streetCred))
                        .apply(instance, PartyEntry::new));

        PartySnapshot snapshot() {
            return new PartySnapshot(id, leader, members, streetCred);
        }

        static PartyEntry from(PartySnapshot party) {
            return new PartyEntry(
                    party.id(), party.leader(), party.members(), party.streetCred());
        }
    }

    private record PlayerAmount(UUID playerId, int amount) {
        private static final Codec<PlayerAmount> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(PlayerAmount::playerId),
                        Codec.INT.fieldOf("amount").forGetter(PlayerAmount::amount))
                        .apply(instance, PlayerAmount::new));
    }

    private record PlayerStoryIds(UUID playerId, List<String> storyIds) {
        private static final Codec<PlayerStoryIds> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.CODEC.fieldOf("player").forGetter(PlayerStoryIds::playerId),
                        Codec.STRING.listOf().fieldOf("story_ids")
                                .forGetter(PlayerStoryIds::storyIds))
                        .apply(instance, PlayerStoryIds::new));
    }

    private record ContractParticipants(UUID instanceId, List<UUID> participants) {
        private static final Codec<ContractParticipants> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        UUIDUtil.CODEC.fieldOf("instance")
                                .forGetter(ContractParticipants::instanceId),
                        UUIDUtil.CODEC.listOf().fieldOf("participants")
                                .forGetter(ContractParticipants::participants))
                        .apply(instance, ContractParticipants::new));
    }
}
