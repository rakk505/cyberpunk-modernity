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
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Permanent mainline site reservations and party-shared node progress. */
final class MainlineQuestData extends SavedData {
    private static final String FIXED_SITE_RESOURCE =
            "/data/neoncity/missions/mainline_sites_50520260801.dat";
    private static final Codec<StoredPlan> PLAN_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("mission").forGetter(StoredPlan::missionId),
                    CompoundTag.CODEC.fieldOf("site").forGetter(StoredPlan::site))
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

    private final Map<String, CompoundTag> plans = new HashMap<>();
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

    private record StoredPlan(String missionId, CompoundTag site) {
        private StoredPlan {
            site = site == null ? new CompoundTag() : site.copy();
        }
    }

    private MainlineQuestData() {
    }

    private MainlineQuestData(List<StoredPlan> plans, List<Progress> progress) {
        for (StoredPlan plan : plans) {
            if (!plan.missionId().isBlank() && !plan.site().isEmpty()) {
                this.plans.putIfAbsent(plan.missionId(), plan.site().copy());
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
        return Optional.ofNullable(fixedSites().get(missionId));
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
                : MissionBuildingPlanner.Site.load(encoded.copy());
    }

    void putSite(String missionId, MissionBuildingPlanner.Site site) {
        CompoundTag encoded = site.save();
        CompoundTag previous = plans.put(missionId, encoded);
        if (previous == null || !previous.equals(encoded)) setDirty();
    }

    boolean conflicts(MissionBuildingPlanner.Site candidate, String exceptMissionId) {
        if (candidate == null) return true;
        for (Map.Entry<String, CompoundTag> entry : plans.entrySet()) {
            if (entry.getKey().equals(exceptMissionId)) continue;
            MissionBuildingPlanner.Site reserved = MissionBuildingPlanner.Site.load(
                    entry.getValue()).orElse(null);
            if (reserved != null && overlaps(reserved, candidate)) return true;
        }
        return false;
    }

    List<MissionBuildingPlanner.Site> sites() {
        return plans.values().stream()
                .map(MissionBuildingPlanner.Site::load)
                .flatMap(Optional::stream)
                .toList();
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
                .map(entry -> new StoredPlan(entry.getKey(), entry.getValue()))
                .toList();
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
            MainlineQuestData catalog = CODEC.parse(
                            NbtOps.INSTANCE, root.getCompoundOrEmpty("data"))
                    .getOrThrow(IllegalStateException::new);
            Map<String, MissionBuildingPlanner.Site> sites = new LinkedHashMap<>();
            catalog.plans.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> MissionBuildingPlanner.Site.load(entry.getValue())
                            .ifPresent(site -> sites.put(entry.getKey(), site)));
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

    private static boolean overlaps(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        BoundingBox a = first.bounds();
        BoundingBox b = second.bounds();
        int clearance = MissionSiteData.SITE_CLEARANCE;
        return a.minX() <= b.maxX() + clearance
                && a.maxX() + clearance >= b.minX()
                && a.minZ() <= b.maxZ() + clearance
                && a.maxZ() + clearance >= b.minZ();
    }
}
