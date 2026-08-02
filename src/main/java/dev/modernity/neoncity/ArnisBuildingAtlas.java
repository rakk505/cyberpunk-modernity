package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Compiles generated multi-chunk Arnis geometry into reusable mission-building candidates. */
public final class ArnisBuildingAtlas {
    private static final int REGION_RADIUS_CHUNKS = 2;
    private static final int MAX_REGION_ATTEMPTS = 24;
    private static final int MAX_SEARCH_RADIUS_CHUNKS = 16;
    private static final long REGION_SALT = 0x41524E4953424C44L;
    private static final Map<CacheKey, MissionBuildingPlanner.AtlasScan> CACHE =
            new LinkedHashMap<>();
    private static final Map<CompilationKey, Compilation> COMPILATIONS =
            new LinkedHashMap<>();
    private static final Map<District, Compilation> LATEST = new java.util.EnumMap<>(District.class);
    private static long compilationRequests;

    private ArnisBuildingAtlas() {
    }

    public record Compilation(
            District district,
            List<MissionBuildingPlanner.AtlasScan> scans,
            List<MissionBuildingPlanner.Site> sites) {
        public Compilation {
            scans = List.copyOf(scans);
            sites = List.copyOf(sites);
        }

        public int buildingCount() {
            return scans.stream().mapToInt(scan -> scan.buildings().size()).sum();
        }

        public int walkableCellCount() {
            return scans.stream().mapToInt(MissionBuildingPlanner.AtlasScan::walkableCellCount).sum();
        }
    }

    public static Optional<MissionBuildingPlanner.Site> findSite(
            ServerLevel level,
            District district,
            BlockPos origin,
            int searchRadiusChunks,
            long selectionSalt,
            int minimumFloors,
            int maximumFloors,
            Predicate<MissionBuildingPlanner.Site> filter) {
        Compilation compilation = compile(
                level, district, origin, searchRadiusChunks, selectionSalt,
                minimumFloors, maximumFloors, true);
        return orderedSites(compilation, selectionSalt).stream()
                .filter(filter)
                .filter(site -> MissionBuildingPlanner.preflight(level, site))
                .findFirst();
    }

