package dev.modernity.neoncity;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/**
 * Finite, world-seeded Project Moon-inspired megacity construction.
 *
 * <p>A {@link MegacityLayout} supplies twenty-six irregular district blobs and
 * a connected travel graph. This class turns that pure plan into terrain,
 * infrastructure, cultural architecture, and untouched wilderness. All
 * sampling is in global coordinates, so chunk generation order cannot change
 * a road, building, bridge, or district border.</p>
 */
public final class NeonCityGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAMESPACE = "neoncity";
    public static final String GENERATOR_FINGERPRINT =
            "project-moon-megacity-v6-arnis-district-atlases-20260729";
    public static final int CITY_GROUND_Y = 72;
    public static final int WATER_Y = 67;
    public static final int ENQUEUE_RADIUS_CHUNKS = 7;
    public static final int SPAWN_PREWARM_RADIUS_CHUNKS = 1;
    public static final int MAX_PENDING_CHUNKS = 768;

    private static final int PLACE_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final int MAX_BUILD_Y = 318;
    private static final BlockPos DEFAULT_SPAWN = new BlockPos(0, CITY_GROUND_Y + 2, 0);
    private static final ResourceKey<DimensionType> MEGACITY_DIMENSION_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            Identifier.fromNamespaceAndPath(NAMESPACE, "megacity_overworld"));

    private static final ArrayDeque<ChunkPos> PENDING = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static final Set<Long> GENERATED = new HashSet<>();
    private static final Map<Long, AlleyMaze.Plan> DIAGNOSTIC_ALLEY_PLANS = new HashMap<>();
    private static MegacityLayout layout = MegacityLayout.create(MegacityLayout.DEFAULT_SEED);
    private static long layoutWorldSeed = Long.MIN_VALUE;
    private static NeonCitySavedData savedData;
    private static boolean enabled;

    private NeonCityGenerator() {}

    public enum RoadClass {
        NONE,
        CENTRAL_PLAZA,
        DISTRICT_BOULEVARD,
        LOCAL_STREET,
        SERVICE_ALLEY,
        INTERDISTRICT_ROAD,
        BRIDGE,
        ELEVATED_RAIL,
        CANAL,
        PARK,
        HARBOR,
        FARM,
        EXTRACTION_SITE,
        BORDER_RIVER,
        BORDER_HILLS,
        WILDERNESS
    }

    /** Pure diagnostic sample shared by runtime, tests, and preview tooling. */
    public record UrbanSample(
            MegacityLayout.Location location,
            District district,
            MegacityLayout.Zone zone,
            RoadClass roadClass,
            int groundY,
            int buildingHeight,
            int parcelSize,
            boolean insideFootprint,
            int parcelX,
            int parcelZ,
            double parcelLocalU,
            double parcelLocalV,
            long parcelHash
    ) {}

    private record Palette(
            BlockState wall,
            BlockState secondary,
            BlockState glass,
            BlockState accent,
            BlockState frame,
            BlockState roof
    ) {}

    private record TreePalette(BlockState trunk, BlockState leaves) {}

    private record LocalCoordinates(double u, double v) {}

    public static boolean initialize(ServerLevel level) {
        clearTransientState();
        savedData = null;
        if (!isMegacityWorld(level)) {
            LOGGER.info("[NeonCity] dedicated megacity dimension marker not detected; disabled");
            return false;
        }
        layout = MegacityLayout.create(level.getSeed());
        layoutWorldSeed = level.getSeed();
        savedData = level.getDataStorage().computeIfAbsent(NeonCitySavedData.TYPE);
        if (!savedData.isCompatible(GENERATOR_FINGERPRINT)) {
            LOGGER.error(
                    "[NeonCity] generator fingerprint mismatch (world={}, bundled={}); disabled",
                    savedData.generatorFingerprint(), GENERATOR_FINGERPRINT);
            return false;
        }
        GENERATED.addAll(savedData.snapshot());
        enabled = true;
        LOGGER.info("[NeonCity] finite layout seed={} restored={} districts={} edges={}",
                layout.seed(), GENERATED.size(), layout.nodes().size(), layout.edges().size());
        return true;
    }

    public static void reset() {
        clearTransientState();
        savedData = null;
        layout = MegacityLayout.create(MegacityLayout.DEFAULT_SEED);
        layoutWorldSeed = Long.MIN_VALUE;
    }

    private static void clearTransientState() {
        PENDING.clear();
        QUEUED.clear();
        GENERATED.clear();
        DIAGNOSTIC_ALLEY_PLANS.clear();
        ArnisPatchLibrary.clearSelectionCache();
        enabled = false;
    }

    /** The custom dimension type is the opt-in marker while terrain stays vanilla noise. */
    public static boolean isMegacityWorld(ServerLevel level) {
        return level.dimensionTypeRegistration().is(MEGACITY_DIMENSION_TYPE);
    }

    public static int enqueueAround(int worldX, int worldZ) {
        return enqueueAroundChunk(
                SectionPos.blockToSectionCoord(worldX),
                SectionPos.blockToSectionCoord(worldZ),
                ENQUEUE_RADIUS_CHUNKS);
    }

    public static int enqueueAroundChunk(int centerChunkX, int centerChunkZ, int radius) {
        if (!enabled || radius < 0) return 0;
        int added = 0;
        for (int ring = 0; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    if (!chunkTouchesCity(chunkX, chunkZ)) continue;
                    long key = ChunkPos.pack(chunkX, chunkZ);
                    if (GENERATED.contains(key) || !QUEUED.add(key)) continue;
                    if (PENDING.size() >= MAX_PENDING_CHUNKS) {
                        ChunkPos evicted = PENDING.removeFirst();
                        QUEUED.remove(evicted.pack());
                    }
                    PENDING.addLast(new ChunkPos(chunkX, chunkZ));
                    added++;
                }
            }
        }
        return added;
    }

    public static boolean chunkTouchesCity(int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int[] offsets = {0, 8, 15};
        for (int dz : offsets) {
            for (int dx : offsets) {
                if (layout.locate(minX + dx, minZ + dz).insideCity()) return true;
            }
        }
        return false;
    }

    /** Generate at most one already-loaded city chunk per tick. */
    public static void tick(ServerLevel level) {
        if (!enabled || savedData == null || PENDING.isEmpty()) return;
        int candidates = PENDING.size();
        while (candidates-- > 0) {
            ChunkPos chunk = PENDING.removeFirst();
            long key = chunk.pack();
            if (GENERATED.contains(key)) {
                QUEUED.remove(key);
                continue;
            }
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
                PENDING.addLast(chunk);
                continue;
            }
            boolean placed = generateChunk(level, chunk);
            QUEUED.remove(key);
            if (placed) recordGenerated(key);
            return;
        }
    }

    public static int prewarmSpawn(ServerLevel level) {
        if (!enabled || savedData == null) return 0;
        int placed = 0;
        for (int chunkZ = -SPAWN_PREWARM_RADIUS_CHUNKS;
             chunkZ <= SPAWN_PREWARM_RADIUS_CHUNKS; chunkZ++) {
            for (int chunkX = -SPAWN_PREWARM_RADIUS_CHUNKS;
                 chunkX <= SPAWN_PREWARM_RADIUS_CHUNKS; chunkX++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                long key = chunk.pack();
                level.getChunk(chunkX, chunkZ);
                removePending(key);
                if (GENERATED.contains(key) || !chunkTouchesCity(chunkX, chunkZ)) continue;
                if (generateChunk(level, chunk)) {
                    recordGenerated(key);
                    placed++;
                }
            }
        }
        if (GENERATED.contains(ChunkPos.ZERO.pack())) {
            level.setRespawnData(LevelData.RespawnData.of(
                    level.dimension(), DEFAULT_SPAWN, 0.0F, 0.0F));
        }
        return placed;
    }

    private static void removePending(long key) {
        QUEUED.remove(key);
        PENDING.removeIf(chunk -> chunk.pack() == key);
    }

    private static void recordGenerated(long key) {
        if (savedData == null || !savedData.isCompatible(GENERATOR_FINGERPRINT)) {
            enabled = false;
            LOGGER.error("[NeonCity] compatible ledger disappeared; generation disabled");
            return;
        }
        GENERATED.add(key);
        savedData.markGenerated(key, GENERATOR_FINGERPRINT);
    }

    private static boolean generateChunk(ServerLevel level, ChunkPos chunk) {
        try {
            Optional<ArnisPatchLibrary.Placement> patchPlacement =
                    ArnisPatchLibrary.select(layout, chunk.x(), chunk.z());
            Map<Long, AlleyMaze.Plan> alleyPlans = new HashMap<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int minX = chunk.getMinBlockX();
            int minZ = chunk.getMinBlockZ();
            UrbanSample[][] samples = new UrbanSample[18][18];
            for (int sampleZ = 0; sampleZ < 18; sampleZ++) {
                for (int sampleX = 0; sampleX < 18; sampleX++) {
                    samples[sampleZ][sampleX] = sample(
                            minX + sampleX - 1, minZ + sampleZ - 1, alleyPlans);
                }
            }

            // Arnis is the default for ordinary developed land. Graph crossings, district
            // borders, and culture-specific infrastructure keep the procedural implementation
            // that can cross chunk and district boundaries safely.
            if (patchPlacement.isPresent()
                    && !isOrdinaryArnisChunk(samples, patchPlacement.get().patch().district())) {
                patchPlacement = Optional.empty();
            }
            StructureTemplate patchTemplate = patchPlacement
                    .flatMap(placement -> level.getStructureManager().get(placement.patch().templateId()))
                    .orElse(null);
            if (patchPlacement.isPresent() && patchTemplate == null) {
                LOGGER.error("[NeonCity] audited Arnis template {} is missing; chunk {} will retry",
                        patchPlacement.get().patch().templateId(), chunk);
                return false;
            }
            if (patchPlacement.isPresent()
                    && !templateMatchesCatalog(patchTemplate, patchPlacement.get().patch())) {
                LOGGER.error("[NeonCity] Arnis template {} size {} disagrees with audited {}x{}x{}; chunk {} will retry",
                        patchPlacement.get().patch().templateId(), patchTemplate.getSize(),
                        patchPlacement.get().patch().sizeX(),
                        patchPlacement.get().patch().sizeY(),
                        patchPlacement.get().patch().sizeZ(), chunk);
                return false;
            }
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int x = minX + localX;
                    int z = minZ + localZ;
                    UrbanSample sample = samples[localZ + 1][localX + 1];
                    if (sample.zone() == MegacityLayout.Zone.WILDERNESS) continue;
                    if (patchTemplate != null) {
                        prepareArnisColumn(level, pos, x, z);
                        continue;
                    }
                    boolean facadeBoundary = !sameBuilding(sample, samples[localZ][localX + 1])
                            || !sameBuilding(sample, samples[localZ + 2][localX + 1])
                            || !sameBuilding(sample, samples[localZ + 1][localX])
                            || !sameBuilding(sample, samples[localZ + 1][localX + 2]);
                    buildColumn(level, pos, x, z, sample, facadeBoundary);
                }
            }
            if (patchTemplate != null && patchPlacement.isPresent()) {
                if (!placeArnisPatch(level, chunk, patchPlacement.get(), patchTemplate)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("[NeonCity] failed generating chunk {}", chunk, exception);
            return false;
        }
    }

    private static boolean isOrdinaryArnisChunk(
            UrbanSample[][] samples, District selectedDistrict) {
        for (UrbanSample[] row : samples) {
            for (UrbanSample sample : row) {
                if (requiresProceduralInfrastructure(sample.roadClass())) return false;
            }
        }
        // A center-selected atlas cell may touch an irregular district edge. Do not stamp its
        // square footprint over wilderness or into another Corp; use the procedural edge pass.
        for (int z = 1; z <= 16; z++) {
            for (int x = 1; x <= 16; x++) {
                UrbanSample sample = samples[z][x];
                if (sample.district() != selectedDistrict
                        || (sample.zone() != MegacityLayout.Zone.NEST
                        && sample.zone() != MegacityLayout.Zone.BACKSTREETS)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean requiresProceduralInfrastructure(RoadClass roadClass) {
        return switch (roadClass) {
            case INTERDISTRICT_ROAD,
                    BRIDGE,
                    ELEVATED_RAIL,
                    BORDER_RIVER,
                    BORDER_HILLS,
                    CANAL,
                    PARK,
                    HARBOR,
                    FARM,
                    EXTRACTION_SITE -> true;
            default -> false;
        };
    }

    private static boolean templateMatchesCatalog(
            StructureTemplate template, ArnisPatchLibrary.Patch patch) {
        Vec3i size = template.getSize();
        return size.getX() == patch.sizeX()
                && size.getY() == patch.sizeY()
                && size.getZ() == patch.sizeZ();
    }

    private static void prepareArnisColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                           int x, int z) {
        int naturalTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        for (int y = CITY_GROUND_Y + 1; y <= Math.min(MAX_BUILD_Y, Math.max(naturalTop + 2, 112)); y++) {
            set(level, pos, x, y, z, Blocks.AIR.defaultBlockState());
        }
        for (int y = Math.min(naturalTop, CITY_GROUND_Y - 5); y <= CITY_GROUND_Y; y++) {
            if (y >= level.getMinY()) set(level, pos, x, y, z, Blocks.DEEPSLATE.defaultBlockState());
        }
        set(level, pos, x, CITY_GROUND_Y, z, Blocks.SMOOTH_STONE.defaultBlockState());
    }

    private static boolean placeArnisPatch(ServerLevel level, ChunkPos chunk,
                                           ArnisPatchLibrary.Placement placement,
                                           StructureTemplate template) {
        ArnisPatchLibrary.Patch patch = placement.patch();
        int minX = chunk.getMinBlockX();
        int minY = CITY_GROUND_Y - patch.surfaceOffset();
        int minZ = chunk.getMinBlockZ();
        BlockPos desiredMin = new BlockPos(minX, minY, minZ);
        BlockPos anchor = template.getZeroPositionWithTransform(
                desiredMin, placement.mirror(), placement.rotation());
        BoundingBox destinationBounds = new BoundingBox(
                minX, minY, minZ,
                chunk.getMaxBlockX(), minY + patch.sizeY() - 1, chunk.getMaxBlockZ());
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setMirror(placement.mirror())
                .setRotation(placement.rotation())
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                .setBoundingBox(destinationBounds)
                .addProcessor(new DistrictPaletteProcessor(
                        patch.district(), patch.surfaceOffset()));
        BoundingBox transformedBounds = template.getBoundingBox(settings, anchor);
        if (!sameBounds(destinationBounds, transformedBounds)) {
            LOGGER.error("[NeonCity] transformed Arnis template {} escaped chunk {}: expected {}, got {}",
                    patch.templateId(), chunk, destinationBounds, transformedBounds);
            return false;
        }
        boolean placed = template.placeInWorld(
                level, anchor, anchor, settings,
                RandomSource.create(placement.selectionHash()), PLACE_FLAGS);
        if (!placed) {
            LOGGER.error("[NeonCity] Arnis template {} refused placement into {}",
                    patch.templateId(), chunk);
        }
        return placed;
    }

    private static boolean sameBounds(BoundingBox left, BoundingBox right) {
        return left.minX() == right.minX()
                && left.minY() == right.minY()
                && left.minZ() == right.minZ()
                && left.maxX() == right.maxX()
                && left.maxY() == right.maxY()
                && left.maxZ() == right.maxZ();
    }

    private static void buildColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                    int x, int z, UrbanSample sample,
                                    boolean facadeBoundary) {
        int naturalTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int ground = sample.groundY();
        int clearFrom = sample.roadClass() == RoadClass.BORDER_RIVER
                || sample.roadClass() == RoadClass.BRIDGE ? WATER_Y + 1 : ground + 1;
        for (int y = clearFrom; y <= Math.min(MAX_BUILD_Y, naturalTop + 2); y++) {
            set(level, pos, x, y, z, Blocks.AIR.defaultBlockState());
        }

        int foundationTop = sample.roadClass() == RoadClass.BORDER_RIVER
                || sample.roadClass() == RoadClass.CANAL
                || sample.roadClass() == RoadClass.HARBOR
                || sample.roadClass() == RoadClass.BRIDGE ? WATER_Y - 1 : ground;
        for (int y = Math.min(naturalTop, foundationTop - 5); y <= foundationTop; y++) {
            if (y < level.getMinY()) continue;
            BlockState state = y == foundationTop
                    ? surface(sample, x, z)
                    : foundation(sample);
            set(level, pos, x, y, z, state);
        }

        if (isWater(sample.roadClass()) || sample.roadClass() == RoadClass.BRIDGE) {
            for (int y = foundationTop + 1; y <= WATER_Y; y++) {
                set(level, pos, x, y, z, Blocks.WATER.defaultBlockState());
            }
        } else {
            set(level, pos, x, ground, z, surface(sample, x, z));
        }

        decorateInfrastructure(level, pos, x, z, sample);
        if (!sample.insideFootprint() || sample.buildingHeight() <= 0
                || sample.roadClass() != RoadClass.NONE) {
            decorateOpenGround(level, pos, x, z, sample);
            return;
        }
        buildBuilding(level, pos, x, z, sample, facadeBoundary);
    }

    private static boolean isWater(RoadClass road) {
        return road == RoadClass.BORDER_RIVER || road == RoadClass.CANAL || road == RoadClass.HARBOR;
    }

    private static BlockState foundation(UrbanSample sample) {
        return switch (sample.zone()) {
            case BORDER_HILLS -> Blocks.STONE.defaultBlockState();
            case BORDER_RIVER -> Blocks.CLAY.defaultBlockState();
            default -> Blocks.DEEPSLATE.defaultBlockState();
        };
    }

    private static BlockState surface(UrbanSample sample, int x, int z) {
        return switch (sample.roadClass()) {
            case CENTRAL_PLAZA -> floorMod(x, 9) == 0 || floorMod(z, 9) == 0
                    ? palette(sample.district()).accent()
                    : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case DISTRICT_BOULEVARD, INTERDISTRICT_ROAD -> laneMark(x, z)
                    ? concrete(DyeColor.YELLOW) : concrete(DyeColor.BLACK);
            case LOCAL_STREET -> laneMark(x * 3, z * 5)
                    ? concrete(DyeColor.LIGHT_GRAY) : concrete(DyeColor.GRAY);
            case SERVICE_ALLEY -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case BRIDGE, ELEVATED_RAIL -> Blocks.SMOOTH_STONE.defaultBlockState();
            case CANAL, HARBOR, BORDER_RIVER -> Blocks.CLAY.defaultBlockState();
            case PARK -> floorMod(x + z, 7) == 0
                    ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            case FARM -> floorMod(x + z, 11) == 0
                    ? Blocks.WATER.defaultBlockState() : Blocks.FARMLAND.defaultBlockState();
            case EXTRACTION_SITE -> Blocks.COARSE_DIRT.defaultBlockState();
            case BORDER_HILLS -> floorMod(x * 7 + z * 11, 5) == 0
                    ? Blocks.STONE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            case NONE -> sidewalk(sample.district());
            case WILDERNESS -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }

    private static boolean laneMark(int x, int z) {
        return floorMod(x + z, 17) <= 1 && floorMod(x - z, 11) == 0;
    }

    private static void decorateInfrastructure(ServerLevel level, BlockPos.MutableBlockPos pos,
                                               int x, int z, UrbanSample sample) {
        if (sample.roadClass() == RoadClass.BRIDGE) {
            boolean graphBridge = sample.location().onConnection();
            int deck = graphBridge ? CITY_GROUND_Y + 8 : CITY_GROUND_Y + 1;
            if (floorMod(x * 31 + z * 17, 37) == 0) {
                for (int y = WATER_Y; y < deck; y++) {
                    set(level, pos, x, y, z, Blocks.POLISHED_BASALT.defaultBlockState());
                }
            }
            set(level, pos, x, deck - 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            set(level, pos, x, deck, z, laneMark(x, z)
                    ? concrete(DyeColor.YELLOW) : concrete(DyeColor.BLACK));
            if (graphBridge && sample.location().connectionDistance() > 10.5) {
                set(level, pos, x, deck + 1, z, Blocks.IRON_BARS.defaultBlockState());
            }
        }
        if (sample.roadClass() == RoadClass.ELEVATED_RAIL) {
            int deck = CITY_GROUND_Y + 15;
            if (floorMod(x + 2 * z, 29) == 0) {
                for (int y = sample.groundY() + 1; y < deck; y++) {
                    set(level, pos, x, y, z, Blocks.IRON_BLOCK.defaultBlockState());
                }
            }
            set(level, pos, x, deck - 1, z, Blocks.SMOOTH_STONE.defaultBlockState());
            set(level, pos, x, deck, z, Blocks.SMOOTH_STONE.defaultBlockState());
            if (sample.location().connectionDistance() <= 2.2) {
                RailShape railShape = railShape(sample.location().nearestConnection(), x, z);
                BlockState rail = floorMod(x + z, 3) == 0
                        ? Blocks.POWERED_RAIL.defaultBlockState().setValue(
                                PoweredRailBlock.SHAPE, railShape)
                        : Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, railShape);
                set(level, pos, x, deck, z, rail);
            }
        }
    }

    private static RailShape railShape(MegacityLayout.Edge edge, int worldX, int worldZ) {
        if (edge == null) return RailShape.EAST_WEST;
        MegacityLayout.Node first = edge.first();
        MegacityLayout.Node second = edge.second();
        double dx = second.x() - first.x();
        double dz = second.z() - first.z();
        double length = Math.max(1.0, Math.hypot(dx, dz));
        double controlX = (first.x() + second.x()) * 0.5 - dz * edge.bend();
        double controlZ = (first.z() + second.z()) * 0.5 + dx * edge.bend();
        double bestDistance = Double.MAX_VALUE;
        double tangentX = dx;
        double tangentZ = dz;
        for (int step = 0; step <= 24; step++) {
            double t = step / 24.0;
            double inverse = 1.0 - t;
            double curveX = inverse * inverse * first.x()
                    + 2.0 * inverse * t * controlX + t * t * second.x();
            double curveZ = inverse * inverse * first.z()
                    + 2.0 * inverse * t * controlZ + t * t * second.z();
            double distance = Math.hypot(worldX - curveX, worldZ - curveZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                tangentX = 2.0 * inverse * (controlX - first.x())
                        + 2.0 * t * (second.x() - controlX);
                tangentZ = 2.0 * inverse * (controlZ - first.z())
                        + 2.0 * t * (second.z() - controlZ);
            }
        }
        return Math.abs(tangentX) >= Math.abs(tangentZ)
                ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    private static void decorateOpenGround(ServerLevel level, BlockPos.MutableBlockPos pos,
                                           int x, int z, UrbanSample sample) {
        int y = sample.groundY() + 1;
        long hash = mix(layout.seed() ^ 0x4445434F52415445L, x, z);
        if (sample.roadClass() == RoadClass.CENTRAL_PLAZA
                && floorMod(x * 13 + z * 19, 73) == 0) {
            for (int dy = 0; dy < 6; dy++) set(level, pos, x, y + dy, z, palette(sample.district()).accent());
        }
        if ((sample.roadClass() == RoadClass.PARK
                || sample.zone() == MegacityLayout.Zone.OUTSKIRTS)
                && unit(hash) < sample.district().vegetation() * 0.018) {
            TreePalette trees = treePalette(sample.district());
            int height = 4 + floorMod((int) hash, 3);
            for (int dy = 0; dy < height; dy++) set(level, pos, x, y + dy, z, trees.trunk());
            for (int dy = height; dy <= height + 2; dy++) set(level, pos, x, y + dy, z, trees.leaves());
        }
        if (sample.roadClass() == RoadClass.FARM && !surface(sample, x, z).is(Blocks.WATER)) {
            set(level, pos, x, y, z, Blocks.WHEAT.defaultBlockState());
        }
        if (sample.roadClass() == RoadClass.HARBOR && floorMod(x * 5 + z * 7, 31) <= 1) {
            int dock = WATER_Y + 1;
            set(level, pos, x, dock, z, Blocks.DARK_OAK_PLANKS.defaultBlockState());
            if (floorMod(x * 11 + z * 3, 43) == 0) {
                for (int dy = 1; dy <= 4; dy++) {
                    set(level, pos, x, dock + dy, z, concrete(DyeColor.values()[floorMod((int) hash, 16)]));
                }
            }
        }
        if (sample.roadClass() == RoadClass.EXTRACTION_SITE
                && floorMod(x * 17 + z * 23, 97) == 0) {
            for (int dy = 0; dy <= 9; dy++) set(level, pos, x, y + dy, z, Blocks.IRON_BARS.defaultBlockState());
            int armDirection = floorMod(x, 16) <= 10 ? 1 : -1;
            for (int arm = 1; arm <= 5; arm++) {
                set(level, pos, x + armDirection * arm, y + 9, z,
                        Blocks.IRON_BARS.defaultBlockState());
            }
            set(level, pos, x, y + 10, z, Blocks.IRON_BLOCK.defaultBlockState());
        }
        if (sample.district() == District.Y_CORP && sample.zone() != MegacityLayout.Zone.BORDER_RIVER
                && floorMod(x * 7 + z * 13, 5) == 0) {
            set(level, pos, x, y, z, Blocks.SNOW.defaultBlockState());
        }
        if (sample.roadClass() == RoadClass.SERVICE_ALLEY && floorMod((int) hash, 89) == 0) {
            set(level, pos, x, y, z, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        }
    }

    private static void buildBuilding(ServerLevel level, BlockPos.MutableBlockPos pos,
                                      int x, int z, UrbanSample sample,
                                      boolean facadeBoundary) {
        Palette palette = palette(sample.district());
        int base = sample.groundY();
        int height = Math.min(MAX_BUILD_Y - base, sample.buildingHeight());
        for (int relativeY = 1; relativeY <= height; relativeY++) {
            int tier = tierInset(sample, relativeY, height);
            if (!insideTier(sample, tier)) continue;
            boolean boundary = facadeBoundary || tierBoundary(sample, tier);
            boolean floor = relativeY == 1 || relativeY == height || floorMod(relativeY - 1, 5) == 0;
            if (!boundary && !floor) continue;

            BlockState state;
            if (relativeY == height) {
                state = palette.roof();
            } else if (!boundary) {
                state = palette.secondary();
            } else if (isNeonStrip(sample, relativeY)) {
                state = palette.accent();
            } else if (isWindow(sample, relativeY)) {
                state = palette.glass();
            } else if (isFrame(sample, relativeY)) {
                state = palette.frame();
            } else {
                state = palette.wall();
            }
            set(level, pos, x, base + relativeY, z, state);
        }

        decorateCulturalRoof(level, pos, x, z, sample, palette, base, height);
    }

    private static void decorateCulturalRoof(
            ServerLevel level,
            BlockPos.MutableBlockPos pos,
            int x,
            int z,
            UrbanSample sample,
            Palette palette,
            int base,
            int height) {
        int supportedTopInset = tierInset(sample, height, height);
        if (!insideTier(sample, supportedTopInset)) return;
        double center = sample.parcelSize() * 0.5;
        double du = Math.abs(sample.parcelLocalU() - center);
        double dv = Math.abs(sample.parcelLocalV() - center);
        double span = Math.max(4.0, center - footprintInset(sample));
        double square = Math.max(du, dv) / span;
        double radial = Math.hypot(du, dv) / (span * 1.34);
        long hash = sample.parcelHash() ^ ((long) x * 31L) ^ ((long) z * 17L);
        int rise = 0;
        BlockState material = palette.roof();
        boolean glowingTip = false;

        switch (sample.district().roofStyle()) {
            case BLACK_CROWN -> {
                rise = taperedRise(square, 12, 0.58);
                material = palette.wall();
                glowingTip = true;
            }
            case GREEN_TERRACE -> {
                if (square < 0.72 && floorMod((int) hash, 5) <= 2) {
                    set(level, pos, x, Math.min(MAX_BUILD_Y, base + height + 1), z,
                            Blocks.MOSS_BLOCK.defaultBlockState());
                }
            }
            case SAWTOOTH -> rise = floorMod(
                    (int) Math.floor(sample.parcelLocalU()), 10) < 5
                    ? Math.max(0, 5 - floorMod((int) Math.floor(sample.parcelLocalU()), 5)) : 0;
            case GLASS_CANOPY -> {
                rise = domeRise(radial, 6);
                material = palette.glass();
            }
            case COURTYARD_TILE -> rise = gableRise(du / span, 5);
            case DECO_FIN -> {
                rise = du < 1.6 ? taperedRise(dv / span, 8, 0.74) : 0;
                material = palette.accent();
            }
            case TROPICAL_GARDEN -> {
                if (square < 0.68 && floorMod((int) hash, 11) == 0) {
                    set(level, pos, x, Math.min(MAX_BUILD_Y, base + height + 1), z,
                            Blocks.JUNGLE_LEAVES.defaultBlockState()
                                    .setValue(LeavesBlock.PERSISTENT, true));
                }
            }
            case MECHANICAL_CLUSTER -> {
                if (floorMod((int) Math.floor(sample.parcelLocalU()), 7) <= 1
                        && floorMod((int) Math.floor(sample.parcelLocalV()), 7) <= 1) {
                    rise = 3 + floorMod((int) hash, 5);
                    material = palette.frame();
                }
            }
            case TERRACOTTA_DOME -> rise = domeRise(radial, 8);
            case CASINO_CROWN -> {
                rise = taperedRise(square, 10, 0.72);
                material = floorMod((int) hash, 3) == 0 ? palette.accent() : palette.frame();
                glowingTip = true;
            }
            case LAB_DOME -> {
                rise = domeRise(radial, 7);
                material = floorMod((int) hash, 4) == 0 ? palette.frame() : palette.glass();
            }
            case METRO_ANTENNA -> {
                rise = du < 0.8 && dv < 0.8 ? 11 : (square < 0.28 ? 2 : 0);
                material = palette.accent();
                glowingTip = true;
            }
            case STEPPED_SLAB -> {
                rise = square < 0.34 ? 5 : square < 0.62 ? 2 : 0;
                material = palette.secondary();
            }
            case MANSARD -> rise = square < 0.78 ? Math.max(1, (int) Math.round((0.78 - square) * 7.0)) : 0;
            case COPPER_DOME -> rise = domeRise(radial, 9);
            case ART_DECO_SPIRE -> {
                rise = taperedRise(square, 15, 0.68);
                material = square < 0.18 ? palette.accent() : palette.roof();
                glowingTip = true;
            }
            case FACTORY_VENTS -> {
                if (floorMod((int) Math.floor(sample.parcelLocalU()), 9) == 2
                        && floorMod((int) Math.floor(sample.parcelLocalV()), 11) == 3) {
                    rise = 5;
                    material = Blocks.IRON_BARS.defaultBlockState();
                }
            }
            case NEON_BILLBOARD -> {
                if (dv > span * 0.72 && du < span * 0.48) {
                    rise = 6;
                    material = floorMod((int) hash, 2) == 0 ? palette.accent() : palette.glass();
                }
            }
            case HANOK_GABLE -> {
                rise = gableRise(du / span, 7);
                if (dv > span * 0.82 && rise > 0) material = palette.frame();
            }
            case STEAM_STACKS -> {
                boolean stack = Math.abs(du - span * 0.56) < 1.1
                        && Math.abs(dv - span * 0.56) < 1.1;
                if (stack) {
                    rise = 8 + floorMod((int) sample.parcelHash(), 7);
                    material = floorMod((int) hash, 5) == 0
                            ? palette.accent() : Blocks.TUFF_BRICKS.defaultBlockState();
                }
            }
            case PORT_CRANE -> {
                if (du < 0.9 && dv < span * 0.66) {
                    rise = 7;
                    material = palette.frame();
                }
            }
            case ALPINE_GABLE -> rise = gableRise(dv / span, 8);
            case FUTURE_SPIRE -> {
                rise = taperedRise(radial, 17, 0.66);
                material = floorMod((int) hash, 4) == 0 ? palette.accent() : palette.glass();
                glowingTip = true;
            }
            case INDUSTRIAL_TANKS -> {
                double tank = Math.hypot(du - span * 0.32, dv - span * 0.32) / (span * 0.28);
                rise = domeRise(tank, 5);
                material = palette.frame();
            }
            case SNOW_CROWN -> {
                rise = taperedRise(square, 8, 0.72);
                material = Blocks.SNOW_BLOCK.defaultBlockState();
                glowingTip = true;
            }
            case ELECTRIC_SIGNS -> {
                if ((du > span * 0.76 || dv > span * 0.76)
                        && floorMod((int) hash, 4) <= 1) {
                    rise = 5 + floorMod((int) sample.parcelHash(), 4);
                    material = floorMod((int) hash, 2) == 0 ? palette.accent() : palette.glass();
                }
            }
        }

        int top = Math.min(rise, MAX_BUILD_Y - base - height);
        for (int dy = 1; dy <= top; dy++) {
            BlockState state = glowingTip && dy == top ? palette.accent() : material;
            set(level, pos, x, base + height + dy, z, state);
        }
    }

    private static int taperedRise(double normalized, int height, double reach) {
        if (normalized >= reach) return 0;
        return Math.max(1, (int) Math.ceil((1.0 - normalized / reach) * height));
    }

    private static int domeRise(double normalized, int height) {
        if (normalized >= 1.0) return 0;
        return Math.max(1, (int) Math.round(Math.sqrt(1.0 - normalized * normalized) * height));
    }

    private static int gableRise(double normalized, int height) {
        if (normalized >= 0.86) return 0;
        return Math.max(1, (int) Math.ceil((1.0 - normalized / 0.86) * height));
    }

    private static int tierInset(UrbanSample sample, int y, int top) {
        District.Architecture architecture = sample.district().architecture();
        return switch (architecture) {
            case CORPORATE, ART_DECO, SHENZHEN -> y > top * 4 / 5 ? 7 : y > top * 3 / 5 ? 4 : 0;
            case HYPER_DENSE, TOKYO_ELECTRIC, OSAKA_NEON -> y > top - 9 ? Math.min(4, top - y + 1) : 0;
            case TROPICAL_DECO, HAUSSMANN, VIENNESE, CLASSICAL -> y > top * 3 / 4 ? 2 : 0;
            case RESEARCH, KOREAN_METRO, METROPOLITAN -> y > top * 2 / 3 ? 4 : 0;
            case STEAMPUNK, HARBOR, HANOI_INDUSTRIAL -> Math.min(5, y / 38);
            case JOSEON -> y > top * 3 / 5 ? 3 : 0;
            default -> y > top * 4 / 5 ? 3 : 0;
        };
    }

    private static boolean insideTier(UrbanSample sample, int extraInset) {
        double min = footprintInset(sample) + extraInset;
        double max = sample.parcelSize() - min;
        double u = sample.parcelLocalU();
        double v = sample.parcelLocalV();
        if (u < min || u >= max || v < min || v >= max) return false;
        int shape = floorMod((int) (sample.parcelHash() >>> 24), 7);
        return switch (shape) {
            case 1 -> !(u > sample.parcelSize() * 0.58 && v > sample.parcelSize() * 0.58);
            case 2 -> !(u < sample.parcelSize() * 0.38 && v > sample.parcelSize() * 0.62);
            case 3 -> sample.district().architecture() == District.Architecture.COURTYARD
                    ? u < sample.parcelSize() * 0.32 || u > sample.parcelSize() * 0.68
                        || v < sample.parcelSize() * 0.32 || v > sample.parcelSize() * 0.68
                    : true;
            default -> true;
        };
    }

    private static boolean tierBoundary(UrbanSample sample, int extraInset) {
        if (!insideTier(sample, extraInset)) return false;
        double min = footprintInset(sample) + extraInset;
        double max = sample.parcelSize() - min;
        double u = sample.parcelLocalU();
        double v = sample.parcelLocalV();
        if (u < min + 1.35 || u >= max - 1.35 || v < min + 1.35 || v >= max - 1.35) return true;
        int shape = floorMod((int) (sample.parcelHash() >>> 24), 7);
        if (shape == 1) {
            double cut = sample.parcelSize() * 0.58;
            return (Math.abs(u - cut) < 1.25 && v >= cut)
                    || (Math.abs(v - cut) < 1.25 && u >= cut);
        }
        return false;
    }

    private static boolean isWindow(UrbanSample sample, int y) {
        if (floorMod(y, 5) < 2) return false;
        int rhythm = switch (sample.district().architecture()) {
            case HYPER_DENSE, TOKYO_ELECTRIC, OSAKA_NEON -> 4;
            case RESEARCH, KOREAN_METRO, SHENZHEN -> 7;
            case CLASSICAL, HAUSSMANN, VIENNESE -> 6;
            default -> 5;
        };
        return floorMod((int) Math.floor(sample.parcelLocalU())
                + (int) Math.floor(sample.parcelLocalV()), rhythm) != 0;
    }

    private static boolean isFrame(UrbanSample sample, int y) {
        return floorMod(y, 10) == 0
                || floorMod((int) Math.floor(sample.parcelLocalU()), 9) == 0
                || floorMod((int) Math.floor(sample.parcelLocalV()), 9) == 0;
    }

    private static boolean isNeonStrip(UrbanSample sample, int y) {
        int spacing = switch (sample.district().architecture()) {
            case CASINO, OSAKA_NEON, TOKYO_ELECTRIC -> 11;
            case HYPER_DENSE, TROPICAL_DENSE -> 15;
            default -> 23;
        };
        return floorMod(y + (int) sample.parcelHash(), spacing) == 0
                && (floorMod((int) Math.floor(sample.parcelLocalU()), 5) == 0
                || floorMod((int) Math.floor(sample.parcelLocalV()), 5) == 0);
    }

    private static Palette palette(District district) {
        return switch (district) {
            case A_CORP -> p(Blocks.POLISHED_BLACKSTONE_BRICKS, concrete(DyeColor.BLACK), Blocks.TINTED_GLASS,
                    Blocks.SEA_LANTERN, Blocks.IRON_BLOCK, concrete(DyeColor.BLACK));
            case B_CORP -> p(Blocks.SMOOTH_STONE, Blocks.QUARTZ_BLOCK, stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN, concrete(DyeColor.LIME), Blocks.MOSS_BLOCK);
            case C_CORP -> p(Blocks.BRICKS, Blocks.DARK_OAK_PLANKS, stainedGlass(DyeColor.BROWN),
                    Blocks.OCHRE_FROGLIGHT, Blocks.COPPER_BLOCK, Blocks.DEEPSLATE_TILES);
            case D_CORP -> p(concrete(DyeColor.LIGHT_GRAY), Blocks.SMOOTH_QUARTZ, stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN, Blocks.SPRUCE_LOG, concrete(DyeColor.GRAY));
            case E_CORP -> p(dyedTerracotta(DyeColor.ORANGE), Blocks.SANDSTONE, stainedGlass(DyeColor.CYAN),
                    Blocks.SHROOMLIGHT, dyedTerracotta(DyeColor.RED), Blocks.TERRACOTTA);
            case F_CORP -> p(concrete(DyeColor.PINK), concrete(DyeColor.CYAN), stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN, concrete(DyeColor.WHITE), concrete(DyeColor.MAGENTA));
            case G_CORP -> p(Blocks.MUD_BRICKS, concrete(DyeColor.GRAY), stainedGlass(DyeColor.LIME),
                    Blocks.OCHRE_FROGLIGHT, cutCopper(WeatheringCopper.WeatherState.EXPOSED), Blocks.DEEPSLATE_TILES);
            case H_CORP -> p(Blocks.DEEPSLATE_TILES, concrete(DyeColor.GRAY), stainedGlass(DyeColor.CYAN),
                    Blocks.SEA_LANTERN, concrete(DyeColor.RED), Blocks.POLISHED_BLACKSTONE);
            case I_CORP -> p(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, stainedGlass(DyeColor.YELLOW),
                    Blocks.GLOWSTONE, Blocks.QUARTZ_PILLAR, Blocks.TERRACOTTA);
            case J_CORP -> p(concrete(DyeColor.WHITE), concrete(DyeColor.RED), stainedGlass(DyeColor.PURPLE),
                    Blocks.PEARLESCENT_FROGLIGHT, Blocks.GOLD_BLOCK, concrete(DyeColor.BLACK));
            case K_CORP -> p(concrete(DyeColor.WHITE), Blocks.SMOOTH_QUARTZ, stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN, concrete(DyeColor.LIGHT_GRAY), Blocks.IRON_BLOCK);
            case L_CORP -> p(concrete(DyeColor.LIGHT_GRAY), Blocks.QUARTZ_BLOCK, stainedGlass(DyeColor.BLUE),
                    Blocks.SEA_LANTERN, concrete(DyeColor.PURPLE), concrete(DyeColor.WHITE));
            case M_CORP -> p(Blocks.STONE_BRICKS, concrete(DyeColor.GRAY), stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN, concrete(DyeColor.RED), Blocks.SMOOTH_STONE);
            case N_CORP -> p(Blocks.CALCITE, Blocks.SMOOTH_SANDSTONE, stainedGlass(DyeColor.GRAY),
                    Blocks.GLOWSTONE, Blocks.QUARTZ_PILLAR, Blocks.DARK_OAK_PLANKS);
            case O_CORP -> p(Blocks.QUARTZ_BRICKS, Blocks.CALCITE, stainedGlass(DyeColor.YELLOW),
                    Blocks.GLOWSTONE, Blocks.CHISELED_STONE_BRICKS, cutCopper(WeatheringCopper.WeatherState.EXPOSED));
            case P_CORP -> p(Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN, Blocks.GOLD_BLOCK, cutCopper(WeatheringCopper.WeatherState.WEATHERED));
            case Q_CORP -> p(Blocks.STONE_BRICKS, concrete(DyeColor.WHITE), stainedGlass(DyeColor.BLUE),
                    Blocks.SEA_LANTERN, concrete(DyeColor.ORANGE), Blocks.SMOOTH_STONE);
            case R_CORP -> p(Blocks.DEEPSLATE_TILES, concrete(DyeColor.RED), stainedGlass(DyeColor.CYAN),
                    Blocks.OCHRE_FROGLIGHT, concrete(DyeColor.YELLOW), Blocks.POLISHED_BLACKSTONE);
            case S_CORP -> p(dyedTerracotta(DyeColor.WHITE), Blocks.DARK_OAK_PLANKS, stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SHROOMLIGHT, Blocks.STRIPPED_DARK_OAK_LOG, Blocks.DEEPSLATE_TILES);
            case T_CORP -> p(Blocks.TUFF_BRICKS, Blocks.BRICKS, stainedGlass(DyeColor.ORANGE),
                    Blocks.OCHRE_FROGLIGHT, cutCopper(WeatheringCopper.WeatherState.WEATHERED), Blocks.IRON_BLOCK);
            case U_CORP -> p(concrete(DyeColor.GRAY), Blocks.IRON_BLOCK, stainedGlass(DyeColor.CYAN),
                    Blocks.SEA_LANTERN, concrete(DyeColor.ORANGE), Blocks.SMOOTH_STONE);
            case V_CORP -> p(Blocks.CALCITE, Blocks.SPRUCE_PLANKS, stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.GLOWSTONE, Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS);
            case W_CORP -> p(concrete(DyeColor.LIGHT_GRAY), Blocks.IRON_BLOCK, stainedGlass(DyeColor.CYAN),
                    Blocks.SEA_LANTERN, concrete(DyeColor.LIME), concrete(DyeColor.WHITE));
            case X_CORP -> p(Blocks.BRICKS, dyedTerracotta(DyeColor.YELLOW), stainedGlass(DyeColor.GREEN),
                    Blocks.OCHRE_FROGLIGHT, Blocks.IRON_BLOCK, Blocks.DEEPSLATE_TILES);
            case Y_CORP -> p(Blocks.PACKED_ICE, concrete(DyeColor.WHITE), stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN, concrete(DyeColor.RED), Blocks.SNOW_BLOCK);
            case Z_CORP -> p(Blocks.POLISHED_BLACKSTONE_BRICKS, concrete(DyeColor.GRAY), stainedGlass(DyeColor.MAGENTA),
                    Blocks.PEARLESCENT_FROGLIGHT, concrete(DyeColor.CYAN), concrete(DyeColor.BLACK));
        };
    }

    private static Palette p(Object wall, Object secondary, Object glass,
                             Object accent, Object frame, Object roof) {
        return new Palette(asState(wall), asState(secondary), asState(glass),
                asState(accent), asState(frame), asState(roof));
    }

    private static TreePalette treePalette(District district) {
        return switch (district.treeStyle()) {
            case FORMAL -> new TreePalette(
                    Blocks.DARK_OAK_LOG.defaultBlockState(), Blocks.AZALEA_LEAVES.defaultBlockState());
            case BROADLEAF -> new TreePalette(
                    Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());
            case EVERGREEN, ALPINE, WINTER -> new TreePalette(
                    Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_LEAVES.defaultBlockState());
            case ARID, MEDITERRANEAN -> new TreePalette(
                    Blocks.ACACIA_LOG.defaultBlockState(), Blocks.ACACIA_LEAVES.defaultBlockState());
            case TROPICAL -> new TreePalette(
                    Blocks.JUNGLE_LOG.defaultBlockState(), Blocks.JUNGLE_LEAVES.defaultBlockState());
            case CHERRY -> new TreePalette(
                    Blocks.CHERRY_LOG.defaultBlockState(), Blocks.CHERRY_LEAVES.defaultBlockState());
            case INDUSTRIAL -> new TreePalette(
                    Blocks.DARK_OAK_LOG.defaultBlockState(), Blocks.DARK_OAK_LEAVES.defaultBlockState());
        };
    }

    private static BlockState asState(Object value) {
        if (value instanceof Block block) return block.defaultBlockState();
        if (value instanceof BlockState state) return state;
        throw new IllegalArgumentException("palette value is not a block: " + value);
    }

    private static BlockState sidewalk(District district) {
        return switch (district.architecture()) {
            case HYPER_DENSE, OSAKA_NEON, TOKYO_ELECTRIC, CORPORATE, ART_DECO ->
                    Blocks.POLISHED_BLACKSTONE.defaultBlockState();
            case CLASSICAL, HAUSSMANN, VIENNESE, RESEARCH -> Blocks.SMOOTH_STONE.defaultBlockState();
            case JOSEON, ALPINE_CANAL, EVERGREEN, CAMPUS -> Blocks.STONE_BRICKS.defaultBlockState();
            case STEAMPUNK, HARBOR, HANOI_INDUSTRIAL -> Blocks.TUFF_BRICKS.defaultBlockState();
            default -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        };
    }

    private static void set(ServerLevel level, BlockPos.MutableBlockPos pos,
                            int x, int y, int z, BlockState state) {
        level.setBlock(pos.set(x, y, z), state, PLACE_FLAGS);
    }

    public static UrbanSample sample(int worldX, int worldZ) {
        // Commands and topology tests often sample thousands of nearby points.
        // Reuse sector plans while keeping this diagnostic cache bounded.
        if (DIAGNOSTIC_ALLEY_PLANS.size() > 512) DIAGNOSTIC_ALLEY_PLANS.clear();
        return sample(worldX, worldZ, DIAGNOSTIC_ALLEY_PLANS);
    }

    private static UrbanSample sample(int worldX, int worldZ, Map<Long, AlleyMaze.Plan> alleyPlans) {
        MegacityLayout.Location location = layout.locate(worldX, worldZ);
        District district = location.district();
        MegacityLayout.Zone zone = location.zone();
        if (zone == MegacityLayout.Zone.WILDERNESS) {
            return new UrbanSample(location, district, zone, RoadClass.WILDERNESS,
                    CITY_GROUND_Y, 0, district.parcelSize(), false,
                    0, 0, 0.0, 0.0, 0L);
        }

        LocalCoordinates local = parcelCoordinates(location.primary(), worldX, worldZ);
        int parcelSize = district.parcelSize();
        int parcelX = floorDiv(local.u(), parcelSize);
        int parcelZ = floorDiv(local.v(), parcelSize);
        double localU = floorMod(local.u(), parcelSize);
        double localV = floorMod(local.v(), parcelSize);
        long parcelHash = mix(layout.seed() ^ location.primary().identity(), parcelX, parcelZ);
        RoadClass road = roadClass(location, local, worldX, worldZ, parcelHash);
        int groundY = terrainHeight(location, worldX, worldZ, road);
        int height = buildingHeight(district, zone, location.normalizedDistance(), parcelHash);
        UrbanSample provisional = new UrbanSample(location, district, zone, road, groundY,
                height, parcelSize, false, parcelX, parcelZ, localU, localV, parcelHash);

        if (road == RoadClass.NONE && zone == MegacityLayout.Zone.BACKSTREETS
                && alleyAt(worldX, worldZ, alleyPlans)) {
            road = RoadClass.SERVICE_ALLEY;
        }
        double density = district.density() * switch (zone) {
            case NEST -> 1.0;
            case BACKSTREETS -> 0.88;
            case OUTSKIRTS -> 0.46;
            default -> 0.0;
        };
        boolean selected = unit(Long.rotateRight(parcelHash, 7)) < density;
        boolean footprint = road == RoadClass.NONE && selected && insideTier(provisional, 0);
        return new UrbanSample(location, district, zone, road, groundY,
                footprint ? height : 0, parcelSize, footprint,
                parcelX, parcelZ, localU, localV, parcelHash);
    }

    private static RoadClass roadClass(MegacityLayout.Location location, LocalCoordinates local,
                                       int worldX, int worldZ, long hash) {
        MegacityLayout.Node node = location.primary();
        double dx = worldX - node.x();
        double dz = worldZ - node.z();
        double radius = Math.hypot(dx, dz);
        double angle = Math.atan2(dz, dx);
        // Preserve a civic heart even when several graph edges meet at the
        // district node; connectors join the outer edge of this plaza.
        if (radius < 34.0) return RoadClass.CENTRAL_PLAZA;
        if (location.onConnection()) {
            if (location.nearestConnection().kind() == MegacityLayout.ConnectionKind.ELEVATED_RAIL) {
                return RoadClass.ELEVATED_RAIL;
            }
            if (location.normalizedDistance() > 0.96 || location.boundaryGap() < 0.085) {
                return RoadClass.BRIDGE;
            }
            return RoadClass.INTERDISTRICT_ROAD;
        }
        if (location.zone() == MegacityLayout.Zone.BORDER_RIVER) return RoadClass.BORDER_RIVER;
        if (location.zone() == MegacityLayout.Zone.BORDER_HILLS) return RoadClass.BORDER_HILLS;
        if (ArnisPatchLibrary.connectorApproachAt(layout, worldX, worldZ)) {
            return RoadClass.LOCAL_STREET;
        }

        double ring = location.normalizedDistance();
        if (Math.abs(ring - 0.34) < 0.018 || Math.abs(ring - 0.69) < 0.015) {
            return RoadClass.DISTRICT_BOULEVARD;
        }
        int spokes = 4 + floorMod((int) node.identity(), 4);
        double curvedAngle = normalizeAngle(angle + 0.16 * Math.sin(radius / 113.0 + node.identity() * 0.00001));
        double spokeAngle = Math.PI * 2.0 / spokes;
        double spokeDistance = angularDistance(curvedAngle,
                Math.rint(curvedAngle / spokeAngle) * spokeAngle) * Math.max(48.0, radius);
        if (spokeDistance < 6.5) return RoadClass.DISTRICT_BOULEVARD;

        District district = node.district();
        if (district == District.V_CORP) {
            double canalA = local.v() - 0.22 * local.u() - 72.0 * Math.sin(local.u() / 131.0);
            double canalB = local.u() + 0.18 * local.v() - 64.0 * Math.sin(local.v() / 117.0);
            double canalDistance = Math.min(
                    Math.abs(foldLine(canalA, 210.0)),
                    Math.abs(foldLine(canalB, 248.0)));
            boolean crossing = distanceToGrid(local.u() + local.v(), 112) < 4.0;
            if (canalDistance < 5.0) {
                if (crossing) return RoadClass.BRIDGE;
                return RoadClass.CANAL;
            }
            if (crossing && canalDistance < 24.0) return RoadClass.LOCAL_STREET;
        }
        if (district == District.U_CORP && location.normalizedDistance() > 0.52) {
            double quay = local.v() - 55.0 * Math.sin(local.u() / 137.0);
            if (Math.abs(foldLine(quay, 180.0)) < 18.0) return RoadClass.HARBOR;
        }
        if (district == District.S_CORP && location.zone() != MegacityLayout.Zone.NEST
                && floorMod((int) (hash >>> 11), 5) <= 3) return RoadClass.FARM;
        if (district == District.X_CORP && location.normalizedDistance() > 0.68
                && floorMod((int) hash, 4) <= 2) return RoadClass.EXTRACTION_SITE;

        RoadClass culturalRoad = culturalRoad(district, local, location, hash);
        if (culturalRoad != null) return culturalRoad;

        int parcel = district.parcelSize();
        double lineU = distanceToGrid(local.u(), parcel);
        double lineV = distanceToGrid(local.v(), parcel);
        double width = district == District.H_CORP || district == District.Z_CORP ? 2.8
                : district == District.U_CORP ? 5.5 : 3.8;
        if (lineU < width || lineV < width) return RoadClass.LOCAL_STREET;

        long patch = mix(location.primary().identity() ^ 0x5041524B53414C54L,
                worldX >> 6, worldZ >> 6);
        double parkChance = district.vegetation() * (location.zone() == MegacityLayout.Zone.OUTSKIRTS ? 0.42 : 0.16);
        if (unit(patch) < parkChance) return RoadClass.PARK;
        return RoadClass.NONE;
    }

    /** A second, culture-specific circulation layer below the shared civic rings. */
    private static RoadClass culturalRoad(
            District district,
            LocalCoordinates local,
            MegacityLayout.Location location,
            long hash) {
        double u = local.u();
        double v = local.v();
        double radius = Math.hypot(u, v);
        double angle = Math.atan2(v, u);
        return switch (district.streetPattern()) {
            case CEREMONIAL_AXES -> Math.abs(foldLine(v + 0.12 * u, 320.0)) < 4.2
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case CAMPUS_LOOPS -> {
                double loop = Math.hypot(foldLine(u, 238.0), foldLine(v, 212.0));
                if (Math.abs(loop - 70.0) < 3.4) yield RoadClass.LOCAL_STREET;
                yield loop < 26.0 && unit(Long.rotateRight(hash, 19)) < 0.72
                        ? RoadClass.PARK : null;
            }
            case PORTLAND_GREENWAYS -> Math.abs(foldLine(
                    v + 21.0 * Math.sin(u / 67.0), 186.0)) < 3.2 ? RoadClass.PARK : null;
            case EVERGREEN_ARCS -> Math.abs(foldLine(
                    radius + 18.0 * Math.sin(angle * 5.0), 176.0)) < 3.0
                    ? RoadClass.PARK : null;
            case COURTYARD_LANES -> Math.abs(foldLine(u + v * 0.34, 118.0)) < 2.4
                    ? RoadClass.SERVICE_ALLEY : null;
            case COASTAL_SWEEPS -> Math.abs(foldLine(
                    v - 42.0 * Math.sin(u / 145.0), 224.0)) < 4.4
                    ? RoadClass.LOCAL_STREET : null;
            case TROPICAL_WEAVE -> Math.abs(foldLine(
                    v + 27.0 * Math.sin(u / 91.0), 154.0)) < 3.0
                    || Math.abs(foldLine(u - 19.0 * Math.sin(v / 83.0), 198.0)) < 2.7
                    ? RoadClass.LOCAL_STREET : null;
            case VERTICAL_ALLEYS -> distanceToGrid(u + 9.0 * Math.sin(v / 43.0), 46) < 2.0
                    ? RoadClass.SERVICE_ALLEY : null;
            case CLASSICAL_RADIALS -> angularDistance(angle, Math.rint(angle * 4.0 / Math.PI)
                    * Math.PI / 4.0) * Math.max(radius, 44.0) < 3.8
                    ? RoadClass.LOCAL_STREET : null;
            case SPECTACLE_STRIP -> Math.abs(foldLine(v, 390.0)) < 7.5
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case RESEARCH_CAMPUS -> {
                double loop = Math.hypot(foldLine(u, 272.0), foldLine(v, 244.0));
                if (Math.abs(loop - 82.0) < 4.0) yield RoadClass.LOCAL_STREET;
                yield loop < 31.0 && unit(Long.rotateRight(hash, 9)) < 0.55
                        ? RoadClass.PARK : null;
            }
            case SEOUL_SUPERBLOCKS -> distanceToGrid(u + v * 0.43, 184) < 4.2
                    ? RoadClass.LOCAL_STREET : null;
            case TORONTO_CONCESSIONS -> distanceToGrid(u, 196) < 4.5
                    || distanceToGrid(v + 0.18 * u, 238) < 3.5 ? RoadClass.LOCAL_STREET : null;
            case PARIS_BOULEVARDS -> angularDistance(angle, Math.rint(angle * 6.0 / Math.PI)
                    * Math.PI / 6.0) * Math.max(radius, 56.0) < 5.0
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case VIENNA_RINGS -> Math.abs(foldLine(radius, 214.0)) < 4.4
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case MANHATTAN_AVENUES -> distanceToGrid(u, 112) < 5.2
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case NAGOYA_SPINES -> distanceToGrid(v + 0.08 * u, 152) < 4.8
                    ? RoadClass.LOCAL_STREET : null;
            case OSAKA_MERCHANT_LANES -> distanceToGrid(
                    u + 8.0 * Math.sin(v / 41.0), 68) < 2.2
                    ? RoadClass.SERVICE_ALLEY : null;
            case JOSEON_FIELD_ROADS -> location.zone() == MegacityLayout.Zone.NEST
                    && distanceToGrid(u + v * 0.18, 164) < 3.2
                    ? RoadClass.LOCAL_STREET : null;
            case STEAMWORKS_YARDS -> distanceToGrid(u, 132) < 5.8
                    && floorMod((int) (hash >>> 7), 3) != 0 ? RoadClass.LOCAL_STREET : null;
            case PORT_QUAYS -> distanceToGrid(u + 0.14 * v, 176) < 5.5
                    ? RoadClass.LOCAL_STREET : null;
            case ALPINE_CANALS -> distanceToGrid(u - v * 0.22, 168) < 3.2
                    ? RoadClass.LOCAL_STREET : null;
            case SHENZHEN_AXES -> Math.abs(foldLine(u + v * 0.52, 246.0)) < 5.0
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case HANOI_INDUSTRIAL -> Math.abs(foldLine(
                    v + 14.0 * Math.sin(u / 73.0), 126.0)) < 4.0
                    ? RoadClass.LOCAL_STREET : null;
            case WINTER_PROSPEKTS -> Math.abs(foldLine(v + u * 0.24, 286.0)) < 6.0
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case TOKYO_CROSSINGS -> distanceToGrid(u + v, 92) < 3.0
                    || distanceToGrid(u - v, 116) < 2.6 ? RoadClass.LOCAL_STREET : null;
        };
    }

    private static double foldLine(double value, double period) {
        double mod = floorMod(value + period * 0.5, (int) period);
        return mod - period * 0.5;
    }

    private static int terrainHeight(MegacityLayout.Location location, int x, int z, RoadClass road) {
        if (road == RoadClass.BORDER_RIVER || road == RoadClass.CANAL || road == RoadClass.HARBOR) {
            return WATER_Y - 1;
        }
        if (road == RoadClass.BORDER_HILLS) {
            return CITY_GROUND_Y + 6
                    + (int) Math.round(7.0 * (0.5 + 0.5 * Math.sin(x / 41.0) * Math.cos(z / 47.0)));
        }
        return CITY_GROUND_Y;
    }

    private static LocalCoordinates parcelCoordinates(MegacityLayout.Node node, int x, int z) {
        District district = node.district();
        double radians = Math.toRadians(district.orientationDegrees()) + node.rotation();
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double dx = x - node.x();
        double dz = z - node.z();
        double u = dx * cosine + dz * sine;
        double v = -dx * sine + dz * cosine;
        double warpedU = u + 16.0 * Math.sin(v / 79.0) + 7.0 * Math.sin((u + v) / 151.0);
        double warpedV = v + 13.0 * Math.sin(u / 91.0) + 6.0 * Math.sin((u - v) / 133.0);
        return new LocalCoordinates(warpedU, warpedV);
    }

    private static boolean alleyAt(int worldX, int worldZ, Map<Long, AlleyMaze.Plan> cache) {
        int sectorX = Math.floorDiv(worldX, AlleyMaze.SIZE);
        int sectorZ = Math.floorDiv(worldZ, AlleyMaze.SIZE);
        long key = ChunkPos.pack(sectorX, sectorZ);
        AlleyMaze.Plan plan = cache.computeIfAbsent(
                key, ignored -> AlleyMaze.generate(layout.seed(), sectorX, sectorZ));
        return plan.isAlley(Math.floorMod(worldX, AlleyMaze.SIZE), Math.floorMod(worldZ, AlleyMaze.SIZE));
    }

    private static int buildingHeight(District district, MegacityLayout.Zone zone,
                                      double normalizedDistance, long hash) {
        int range = district.maxHeight() - district.minHeight() + 1;
        int base = district.minHeight() + floorMod((int) hash, range);
        double taper = switch (zone) {
            case NEST -> 1.0;
            case BACKSTREETS -> 0.66;
            case OUTSKIRTS -> 0.30;
            default -> 0.0;
        };
        if (district == District.S_CORP && zone != MegacityLayout.Zone.NEST) taper *= 0.42;
        if (district == District.A_CORP || district == District.P_CORP || district == District.W_CORP) {
            taper *= 1.0 + Math.max(0.0, 0.42 - normalizedDistance) * 0.5;
        }
        return Math.max(8, Math.min(MAX_BUILD_Y - CITY_GROUND_Y, (int) Math.round(base * taper)));
    }

    private static int footprintInset(UrbanSample sample) {
        int base = switch (sample.district().architecture()) {
            case HYPER_DENSE, TOKYO_ELECTRIC, OSAKA_NEON -> 2;
            case HARBOR, CAMPUS, RESEARCH -> 6;
            default -> 4;
        };
        if (sample.zone() == MegacityLayout.Zone.OUTSKIRTS) base += 2;
        return base + floorMod((int) (sample.parcelHash() >>> 12), 2);
    }

    private static boolean sameBuilding(UrbanSample first, UrbanSample second) {
        return first.insideFootprint() && second.insideFootprint()
                && first.location().primary().identity() == second.location().primary().identity()
                && first.parcelX() == second.parcelX() && first.parcelZ() == second.parcelZ();
    }

    private static double distanceToGrid(double value, int period) {
        double mod = floorMod(value, period);
        return Math.min(mod, period - mod);
    }

    private static double angularDistance(double left, double right) {
        double delta = Math.abs(normalizeAngle(left) - normalizeAngle(right));
        return Math.min(delta, Math.PI * 2.0 - delta);
    }

    private static double normalizeAngle(double angle) {
        double value = angle % (Math.PI * 2.0);
        return value < 0 ? value + Math.PI * 2.0 : value;
    }

    private static int floorDiv(double value, int divisor) {
        return (int) Math.floor(value / divisor);
    }

    private static double floorMod(double value, int divisor) {
        return value - Math.floor(value / divisor) * divisor;
    }

    private static int floorMod(int value, int divisor) {
        return Math.floorMod(value, divisor);
    }

    private static BlockState concrete(DyeColor color) {
        return Blocks.CONCRETE.pick(color).defaultBlockState();
    }

    private static BlockState stainedGlass(DyeColor color) {
        return Blocks.STAINED_GLASS.pick(color).defaultBlockState();
    }

    private static BlockState dyedTerracotta(DyeColor color) {
        return Blocks.DYED_TERRACOTTA.pick(color).defaultBlockState();
    }

    private static BlockState cutCopper(WeatheringCopper.WeatherState state) {
        return Blocks.CUT_COPPER.waxed().pick(state).defaultBlockState();
    }

    private static long mix(long seed, int x, int z) {
        return MegacityLayout.mix(seed, x, z);
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    public static MegacityLayout layout() { return layout; }
    public static District districtAt(int worldX, int worldZ) { return layout.locate(worldX, worldZ).district(); }
    public static RoadClass roadAt(int worldX, int worldZ) { return sample(worldX, worldZ).roadClass(); }
    public static boolean isInsideCity(int worldX, int worldZ) { return layout.locate(worldX, worldZ).insideCity(); }
    public static boolean isInsideCity(ServerLevel level, int worldX, int worldZ) {
        if (!isMegacityWorld(level)) return false;
        MegacityLayout activeLayout = layoutWorldSeed == level.getSeed()
                ? layout : MegacityLayout.create(level.getSeed());
        return activeLayout.locate(worldX, worldZ).insideCity();
    }
    public static boolean isEnabled() { return enabled; }
    public static int pendingChunks() { return PENDING.size(); }
    public static int generatedChunks() { return GENERATED.size(); }
}
