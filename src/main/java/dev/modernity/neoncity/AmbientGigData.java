package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
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

/** Persistent, party-owned district gig boards. */
final class AmbientGigData extends SavedData {
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_APPLIED_COMPLETIONS = 8_192;

    private static final Codec<StoredOffer> OFFER_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(StoredOffer::id),
                    Codec.STRING.fieldOf("definition").forGetter(StoredOffer::definitionId),
                    Codec.INT.fieldOf("x").forGetter(StoredOffer::targetX),
                    Codec.INT.fieldOf("z").forGetter(StoredOffer::targetZ),
                    Codec.INT.fieldOf("reward").forGetter(StoredOffer::reward))
                    .apply(instance, StoredOffer::new));

    private static final Codec<StoredPool> POOL_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("owner").forGetter(StoredPool::ownerId),
                    Codec.BOOL.optionalFieldOf("party", false).forGetter(StoredPool::party),
                    Codec.STRING.fieldOf("district").forGetter(StoredPool::district),
                    Codec.LONG.optionalFieldOf("generation", 0L)
                            .forGetter(StoredPool::generation),
                    Codec.BOOL.optionalFieldOf("refresh_pending", false)
                            .forGetter(StoredPool::refreshPending),
                    Codec.BOOL.optionalFieldOf("refresh_eligible", false)
                            .forGetter(StoredPool::refreshEligible),
                    OFFER_CODEC.listOf().optionalFieldOf("offers", List.of())
                            .forGetter(StoredPool::offers))
                    .apply(instance, StoredPool::new));

    private static final Codec<StoredPresence> PRESENCE_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("player").forGetter(StoredPresence::playerId),
                    Codec.STRING.fieldOf("district").forGetter(StoredPresence::district))
                    .apply(instance, StoredPresence::new));

    private static final Codec<AmbientGigData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("format_version", FORMAT_VERSION)
                            .forGetter(ignored -> FORMAT_VERSION),
                    POOL_CODEC.listOf().optionalFieldOf("pools", List.of())
                            .forGetter(AmbientGigData::serializedPools),
                    PRESENCE_CODEC.listOf().optionalFieldOf("last_districts", List.of())
                            .forGetter(AmbientGigData::serializedPresence),
                    UUIDUtil.CODEC.listOf().optionalFieldOf("applied_completions", List.of())
                            .forGetter(AmbientGigData::serializedAppliedCompletions))
                    .apply(instance, AmbientGigData::new));

    static final SavedDataType<AmbientGigData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "ambient_gig_boards_v1"),
            AmbientGigData::new,
            CODEC);

    record OwnerKey(boolean party, UUID id) {
        OwnerKey {
            if (id == null) throw new IllegalArgumentException("Gig board owner is required");
        }
    }

    record StoredOffer(UUID id, String definitionId, int targetX, int targetZ, int reward) {
        StoredOffer {
            definitionId = definitionId == null ? "" : definitionId;
        }
    }

    record Pool(
            OwnerKey owner,
            District district,
            long generation,
            boolean refreshPending,
            boolean refreshEligible,
            List<StoredOffer> offers) {
        Pool {
            offers = offers == null ? List.of() : List.copyOf(offers);
        }

        Pool withRefreshEligible(boolean value) {
            return new Pool(owner, district, generation, false, value, offers);
        }

        Pool withRefreshPending(boolean value) {
            return new Pool(owner, district, generation, value, refreshEligible, offers);
        }
    }

    private record StoredPool(
            UUID ownerId,
            boolean party,
            String district,
            long generation,
            boolean refreshPending,
            boolean refreshEligible,
            List<StoredOffer> offers) {
    }

    private record StoredPresence(UUID playerId, String district) {
    }

    private record PoolKey(OwnerKey owner, District district) {
    }

    private final Map<PoolKey, Pool> pools = new HashMap<>();
    private final Map<UUID, District> lastDistricts = new HashMap<>();
    private final LinkedHashSet<UUID> appliedCompletions = new LinkedHashSet<>();

    private AmbientGigData() {
        this(FORMAT_VERSION, List.of(), List.of(), List.of());
    }

    private AmbientGigData(
            int ignoredVersion,
            List<StoredPool> storedPools,
            List<StoredPresence> storedPresence,
            List<UUID> storedAppliedCompletions) {
        for (StoredPool stored : storedPools) {
            decode(stored).ifPresent(pool -> pools.putIfAbsent(
                    new PoolKey(pool.owner(), pool.district()), pool));
        }
        for (StoredPresence stored : storedPresence) {
            try {
                lastDistricts.putIfAbsent(
                        stored.playerId(), District.valueOf(stored.district()));
            } catch (RuntimeException ignored) {
                // Ignore malformed presence without discarding otherwise valid gig boards.
            }
        }
        int firstRetained = Math.max(
                0, storedAppliedCompletions.size() - MAX_APPLIED_COMPLETIONS);
        for (int index = firstRetained; index < storedAppliedCompletions.size(); index++) {
            UUID completionId = storedAppliedCompletions.get(index);
            if (completionId != null) appliedCompletions.add(completionId);
        }
    }

    static AmbientGigData get(ServerLevel context) {
        return context.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    Optional<Pool> pool(OwnerKey owner, District district) {
        return Optional.ofNullable(pools.get(new PoolKey(owner, district)));
    }

    List<Pool> pools(OwnerKey owner) {
        return pools.values().stream()
                .filter(pool -> pool.owner().equals(owner))
                .sorted(Comparator.comparingInt(pool -> pool.district().ordinal()))
                .toList();
    }

    Optional<District> lastDistrict(UUID playerId) {
        return Optional.ofNullable(lastDistricts.get(playerId));
    }

    boolean setLastDistrict(UUID playerId, District district) {
        District previous = district == null
                ? lastDistricts.remove(playerId)
                : lastDistricts.put(playerId, district);
        if (previous == district) return false;
        setDirty();
        return true;
    }

    Pool replace(
            OwnerKey owner,
            District district,
            long generation,
            List<StoredOffer> offers) {
        Pool pool = new Pool(owner, district, generation, false, false, offers);
        pools.put(new PoolKey(owner, district), pool);
        setDirty();
        return pool;
    }

    boolean removeOffer(OwnerKey owner, District district, UUID offerId) {
        PoolKey key = new PoolKey(owner, district);
        Pool current = pools.get(key);
        if (current == null || current.offers().stream()
                .noneMatch(offer -> offer.id().equals(offerId))) {
            return false;
        }
        pools.put(key, new Pool(
                current.owner(), current.district(), current.generation(),
                current.refreshPending(), current.refreshEligible(), current.offers().stream()
                        .filter(offer -> !offer.id().equals(offerId)).toList()));
        setDirty();
        return true;
    }

    boolean replaceOffers(OwnerKey owner, District district, List<StoredOffer> offers) {
        PoolKey key = new PoolKey(owner, district);
        Pool current = pools.get(key);
        if (current == null || current.offers().equals(offers)) return false;
        pools.put(key, new Pool(
                current.owner(), current.district(), current.generation(),
                current.refreshPending(), current.refreshEligible(), offers));
        setDirty();
        return true;
    }

    void restoreOffer(OwnerKey owner, District district, StoredOffer offer) {
        PoolKey key = new PoolKey(owner, district);
        Pool current = pools.get(key);
        if (current == null || current.offers().stream()
                .anyMatch(existing -> existing.id().equals(offer.id()))
                || current.offers().size() >= AmbientGigService.OFFERS_PER_DISTRICT) {
            return;
        }
        ArrayList<StoredOffer> restored = new ArrayList<>(current.offers());
        restored.add(offer);
        pools.put(key, new Pool(
                current.owner(), current.district(), current.generation(),
                current.refreshPending(), current.refreshEligible(), restored));
        setDirty();
    }

    boolean markRefreshPending(OwnerKey owner, District district) {
        PoolKey key = new PoolKey(owner, district);
        Pool current = pools.get(key);
        if (current == null || current.refreshPending() || current.refreshEligible()) return false;
        pools.put(key, current.withRefreshPending(true));
        setDirty();
        return true;
    }

    boolean armRefresh(OwnerKey owner, District district) {
        PoolKey key = new PoolKey(owner, district);
        Pool current = pools.get(key);
        if (current == null || current.refreshEligible()) return false;
        pools.put(key, current.withRefreshEligible(true));
        setDirty();
        return true;
    }

    boolean claimCompletion(UUID instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("Completion instance is required");
        }
        if (!appliedCompletions.add(instanceId)) return false;
        while (appliedCompletions.size() > MAX_APPLIED_COMPLETIONS) {
            appliedCompletions.remove(appliedCompletions.iterator().next());
        }
        setDirty();
        return true;
    }

    private List<StoredPool> serializedPools() {
        ArrayList<StoredPool> stored = new ArrayList<>();
        pools.values().stream()
                .sorted(Comparator.comparing((Pool pool) -> pool.owner().party())
                        .thenComparing(pool -> pool.owner().id())
                        .thenComparingInt(pool -> pool.district().ordinal()))
                .forEach(pool -> stored.add(new StoredPool(
                        pool.owner().id(), pool.owner().party(), pool.district().name(),
                        pool.generation(), pool.refreshPending(),
                        pool.refreshEligible(), pool.offers())));
        return List.copyOf(stored);
    }

    private List<StoredPresence> serializedPresence() {
        return lastDistricts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new StoredPresence(entry.getKey(), entry.getValue().name()))
                .toList();
    }

    private List<UUID> serializedAppliedCompletions() {
        return List.copyOf(appliedCompletions);
    }

    private static Optional<Pool> decode(StoredPool stored) {
        try {
            District district = District.valueOf(stored.district());
            OwnerKey owner = new OwnerKey(stored.party(), stored.ownerId());
            List<StoredOffer> offers = stored.offers().stream()
                    .filter(offer -> offer.id() != null
                            && !offer.definitionId().isBlank()
                            && offer.reward() > 0)
                    .limit(AmbientGigService.OFFERS_PER_DISTRICT)
                    .toList();
            return Optional.of(new Pool(
                    owner, district, Math.max(0L, stored.generation()),
                    stored.refreshPending(), stored.refreshEligible(), offers));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
