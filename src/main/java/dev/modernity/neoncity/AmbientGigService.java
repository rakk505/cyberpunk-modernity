package dev.modernity.neoncity;

import com.example.cyberdeck.network.GigJournalPacket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Persistent district gig boards; discovery never accepts a contract on the player's behalf. */
public final class AmbientGigService {
    public static final int OFFERS_PER_DISTRICT = 5;
    private static final long BOARD_SALT = 0x414D4249454E5447L;
    private static final String OWNER_ID_PREFIX = "cyberdeck:gig-board-owner:v2:";
    private static final int TARGET_SPREAD = 256;
    private static final Map<UUID, AmbientGigData.OwnerKey> LAST_OWNER = new HashMap<>();
    private static final Map<UUID, District> LAST_DISTRICT = new HashMap<>();

    private AmbientGigService() {
    }

    public record DiscoveredGig(UUID offerId, MissionService.MissionOffer offer) {
        public DiscoveredGig {
            if (offerId == null || offer == null) {
                throw new IllegalArgumentException("Discovered gig fields are required");
            }
        }
    }

    private record BoardOwner(AmbientGigData.OwnerKey key, List<UUID> members) {
        private BoardOwner {
            members = List.copyOf(members);
        }
    }

    /** Creates or refreshes the board for the district currently occupied by this player. */
    public static void tick(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        recordPresence(player);
        if (player.isSpectator() || !player.isAlive()) return;
        ServerLevel overworld = level.getServer().overworld();
        if (!NeonCityGenerator.isMegacityWorld(overworld)) return;
        District district = inhabitedDistrict(player).orElse(null);
        AmbientGigData data = AmbientGigData.get(overworld);
        BoardOwner boardOwner = boardOwner(player);
        AmbientGigData.OwnerKey owner = boardOwner.key();
        promotePendingRefreshes(overworld, owner, boardOwner.members());
        if (district == null) {
            if (LAST_DISTRICT.remove(player.getUUID()) != null) notifyBoardChanged(player);
            return;
        }
        boolean districtChanged = district != LAST_DISTRICT.put(player.getUUID(), district);
        boolean ownerChanged = !owner.equals(LAST_OWNER.put(player.getUUID(), owner));
        boolean boardChanged = ensureBoard(overworld, owner, district);
        if (boardChanged || ownerChanged || districtChanged) notifyBoardChanged(player);
    }

    /** Returns the unaccepted local district board shared by the player's current party. */
    public static List<DiscoveredGig> availableOffers(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return List.of();
        District district = inhabitedDistrict(player).orElse(null);
        if (district == null) return List.of();
        return availableOffers(level, owner(player), district);
    }

    /** Accepts one stable offer after revalidating party ownership and current district. */
    public static boolean accept(ServerPlayer player, UUID offerId) {
        if (offerId == null || !(player.level() instanceof ServerLevel level)) return false;
        District district = inhabitedDistrict(player).orElse(null);
        if (district == null) return false;
        AmbientGigData.OwnerKey owner = owner(player);
        DiscoveredGig discovered = availableOffers(level, owner, district).stream()
                .filter(gig -> gig.offerId().equals(offerId))
                .findFirst().orElse(null);
        if (discovered == null) return false;

        MissionCatalog.MissionDefinition definition;
        try {
            definition = MissionCatalog.definition(discovered.offer().definitionId());
        } catch (IllegalArgumentException removedDefinition) {
            return false;
        }
        AmbientGigData data = AmbientGigData.get(level);
        AmbientGigData.StoredOffer stored = new AmbientGigData.StoredOffer(
                offerId, definition.id(), discovered.offer().targetX(),
                discovered.offer().targetZ(), discovered.offer().reward());
        if (!data.removeOffer(owner, district, offerId)) return false;
        if (!MissionService.acceptDiscovered(player, definition, discovered.offer())) {
            data.restoreOffer(owner, district, stored);
            notifyBoardChanged(player);
            return false;
        }
        notifyBoardChanged(player);
        return true;
    }

