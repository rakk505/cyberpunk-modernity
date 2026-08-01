package dev.modernity.neoncity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/** Deterministic solar installations on raised cliff borders. */
final class CliffInfrastructureLibrary {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CATALOG_RESOURCE =
            "/data/neoncity/cliff_infrastructure/catalog.json";
    private static final long CANDIDATE_SALT = 0x434C494646534F4CL;
    private static final int SITE_CELL_SIZE = 64;
    private static final int SITE_JITTER = 8;
    private static final int MAX_TERRAIN_RELIEF = 6;
    private static final int MAX_PLAN_CACHE = 32_768;
    private static final double SITE_DENSITY = 0.55;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;

    private static final List<SolarAsset> ASSETS = loadCatalog();
    private static final SolarAsset SOLAR_PANEL = ASSETS.getFirst();
    private static final Map<SiteKey, Optional<SolarCandidate>> PLAN_CACHE =
            new ConcurrentHashMap<>();

    private CliffInfrastructureLibrary() {
    }

    record SupportBounds(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {
    }

    record SolarAsset(
            String catalogId,
            Identifier templateId,
            int sizeX,
            int sizeY,
            int sizeZ,
            int blockCount,
            SupportBounds supportBounds,
            String sha256) {
        int sizeX(Rotation rotation) {
            return swapsAxes(rotation) ? sizeZ : sizeX;
        }

        int sizeZ(Rotation rotation) {
            return swapsAxes(rotation) ? sizeX : sizeZ;
        }
    }

    record SolarCandidate(
            SolarAsset asset,
            int cellX,
            int cellZ,
            int minX,
            int minZ,
            int baseY,
            Rotation rotation,
            District firstDistrict,
            District secondDistrict,
            int minGroundY,
            int maxGroundY,
            long selectionHash) {
        int sizeX() {
            return asset.sizeX(rotation);
        }

        int sizeZ() {
            return asset.sizeZ(rotation);
        }

        int maxX() {
            return minX + sizeX() - 1;
        }

        int maxZ() {
            return minZ + sizeZ() - 1;
        }

        BoundingBox bounds() {
            return new BoundingBox(
                    minX,
                    baseY,
                    minZ,
                    maxX(),
                    baseY + asset.sizeY() - 1,
                    maxZ());
        }

        boolean intersects(ChunkPos chunk) {
            return maxX() >= chunk.getMinBlockX()
                    && minX <= chunk.getMaxBlockX()
                    && maxZ() >= chunk.getMinBlockZ()
                    && minZ <= chunk.getMaxBlockZ();
        }

        boolean containsColumn(int worldX, int worldZ) {
            return worldX >= minX && worldX <= maxX()
                    && worldZ >= minZ && worldZ <= maxZ();
        }
    }

    private record SiteKey(long seed, int cellX, int cellZ) {
    }

    private record FootprintMetrics(int minGroundY, int maxGroundY) {
    }

    static List<SolarAsset> templates() {
        return ASSETS;
    }

    static SolarAsset solarPanel() {
        return SOLAR_PANEL;
    }

    static int siteCellSize() {
        return SITE_CELL_SIZE;
    }

    static double siteDensity() {
        return SITE_DENSITY;
    }

    static int maxTerrainRelief() {
        return MAX_TERRAIN_RELIEF;
    }

    static boolean isCandidateCell(long seed, int cellX, int cellZ) {
        long hash = siteHash(seed, cellX, cellZ);
        return unit(Long.rotateLeft(hash, 19)) < SITE_DENSITY;
    }

    /** Resolve the globally stable solar site owned by one lattice cell. */
    static Optional<SolarCandidate> candidateForCell(
            MegacityLayout layout,
            int cellX,
            int cellZ) {
        SiteKey key = new SiteKey(layout.seed(), cellX, cellZ);
        Optional<SolarCandidate> cached = PLAN_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (PLAN_CACHE.size() >= MAX_PLAN_CACHE) {
            PLAN_CACHE.clear();
        }
        Optional<SolarCandidate> planned = planCell(layout, cellX, cellZ);
        PLAN_CACHE.put(key, planned);
        return planned;
    }

    /** Every chunk independently derives all global sites whose bounds overlap it. */
    static List<SolarCandidate> candidatesForChunk(MegacityLayout layout, ChunkPos chunk) {
        int reach = Math.max(SOLAR_PANEL.sizeX(), SOLAR_PANEL.sizeZ()) / 2 + 1;
        int minCellX = Math.floorDiv(chunk.getMinBlockX() - reach, SITE_CELL_SIZE);
        int maxCellX = Math.floorDiv(chunk.getMaxBlockX() + reach, SITE_CELL_SIZE);
        int minCellZ = Math.floorDiv(chunk.getMinBlockZ() - reach, SITE_CELL_SIZE);
        int maxCellZ = Math.floorDiv(chunk.getMaxBlockZ() + reach, SITE_CELL_SIZE);
        List<SolarCandidate> result = new ArrayList<>();
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                candidateForCell(layout, cellX, cellZ)
                        .filter(candidate -> candidate.intersects(chunk))
                        .ifPresent(result::add);
            }
        }
        result.sort(Comparator
                .comparingInt(SolarCandidate::cellZ)
                .thenComparingInt(SolarCandidate::cellX));
        return List.copyOf(result);
    }

    /** True only when every rotated footprint column remains on one cliff boundary pair. */
    static boolean isEligibleFootprint(
            MegacityLayout layout,
            SolarCandidate candidate) {
        return inspectFootprint(
                layout,
                candidate.minX(),
                candidate.minZ(),
                candidate.sizeX(),
                candidate.sizeZ(),
                candidate.firstDistrict(),
                candidate.secondDistrict()).filter(metrics ->
                        metrics.minGroundY() == candidate.minGroundY()
                                && metrics.maxGroundY() == candidate.maxGroundY())
                .isPresent();
    }

    /** Allows other cliff decorators to honor the solar reservation before placing props. */
    static boolean reservesColumn(MegacityLayout layout, int worldX, int worldZ) {
        int reach = Math.max(SOLAR_PANEL.sizeX(), SOLAR_PANEL.sizeZ()) / 2 + 1;
        int minCellX = Math.floorDiv(worldX - reach, SITE_CELL_SIZE);
        int maxCellX = Math.floorDiv(worldX + reach, SITE_CELL_SIZE);
        int minCellZ = Math.floorDiv(worldZ - reach, SITE_CELL_SIZE);
        int maxCellZ = Math.floorDiv(worldZ + reach, SITE_CELL_SIZE);
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                Optional<SolarCandidate> candidate = candidateForCell(layout, cellX, cellZ);
                if (candidate.isPresent()
                        && candidate.orElseThrow().containsColumn(worldX, worldZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void clearCaches() {
        PLAN_CACHE.clear();
    }

    /** Place only this chunk's slice; neighboring chunks reproduce the same global anchor. */
    static int decorateChunk(ServerLevel level, ChunkPos chunk) {
        List<SolarCandidate> candidates = candidatesForChunk(NeonCityGenerator.layout(), chunk);
        if (candidates.isEmpty()) {
            return 0;
        }
        StructureTemplate template = level.getStructureManager()
                .get(SOLAR_PANEL.templateId()).orElse(null);
        if (template == null) {
            LOGGER.error("[NeonCity] missing cliff solar template {}", SOLAR_PANEL.templateId());
            return 0;
        }
        Vec3i size = template.getSize();
        if (size.getX() != SOLAR_PANEL.sizeX()
                || size.getY() != SOLAR_PANEL.sizeY()
                || size.getZ() != SOLAR_PANEL.sizeZ()) {
            LOGGER.error(
                    "[NeonCity] cliff solar template {} size {} disagrees with catalog {}x{}x{}",
                    SOLAR_PANEL.templateId(),
                    size,
                    SOLAR_PANEL.sizeX(),
                    SOLAR_PANEL.sizeY(),
                    SOLAR_PANEL.sizeZ());
            return 0;
        }

        int placedSlices = 0;
        for (SolarCandidate candidate : candidates) {
            if (!isChunkSliceClear(level, chunk, candidate)) {
                continue;
            }
            if (placeChunkSlice(level, chunk, template, candidate)) {
                placedSlices++;
            }
        }
        return placedSlices;
    }

    private static Optional<SolarCandidate> planCell(
            MegacityLayout layout,
            int cellX,
            int cellZ) {
        if (!isCandidateCell(layout.seed(), cellX, cellZ)) {
            return Optional.empty();
        }
        long hash = siteHash(layout.seed(), cellX, cellZ);
        int centerX = cellX * SITE_CELL_SIZE + SITE_CELL_SIZE / 2
                + Math.floorMod((int) hash, SITE_JITTER * 2 + 1) - SITE_JITTER;
        int centerZ = cellZ * SITE_CELL_SIZE + SITE_CELL_SIZE / 2
                + Math.floorMod((int) (hash >>> 32), SITE_JITTER * 2 + 1) - SITE_JITTER;
        MegacityLayout.Location location = layout.locate(centerX, centerZ);
        if (location.zone() != MegacityLayout.Zone.BORDER_CLIFF) {
            return Optional.empty();
        }
        NeonCityGenerator.UrbanSample centerSample = NeonCityGenerator.topologySample(
                layout, centerX, centerZ);
        if (centerSample.roadClass() != NeonCityGenerator.RoadClass.BORDER_CLIFF) {
            return Optional.empty();
        }
        MegacityLayout.BoundaryFrame frame = layout.boundaryFrame(location, centerX, centerZ);
        Rotation rotation = tangentRotation(frame);
        int sizeX = SOLAR_PANEL.sizeX(rotation);
        int sizeZ = SOLAR_PANEL.sizeZ(rotation);
        int minX = centerX - sizeX / 2;
        int minZ = centerZ - sizeZ / 2;
        Optional<FootprintMetrics> metrics = inspectFootprint(
                layout,
                minX,
                minZ,
                sizeX,
                sizeZ,
                frame.first(),
                frame.second());
        if (metrics.isEmpty()) {
            return Optional.empty();
        }
        FootprintMetrics footprint = metrics.orElseThrow();
        int baseY = footprint.maxGroundY() + 1;
        if (baseY + SOLAR_PANEL.sizeY() - 1 > NeonCityGenerator.MAX_BUILD_Y) {
            return Optional.empty();
        }
        return Optional.of(new SolarCandidate(
                SOLAR_PANEL,
                cellX,
                cellZ,
                minX,
                minZ,
                baseY,
                rotation,
                frame.first(),
                frame.second(),
                footprint.minGroundY(),
                footprint.maxGroundY(),
                hash));
    }

    private static Optional<FootprintMetrics> inspectFootprint(
            MegacityLayout layout,
            int minX,
            int minZ,
            int sizeX,
            int sizeZ,
            District expectedFirst,
            District expectedSecond) {
        int minGround = Integer.MAX_VALUE;
        int maxGround = Integer.MIN_VALUE;
        for (int z = minZ; z < minZ + sizeZ; z++) {
            for (int x = minX; x < minX + sizeX; x++) {
                NeonCityGenerator.UrbanSample sample = NeonCityGenerator.topologySample(
                        layout, x, z);
                if (sample.roadClass() != NeonCityGenerator.RoadClass.BORDER_CLIFF) {
                    return Optional.empty();
                }
                MegacityLayout.BoundaryFrame frame = layout.boundaryFrame(
                        sample.location(), x, z);
                if (frame.first() != expectedFirst || frame.second() != expectedSecond) {
                    return Optional.empty();
                }
                minGround = Math.min(minGround, sample.groundY());
                maxGround = Math.max(maxGround, sample.groundY());
                if (maxGround - minGround > MAX_TERRAIN_RELIEF) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new FootprintMetrics(minGround, maxGround));
    }

    private static boolean isChunkSliceClear(
            ServerLevel level,
            ChunkPos chunk,
            SolarCandidate candidate) {
        int minX = Math.max(chunk.getMinBlockX(), candidate.minX());
        int maxX = Math.min(chunk.getMaxBlockX(), candidate.maxX());
        int minZ = Math.max(chunk.getMinBlockZ(), candidate.minZ());
        int maxZ = Math.min(chunk.getMaxBlockZ(), candidate.maxZ());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = candidate.baseY();
                        y < candidate.baseY() + candidate.asset().sizeY();
                        y++) {
                    cursor.set(x, y, z);
                    if (!level.isEmptyBlock(cursor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean placeChunkSlice(
            ServerLevel level,
            ChunkPos chunk,
            StructureTemplate template,
            SolarCandidate candidate) {
        BlockPos desiredMin = new BlockPos(candidate.minX(), candidate.baseY(), candidate.minZ());
        BlockPos anchor = template.getZeroPositionWithTransform(
                desiredMin, Mirror.NONE, candidate.rotation());
        StructurePlaceSettings transform = placementSettings(candidate.rotation(), null);
        BoundingBox transformedBounds = template.getBoundingBox(transform, anchor);
        if (!sameBounds(candidate.bounds(), transformedBounds)) {
            LOGGER.error(
                    "[NeonCity] transformed cliff solar {} disagrees with plan: expected {}, got {}",
                    candidate.asset().templateId(), candidate.bounds(), transformedBounds);
            return false;
        }

        placeSupportPiers(level, chunk, candidate);
        BoundingBox slice = new BoundingBox(
                Math.max(chunk.getMinBlockX(), candidate.minX()),
                candidate.baseY(),
                Math.max(chunk.getMinBlockZ(), candidate.minZ()),
                Math.min(chunk.getMaxBlockX(), candidate.maxX()),
                candidate.baseY() + candidate.asset().sizeY() - 1,
                Math.min(chunk.getMaxBlockZ(), candidate.maxZ()));
        StructurePlaceSettings settings = placementSettings(candidate.rotation(), slice);
        boolean placed = template.placeInWorld(
                level,
                anchor,
                anchor,
                settings,
                RandomSource.create(candidate.selectionHash()),
                PLACE_FLAGS);
        if (!placed) {
            LOGGER.error(
                    "[NeonCity] cliff solar template {} refused slice placement in {}",
                    candidate.asset().templateId(), chunk);
        } else if (chunk.x() == Math.floorDiv(candidate.minX() + candidate.sizeX() / 2, 16)
                && chunk.z() == Math.floorDiv(candidate.minZ() + candidate.sizeZ() / 2, 16)) {
            LOGGER.debug(
                    "[NeonCity] placed cliff solar site {} at {} across boundary {}-{}",
                    candidate.asset().catalogId(),
                    desiredMin,
                    candidate.firstDistrict().label(),
                    candidate.secondDistrict().label());
        }
        return placed;
    }

    private static StructurePlaceSettings placementSettings(
            Rotation rotation,
            BoundingBox bounds) {
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setMirror(Mirror.NONE)
                .setRotation(rotation)
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
        return bounds == null ? settings : settings.setBoundingBox(bounds);
    }

    private static void placeSupportPiers(
            ServerLevel level,
            ChunkPos chunk,
            SolarCandidate candidate) {
        SupportBounds support = candidate.asset().supportBounds();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int localZ = support.minZ(); localZ <= support.maxZ(); localZ++) {
            for (int localX = support.minX(); localX <= support.maxX(); localX++) {
                int[] transformed = transformLocalXZ(
                        candidate.asset(), candidate.rotation(), localX, localZ);
                int worldX = candidate.minX() + transformed[0];
                int worldZ = candidate.minZ() + transformed[1];
                if (worldX < chunk.getMinBlockX() || worldX > chunk.getMaxBlockX()
                        || worldZ < chunk.getMinBlockZ() || worldZ > chunk.getMaxBlockZ()) {
                    continue;
                }
                int groundY = NeonCityGenerator.sample(worldX, worldZ).groundY();
                for (int y = groundY + 1; y < candidate.baseY(); y++) {
                    level.setBlock(
                            cursor.set(worldX, y, worldZ),
                            Blocks.POLISHED_ANDESITE.defaultBlockState(),
                            PLACE_FLAGS);
                }
            }
        }
    }

    private static int[] transformLocalXZ(
            SolarAsset asset,
            Rotation rotation,
            int localX,
            int localZ) {
        return switch (rotation) {
            case NONE -> new int[]{localX, localZ};
            case CLOCKWISE_90 -> new int[]{asset.sizeZ() - 1 - localZ, localX};
            case CLOCKWISE_180 ->
                    new int[]{asset.sizeX() - 1 - localX, asset.sizeZ() - 1 - localZ};
            case COUNTERCLOCKWISE_90 -> new int[]{localZ, asset.sizeX() - 1 - localX};
        };
    }

    private static Rotation tangentRotation(MegacityLayout.BoundaryFrame frame) {
        if (Math.abs(frame.tangentZ()) >= Math.abs(frame.tangentX())) {
            return frame.tangentZ() >= 0.0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
        }
        return frame.tangentX() >= 0.0
                ? Rotation.COUNTERCLOCKWISE_90
                : Rotation.CLOCKWISE_90;
    }

    private static long siteHash(long seed, int cellX, int cellZ) {
        return MegacityLayout.mix(seed ^ CANDIDATE_SALT, cellX, cellZ);
    }

    private static boolean swapsAxes(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90
                || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    private static boolean sameBounds(BoundingBox first, BoundingBox second) {
        return first.minX() == second.minX()
                && first.minY() == second.minY()
                && first.minZ() == second.minZ()
                && first.maxX() == second.maxX()
                && first.maxY() == second.maxY()
                && first.maxZ() == second.maxZ();
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static List<SolarAsset> loadCatalog() {
        try (InputStream stream = CliffInfrastructureLibrary.class
                .getResourceAsStream(CATALOG_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "missing cliff infrastructure catalog " + CATALOG_RESOURCE);
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray templates = root.getAsJsonArray("templates");
            List<SolarAsset> result = new ArrayList<>(templates.size());
            Set<String> ids = ConcurrentHashMap.newKeySet();
            for (JsonElement element : templates) {
                JsonObject entry = element.getAsJsonObject();
                String id = entry.get("id").getAsString();
                if (!ids.add(id)) {
                    throw new IllegalStateException(
                            "duplicate cliff infrastructure id " + id);
                }
                JsonArray size = entry.getAsJsonArray("size");
                JsonArray support = entry.getAsJsonArray("support_bounds");
                String expectedTemplate = NeonCityGenerator.NAMESPACE
                        + ":cliff_infrastructure/" + id;
                if (!entry.get("template").getAsString().equals(expectedTemplate)) {
                    throw new IllegalStateException(
                            "unexpected cliff infrastructure template for " + id);
                }
                result.add(new SolarAsset(
                        id,
                        Identifier.fromNamespaceAndPath(
                                NeonCityGenerator.NAMESPACE, "cliff_infrastructure/" + id),
                        size.get(0).getAsInt(),
                        size.get(1).getAsInt(),
                        size.get(2).getAsInt(),
                        entry.get("blocks").getAsInt(),
                        new SupportBounds(
                                support.get(0).getAsInt(),
                                support.get(1).getAsInt(),
                                support.get(2).getAsInt(),
                                support.get(3).getAsInt(),
                                support.get(4).getAsInt(),
                                support.get(5).getAsInt()),
                        entry.get("sha256").getAsString()));
            }
            if (result.size() != 1 || !result.getFirst().catalogId().equals("solar_panel")) {
                throw new IllegalStateException(
                        "expected one audited cliff solar template, found " + result.size());
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "could not load cliff infrastructure catalog", exception);
        }
    }
}