    public static Compilation compile(
            ServerLevel level,
            District district,
            BlockPos origin,
            int searchRadiusChunks,
            long selectionSalt,
            int minimumFloors,
            int maximumFloors,
            boolean generateChunks) {
        if (level == null || district == null || origin == null) {
            throw new IllegalArgumentException("incomplete Arnis building compilation request");
        }
        compilationRequests++;
        int radius = Math.max(1, Math.min(MAX_SEARCH_RADIUS_CHUNKS, searchRadiusChunks));
        int centerChunkX = Math.floorDiv(origin.getX(), 16);
        int centerChunkZ = Math.floorDiv(origin.getZ(), 16);
        CompilationKey compilationKey = new CompilationKey(
                NeonCityGenerator.contentSeed(), NeonCityGenerator.layout().seed(), district,
                centerChunkX, centerChunkZ, radius, minimumFloors, maximumFloors);
        Compilation existing = COMPILATIONS.get(compilationKey);
        if (existing != null) {
            LATEST.put(district, existing);
            return existing;
        }
        long compilationSeed = MegacityLayout.mix(
                NeonCityGenerator.contentSeed()
                        ^ NeonCityGenerator.layout().seed() ^ REGION_SALT,
                district.ordinal(), minimumFloors * 31 + maximumFloors);
        int desiredSites = Math.max(1, (int) StoryMissionCatalog.definitions().stream()
                .filter(mission -> mission.primaryDistrict() == district)
                .count());
        List<RegionCandidate> candidates = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                        NeonCityGenerator.layout(), chunkX, chunkZ).orElse(null);
                if (placement == null || placement.patch().district() != district) continue;
                long score = MegacityLayout.mix(compilationSeed, chunkX, chunkZ);
                candidates.add(new RegionCandidate(
                        chunkX, chunkZ, Math.max(Math.abs(dx), Math.abs(dz)),
                        regionDensity(district, chunkX, chunkZ), score));
            }
        }
        List<RegionCandidate> densityRanked = candidates.stream()
                .sorted(Comparator.comparingLong(RegionCandidate::density).reversed()
                        .thenComparingInt(RegionCandidate::distance)
                        .thenComparingLong(RegionCandidate::score)
                        .thenComparingInt(RegionCandidate::chunkX)
                        .thenComparingInt(RegionCandidate::chunkZ))
                .toList();
        List<RegionCandidate> proximityRanked = candidates.stream()
                .sorted(Comparator.comparingInt(RegionCandidate::distance)
                        .thenComparing(Comparator.comparingLong(
                                RegionCandidate::density).reversed())
                        .thenComparingLong(RegionCandidate::score)
                        .thenComparingInt(RegionCandidate::chunkX)
                        .thenComparingInt(RegionCandidate::chunkZ))
                .toList();
        Map<Long, RegionCandidate> interleaved = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            RegionCandidate dense = densityRanked.get(index);
            interleaved.putIfAbsent(
                    net.minecraft.world.level.ChunkPos.pack(dense.chunkX(), dense.chunkZ()), dense);
            RegionCandidate nearby = proximityRanked.get(index);
            interleaved.putIfAbsent(
                    net.minecraft.world.level.ChunkPos.pack(nearby.chunkX(), nearby.chunkZ()), nearby);
        }
        candidates = new ArrayList<>(interleaved.values());

        List<MissionBuildingPlanner.AtlasScan> scans = new ArrayList<>();
        Map<String, MissionBuildingPlanner.Site> sites = new LinkedHashMap<>();
        Map<String, net.minecraft.world.level.levelgen.structure.BoundingBox> siteBuildings =
                new LinkedHashMap<>();
        int attempts = 0;
        for (RegionCandidate candidate : candidates) {
            if (attempts >= MAX_REGION_ATTEMPTS) break;
            if (!NeonCityGenerator.isUsableArnisChunk(
                    level, candidate.chunkX() << 4, candidate.chunkZ() << 4)) {
                continue;
            }
            attempts++;
            if (generateChunks) {
                NeonCityGenerator.generateNow(
                        level, candidate.chunkX(), candidate.chunkZ(), REGION_RADIUS_CHUNKS);
            }
            CacheKey key = new CacheKey(
                    NeonCityGenerator.contentSeed(), NeonCityGenerator.layout().seed(), district,
                    candidate.chunkX(), candidate.chunkZ(), minimumFloors, maximumFloors);
            MissionBuildingPlanner.AtlasScan scan = CACHE.get(key);
            if (scan == null) {
                scan = MissionBuildingPlanner.scanArnisRegion(
                        level, district, candidate.chunkX(), candidate.chunkZ(),
                        REGION_RADIUS_CHUNKS,
                        compilationSeed ^ candidate.score(), minimumFloors, maximumFloors);
                CACHE.put(key, scan);
            }
            scans.add(scan);
            for (MissionBuildingPlanner.Site site : scan.sites()) {
                MissionBuildingPlanner.BuildingLabel building = scan.buildings().stream()
                        .filter(label -> label.siteId().equals(site.id()))
                        .findFirst().orElse(null);
                String buildingId = building == null ? site.id() : building.id();
                var buildingBounds = building == null ? site.bounds() : building.bounds();
                if (!sites.containsKey(buildingId)
                        && siteBuildings.values().stream().noneMatch(
                                existingBounds -> footprintsOverlap(
                                        existingBounds, buildingBounds, 3))) {
                    sites.put(buildingId, site);
                    siteBuildings.put(buildingId, buildingBounds);
                }
            }
            if (sites.size() >= desiredSites) break;
        }
        Compilation result = new Compilation(district, scans, List.copyOf(sites.values()));
        COMPILATIONS.put(compilationKey, result);
        LATEST.put(district, result);
        long readyBuildings = scans.stream().flatMap(scan -> scan.buildings().stream())
                .filter(MissionBuildingPlanner.BuildingLabel::missionReady).count();
        Map<String, Long> decisions = scans.stream()
                .flatMap(scan -> scan.buildings().stream())
                .collect(java.util.stream.Collectors.groupingBy(
                        MissionBuildingPlanner.BuildingLabel::decision,
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
        Cyberdeck.LOGGER.info(
                "[BuildingAtlas] District {} scanned {} regions, {} walkable cells, "
                        + "{} building stacks, {} ready buildings, {} distinct sites for {}..{} floors",
                district.commandCode(), scans.size(), result.walkableCellCount(),
                result.buildingCount(), readyBuildings, result.sites().size(),
                minimumFloors, maximumFloors);
        Cyberdeck.LOGGER.info("[BuildingAtlas] District {} decisions {}",
                district.commandCode(), decisions);
        scans.stream().flatMap(scan -> scan.buildings().stream())
                .sorted(Comparator
                        .comparingInt((MissionBuildingPlanner.BuildingLabel label) ->
                                label.floorYs().size()).reversed()
                        .thenComparing(MissionBuildingPlanner.BuildingLabel::id))
                .limit(8)
                .forEach(label -> Cyberdeck.LOGGER.info(
                        "[BuildingAtlas] District {} candidate {} bounds {} floors {} cells {}: {}",
                        district.commandCode(), label.id(), label.bounds(), label.floorYs(),
                        label.walkableCellsPerFloor(), label.decision()));
        return result;
    }

    public static Optional<Compilation> latest(District district) {
        return Optional.ofNullable(LATEST.get(district));
    }

    public static void clear() {
        CACHE.clear();
        COMPILATIONS.clear();
        LATEST.clear();
        compilationRequests = 0L;
    }

    static long compilationRequests() {
        return compilationRequests;
    }

    private static long regionDensity(District district, int centerChunkX, int centerChunkZ) {
        long blocks = 0L;
        for (int dz = -REGION_RADIUS_CHUNKS; dz <= REGION_RADIUS_CHUNKS; dz++) {
            for (int dx = -REGION_RADIUS_CHUNKS; dx <= REGION_RADIUS_CHUNKS; dx++) {
                ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                        NeonCityGenerator.layout(), centerChunkX + dx, centerChunkZ + dz)
                        .orElse(null);
                if (placement != null && placement.patch().district() == district) {
                    blocks += placement.patch().blockCount();
                }
            }
        }
        return blocks;
    }

    private static boolean footprintsOverlap(
            net.minecraft.world.level.levelgen.structure.BoundingBox first,
            net.minecraft.world.level.levelgen.structure.BoundingBox second,
            int margin) {
        return first.minX() - margin <= second.maxX()
                && first.maxX() + margin >= second.minX()
                && first.minZ() - margin <= second.maxZ()
                && first.maxZ() + margin >= second.minZ();
    }

    private static List<MissionBuildingPlanner.Site> orderedSites(
            Compilation compilation, long selectionSalt) {
        return compilation.sites().stream()
                .sorted(Comparator
                        .comparingInt((MissionBuildingPlanner.Site site) ->
                                site.floorMasks().stream()
                                        .mapToInt(mask -> mask.cells().size()).sum())
                        .reversed()
                        .thenComparingLong(site -> MegacityLayout.mix(
                                selectionSalt, site.target().getX(), site.target().getZ()))
                        .thenComparing(MissionBuildingPlanner.Site::id))
                .toList();
    }

    private record CacheKey(
            long worldSeed,
            long layoutSeed,
            District district,
            int centerChunkX,
            int centerChunkZ,
            int minimumFloors,
            int maximumFloors) {
    }

    private record CompilationKey(
            long worldSeed,
            long layoutSeed,
            District district,
            int centerChunkX,
            int centerChunkZ,
            int searchRadius,
            int minimumFloors,
            int maximumFloors) {
    }

    private record RegionCandidate(
            int chunkX, int chunkZ, int distance, long density, long score) {
    }
}
