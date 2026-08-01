package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent one-contract ownership for buildings whose geometry has been mission-decorated. */
final class MissionSiteData extends SavedData {
    private static final Codec<MissionSiteData> CODEC = Reservation.CODEC.listOf()
            .xmap(MissionSiteData::new, MissionSiteData::serialized);
    static final SavedDataType<MissionSiteData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "mission_sites_v1"),
            MissionSiteData::new,
            CODEC);

    private final Map<String, UUID> reservations = new HashMap<>();

    private MissionSiteData() {
    }

    private MissionSiteData(List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            this.reservations.putIfAbsent(reservation.siteId(), reservation.instanceId());
        }
    }

    static MissionSiteData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    boolean reserve(String siteId, UUID instanceId) {
        UUID existing = reservations.get(siteId);
        if (existing != null) return existing.equals(instanceId);
        reservations.put(siteId, instanceId);
        setDirty();
        return true;
    }

    void releaseIfOwned(String siteId, UUID instanceId) {
        if (reservations.remove(siteId, instanceId)) setDirty();
    }

    void releaseOwned(UUID instanceId) {
        boolean changed = reservations.entrySet().removeIf(
                entry -> entry.getValue().equals(instanceId));
        if (changed) setDirty();
    }

    boolean isReservedByOther(String siteId, UUID instanceId) {
        UUID existing = reservations.get(siteId);
        return existing != null && !existing.equals(instanceId);
    }

    boolean hasReservation(UUID instanceId) {
        return instanceId != null && reservations.containsValue(instanceId);
    }

    private List<Reservation> serialized() {
        return reservations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new Reservation(entry.getKey(), entry.getValue()))
                .toList();
    }

    private record Reservation(String siteId, UUID instanceId) {
        private static final Codec<Reservation> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.STRING.fieldOf("site").forGetter(Reservation::siteId),
                        UUIDUtil.CODEC.fieldOf("instance").forGetter(Reservation::instanceId))
                        .apply(instance, Reservation::new));
    }
}