    /**
     * Arms old district boards only after this snapshotted group completes work elsewhere and
     * every member's current or last-known location has left the old district.
     */
    static boolean recordCompletion(
            ServerLevel context,
            UUID instanceId,
            PartyService.ParticipantSnapshot participants,
            District completedDistrict) {
        AmbientGigData data = AmbientGigData.get(context);
        if (!data.claimCompletion(instanceId)) return false;
        for (BoardOwner boardOwner : completionOwners(context, participants)) {
            for (AmbientGigData.Pool pool : data.pools(boardOwner.key())) {
                if (pool.district() == completedDistrict) continue;
                data.markRefreshPending(boardOwner.key(), pool.district());
                if (allMembersOutside(context, boardOwner.members(), pool.district())) {
                    data.armRefresh(boardOwner.key(), pool.district());
                }
            }
        }
        return true;
    }

    static void promotePendingRefreshes(
            ServerLevel context,
            AmbientGigData.OwnerKey owner,
            List<UUID> fallbackMembers) {
        AmbientGigData data = AmbientGigData.get(context);
        for (AmbientGigData.Pool pool : data.pools(owner)) {
            if (pool.refreshPending()
                    && allMembersOutside(context, fallbackMembers, pool.district())) {
                data.armRefresh(owner, pool.district());
            }
        }
    }

    static List<DiscoveredGig> availableOffers(
            ServerLevel level,
            AmbientGigData.OwnerKey owner,
            District district) {
        AmbientGigData.Pool pool = AmbientGigData.get(level).pool(owner, district).orElse(null);
        if (pool == null || pool.refreshEligible()) return List.of();
        ArrayList<DiscoveredGig> available = new ArrayList<>();
        for (AmbientGigData.StoredOffer stored : pool.offers()) {
            try {
                MissionCatalog.MissionDefinition definition =
                        MissionCatalog.definition(stored.definitionId());
                if (!definition.targetDistricts().contains(district)) continue;
                available.add(new DiscoveredGig(stored.id(), new MissionService.MissionOffer(
                        definition.id(), definition.type(), definition.title(),
                        definition.briefing(), definition.objectiveText(), district.ordinal(),
                        stored.targetX(), stored.targetZ(), stored.reward(),
                        definition.streetCred())));
            } catch (IllegalArgumentException removedDefinition) {
                // A server configuration change invalidates only this entry; it is pruned on
                // the next tick without replenishing offers the group already accepted.
            }
        }
        return List.copyOf(available);
    }

    /** Reconciles one board without replenishing accepted offers before a qualified refresh. */
    static boolean ensureBoard(
            ServerLevel level,
            AmbientGigData.OwnerKey owner,
            District district) {
        AmbientGigData data = AmbientGigData.get(level);
        AmbientGigData.Pool previous = data.pool(owner, district).orElse(null);
        if (previous != null && !previous.refreshEligible()) {
            List<AmbientGigData.StoredOffer> retained = previous.offers().stream()
                    .filter(offer -> valid(offer, district))
                    .limit(OFFERS_PER_DISTRICT)
                    .toList();
            return data.replaceOffers(owner, district, retained);
        }

        long generation = previous == null ? 0L : previous.generation() + 1L;
        data.replace(owner, district, generation,
                generateStoredOffers(
                        NeonCityGenerator.layout(), NeonCityGenerator.contentSeed(),
                        owner, district, generation));
        return true;
    }

