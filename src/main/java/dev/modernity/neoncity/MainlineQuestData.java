package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Permanent mainline site reservations and party-shared node progress. */
final class MainlineQuestData extends SavedData {
    private static final String FIXED_SITE_RESOURCE =
            "/data/neoncity/missions/mainline_sites_50520260801.dat";
    /**
     * Minimum clear ground between two mainline building footprints. An alley narrower than this
     * puts two mission actors on one facade, which players read as one building however the atlas
     * segmented the interiors.
     */
    static final int DISTINCT_BUILDING_SEPARATION = 32;
    private static final Codec<StoredPlan> PLAN_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("mission").forGetter(StoredPlan::missionId),
                    CompoundTag.CODEC.fieldOf("site").forGetter(StoredPlan::site),
                    CompoundTag.CODEC.optionalFieldOf("interior", new CompoundTag())
                            .forGetter(StoredPlan::interior),
                    Codec.BOOL.optionalFieldOf("committed_recovery", false)
                            .forGetter(StoredPlan::committedRecovery))
                    .apply(instance, StoredPlan::new));
    private static final Codec<Progress> PROGRESS_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("instance").forGetter(Progress::instanceId),
                    Codec.STRING.fieldOf("mission").forGetter(Progress::missionId),
                    Codec.STRING.listOf().optionalFieldOf("completed_nodes", List.of())
                            .forGetter(Progress::completedNodes))
                    .apply(instance, Progress::new));
    private static final Codec<MainlineQuestData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    PLAN_CODEC.listOf().optionalFieldOf("plans", List.of())
                            .forGetter(MainlineQuestData::serializedPlans),
                    PROGRESS_CODEC.listOf().optionalFieldOf("progress", List.of())
                            .forGetter(MainlineQuestData::serializedProgress))
                    .apply(instance, MainlineQuestData::new));
    static final SavedDataType<MainlineQuestData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "mainline_quests_v1"),
            MainlineQuestData::new,
            CODEC);
    private static volatile Map<String, MissionBuildingPlanner.Site> fixedSites;
    private static volatile Set<Long> fixedReservedChunks;

    private final Map<String, CompoundTag> plans = new HashMap<>();
    /** Cached packed chunk keys covered by dynamic site reservations; null when stale. */
    private volatile Set<Long> reservedChunks;
    private final Map<String, CompoundTag> permanentInteriors = new HashMap<>();
    private final Set<String> committedRecoveryPlans = new HashSet<>();
    private final Map<UUID, Progress> progress = new HashMap<>();

    record Progress(UUID instanceId, String missionId, List<String> completedNodes) {
        Progress {
            completedNodes = completedNodes == null
                    ? List.of()
                    : completedNodes.stream().filter(value -> value != null && !value.isBlank())
                            .distinct().sorted().toList();
        }

        Progress complete(String nodeId) {
            HashSet<String> completed = new HashSet<>(completedNodes);
            completed.add(nodeId);
            return new Progress(instanceId, missionId, new ArrayList<>(completed));
        }
    }

    private record StoredPlan(
            String missionId,
            CompoundTag site,
            CompoundTag interior,
            boolean committedRecovery) {
        private StoredPlan {
            site = site == null ? new CompoundTag() : site.copy();
            interior = interior == null ? new CompoundTag() : interior.copy();
        }
    }

    private MainlineQuestData() {
    }

    private MainlineQuestData(List<StoredPlan> plans, List<Progress> progress) {
        for (StoredPlan plan : plans) {
            if (!plan.missionId().isBlank() && !plan.site().isEmpty()) {
                CompoundTag previous = this.plans.putIfAbsent(
                        plan.missionId(), plan.site().copy());
                if (previous == null && plan.committedRecovery()) {
                    this.committedRecoveryPlans.add(plan.missionId());
                }
                if (previous == null && matchingInterior(plan.site(), plan.interior())) {
                    this.permanentInteriors.put(plan.missionId(), plan.interior().copy());
                }
            }
        }
        for (Progress entry : progress) {
            this.progress.putIfAbsent(entry.instanceId(), entry);
        }
    }

    static MainlineQuestData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    static Optional<MissionBuildingPlanner.Site> fixedSite(String missionId) {
        return Optional.ofNullable(fixedSites().get(missionId))
                .map(MissionBuildingPlanner::withoutMissionInteriorPlan);
    }

    static Map<String, MissionBuildingPlanner.Site> fixedSites() {
        Map<String, MissionBuildingPlanner.Site> loaded = fixedSites;
        if (loaded != null) return loaded;
        synchronized (MainlineQuestData.class) {
            if (fixedSites == null) fixedSites = loadFixedSites();
            return fixedSites;
        }
    }

    Optional<MissionBuildingPlanner.Site> site(String missionId) {
        CompoundTag encoded = plans.get(missionId);
        return encoded == null
                ? Optional.empty()
                : MissionBuildingPlanner.Site.load(encoded.copy())
                        .map(MissionBuildingPlanner::withoutMissionInteriorPlan);
    }

    Optional<MissionBuildingPlanner.Site> permanentInterior(String missionId) {
        CompoundTag encoded = permanentInteriors.get(missionId);
        return encoded == null
                ? Optional.empty()
                : MissionBuildingPlanner.Site.load(encoded.copy())
                        .map(MissionBuildingPlanner::withoutMissionTurretPlan);
    }

    void putSite(String missionId, MissionBuildingPlanner.Site site) {
        putSite(missionId, site, false);
    }

    void putSite(
            String missionId,
            MissionBuildingPlanner.Site site,
            boolean committedRecovery) {
        CompoundTag encoded = MissionBuildingPlanner.withoutMissionInteriorPlan(site).save();
        CompoundTag previous = plans.put(missionId, encoded);
        reservedChunks = null;
        boolean sameBuilding = previous != null && matchingSite(previous, encoded);
        boolean interiorChanged = !sameBuilding
                && permanentInteriors.remove(missionId) != null;
        boolean markerChanged = committedRecovery
                ? committedRecoveryPlans.add(missionId)
                : committedRecoveryPlans.remove(missionId);
        if (previous == null || !previous.equals(encoded) || markerChanged || interiorChanged) {
            setDirty();
        }
    }

    void commitSite(
            String missionId,
            MissionBuildingPlanner.Site site,
            boolean committedRecovery,
            MissionBuildingPlanner.Site permanentInterior) {
        putSite(missionId, site, committedRecovery);
        MissionBuildingPlanner.Site interior = MissionBuildingPlanner.withoutMissionTurretPlan(
                permanentInterior);
        if (interior == null || interior.decorations().isEmpty()
                || !sameSite(site, interior)) {
            return;
        }
        CompoundTag encoded = interior.save();
        CompoundTag previous = permanentInteriors.put(missionId, encoded);
        if (previous == null || !previous.equals(encoded)) setDirty();
    }

    boolean isCommittedRecovery(String missionId) {
        return committedRecoveryPlans.contains(missionId);
    }

    void removeSite(String missionId) {
        boolean changed = plans.remove(missionId) != null;
        if (changed) reservedChunks = null;
        changed |= permanentInteriors.remove(missionId) != null;
        changed |= committedRecoveryPlans.remove(missionId);
        if (changed) {
            setDirty();
        }
    }

    boolean conflicts(MissionBuildingPlanner.Site candidate, String exceptMissionId) {
        if (candidate == null) return true;
        for (Map.Entry<String, CompoundTag> entry : plans.entrySet()) {
            if (entry.getKey().equals(exceptMissionId)) continue;
            MissionBuildingPlanner.Site reserved = MissionBuildingPlanner.Site.load(
                    entry.getValue()).orElse(null);
            // sameApparentBuilding, not buildingConflicts: a second actor may not move into a
            // tower another mission already occupies, even on a floor nothing has reserved, and
            // may not move into a structure a player would call the same building either.
            if (reserved != null && sameApparentBuilding(reserved, candidate)) return true;
        }
        return false;
    }

    List<MissionBuildingPlanner.Site> sites() {
        return plans.values().stream()
                .map(MissionBuildingPlanner.Site::load)
                .flatMap(Optional::stream)
                .map(MissionBuildingPlanner::withoutMissionInteriorPlan)
                .toList();
    }

    /**
     * Packed chunk keys covered by dynamic (runtime-committed) site reservations. Computed once
     * and cached; recomputed only when a site is added or removed. Avoids deserializing every
     * site's NBT on every chunk generation, which the trace showed dominated the ads phase.
     */
    Set<Long> reservedBuildingChunks() {
        Set<Long> cached = reservedChunks;
        if (cached != null) return cached;
        Set<Long> computed = new HashSet<>();
        for (MissionBuildingPlanner.Site site : sites()) {
            addChunkKeys(computed, site.buildingBounds());
        }
        reservedChunks = computed;
        return computed;
    }

    /** Packed chunk keys covered by the immutable, resource-loaded fixed sites. */
    static Set<Long> fixedReservedBuildingChunks() {
        Set<Long> cached = fixedReservedChunks;
        if (cached != null) return cached;
        synchronized (MainlineQuestData.class) {
            if (fixedReservedChunks == null) {
                Set<Long> computed = new HashSet<>();
                for (MissionBuildingPlanner.Site site : fixedSites().values()) {
                    addChunkKeys(computed, site.buildingBounds());
                }
                fixedReservedChunks = computed;
            }
            return fixedReservedChunks;
        }
    }

    private static void addChunkKeys(Set<Long> out, BoundingBox bounds) {
        int minChunkX = bounds.minX() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                out.add(ChunkPos.pack(chunkX, chunkZ));
            }
        }
    }

    void start(UUID instanceId, String missionId) {
        Progress next = new Progress(instanceId, missionId, List.of());
        Progress previous = progress.putIfAbsent(instanceId, next);
        if (previous == null) setDirty();
    }

    Optional<Progress> progress(UUID instanceId) {
        return Optional.ofNullable(progress.get(instanceId));
    }

    boolean completeNode(UUID instanceId, String nodeId) {
        Progress current = progress.get(instanceId);
        if (current == null || current.completedNodes().contains(nodeId)) return false;
        progress.put(instanceId, current.complete(nodeId));
        setDirty();
        return true;
    }

    void removeProgress(UUID instanceId) {
        if (progress.remove(instanceId) != null) setDirty();
    }

    private List<StoredPlan> serializedPlans() {
        return plans.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new StoredPlan(
                        entry.getKey(), entry.getValue(),
                        permanentInteriors.getOrDefault(entry.getKey(), new CompoundTag()),
                        committedRecoveryPlans.contains(entry.getKey())))
                .toList();
    }

    private static boolean matchingInterior(CompoundTag site, CompoundTag interior) {
        if (interior == null || interior.isEmpty()) return false;
        MissionBuildingPlanner.Site structural = MissionBuildingPlanner.Site.load(site)
                .orElse(null);
        MissionBuildingPlanner.Site decorated = MissionBuildingPlanner.Site.load(interior)
                .orElse(null);
        return structural != null && decorated != null
                && !decorated.decorations().isEmpty()
                && sameSite(structural, decorated);
    }

    private static boolean matchingSite(CompoundTag first, CompoundTag second) {
        MissionBuildingPlanner.Site a = MissionBuildingPlanner.Site.load(first).orElse(null);
        MissionBuildingPlanner.Site b = MissionBuildingPlanner.Site.load(second).orElse(null);
        return a != null && b != null && sameSite(a, b);
    }

    private static boolean sameSite(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        return first.id().equals(second.id())
                && first.buildingId().equals(second.buildingId())
                && first.bounds().equals(second.bounds());
    }

    private List<Progress> serializedProgress() {
        return progress.values().stream()
                .sorted(java.util.Comparator.comparing(Progress::instanceId))
                .toList();
    }

    private static Map<String, MissionBuildingPlanner.Site> loadFixedSites() {
        try (InputStream stream = MainlineQuestData.class.getResourceAsStream(
                FIXED_SITE_RESOURCE)) {
            if (stream == null) {
                throw new IOException("missing " + FIXED_SITE_RESOURCE);
            }
            CompoundTag root = NbtIo.readCompressed(stream, NbtAccounter.defaultQuota());
            CompoundTag encodedData = root.getCompoundOrEmpty("data");
            int encodedPlanCount = encodedData.getListOrEmpty("plans").size();
            MainlineQuestData catalog = CODEC.parse(
                            NbtOps.INSTANCE, encodedData)
                    .getOrThrow(IllegalStateException::new);
            if (catalog.plans.size() != encodedPlanCount) {
                throw new IOException("fixed mainline catalog contains a duplicate or empty plan");
            }
            Map<String, MissionBuildingPlanner.Site> sites = new LinkedHashMap<>();
            catalog.plans.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> MissionBuildingPlanner.Site.load(entry.getValue())
                            .map(MissionBuildingPlanner::withoutMissionInteriorPlan)
                            .ifPresent(site -> sites.put(entry.getKey(), site)));
            if (sites.size() != catalog.plans.size()) {
                throw new IOException("fixed mainline catalog contains an invalid site descriptor");
            }
            for (StoryMissionCatalog.StoryMission mission : StoryMissionCatalog.definitions()) {
                if (mission.encounter().type()
                        != MissionCatalog.MissionType.NEUTRALIZE_CYBERPSYCHO) {
                    continue;
                }
                sites.remove(mission.id());
                PublicEncounterPlanner.plan(
                                NeonCityGenerator.fixedLayout(), mission.primaryDistrict(),
                                NeonCityGenerator.contentSeed() ^ mission.id().hashCode(),
                                mission.id(), sites.values())
                        .ifPresent(site -> sites.put(mission.id(), site));
            }
            Cyberdeck.LOGGER.info(
                    "[Mainline] loaded {} pre-analyzed sites for fixed city seed {}",
                    sites.size(), NeonCityGenerator.contentSeed());
            return Map.copyOf(sites);
        } catch (IOException | RuntimeException exception) {
            Cyberdeck.LOGGER.error(
                    "[Mainline] fixed site catalog {} could not be loaded; missions will use "
                            + "on-demand recovery",
                    FIXED_SITE_RESOURCE, exception);
            return Map.of();
        }
    }

    static boolean buildingConflicts(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        if (first.buildingId().equals(second.buildingId())) return true;
        BoundingBox a = first.buildingBounds();
        BoundingBox b = second.buildingBounds();
        int clearance = hasPhysicalBuildingIdentity(first)
                        && hasPhysicalBuildingIdentity(second)
                ? 0 : MissionSiteData.SITE_CLEARANCE;
        // Compared on the footprint only, deliberately ignoring height. Two actors stacked on
        // different floors of one tower occupy the same building to anyone looking at it, and to
        // the map tools, even when the planner carved their floors into separate stacks whose
        // reserved volumes never meet.
        return a.minX() <= b.maxX() + clearance
                && a.maxX() + clearance >= b.minX()
                && a.minZ() <= b.maxZ() + clearance
                && a.maxZ() + clearance >= b.minZ();
    }

    /**
     * True when two sites share a physical building. Footprints that overlap at all are the same
     * structure regardless of which floors each site reserved, so a caller placing a second actor
     * can reject the whole tower rather than only the exact volume already taken.
     */
    static boolean sharesBuilding(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        if (first.buildingId().equals(second.buildingId())) return true;
        BoundingBox a = first.buildingBounds();
        BoundingBox b = second.buildingBounds();
        return a.minX() <= b.maxX() && a.maxX() >= b.minX()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    /**
     * True when two mainline sites would read to a player as one building, which is a stronger
     * requirement than "not the same reserved volume".
     *
     * <p>Three distinct ways two reservations collapse into one apparent building:</p>
     * <ol>
     *   <li>they literally share a structure ({@link #sharesBuilding}),</li>
     *   <li>their facades are close enough to belong to one complex - two towers separated by a
     *       nine-block alley on the same street are one address to whoever walks up to them, and</li>
     *   <li>they were stamped from the same Arnis source geometry. The atlas repeats by
     *       reflection, so a mission placed just past a mirror line lands in a pixel-perfect copy
     *       of its neighbour: different coordinates, identical building.</li>
     * </ol>
     */
    static boolean sameApparentBuilding(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        return sharesBuilding(first, second)
                || withinDistinctBuildingSeparation(first, second)
                || sharesSourceGeometry(first, second);
    }

    private static boolean withinDistinctBuildingSeparation(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        if (!hasPhysicalBuildingIdentity(first) || !hasPhysicalBuildingIdentity(second)) {
            return false;
        }
        BoundingBox a = first.buildingBounds();
        BoundingBox b = second.buildingBounds();
        int gapX = Math.max(0, Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()));
        int gapZ = Math.max(0, Math.max(a.minZ() - b.maxZ(), b.minZ() - a.maxZ()));
        return Math.max(gapX, gapZ) < DISTINCT_BUILDING_SEPARATION;
    }

    private static boolean sharesSourceGeometry(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        if (!hasPhysicalBuildingIdentity(first) || !hasPhysicalBuildingIdentity(second)) {
            return false;
        }
        Optional<String> a = sourceGeometryKey(first);
        return a.isPresent() && a.equals(sourceGeometryKey(second));
    }

    private static Optional<String> sourceGeometryKey(MissionBuildingPlanner.Site site) {
        BoundingBox bounds = site.buildingBounds();
        return ArnisPatchLibrary.sourceGeometryKey(
                NeonCityGenerator.layout(),
                bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
    }

    private static boolean hasPhysicalBuildingIdentity(MissionBuildingPlanner.Site site) {
        return !site.buildingId().equals(site.id());
    }
}
