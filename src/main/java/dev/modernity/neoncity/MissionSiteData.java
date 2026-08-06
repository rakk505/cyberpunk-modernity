package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent ownership, visit state, and rollback data for mission-decorated buildings. */
final class MissionSiteData extends SavedData {
    static final int SITE_CLEARANCE = 10;
    private static final int UNKNOWN = Integer.MIN_VALUE;
    private static final String LIFECYCLE_ACTIVE = "active";
    private static final String LIFECYCLE_COMPLETED_COMBAT_LIVE = "completed_combat_live";
    private static final String LIFECYCLE_COMPLETED_COMBAT_CLEARED = "completed_combat_cleared";
    private static final Codec<MissionSiteData> CODEC = Reservation.CODEC.listOf()
            .xmap(MissionSiteData::new, MissionSiteData::serialized);
    static final SavedDataType<MissionSiteData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "mission_sites_v1"),
            MissionSiteData::new,
            CODEC);

    private final Map<String, Reservation> reservations = new HashMap<>();

    record CompletedSite(
            UUID instanceId,
            District district,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            List<UUID> participants,
            boolean combatCleared) {
        CompletedSite {
            participants = List.copyOf(participants);
        }
    }

    private MissionSiteData() {
    }

    private MissionSiteData(List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            this.reservations.putIfAbsent(reservation.siteId(), reservation);
        }
    }

    static MissionSiteData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    boolean reserve(String siteId, UUID instanceId) {
        Reservation existing = reservations.get(siteId);
        if (existing != null) return existing.instanceId().equals(instanceId);
        reservations.put(siteId, Reservation.legacy(siteId, instanceId));
        setDirty();
        return true;
    }

    /** Reserves this physical mission area and excludes other sites within its safety buffer. */
    boolean reserve(
            String siteId, MissionBuildingPlanner.Site site, UUID instanceId) {
        if (site == null || isReservedByOther(siteId, site, instanceId)) return false;
        Reservation existing = reservations.get(siteId);
        Set<UUID> entered = existing == null
                ? Set.of()
                : Set.copyOf(existing.enteredPlayers());
        CompoundTag restoration = existing == null
                ? new CompoundTag()
                : existing.restoration().copy();
        Reservation enriched = Reservation.from(
                siteId, site, instanceId, entered, restoration,
                existing == null ? List.of() : existing.participants(),
                existing == null ? LIFECYCLE_ACTIVE : existing.lifecycle());
        if (!enriched.equals(existing)) {
            reservations.put(siteId, enriched);
            setDirty();
        }
        return true;
    }

    /** Returns a copy-safe exact site descriptor owned by this contract reservation. */
    java.util.Optional<MissionBuildingPlanner.Site> reservedSite(UUID instanceId) {
        if (instanceId == null) return java.util.Optional.empty();
        return reservations.values().stream()
                .filter(reservation -> reservation.instanceId().equals(instanceId))
                .sorted(java.util.Comparator.comparing(Reservation::siteId))
                .map(Reservation::sitePlan)
                .filter(tag -> !tag.isEmpty())
                .map(MissionBuildingPlanner.Site::load)
                .flatMap(java.util.Optional::stream)
                .findFirst();
    }

    /** Enriches legacy reservations from a surviving participant copy of the exact site. */
    boolean storeSite(UUID instanceId, MissionBuildingPlanner.Site site) {
        if (instanceId == null || site == null) return false;
        boolean found = false;
        boolean changed = false;
        for (Map.Entry<String, Reservation> entry : reservations.entrySet()) {
            Reservation reservation = entry.getValue();
            if (!reservation.instanceId().equals(instanceId)) continue;
            found = true;
            Reservation enriched = reservation.withSite(site);
            if (!enriched.equals(reservation)) {
                entry.setValue(enriched);
                changed = true;
            }
        }
        if (changed) setDirty();
        return found;
    }

    void releaseIfOwned(String siteId, UUID instanceId) {
        Reservation existing = reservations.get(siteId);
        if (existing != null && existing.instanceId().equals(instanceId)) {
            reservations.remove(siteId);
            setDirty();
        }
    }

    void releaseOwned(UUID instanceId) {
        boolean changed = reservations.entrySet().removeIf(
                entry -> entry.getValue().instanceId().equals(instanceId));
        if (changed) setDirty();
    }

    boolean isReservedByOther(String siteId, UUID instanceId) {
        Reservation existing = reservations.get(siteId);
        return existing != null && !existing.instanceId().equals(instanceId);
    }

    boolean isReservedByOther(
            String siteId, MissionBuildingPlanner.Site site, UUID instanceId) {
        if (site == null) return true;
        for (Reservation reservation : reservations.values()) {
            if (reservation.instanceId().equals(instanceId)) continue;
            if (reservation.siteId().equals(siteId) || reservation.conflicts(site)) return true;
        }
        return false;
    }

    /** Reservation ids whose area blocks {@code site}, so a refused reserve can name the cause. */
    java.util.List<String> conflictingSiteIds(
            MissionBuildingPlanner.Site site, UUID instanceId) {
        if (site == null) return java.util.List.of("<null site>");
        return reservations.values().stream()
                .filter(reservation -> !reservation.instanceId().equals(instanceId))
                .filter(reservation -> reservation.conflicts(site))
                .map(Reservation::siteId)
                .sorted()
                .toList();
    }

    boolean hasReservation(UUID instanceId) {
        return instanceId != null && reservations.values().stream()
                .anyMatch(reservation -> reservation.instanceId().equals(instanceId));
    }

    boolean isReservedSite(MissionBuildingPlanner.Site site) {
        if (site == null) return false;
        return reservations.values().stream()
                .map(Reservation::sitePlan)
                .filter(tag -> !tag.isEmpty())
                .map(MissionBuildingPlanner.Site::load)
                .flatMap(java.util.Optional::stream)
                .anyMatch(reserved -> reserved.id().equals(site.id())
                        && reserved.buildingId().equals(site.buildingId()));
    }

    /** Records which online contract members have physically entered the target district. */
    void markEntered(UUID instanceId, List<UUID> playerIds) {
        if (instanceId == null || playerIds == null || playerIds.isEmpty()) return;
        Set<UUID> additions = new HashSet<>(playerIds);
        boolean changed = false;
        for (Map.Entry<String, Reservation> entry : reservations.entrySet()) {
            Reservation reservation = entry.getValue();
            if (!reservation.instanceId().equals(instanceId)) continue;
            HashSet<UUID> entered = new HashSet<>(reservation.enteredPlayers());
            if (entered.addAll(additions)) {
                entry.setValue(reservation.withEntered(entered));
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    boolean hasEntered(UUID instanceId) {
        return instanceId != null && reservations.values().stream()
                .filter(reservation -> reservation.instanceId().equals(instanceId))
                .anyMatch(reservation -> !reservation.enteredPlayers().isEmpty());
    }

    void storeRestoration(UUID instanceId, CompoundTag restoration) {
        if (instanceId == null || restoration == null || restoration.isEmpty()) return;
        boolean changed = false;
        for (Map.Entry<String, Reservation> entry : reservations.entrySet()) {
            Reservation reservation = entry.getValue();
            if (!reservation.instanceId().equals(instanceId)) continue;
            entry.setValue(reservation.withRestoration(restoration));
            changed = true;
        }
        if (changed) setDirty();
    }

    java.util.Optional<CompoundTag> restoration(UUID instanceId) {
        if (instanceId == null) return java.util.Optional.empty();
        return reservations.values().stream()
                .filter(reservation -> reservation.instanceId().equals(instanceId))
                .map(Reservation::restoration)
                .filter(tag -> !tag.isEmpty())
                .findFirst()
                .map(CompoundTag::copy);
    }

    /** Retains a completed mission site until its combat and district cleanup gates are met. */
    boolean retainCompleted(UUID instanceId, List<UUID> participantIds) {
        if (instanceId == null || participantIds == null || participantIds.isEmpty()) return false;
        List<UUID> participants = participantIds.stream()
                .filter(id -> id != null).distinct().sorted().toList();
        if (participants.isEmpty()) return false;
        boolean retained = false;
        boolean changed = false;
        for (Map.Entry<String, Reservation> entry : reservations.entrySet()) {
            Reservation reservation = entry.getValue();
            if (!reservation.instanceId().equals(instanceId)
                    || !reservation.canRetainCompletion()) continue;
            retained = true;
            String lifecycle = LIFECYCLE_COMPLETED_COMBAT_CLEARED.equals(
                    reservation.lifecycle())
                    ? LIFECYCLE_COMPLETED_COMBAT_CLEARED
                    : LIFECYCLE_COMPLETED_COMBAT_LIVE;
            Reservation completed = reservation.withCompletion(
                    participants, lifecycle);
            if (!completed.equals(reservation)) {
                entry.setValue(completed);
                changed = true;
            }
        }
        if (changed) setDirty();
        return retained;
    }

    boolean isRetainedCompletion(UUID instanceId) {
        return completedSite(instanceId).isPresent();
    }

    java.util.Optional<CompletedSite> completedSite(UUID instanceId) {
        if (instanceId == null) return java.util.Optional.empty();
        return reservations.values().stream()
                .filter(reservation -> reservation.instanceId().equals(instanceId))
                .map(Reservation::completedSite)
                .flatMap(java.util.Optional::stream)
                .findFirst();
    }

    List<CompletedSite> completedSites() {
        Map<UUID, CompletedSite> completed = new HashMap<>();
        for (Reservation reservation : reservations.values()) {
            reservation.completedSite().ifPresent(site ->
                    completed.putIfAbsent(site.instanceId(), site));
        }
        return completed.values().stream()
                .sorted(java.util.Comparator.comparing(CompletedSite::instanceId))
                .toList();
    }

    void markCombatCleared(UUID instanceId) {
        if (instanceId == null) return;
        boolean changed = false;
        for (Map.Entry<String, Reservation> entry : reservations.entrySet()) {
            Reservation reservation = entry.getValue();
            if (!reservation.instanceId().equals(instanceId)
                    || !LIFECYCLE_COMPLETED_COMBAT_LIVE.equals(reservation.lifecycle())) continue;
            entry.setValue(reservation.withCompletion(
                    reservation.participants(), LIFECYCLE_COMPLETED_COMBAT_CLEARED));
            changed = true;
        }
        if (changed) setDirty();
    }

    private List<Reservation> serialized() {
        return reservations.values().stream()
                .sorted(java.util.Comparator.comparing(Reservation::siteId))
                .toList();
    }

    private record Reservation(
            String siteId,
            UUID instanceId,
            int district,
            int minX,
            int minZ,
            int maxX,
            int maxZ,
            List<UUID> enteredPlayers,
            CompoundTag restoration,
            CompoundTag sitePlan,
            List<UUID> participants,
            String lifecycle) {
        private static final Codec<Reservation> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.STRING.fieldOf("site").forGetter(Reservation::siteId),
                        UUIDUtil.CODEC.fieldOf("instance").forGetter(Reservation::instanceId),
                        Codec.INT.optionalFieldOf("district", UNKNOWN)
                                .forGetter(Reservation::district),
                        Codec.INT.optionalFieldOf("min_x", UNKNOWN).forGetter(Reservation::minX),
                        Codec.INT.optionalFieldOf("min_z", UNKNOWN).forGetter(Reservation::minZ),
                        Codec.INT.optionalFieldOf("max_x", UNKNOWN).forGetter(Reservation::maxX),
                        Codec.INT.optionalFieldOf("max_z", UNKNOWN).forGetter(Reservation::maxZ),
                        UUIDUtil.CODEC.listOf().optionalFieldOf("entered_players", List.of())
                                .forGetter(Reservation::enteredPlayers),
                        CompoundTag.CODEC.optionalFieldOf("restoration", new CompoundTag())
                                .forGetter(Reservation::restoration),
                        CompoundTag.CODEC.optionalFieldOf("site_plan", new CompoundTag())
                                .forGetter(Reservation::sitePlan),
                        UUIDUtil.CODEC.listOf().optionalFieldOf("participants", List.of())
                                .forGetter(Reservation::participants),
                        Codec.STRING.optionalFieldOf("lifecycle", LIFECYCLE_ACTIVE)
                                .forGetter(Reservation::lifecycle))
                        .apply(instance, Reservation::new));

        private Reservation {
            enteredPlayers = enteredPlayers == null
                    ? List.of()
                    : enteredPlayers.stream().filter(id -> id != null).distinct().sorted().toList();
            restoration = restoration == null ? new CompoundTag() : restoration.copy();
            sitePlan = sitePlan == null ? new CompoundTag() : sitePlan.copy();
            participants = participants == null
                    ? List.of()
                    : participants.stream().filter(id -> id != null).distinct().sorted().toList();
            lifecycle = lifecycle == null ? LIFECYCLE_ACTIVE : lifecycle;
            lifecycle = switch (lifecycle) {
                case LIFECYCLE_COMPLETED_COMBAT_LIVE,
                        LIFECYCLE_COMPLETED_COMBAT_CLEARED -> lifecycle;
                default -> LIFECYCLE_ACTIVE;
            };
        }

        @Override
        public CompoundTag restoration() {
            return restoration.copy();
        }

        @Override
        public CompoundTag sitePlan() {
            return sitePlan.copy();
        }

        private static Reservation legacy(String siteId, UUID instanceId) {
            return new Reservation(
                    siteId, instanceId, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN,
                    List.of(), new CompoundTag(), new CompoundTag(), List.of(), LIFECYCLE_ACTIVE);
        }

        private static Reservation from(
                String siteId,
                MissionBuildingPlanner.Site site,
                UUID instanceId,
                Set<UUID> enteredPlayers,
                CompoundTag restoration,
                List<UUID> participants,
                String lifecycle) {
            return new Reservation(
                    siteId,
                    instanceId,
                    site.district().ordinal(),
                    site.bounds().minX(),
                    site.bounds().minZ(),
                    site.bounds().maxX(),
                    site.bounds().maxZ(),
                    new ArrayList<>(enteredPlayers),
                    restoration,
                    site.save(),
                    participants,
                    lifecycle);
        }

        private Reservation withEntered(Set<UUID> entered) {
            return new Reservation(
                    siteId, instanceId, district, minX, minZ, maxX, maxZ,
                    new ArrayList<>(entered), restoration, sitePlan, participants, lifecycle);
        }

        private Reservation withRestoration(CompoundTag nextRestoration) {
            return new Reservation(
                    siteId, instanceId, district, minX, minZ, maxX, maxZ,
                    enteredPlayers, nextRestoration, sitePlan, participants, lifecycle);
        }

        private Reservation withSite(MissionBuildingPlanner.Site site) {
            return new Reservation(
                    siteId, instanceId, site.district().ordinal(),
                    site.bounds().minX(), site.bounds().minZ(),
                    site.bounds().maxX(), site.bounds().maxZ(),
                    enteredPlayers, restoration, site.save(), participants, lifecycle);
        }

        private Reservation withCompletion(
                List<UUID> completionParticipants, String completionLifecycle) {
            return new Reservation(
                    siteId, instanceId, district, minX, minZ, maxX, maxZ,
                    enteredPlayers, restoration, sitePlan,
                    completionParticipants, completionLifecycle);
        }

        private boolean canRetainCompletion() {
            return district >= 0 && district < District.values().length
                    && minX != UNKNOWN && minZ != UNKNOWN && maxX != UNKNOWN && maxZ != UNKNOWN
                    && !restoration.isEmpty();
        }

        private java.util.Optional<CompletedSite> completedSite() {
            if (LIFECYCLE_ACTIVE.equals(lifecycle) || !canRetainCompletion()
                    || participants.isEmpty()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new CompletedSite(
                    instanceId, District.values()[district], minX, minZ, maxX, maxZ,
                    participants, LIFECYCLE_COMPLETED_COMBAT_CLEARED.equals(lifecycle)));
        }

        private boolean conflicts(MissionBuildingPlanner.Site site) {
            if (district == UNKNOWN || minX == UNKNOWN || minZ == UNKNOWN
                    || maxX == UNKNOWN || maxZ == UNKNOWN) {
                return false;
            }
            MissionBuildingPlanner.Site reserved = MissionBuildingPlanner.Site.load(sitePlan)
                    .orElse(null);
            if (reserved != null) {
                return MainlineQuestData.buildingConflicts(reserved, site);
            }
            net.minecraft.world.level.levelgen.structure.BoundingBox reservedBounds =
                    new net.minecraft.world.level.levelgen.structure.BoundingBox(
                            minX, 0, minZ, maxX, 0, maxZ);
            return reservedBounds.minX() <= site.buildingBounds().maxX() + SITE_CLEARANCE
                    && reservedBounds.maxX() + SITE_CLEARANCE
                            >= site.buildingBounds().minX()
                    && reservedBounds.minZ() <= site.buildingBounds().maxZ() + SITE_CLEARANCE
                    && reservedBounds.maxZ() + SITE_CLEARANCE
                            >= site.buildingBounds().minZ();
        }
    }
}