    static List<AmbientGigData.StoredOffer> generateStoredOffers(
            MegacityLayout layout,
            long worldSeed,
            AmbientGigData.OwnerKey owner,
            District district,
            long generation) {
        List<MissionCatalog.MissionDefinition> definitions = MissionCatalog.definitions().stream()
                .filter(definition -> definition.targetDistricts().contains(district))
                .toList();
        if (definitions.isEmpty()) return List.of();

        ArrayList<AmbientGigData.StoredOffer> offers = new ArrayList<>(OFFERS_PER_DISTRICT);
        Set<BlockPos> usedTargets = new HashSet<>();
        for (int index = 0; index < OFFERS_PER_DISTRICT; index++) {
            long hash = boardHash(layout, worldSeed, owner, district, generation, index);
            MissionCatalog.MissionDefinition definition = definitions.get(
                    Math.floorMod((int) Long.rotateRight(hash, 19), definitions.size()));
            BlockPos target = target(layout, district, hash, usedTargets);
            usedTargets.add(target);
            int reward = definition.rewardMin() + Math.floorMod(
                    (int) Long.rotateRight(hash, 41),
                    definition.rewardMax() - definition.rewardMin() + 1);
            UUID offerId = UUID.nameUUIDFromBytes((
                    worldSeed + ":" + layout.seed() + ":" + owner.party() + ":" + owner.id()
                            + ":" + district.name() + ":" + generation + ":" + index)
                    .getBytes(StandardCharsets.UTF_8));
            offers.add(new AmbientGigData.StoredOffer(
                    offerId, definition.id(), target.getX(), target.getZ(), reward));
        }
        return List.copyOf(offers);
    }

    static Optional<District> inhabitedDistrict(ServerPlayer player) {
        if (player.level() != player.level().getServer().overworld()) return Optional.empty();
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(
                player.getBlockX(), player.getBlockZ());
        MegacityLayout.Location location = NeonCityGenerator.effectiveLocation(sample);
        if (!location.insideCity() || location.zone() == MegacityLayout.Zone.WILDERNESS) {
            return Optional.empty();
        }
        return Optional.of(location.district());
    }

    static void recordPresence(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        ServerLevel overworld = level.getServer().overworld();
        if (!NeonCityGenerator.isMegacityWorld(overworld)) return;
        AmbientGigData.get(overworld).setLastDistrict(
                player.getUUID(), inhabitedDistrict(player).orElse(null));
    }

    static AmbientGigData.OwnerKey ownerForMembers(List<UUID> members) {
        List<UUID> normalized = members == null ? List.of() : members.stream()
                .filter(id -> id != null)
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Gig board membership is required");
        }
        if (normalized.size() == 1) {
            return new AmbientGigData.OwnerKey(false, normalized.getFirst());
        }
        String membership = normalized.stream()
                .map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(":"));
        return new AmbientGigData.OwnerKey(true, UUID.nameUUIDFromBytes(
                (OWNER_ID_PREFIX + membership).getBytes(StandardCharsets.UTF_8)));
    }

    private static AmbientGigData.OwnerKey owner(ServerPlayer player) {
        return boardOwner(player).key();
    }

    private static BoardOwner boardOwner(ServerPlayer player) {
        List<UUID> members = PartySavedData.get((ServerLevel) player.level())
                .partyFor(player.getUUID())
                .map(PartySavedData.PartySnapshot::members)
                .orElseGet(() -> List.of(player.getUUID()));
        return new BoardOwner(ownerForMembers(members), members);
    }

    private static List<BoardOwner> completionOwners(
            ServerLevel context, PartyService.ParticipantSnapshot participants) {
        if (participants.partyId().isPresent()) {
            PartySavedData parties = PartySavedData.get(context);
            PartySavedData.PartySnapshot currentParty = participants.partyId()
                    .flatMap(parties::party)
                    .or(() -> participants.playerIds().stream()
                            .map(parties::partyFor)
                            .flatMap(Optional::stream)
                            .filter(party -> party.members().containsAll(participants.playerIds()))
                            .findFirst())
                    .orElse(null);
            if (currentParty != null) {
                return List.of(new BoardOwner(
                        ownerForMembers(currentParty.members()), currentParty.members()));
            }

            ArrayList<BoardOwner> currentOwners = new ArrayList<>();
            for (UUID participantId : participants.playerIds()) {
                List<UUID> members = parties.partyFor(participantId)
                        .map(PartySavedData.PartySnapshot::members)
                        .orElseGet(() -> List.of(participantId));
                BoardOwner boardOwner = new BoardOwner(ownerForMembers(members), members);
                if (currentOwners.stream().noneMatch(
                        existing -> existing.key().equals(boardOwner.key()))) {
                    currentOwners.add(boardOwner);
                }
            }
            return List.copyOf(currentOwners);
        }
        return participants.playerIds().stream()
                .distinct()
                .sorted()
                .map(id -> new BoardOwner(ownerForMembers(List.of(id)), List.of(id)))
                .toList();
    }

    private static boolean valid(AmbientGigData.StoredOffer offer, District district) {
        try {
            return MissionCatalog.definition(offer.definitionId())
                    .targetDistricts().contains(district);
        } catch (IllegalArgumentException removedDefinition) {
            return false;
        }
    }

    private static long boardHash(
            MegacityLayout layout,
            long worldSeed,
            AmbientGigData.OwnerKey owner,
            District district,
            long generation,
            int index) {
        long seed = worldSeed ^ layout.seed() ^ BOARD_SALT
                ^ owner.id().getMostSignificantBits()
                ^ Long.rotateLeft(owner.id().getLeastSignificantBits(), 17)
                ^ Long.rotateLeft(generation, 31)
                ^ (owner.party() ? 0x5041525459474947L : 0x534F4C4F47494753L);
        return MegacityLayout.mix(seed, district.ordinal(), index);
    }

    private static BlockPos target(
            MegacityLayout layout,
            District district,
            long hash,
            Set<BlockPos> usedTargets) {
        MegacityLayout.Node node = layout.node(district);
        for (int attempt = 0; attempt < 96; attempt++) {
            long candidateHash = MegacityLayout.mix(
                    hash ^ BOARD_SALT, attempt, district.ordinal());
            int x = node.x() - TARGET_SPREAD
                    + Math.floorMod((int) candidateHash, TARGET_SPREAD * 2 + 1);
            int z = node.z() - TARGET_SPREAD
                    + Math.floorMod((int) Long.rotateLeft(candidateHash, 29),
                            TARGET_SPREAD * 2 + 1);
            BlockPos candidate = new BlockPos(x, NeonCityGenerator.CITY_GROUND_Y + 1, z);
            MegacityLayout.Location location = layout.locateDistrict(x, z);
            if (location.insideCity() && location.district() == district
                    && !usedTargets.contains(candidate)) {
                return candidate;
            }
        }
        int fallbackOffset = usedTargets.size() * 7;
        return new BlockPos(
                node.x() + fallbackOffset,
                NeonCityGenerator.CITY_GROUND_Y + 1,
                node.z() + fallbackOffset);
    }

    private static boolean allMembersOutside(
            ServerLevel context,
            List<UUID> members,
            District district) {
        for (UUID memberId : members) {
            ServerPlayer member = context.getServer().getPlayerList().getPlayer(memberId);
            District lastDistrict = member == null
                    ? AmbientGigData.get(context).lastDistrict(memberId).orElse(null)
                    : inhabitedDistrict(member).orElse(null);
            if (lastDistrict == district) {
                return false;
            }
        }
        return true;
    }

    private static void notifyBoardChanged(ServerPlayer player) {
        for (ServerPlayer member : PartyService.onlineMembers(player)) {
            syncJournal(member);
            CityMapService.open(member, false);
        }
    }

    /** Sends the freshest accepted journal and local unaccepted gig board to the client. */
    public static void syncJournal(ServerPlayer player) {
        if (player.connection == null
                || !NetworkRegistry.hasChannel(player.connection, GigJournalPacket.TYPE.id())) {
            return;
        }
        PacketDistributor.sendToPlayer(player, GigJournalPacket.snapshot(player));
    }

    static void forgetPlayer(ServerPlayer player) {
        recordPresence(player);
        LAST_OWNER.remove(player.getUUID());
        LAST_DISTRICT.remove(player.getUUID());
    }

    static void reset() {
        LAST_OWNER.clear();
        LAST_DISTRICT.clear();
    }
}
