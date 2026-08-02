package dev.modernity.neoncity;

import com.example.cyberdeck.city.CityLootGeneration;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Finite, world-seeded Project Moon-inspired megacity construction.
 *
 * <p>A {@link MegacityLayout} supplies the irregular district blobs and
 * a connected travel graph. This class turns that pure plan into terrain,
 * infrastructure, imported Arnis architecture, and untouched wilderness. All
 * sampling is in global coordinates, so chunk generation order cannot change
 * a road, bridge, open space, or district border.</p>
 */
public final class NeonCityGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAMESPACE = "neoncity";
    public static final String GENERATOR_FINGERPRINT =
            "project-moon-megacity-v20-fixed-perimeter-20260801";
    public static final int CITY_GROUND_Y = 72;
    public static final int WATER_Y = 67;
    public static final int ENQUEUE_RADIUS_CHUNKS = 7;
    public static final int SPAWN_PREWARM_RADIUS_CHUNKS = 1;
    public static final int MAX_PENDING_CHUNKS = 768;

    private static final int PLACE_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final int MAX_ARNIS_PLAN_CACHE = 32_768;
    private static final int MAX_PARK_SITE_CACHE = 32_768;
    private static final int MIN_ARNIS_COLUMNS = 32;
    static final int MIN_PARK_SITE_COLUMNS = 128;
    private static final int MIN_PARK_SITE_AXIS = 10;
    static final double HIGHWAY_HALF_WIDTH = MegacityLayout.CONNECTION_HALF_WIDTH;
    static final double HIGHWAY_ARNIS_SETBACK = 10.0;
    static final double HIGHWAY_CLEARANCE_RADIUS = MegacityLayout.CONNECTION_CLEARANCE_RADIUS;
    static final int BRIDGE_RISE = 8;
    static final int BRIDGE_GRADE_STEP = 6;
    private static final int ELEVATED_DECK_Y = CITY_GROUND_Y + 15;
    private static final double ELEVATED_HALF_WIDTH = 4.5;
    private static final double ELEVATED_TRACK_HALF_WIDTH = 2.2;
    private static final double ELEVATED_JUNCTION_RADIUS = 18.0;
    private static final double ELEVATED_SUPPORT_OFFSET = HIGHWAY_HALF_WIDTH + 2.0;
    private static final double ELEVATED_SUPPORT_HALF_WIDTH = 0.75;
    private static final int ELEVATED_SUPPORT_SPACING = 29;
    private static final long DECORATION_SALT = 0x4445434F52415445L;
    static final int MAX_BUILD_Y = 318;
    private static final BlockPos DEFAULT_SPAWN = new BlockPos(0, CITY_GROUND_Y + 2, 0);
    private static final ResourceKey<DimensionType> MEGACITY_DIMENSION_TYPE = ResourceKey.create(
            Registries.DIMENSION_TYPE,
            Identifier.fromNamespaceAndPath(NAMESPACE, "megacity_overworld"));

    private static final ArrayDeque<ChunkPos> PENDING = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static final Set<Long> GENERATED = new HashSet<>();
    private static final Map<Long, AlleyMaze.Plan> DIAGNOSTIC_ALLEY_PLANS = new HashMap<>();
    private static final Map<Long, Optional<ArnisPatchLibrary.Placement>>
            USABLE_ARNIS_PLACEMENTS = new HashMap<>();
    private static final Map<Long, BridgeProfile> BRIDGE_PROFILES = new ConcurrentHashMap<>();
    private static final Map<ParkSiteKey, ParkSitePlan> PARK_SITE_PLANS =
            new ConcurrentHashMap<>();
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
        HIGHWAY_BUFFER,
        CANAL,
        PARK,
        HARBOR,
        CONTAINER_PORT,
        OCEAN,
        PORTSHIP,
        FARM,
        EXTRACTION_SITE,
        TUNDRA_OUTSKIRTS,
        LAND_OUTSKIRTS,
        BORDER_WALLED,
        BORDER_FOREST,
        BORDER_CLIFF,
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

    private record BridgeProfile(int[] rise) {
        int riseAt(double progress) {
            int index = Math.max(0, Math.min(rise.length - 1,
                    (int) Math.round(progress * (rise.length - 1))));
            return rise[index];
        }
    }

    private record ParkSiteKey(long seed, int chunkX, int chunkZ) {}

    private record ParkSitePlan(
            ArnisPatchLibrary.Placement placement,
            boolean[] parkColumns,
            boolean[] accessColumns,
            int parkColumnCount
    ) {
        boolean parkAt(int worldX, int worldZ) {
            return parkColumns[parkIndex(worldX, worldZ)];
        }

        boolean accessAt(int worldX, int worldZ) {
            return accessColumns[parkIndex(worldX, worldZ)];
        }
    }

    private static final ParkSitePlan NO_PARK_SITE = new ParkSitePlan(
            null, new boolean[256], new boolean[256], 0);

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
        USABLE_ARNIS_PLACEMENTS.clear();
        BRIDGE_PROFILES.clear();
        PARK_SITE_PLANS.clear();
        ArnisPatchLibrary.clearSelectionCache();
        MerchantTruckLibrary.clearCaches();
        UCorpPortGeneration.clearCache();
        PerimeterOutskirts.clearCache();
        CliffInfrastructureLibrary.clearCaches();
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
        if (UCorpPortGeneration.plan(layout).chunkIntersectsManagedArea(chunkX, chunkZ)) {
            return true;
        }
        if (PerimeterOutskirts.plan(layout).chunkIntersectsManagedArea(chunkX, chunkZ)) {
            return true;
        }
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
                if (GENERATED.contains(key)) {
                    QuicktimeTravelService.installCanonicalStations(level, chunk);
                    continue;
                }
                if (!chunkTouchesCity(chunkX, chunkZ)) continue;
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

    /** Synchronously generates a small operator-selected area for maintenance and verification. */
    public static int generateNow(
            ServerLevel level,
            int centerChunkX,
            int centerChunkZ,
            int radius) {
        if (!enabled || savedData == null || !isMegacityWorld(level)) {
            return 0;
        }
        int placed = 0;
        for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                long key = chunk.pack();
                level.getChunk(chunkX, chunkZ);
                removePending(key);
                if (GENERATED.contains(key) || !chunkTouchesCity(chunkX, chunkZ)) {
                    continue;
                }
                if (generateChunk(level, chunk)) {
                    recordGenerated(key);
                    placed++;
                }
            }
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
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int minX = chunk.getMinBlockX();
            int minZ = chunk.getMinBlockZ();
            UrbanSample[][] samples = sampleChunk(minX, minZ);
            Optional<ArnisPatchLibrary.Placement> patchPlacement =
                    usableArnisPlacement(chunk.x(), chunk.z(), samples);
            District patchDistrict = patchPlacement
                    .map(placement -> placement.patch().district())
                    .orElse(null);

            // Buildings come only from Arnis. Graph crossings, district borders, and special
            // infrastructure use a non-building procedural pass that can cross chunks safely.
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
                    if (patchTemplate != null && keepsArnisColumn(sample, patchDistrict)) {
                        prepareArnisColumn(level, pos, x, z);
                        continue;
                    }
                    if (sample.roadClass() == RoadClass.WILDERNESS) continue;
                    buildColumn(level, pos, x, z, sample);
                }
            }
            decoratePerimeterInfrastructure(level, chunk, samples);
            if (patchTemplate != null && patchPlacement.isPresent()) {
                if (!placeArnisPatch(
                        level, chunk, patchPlacement.get(), patchTemplate, samples)) {
                    return false;
                }
                EnumSet<ArnisPatchLibrary.Connector.Edge> interruptedEdges =
                        interruptedArnisEdges(chunk, patchPlacement.get());
                int completedBlocks = ArnisFacadeRepair.sealInterruptedEdges(
                        level,
                        chunk,
                        interruptedEdges);
                if (completedBlocks > 0) {
                    LOGGER.debug(
                            "[NeonCity] completed {} exposed facade blocks in {} along {}",
                            completedBlocks,
                            chunk,
                            interruptedEdges);
                }
                ArnisFacadeRepair.sealInfrastructureCuts(
                        level, chunk, samples, patchPlacement.get().patch().district());
            }
            DistrictWorldFeatures.decorateChunk(level, chunk, samples);
            QuicktimeTravelService.installCanonicalStations(level, chunk);
            if (patchPlacement.isPresent()) {
                ArnisPatchLibrary.Placement placement = patchPlacement.get();
                DistrictLogoBanners.decorateArnisChunk(
                        level,
                        chunk,
                        placement.patch().district(),
                        placement.selectionHash());
            }
            if (!CityLootGeneration.decorateMegacityChunk(level, chunk, samples)) {
                UrbanCrateGeneration.decorateChunk(level, chunk, samples);
            }
            applyEdgeBiomes(level, chunk);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("[NeonCity] failed generating chunk {}", chunk, exception);
            return false;
        }
    }

    private static void applyEdgeBiomes(ServerLevel level, ChunkPos chunkPos) {
        UCorpPortGeneration.Plan portPlan = UCorpPortGeneration.plan(layout);
        PerimeterOutskirts.Plan outskirts = PerimeterOutskirts.plan(layout);
        boolean containsOverride = false;
        for (int z = chunkPos.getMinBlockZ() + 2; z <= chunkPos.getMaxBlockZ(); z += 4) {
            for (int x = chunkPos.getMinBlockX() + 2; x <= chunkPos.getMaxBlockX(); x += 4) {
                if (portPlan.isOceanBiomeAt(x, z) || outskirts.isTundraBiomeAt(x, z)) {
                    containsOverride = true;
                    break;
                }
            }
            if (containsOverride) break;
        }
        if (!containsOverride) return;

        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
        if (chunk == null) return;
        Holder<Biome> deepOcean = level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.DEEP_OCEAN);
        Holder<Biome> snowyPlains = level.registryAccess()
                .lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.SNOWY_PLAINS);
        var sampler = level.getChunkSource().randomState().sampler();
        chunk.fillBiomesFromNoise((quartX, quartY, quartZ, climateSampler) -> {
            int blockX = QuartPos.toBlock(quartX) + 2;
            int blockZ = QuartPos.toBlock(quartZ) + 2;
            if (portPlan.isOceanBiomeAt(blockX, blockZ)) return deepOcean;
            if (outskirts.isTundraBiomeAt(blockX, blockZ)) return snowyPlains;
            return chunk.getNoiseBiome(quartX, quartY, quartZ);
        }, sampler);
        chunk.markUnsaved();
        level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
    }

    static UrbanSample[][] sampleChunk(int minX, int minZ) {
        Map<Long, AlleyMaze.Plan> alleyPlans = new HashMap<>();
        UrbanSample[][] samples = new UrbanSample[18][18];
        for (int sampleZ = 0; sampleZ < 18; sampleZ++) {
            for (int sampleX = 0; sampleX < 18; sampleX++) {
                samples[sampleZ][sampleX] = sample(
                        minX + sampleX - 1, minZ + sampleZ - 1, alleyPlans);
            }
        }
        return samples;
    }

    private static Optional<ArnisPatchLibrary.Placement> usableArnisPlacement(
            int chunkX, int chunkZ, UrbanSample[][] samples) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        Optional<ArnisPatchLibrary.Placement> cached = USABLE_ARNIS_PLACEMENTS.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<ArnisPatchLibrary.Placement> placement =
                ArnisPatchLibrary.select(layout, chunkX, chunkZ)
                        .filter(value -> isArnisCompatibleChunk(
                                samples, value.patch().district()));
        if (USABLE_ARNIS_PLACEMENTS.size() >= MAX_ARNIS_PLAN_CACHE) {
            USABLE_ARNIS_PLACEMENTS.clear();
        }
        USABLE_ARNIS_PLACEMENTS.put(key, placement);
        return placement;
    }

    private static Optional<ArnisPatchLibrary.Placement> usableArnisPlacement(
            int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        Optional<ArnisPatchLibrary.Placement> cached = USABLE_ARNIS_PLACEMENTS.get(key);
        if (cached != null) {
            return cached;
        }
        return usableArnisPlacement(
                chunkX, chunkZ, sampleChunk(chunkX << 4, chunkZ << 4));
    }

    private static EnumSet<ArnisPatchLibrary.Connector.Edge> interruptedArnisEdges(
            ChunkPos chunk, ArnisPatchLibrary.Placement placement) {
        EnumSet<ArnisPatchLibrary.Connector.Edge> interrupted = EnumSet.noneOf(
                ArnisPatchLibrary.Connector.Edge.class);
        for (ArnisPatchLibrary.Connector.Edge edge
                : ArnisPatchLibrary.Connector.Edge.values()) {
            int deltaX = switch (edge) {
                case WEST -> -1;
                case EAST -> 1;
                default -> 0;
            };
            int deltaZ = switch (edge) {
                case NORTH -> -1;
                case SOUTH -> 1;
                default -> 0;
            };
            Optional<ArnisPatchLibrary.Placement> neighbour = usableArnisPlacement(
                    chunk.x() + deltaX, chunk.z() + deltaZ);
            if (neighbour.isEmpty()
                    || !ArnisPatchLibrary.continuesCoherently(
                            placement, neighbour.get(), deltaX, deltaZ)) {
                interrupted.add(edge);
            }
        }
        return interrupted;
    }

    static boolean isArnisCompatibleChunk(
            UrbanSample[][] samples, District selectedDistrict) {
        int retainedColumns = 0;
        for (int z = 1; z <= 16; z++) {
            for (int x = 1; x <= 16; x++) {
                if (keepsArnisColumn(samples[z][x], selectedDistrict)) {
                    retainedColumns++;
                }
            }
        }
        return retainedColumns >= MIN_ARNIS_COLUMNS;
    }

    static boolean keepsArnisColumn(UrbanSample sample, District selectedDistrict) {
        return sample.district() == selectedDistrict
                && (sample.zone() == MegacityLayout.Zone.NEST
                || sample.zone() == MegacityLayout.Zone.BACKSTREETS)
                && !overridesArnis(sample.roadClass());
    }

    static boolean overridesArnis(RoadClass roadClass) {
        return switch (roadClass) {
            case CENTRAL_PLAZA,
                    INTERDISTRICT_ROAD,
                    BRIDGE,
                    ELEVATED_RAIL,
                    HIGHWAY_BUFFER,
                    BORDER_WALLED,
                    BORDER_FOREST,
                    BORDER_CLIFF,
                    CANAL,
                    PARK,
                    HARBOR,
                    CONTAINER_PORT,
                    OCEAN,
                    PORTSHIP,
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
                                           StructureTemplate template,
                                           UrbanSample[][] samples) {
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
        StructurePlaceSettings settings = arnisPlaceSettings(placement, destinationBounds)
                .addProcessor(new ArnisColumnMaskProcessor(
                        minX, minZ, samples, placement.patch().district()));
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

    static StructurePlaceSettings arnisPlaceSettings(
            ArnisPatchLibrary.Placement placement, BoundingBox destinationBounds) {
        return new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setMirror(placement.mirror())
                .setRotation(placement.rotation())
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                .setBoundingBox(destinationBounds);
    }

    private static final class ArnisColumnMaskProcessor implements StructureProcessor {
        private final int minX;
        private final int minZ;
        private final boolean[] retained = new boolean[16 * 16];

        private ArnisColumnMaskProcessor(
                int minX,
                int minZ,
                UrbanSample[][] samples,
                District selectedDistrict) {
            this.minX = minX;
            this.minZ = minZ;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    retained[z * 16 + x] = keepsArnisColumn(
                            samples[z + 1][x + 1], selectedDistrict);
                }
            }
        }

        @Override
        public MapCodec<? extends StructureProcessor> codec() {
            return MapCodec.unit(this);
        }

        @Override
        public StructureTemplate.@Nullable StructureBlockInfo processBlock(
                LevelReader level,
                BlockPos targetPosition,
                BlockPos referencePos,
                BlockPos placementPosition,
                StructureTemplate.StructureBlockInfo processedBlockInfo,
                StructurePlaceSettings settings) {
            BlockPos worldPosition = processedBlockInfo.pos();
            int localX = worldPosition.getX() - minX;
            int localZ = worldPosition.getZ() - minZ;
            if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) {
                return null;
            }
            return retained[localZ * 16 + localX] ? processedBlockInfo : null;
        }
    }

    private static boolean sameBounds(BoundingBox left, BoundingBox right) {
        return left.minX() == right.minX()
                && left.minY() == right.minY()
                && left.minZ() == right.minZ()
                && left.maxX() == right.maxX()
                && left.maxY() == right.maxY()
                && left.maxZ() == right.maxZ();
    }

    static void buildColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                            int x, int z, UrbanSample sample) {
        int naturalTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int ground = sample.groundY();
        boolean canalBridge = sample.roadClass() == RoadClass.BRIDGE
                && !sample.location().onConnection();
        int clearFrom = canalBridge
                ? WATER_Y + 1
                : ground + 1;
        for (int y = clearFrom; y <= Math.min(MAX_BUILD_Y, naturalTop + 2); y++) {
            set(level, pos, x, y, z, Blocks.AIR.defaultBlockState());
        }

        int foundationTop = canalBridge
                ? WATER_Y - 1
                : ground;
        for (int y = Math.min(naturalTop, foundationTop - 5); y <= foundationTop; y++) {
            if (y < level.getMinY()) continue;
            BlockState state = y == foundationTop
                    ? surface(sample, x, z)
                    : foundation(sample);
            set(level, pos, x, y, z, state);
        }

        if (isWater(sample.roadClass()) || canalBridge) {
            for (int y = foundationTop + 1; y <= WATER_Y; y++) {
                set(level, pos, x, y, z, Blocks.WATER.defaultBlockState());
            }
        } else {
            set(level, pos, x, ground, z, surface(sample, x, z));
        }

        decorateInfrastructure(level, pos, x, z, sample);
        decorateOpenGround(level, pos, x, z, sample);
    }

    private static boolean isWater(RoadClass road) {
        return road == RoadClass.CANAL
                || road == RoadClass.HARBOR
                || road == RoadClass.OCEAN
                || road == RoadClass.PORTSHIP;
    }

    private static BlockState foundation(UrbanSample sample) {
        if (sample.roadClass() == RoadClass.FARM) {
            return Blocks.MUD_BRICKS.defaultBlockState();
        }
        if (sample.roadClass() == RoadClass.TUNDRA_OUTSKIRTS
                || sample.roadClass() == RoadClass.LAND_OUTSKIRTS
                || sample.roadClass() == RoadClass.EXTRACTION_SITE) {
            return Blocks.DIRT.defaultBlockState();
        }
        return switch (sample.zone()) {
            case BORDER_CLIFF -> Blocks.STONE.defaultBlockState();
            case BORDER_FOREST -> Blocks.DIRT.defaultBlockState();
            case BORDER_WALLED -> Blocks.DEEPSLATE.defaultBlockState();
            default -> Blocks.DEEPSLATE.defaultBlockState();
        };
    }

    private static BlockState surface(UrbanSample sample, int x, int z) {
        return switch (sample.roadClass()) {
            case CENTRAL_PLAZA -> floorMod(x, 9) == 0 || floorMod(z, 9) == 0
                    ? palette(sample.district()).accent()
                    : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case DISTRICT_BOULEVARD, INTERDISTRICT_ROAD, ELEVATED_RAIL -> laneMark(x, z)
                    ? concrete(DyeColor.YELLOW) : concrete(DyeColor.BLACK);
            case LOCAL_STREET -> laneMark(x * 3, z * 5)
                    ? concrete(DyeColor.LIGHT_GRAY) : concrete(DyeColor.GRAY);
            case SERVICE_ALLEY -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case BRIDGE -> Blocks.SMOOTH_STONE.defaultBlockState();
            case HIGHWAY_BUFFER -> floorMod(x * 7 + z * 11, 13) == 0
                    ? Blocks.GRAVEL.defaultBlockState()
                    : Blocks.SMOOTH_STONE.defaultBlockState();
            case CANAL, HARBOR -> Blocks.CLAY.defaultBlockState();
            case OCEAN, PORTSHIP -> floorMod(x * 5 + z * 7, 9) <= 2
                    ? Blocks.CLAY.defaultBlockState()
                    : Blocks.GRAVEL.defaultBlockState();
            case CONTAINER_PORT -> floorMod(x * 7 + z * 11, 19) <= 1
                    ? concrete(DyeColor.YELLOW)
                    : floorMod(x - z, 13) == 0
                            ? concrete(DyeColor.LIGHT_GRAY)
                            : concrete(DyeColor.GRAY);
            case PARK -> floorMod(x + z, 7) == 0
                    ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            case FARM -> DistrictWorldFeatures.farmSurface(sample);
            case EXTRACTION_SITE -> Blocks.COARSE_DIRT.defaultBlockState();
            case TUNDRA_OUTSKIRTS -> floorMod(x * 5 + z * 7, 17) <= 1
                    ? Blocks.PACKED_ICE.defaultBlockState()
                    : Blocks.SNOW_BLOCK.defaultBlockState();
            case LAND_OUTSKIRTS -> floorMod(x * 7 + z * 11, 23) == 0
                    ? Blocks.COARSE_DIRT.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState();
            case BORDER_WALLED -> floorMod(x * 7 + z * 13, 17) <= 1
                    ? Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState()
                    : Blocks.PACKED_MUD.defaultBlockState();
            case BORDER_FOREST -> Blocks.GRASS_BLOCK.defaultBlockState();
            case BORDER_CLIFF -> DistrictWorldFeatures.borderHillSurface(
                    layout.seed(), x, z);
            case NONE -> sidewalk(sample.district());
            case WILDERNESS -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }

    private static boolean laneMark(int x, int z) {
        return floorMod(x + z, 17) <= 1 && floorMod(x - z, 11) == 0;
    }

    private static void decorateInfrastructure(ServerLevel level, BlockPos.MutableBlockPos pos,
                                               int x, int z, UrbanSample sample) {
        boolean graphHighway = sample.location().onConnection();
        if (graphHighway) {
            int deck = highwayDeckY(layout, sample.location(), x, z);
            if (floorMod(x * 31 + z * 17, 37) == 0) {
                for (int y = sample.groundY() + 1; y < deck; y++) {
                    set(level, pos, x, y, z, Blocks.POLISHED_BASALT.defaultBlockState());
                }
            }
            if (deck > sample.groundY()) {
                set(level, pos, x, deck - 1, z,
                        Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
            set(level, pos, x, deck, z, laneMark(x, z)
                    ? concrete(DyeColor.YELLOW) : concrete(DyeColor.BLACK));
            if (sample.location().connectionDistance() > 10.5) {
                set(level, pos, x, deck + 1, z, Blocks.IRON_BARS.defaultBlockState());
            }
        } else if (sample.roadClass() == RoadClass.BRIDGE) {
            int deck = CITY_GROUND_Y + 1;
            if (floorMod(x * 31 + z * 17, 37) == 0) {
                for (int y = WATER_Y; y < deck; y++) {
                    set(level, pos, x, y, z, Blocks.POLISHED_BASALT.defaultBlockState());
                }
            }
            set(level, pos, x, deck - 1, z,
                    Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            set(level, pos, x, deck, z, laneMark(x, z)
                    ? concrete(DyeColor.YELLOW) : concrete(DyeColor.BLACK));
        }

        Optional<MegacityLayout.ConnectionProjection> elevated =
                layout.nearestElevatedConnection(x, z);
        double elevatedDistance = elevated.map(
                MegacityLayout.ConnectionProjection::distance).orElse(Double.MAX_VALUE);
        boolean elevatedRoute = elevated.isPresent()
                && elevatedDistance <= ELEVATED_HALF_WIDTH;
        boolean elevatedJunction = isElevatedJunctionAt(layout, x, z);
        boolean supportStation = elevated.isPresent()
                && isElevatedSupportStation(elevated.get());
        boolean supportPillar = supportStation
                && Math.abs(elevatedDistance - ELEVATED_SUPPORT_OFFSET)
                <= ELEVATED_SUPPORT_HALF_WIDTH;
        if (supportPillar) {
            for (int y = sample.groundY() + 1; y < ELEVATED_DECK_Y; y++) {
                set(level, pos, x, y, z, Blocks.IRON_BLOCK.defaultBlockState());
            }
        }
        if (supportStation
                && elevatedDistance <= ELEVATED_SUPPORT_OFFSET + ELEVATED_SUPPORT_HALF_WIDTH) {
            set(level, pos, x, ELEVATED_DECK_Y - 1, z, Blocks.IRON_BLOCK.defaultBlockState());
        }
        if (elevatedRoute || elevatedJunction) {
            set(level, pos, x, ELEVATED_DECK_Y - 1, z,
                    Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            set(level, pos, x, ELEVATED_DECK_Y, z, Blocks.SMOOTH_STONE.defaultBlockState());
            if (elevatedRoute && elevated.get().distance() <= ELEVATED_TRACK_HALF_WIDTH) {
                RailShape railShape = railShape(elevated.get());
                BlockState rail = floorMod(x + z, 3) == 0
                        ? Blocks.POWERED_RAIL.defaultBlockState().setValue(
                                PoweredRailBlock.SHAPE, railShape)
                        : Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, railShape);
                set(level, pos, x, ELEVATED_DECK_Y, z, rail);
            }
        }
    }

    private static RailShape railShape(MegacityLayout.ConnectionProjection projection) {
        return Math.abs(projection.tangentX()) >= Math.abs(projection.tangentZ())
                ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    private static boolean isElevatedSupportStation(
            MegacityLayout.ConnectionProjection projection) {
        double tangentLength = Math.max(
                1.0, Math.hypot(projection.tangentX(), projection.tangentZ()));
        double along = (projection.x() * projection.tangentX()
                + projection.z() * projection.tangentZ()) / tangentLength;
        return Math.floorMod((int) Math.floor(along), ELEVATED_SUPPORT_SPACING) <= 1;
    }

    private static boolean isElevatedJunctionAt(
            MegacityLayout activeLayout, int worldX, int worldZ) {
        for (MegacityLayout.Node node : activeLayout.nodes()) {
            if (Math.hypot(worldX - node.x(), worldZ - node.z())
                    <= ELEVATED_JUNCTION_RADIUS) {
                return true;
            }
        }
        return false;
    }

    static int highwayDeckY(
            MegacityLayout activeLayout, int worldX, int worldZ) {
        MegacityLayout.Location location = activeLayout.locate(worldX, worldZ);
        return highwayDeckY(activeLayout, location, worldX, worldZ);
    }

    static int highwayDeckY(
            MegacityLayout activeLayout, MegacityLayout.Edge edge, double progress) {
        return CITY_GROUND_Y + bridgeProfile(activeLayout, edge).riseAt(progress);
    }

    private static int highwayDeckY(
            MegacityLayout activeLayout,
            MegacityLayout.Location location,
            int worldX,
            int worldZ) {
        if (!location.onConnection() || location.nearestConnection() == null) {
            return CITY_GROUND_Y;
        }
        MegacityLayout.ConnectionProjection projection = MegacityLayout.projectConnection(
                location.nearestConnection(), worldX, worldZ);
        BridgeProfile profile = bridgeProfile(activeLayout, location.nearestConnection());
        return CITY_GROUND_Y + profile.riseAt(projection.progress());
    }

    private static BridgeProfile bridgeProfile(
            MegacityLayout activeLayout, MegacityLayout.Edge edge) {
        long key = activeLayout.seed() ^ Long.rotateLeft(edge.identity(), 17);
        return BRIDGE_PROFILES.computeIfAbsent(
                key, ignored -> buildBridgeProfile(activeLayout, edge));
    }

    private static BridgeProfile buildBridgeProfile(
            MegacityLayout activeLayout, MegacityLayout.Edge edge) {
        double curveLength = 0.0;
        MegacityLayout.CurvePoint previous = MegacityLayout.curvePoint(edge, 0.0);
        for (int step = 1; step <= 64; step++) {
            MegacityLayout.CurvePoint current = MegacityLayout.curvePoint(edge, step / 64.0);
            curveLength += Math.hypot(current.x() - previous.x(), current.z() - previous.z());
            previous = current;
        }
        int sampleCount = Math.max(2, (int) Math.ceil(curveLength) + 1);
        double[] distanceAlong = new double[sampleCount];
        boolean[] highSpan = new boolean[sampleCount];
        previous = MegacityLayout.curvePoint(edge, 0.0);
        highSpan[0] = rawBridgeAt(activeLayout, previous);
        for (int index = 1; index < sampleCount; index++) {
            double progress = index / (double) (sampleCount - 1);
            MegacityLayout.CurvePoint current = MegacityLayout.curvePoint(edge, progress);
            distanceAlong[index] = distanceAlong[index - 1]
                    + Math.hypot(current.x() - previous.x(), current.z() - previous.z());
            highSpan[index] = rawBridgeAt(activeLayout, current);
            previous = current;
        }

        double[] distanceToSpan = new double[sampleCount];
        double lastSpanDistance = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < sampleCount; index++) {
            if (highSpan[index]) lastSpanDistance = distanceAlong[index];
            distanceToSpan[index] = distanceAlong[index] - lastSpanDistance;
        }
        lastSpanDistance = Double.POSITIVE_INFINITY;
        for (int index = sampleCount - 1; index >= 0; index--) {
            if (highSpan[index]) lastSpanDistance = distanceAlong[index];
            distanceToSpan[index] = Math.min(
                    distanceToSpan[index], lastSpanDistance - distanceAlong[index]);
        }

        int[] rise = new int[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            double remainingGrade = BRIDGE_RISE * BRIDGE_GRADE_STEP - distanceToSpan[index];
            rise[index] = Math.max(0, Math.min(BRIDGE_RISE,
                    (int) Math.ceil(remainingGrade / BRIDGE_GRADE_STEP)));
        }
        return new BridgeProfile(rise);
    }

    private static boolean rawBridgeAt(
            MegacityLayout activeLayout, MegacityLayout.CurvePoint point) {
        int worldX = (int) Math.round(point.x());
        int worldZ = (int) Math.round(point.z());
        MegacityLayout.Location district = activeLayout.locateDistrict(worldX, worldZ);
        return (!activeLayout.insideUrbanHull(worldX, worldZ)
                        && district.normalizedDistance() > 0.96)
                || district.boundaryGap() < 0.085;
    }

    private static void decorateOpenGround(ServerLevel level, BlockPos.MutableBlockPos pos,
                                           int x, int z, UrbanSample sample) {
        int y = sample.groundY() + 1;
        long hash = mix(layout.seed() ^ DECORATION_SALT, x, z);
        if (sample.zone() == MegacityLayout.Zone.OUTSKIRTS
                && (sample.roadClass() == RoadClass.NONE
                        || sample.roadClass() == RoadClass.LAND_OUTSKIRTS)
                && unit(hash) < Math.max(0.006, sample.district().vegetation() * 0.018)) {
            TreePalette trees = treePalette(sample.district());
            int height = 4 + floorMod((int) hash, 3);
            for (int dy = 0; dy < height; dy++) set(level, pos, x, y + dy, z, trees.trunk());
            for (int dy = height; dy <= height + 2; dy++) set(level, pos, x, y + dy, z, trees.leaves());
        }
        if (sample.roadClass() == RoadClass.FARM && !surface(sample, x, z).is(Blocks.WATER)) {
            set(level, pos, x, y, z, DistrictWorldFeatures.matureWheat());
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
        if (sample.roadClass() == RoadClass.CONTAINER_PORT
                || sample.roadClass() == RoadClass.PORTSHIP) {
            UCorpPortGeneration.Plan portPlan = UCorpPortGeneration.plan(layout);
            for (int overlayY = UCorpPortGeneration.OVERLAY_MIN_Y;
                 overlayY <= UCorpPortGeneration.OVERLAY_MAX_Y;
                 overlayY++) {
                BlockState overlay = UCorpPortGeneration.overlayAt(portPlan, x, overlayY, z);
                if (overlay != null) {
                    set(level, pos, x, overlayY, z, overlay);
                }
            }
        }
        if (sample.roadClass() == RoadClass.SERVICE_ALLEY && floorMod((int) hash, 89) == 0) {
            set(level, pos, x, y, z, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        }
    }

    private static void decoratePerimeterInfrastructure(
            ServerLevel level,
            ChunkPos chunk,
            UrbanSample[][] samples) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                UrbanSample sample = samples[localZ + 1][localX + 1];
                int x = chunk.getMinBlockX() + localX;
                int z = chunk.getMinBlockZ() + localZ;
                int y = sample.groundY() + 1;
                long hash = mix(layout.seed() ^ DECORATION_SALT, x, z);
                if (sample.roadClass() == RoadClass.EXTRACTION_SITE) {
                    decorateExtractionSite(level, pos, x, y, z, hash);
                }
                if (sample.roadClass() == RoadClass.TUNDRA_OUTSKIRTS
                        && isTundraTreeAnchor(layout.seed(), x, z)) {
                    decorateTundraTree(level, pos, x, y, z, hash);
                }
            }
        }
    }

    private static void decorateTundraTree(
            ServerLevel level,
            BlockPos.MutableBlockPos pos,
            int x,
            int y,
            int z,
            long hash) {
        int height = 3 + floorMod((int) (hash >>> 19), 3);
        for (int dy = 0; dy < height; dy++) {
            set(level, pos, x, y + dy, z, Blocks.SPRUCE_LOG.defaultBlockState());
        }
        for (int dy = height - 1; dy <= height + 1; dy++) {
            int radius = dy == height + 1 ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= radius + 1) {
                        set(level, pos, x + dx, y + dy, z + dz,
                                Blocks.SPRUCE_LEAVES.defaultBlockState());
                    }
                }
            }
        }
    }

    static boolean isTundraTreeAnchor(long seed, int worldX, int worldZ) {
        int localX = Math.floorMod(worldX, 16);
        int localZ = Math.floorMod(worldZ, 16);
        if (localX < 2 || localX > 13 || localZ < 2 || localZ > 13) {
            return false;
        }
        return floorMod((int) mix(seed ^ DECORATION_SALT, worldX, worldZ), 181) == 0;
    }

    private static void decorateExtractionSite(
            ServerLevel level,
            BlockPos.MutableBlockPos pos,
            int x,
            int y,
            int z,
            long hash) {
        if (floorMod(x * 17 + z * 23, 97) == 0) {
            for (int dy = 0; dy <= 9; dy++) {
                set(level, pos, x, y + dy, z, Blocks.IRON_BARS.defaultBlockState());
            }
            int armDirection = floorMod(x, 16) <= 10 ? 1 : -1;
            for (int arm = 1; arm <= 5; arm++) {
                set(level, pos, x + armDirection * arm, y + 9, z,
                        Blocks.IRON_BARS.defaultBlockState());
            }
            set(level, pos, x, y + 10, z, Blocks.IRON_BLOCK.defaultBlockState());
        }
        if (floorMod(x * 29 + z * 13, 173) == 0) {
            for (int dy = 0; dy <= 6; dy++) {
                set(level, pos, x, y + dy, z, dy % 3 == 0
                        ? cutCopper(WeatheringCopper.WeatherState.EXPOSED)
                        : Blocks.DEEPSLATE_BRICK_WALL.defaultBlockState());
            }
            set(level, pos, x, y + 7, z, Blocks.POINTED_DRIPSTONE.defaultBlockState());
        }
        if (floorMod((int) hash, 41) == 0) {
            set(level, pos, x, y, z, Blocks.RAIL.defaultBlockState());
        }
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
            case AE_DISTRICT -> p(Blocks.STONE_BRICKS, Blocks.SPRUCE_PLANKS,
                    stainedGlass(DyeColor.LIGHT_BLUE), Blocks.SEA_LANTERN,
                    cutCopper(WeatheringCopper.WeatherState.WEATHERED), Blocks.SNOW_BLOCK);
            case YI_DISTRICT -> p(Blocks.TUFF_BRICKS, concrete(DyeColor.GRAY),
                    stainedGlass(DyeColor.LIGHT_GRAY), Blocks.OCHRE_FROGLIGHT,
                    concrete(DyeColor.RED), Blocks.DEEPSLATE_TILES);
            case WANG_DISTRICT -> p(Blocks.BRICKS, Blocks.DARK_OAK_PLANKS,
                    stainedGlass(DyeColor.GRAY), Blocks.GLOWSTONE,
                    cutCopper(WeatheringCopper.WeatherState.EXPOSED), Blocks.DEEPSLATE_BRICKS);
            case XI_DISTRICT -> p(Blocks.MUD_BRICKS, dyedTerracotta(DyeColor.ORANGE),
                    stainedGlass(DyeColor.CYAN), Blocks.SHROOMLIGHT,
                    concrete(DyeColor.RED), Blocks.DARK_OAK_PLANKS);
            case UI_DISTRICT -> p(Blocks.QUARTZ_BRICKS, concrete(DyeColor.LIGHT_GRAY),
                    stainedGlass(DyeColor.CYAN), Blocks.SEA_LANTERN,
                    concrete(DyeColor.GREEN), Blocks.MOSS_BLOCK);
            case UANG_DISTRICT -> p(Blocks.BRICKS, Blocks.DARK_OAK_PLANKS,
                    stainedGlass(DyeColor.LIGHT_BLUE), Blocks.GLOWSTONE,
                    cutCopper(WeatheringCopper.WeatherState.WEATHERED), Blocks.DARK_OAK_PLANKS);
            case PON_DISTRICT -> p(Blocks.SMOOTH_SANDSTONE, dyedTerracotta(DyeColor.RED),
                    stainedGlass(DyeColor.YELLOW), Blocks.SHROOMLIGHT,
                    Blocks.QUARTZ_PILLAR, Blocks.TERRACOTTA);
            case POK_DISTRICT -> p(Blocks.TUFF_BRICKS, Blocks.BRICKS,
                    stainedGlass(DyeColor.ORANGE), Blocks.OCHRE_FROGLIGHT,
                    Blocks.IRON_BLOCK, Blocks.SMOOTH_STONE);
            case PAK_DISTRICT -> p(Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_SANDSTONE,
                    stainedGlass(DyeColor.CYAN), Blocks.PEARLESCENT_FROGLIGHT,
                    Blocks.GOLD_BLOCK, concrete(DyeColor.WHITE));
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
        return sample(layout, worldX, worldZ, DIAGNOSTIC_ALLEY_PLANS, true);
    }

    private static UrbanSample sample(int worldX, int worldZ, Map<Long, AlleyMaze.Plan> alleyPlans) {
        return sample(layout, worldX, worldZ, alleyPlans, true);
    }

    /** Seed-bound schematic sample that is safe for asynchronous client map rendering. */
    public static UrbanSample mapSample(MegacityLayout mapLayout, int worldX, int worldZ) {
        return sample(mapLayout, worldX, worldZ, null, false);
    }

    /** Full infrastructure sample for deterministic planners bound to an explicit layout. */
    static UrbanSample topologySample(MegacityLayout activeLayout, int worldX, int worldZ) {
        return sample(activeLayout, worldX, worldZ, new HashMap<>(), true);
    }

    private static UrbanSample sample(
            MegacityLayout activeLayout,
            int worldX,
            int worldZ,
            Map<Long, AlleyMaze.Plan> alleyPlans,
            boolean detailedRuntimeSample) {
        MegacityLayout.Location location = detailedRuntimeSample
                ? activeLayout.locate(worldX, worldZ)
                : activeLayout.locateDistrict(worldX, worldZ);
        UCorpPortGeneration.Feature marineFeature = UCorpPortGeneration.plan(activeLayout)
                .featureAt(worldX, worldZ);
        boolean marine = marineFeature != UCorpPortGeneration.Feature.NONE;
        PerimeterOutskirts.Feature perimeterFeature = !marine
                ? PerimeterOutskirts.plan(activeLayout).featureAt(worldX, worldZ)
                : PerimeterOutskirts.Feature.NONE;
        boolean managedOutskirts = perimeterFeature != PerimeterOutskirts.Feature.NONE;
        District district = marine ? District.U_CORP : location.district();
        MegacityLayout.Zone zone = marine
                ? location.district() == District.U_CORP
                        && location.zone() != MegacityLayout.Zone.WILDERNESS
                                ? location.zone()
                                : MegacityLayout.Zone.OUTSKIRTS
                : managedOutskirts ? MegacityLayout.Zone.OUTSKIRTS : location.zone();
        if (zone == MegacityLayout.Zone.WILDERNESS) {
            return new UrbanSample(location, district, zone, RoadClass.WILDERNESS,
                    CITY_GROUND_Y, 0, district.parcelSize(), false,
                    0, 0, 0.0, 0.0, 0L);
        }

        MegacityLayout.Node parcelNode = marine
                ? activeLayout.node(District.U_CORP)
                : location.primary();
        LocalCoordinates local = parcelCoordinates(parcelNode, worldX, worldZ);
        int parcelSize = district.parcelSize();
        int parcelX = floorDiv(local.u(), parcelSize);
        int parcelZ = floorDiv(local.v(), parcelSize);
        double localU = floorMod(local.u(), parcelSize);
        double localV = floorMod(local.v(), parcelSize);
        long parcelHash = mix(activeLayout.seed() ^ parcelNode.identity(), parcelX, parcelZ);
        RoadClass road = switch (perimeterFeature) {
            case NORTH_TUNDRA -> RoadClass.TUNDRA_OUTSKIRTS;
            case WEST_LAND, EAST_LAND -> RoadClass.LAND_OUTSKIRTS;
            case EAST_EXTRACTION -> RoadClass.EXTRACTION_SITE;
            case NONE -> roadClass(
                    activeLayout,
                    location,
                    marineFeature,
                    local,
                    worldX,
                    worldZ,
                    parcelHash,
                    detailedRuntimeSample);
        };
        int groundY = terrainHeight(activeLayout, location, worldX, worldZ, road);
        if (detailedRuntimeSample && road == RoadClass.NONE
                && zone == MegacityLayout.Zone.BACKSTREETS
                && alleyAt(activeLayout, worldX, worldZ, alleyPlans)) {
            road = RoadClass.SERVICE_ALLEY;
        }
        return new UrbanSample(location, district, zone, road, groundY,
                0, parcelSize, false,
                parcelX, parcelZ, localU, localV, parcelHash);
    }

    private static RoadClass roadClass(
            MegacityLayout activeLayout,
            MegacityLayout.Location location,
            UCorpPortGeneration.Feature marineFeature,
            LocalCoordinates local,
            int worldX,
            int worldZ,
            long hash,
            boolean includeAtlasConnectors) {
        RoadClass priority = priorityRoadClass(
                activeLayout,
                location,
                marineFeature,
                local,
                worldX,
                worldZ,
                hash,
                includeAtlasConnectors);
        if (priority != null) return priority;

        MegacityLayout.Node node = marineFeature == UCorpPortGeneration.Feature.NONE
                ? location.primary()
                : activeLayout.node(District.U_CORP);
        District district = node.district();
        ParkSitePlan parkSite = parkSitePlan(
                activeLayout, Math.floorDiv(worldX, 16), Math.floorDiv(worldZ, 16));
        if (parkSite.accessAt(worldX, worldZ)) return RoadClass.LOCAL_STREET;
        if (parkSite.parkAt(worldX, worldZ)) return RoadClass.PARK;

        int parcel = district.parcelSize();
        double lineU = distanceToGrid(local.u(), parcel);
        double lineV = distanceToGrid(local.v(), parcel);
        double width = district == District.H_CORP || district == District.Z_CORP ? 2.8
                : district == District.U_CORP ? 5.5 : 3.8;
        if (lineU < width || lineV < width) return RoadClass.LOCAL_STREET;
        return RoadClass.NONE;
    }

    /** Infrastructure and cultural circulation that must win before a park is considered. */
    private static RoadClass priorityRoadClass(
            MegacityLayout activeLayout,
            MegacityLayout.Location location,
            UCorpPortGeneration.Feature marineFeature,
            LocalCoordinates local,
            int worldX,
            int worldZ,
            long hash,
            boolean includeAtlasConnectors) {
        MegacityLayout.Node node = marineFeature == UCorpPortGeneration.Feature.NONE
                ? location.primary()
                : activeLayout.node(District.U_CORP);
        double dx = worldX - node.x();
        double dz = worldZ - node.z();
        double radius = Math.hypot(dx, dz);
        double angle = Math.atan2(dz, dx);
        if (location.onConnection()) {
            if (location.nearestConnection().hasElevatedLayer()
                    && location.connectionDistance() <= ELEVATED_HALF_WIDTH) {
                return RoadClass.ELEVATED_RAIL;
            }
            if (highwayDeckY(activeLayout, location, worldX, worldZ) > CITY_GROUND_Y) {
                return RoadClass.BRIDGE;
            }
            return RoadClass.INTERDISTRICT_ROAD;
        }
        // The ground routes enter the civic plaza instead of terminating at its rim. The
        // elevated backbone crosses above this same hub in decorateInfrastructure.
        if (radius < 34.0) return RoadClass.CENTRAL_PLAZA;
        if (location.nearestConnection() != null
                && location.connectionDistance() <= HIGHWAY_CLEARANCE_RADIUS) {
            return RoadClass.HIGHWAY_BUFFER;
        }
        RoadClass marineRoad = switch (marineFeature) {
            case NONE -> null;
            case CONTAINER_PORT -> RoadClass.CONTAINER_PORT;
            case HARBOR_WATER -> RoadClass.HARBOR;
            case OCEAN -> RoadClass.OCEAN;
            case PORTSHIP -> RoadClass.PORTSHIP;
        };
        if (marineRoad != null) return marineRoad;
        if (location.zone() == MegacityLayout.Zone.BORDER_WALLED) {
            return RoadClass.BORDER_WALLED;
        }
        if (location.zone() == MegacityLayout.Zone.BORDER_FOREST) {
            MegacityLayout.BoundaryFrame frame = activeLayout.boundaryFrame(
                    location, worldX, worldZ);
            return frame.gapRatio() <= 0.10
                    ? RoadClass.LOCAL_STREET : RoadClass.BORDER_FOREST;
        }
        if (location.zone() == MegacityLayout.Zone.BORDER_CLIFF) {
            return RoadClass.BORDER_CLIFF;
        }
        if (location.district() == District.S_CORP
                && location.zone() == MegacityLayout.Zone.BACKSTREETS
                && location.normalizedDistance() <= MegacityLayout.DISTRICT_BLOB_LIMIT) {
            return RoadClass.FARM;
        }
        if (includeAtlasConnectors
                && ArnisPatchLibrary.connectorApproachAt(activeLayout, worldX, worldZ)) {
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
        if (district == District.X_CORP && dx > 0.0) {
            double edgeProgress = Math.max(0.0,
                    (location.normalizedDistance() - 0.70) / 0.38);
            double eastAlignment = dx / Math.max(1.0, Math.hypot(dx, dz));
            double extractionDensity = Math.min(1.0, edgeProgress)
                    * Math.max(0.0, eastAlignment) * 0.96;
            if (unit(Long.rotateLeft(hash, 27)) < extractionDensity) {
                return RoadClass.EXTRACTION_SITE;
            }
        }

        RoadClass culturalRoad = culturalRoad(district, local, location, hash);
        if (culturalRoad != null && culturalRoad != RoadClass.PARK) return culturalRoad;
        return null;
    }

    private static ParkSitePlan parkSitePlan(
            MegacityLayout activeLayout, int chunkX, int chunkZ) {
        if (PARK_SITE_PLANS.size() > MAX_PARK_SITE_CACHE) PARK_SITE_PLANS.clear();
        ParkSiteKey key = new ParkSiteKey(activeLayout.seed(), chunkX, chunkZ);
        return PARK_SITE_PLANS.computeIfAbsent(
                key, ignored -> buildParkSitePlan(activeLayout, chunkX, chunkZ));
    }

    private static ParkSitePlan buildParkSitePlan(
            MegacityLayout activeLayout, int chunkX, int chunkZ) {
        ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                activeLayout, chunkX, chunkZ).orElse(null);
        if (placement == null
                || !ArnisPatchLibrary.isConservativeOpenParkTile(placement.patch())) {
            return NO_PARK_SITE;
        }

        boolean[] candidates = new boolean[256];
        boolean[] access = new boolean[256];
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int worldX = minX + localX;
                int worldZ = minZ + localZ;
                int index = localZ * 16 + localX;
                boolean accessColumn = ArnisPatchLibrary.isParkAccessLaneAt(
                        placement, worldX, worldZ);
                MegacityLayout.Location location = activeLayout.locate(worldX, worldZ);
                boolean compatible = location.district() == placement.patch().district()
                        && placement.patch().placementZones().contains(location.zone());
                if (accessColumn && !compatible) return NO_PARK_SITE;
                if (!compatible) continue;

                RoadClass priority = priorityRoadAt(
                        activeLayout, location, worldX, worldZ);
                if (accessColumn) {
                    if (priority != null && priority != RoadClass.LOCAL_STREET) {
                        return NO_PARK_SITE;
                    }
                    access[index] = true;
                } else if (priority == null) {
                    candidates[index] = true;
                }
            }
        }

        boolean[] parkColumns = connectedParkComponent(candidates, access);
        int parkColumnCount = 0;
        for (boolean parkColumn : parkColumns) {
            if (parkColumn) parkColumnCount++;
        }
        if (parkColumnCount < MIN_PARK_SITE_COLUMNS) return NO_PARK_SITE;
        return new ParkSitePlan(placement, parkColumns, access, parkColumnCount);
    }

    private static RoadClass priorityRoadAt(
            MegacityLayout activeLayout,
            MegacityLayout.Location location,
            int worldX,
            int worldZ) {
        UCorpPortGeneration.Feature marineFeature = UCorpPortGeneration.plan(activeLayout)
                .featureAt(worldX, worldZ);
        MegacityLayout.Node parcelNode = marineFeature == UCorpPortGeneration.Feature.NONE
                ? location.primary()
                : activeLayout.node(District.U_CORP);
        LocalCoordinates local = parcelCoordinates(parcelNode, worldX, worldZ);
        int parcelSize = parcelNode.district().parcelSize();
        int parcelX = floorDiv(local.u(), parcelSize);
        int parcelZ = floorDiv(local.v(), parcelSize);
        long hash = mix(activeLayout.seed() ^ parcelNode.identity(), parcelX, parcelZ);
        return priorityRoadClass(
                activeLayout,
                location,
                marineFeature,
                local,
                worldX,
                worldZ,
                hash,
                true);
    }

    /** Retain only one large, entrance-connected component; smaller remnants are not parks. */
    private static boolean[] connectedParkComponent(
            boolean[] candidates, boolean[] access) {
        boolean[] visited = new boolean[256];
        boolean[] best = new boolean[256];
        int[] queue = new int[256];
        int bestCount = 0;
        for (int start = 0; start < candidates.length; start++) {
            if (!candidates[start] || visited[start]) continue;
            int head = 0;
            int tail = 0;
            int minX = 16;
            int minZ = 16;
            int maxX = -1;
            int maxZ = -1;
            boolean touchesAccess = false;
            visited[start] = true;
            queue[tail++] = start;
            while (head < tail) {
                int index = queue[head++];
                int x = index & 15;
                int z = index >>> 4;
                minX = Math.min(minX, x);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxZ = Math.max(maxZ, z);
                if (x > 0) {
                    touchesAccess |= access[index - 1];
                    if (candidates[index - 1] && !visited[index - 1]) {
                        visited[index - 1] = true;
                        queue[tail++] = index - 1;
                    }
                }
                if (x < 15) {
                    touchesAccess |= access[index + 1];
                    if (candidates[index + 1] && !visited[index + 1]) {
                        visited[index + 1] = true;
                        queue[tail++] = index + 1;
                    }
                }
                if (z > 0) {
                    touchesAccess |= access[index - 16];
                    if (candidates[index - 16] && !visited[index - 16]) {
                        visited[index - 16] = true;
                        queue[tail++] = index - 16;
                    }
                }
                if (z < 15) {
                    touchesAccess |= access[index + 16];
                    if (candidates[index + 16] && !visited[index + 16]) {
                        visited[index + 16] = true;
                        queue[tail++] = index + 16;
                    }
                }
            }
            int width = maxX - minX + 1;
            int depth = maxZ - minZ + 1;
            if (touchesAccess && tail >= MIN_PARK_SITE_COLUMNS
                    && width >= MIN_PARK_SITE_AXIS && depth >= MIN_PARK_SITE_AXIS
                    && tail > bestCount) {
                best = new boolean[256];
                for (int index = 0; index < tail; index++) best[queue[index]] = true;
                bestCount = tail;
            }
        }
        return best;
    }

    private static int parkIndex(int worldX, int worldZ) {
        return Math.floorMod(worldZ, 16) * 16 + Math.floorMod(worldX, 16);
    }

    static int parkSiteColumnCount(MegacityLayout activeLayout, int chunkX, int chunkZ) {
        return parkSitePlan(activeLayout, chunkX, chunkZ).parkColumnCount();
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
            case FUKUOKA_TRANSIT_LANES -> distanceToGrid(v + 0.08 * u, 152) < 4.8
                    || distanceToGrid(u - 0.16 * v, 214) < 3.2
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
            case NORDIC_WATERFRONTS -> Math.abs(foldLine(
                    v + 32.0 * Math.sin(u / 138.0), 236.0)) < 4.0
                    ? RoadClass.LOCAL_STREET : null;
            case MOSCOW_RADIALS -> Math.abs(foldLine(radius, 248.0)) < 4.8
                    || angularDistance(angle, Math.rint(angle * 5.0 / Math.PI)
                            * Math.PI / 5.0) * Math.max(radius, 52.0) < 5.2
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case BOSTON_ROW_STREETS -> distanceToGrid(u, 104) < 3.6
                    || distanceToGrid(v + 0.12 * u, 148) < 3.2
                    ? RoadClass.LOCAL_STREET : null;
            case BANGKOK_SOIS -> Math.abs(foldLine(
                    u + 18.0 * Math.sin(v / 49.0), 72.0)) < 2.2
                    ? RoadClass.SERVICE_ALLEY : null;
            case SINGAPORE_SUPERBLOCKS -> {
                double superblock = Math.hypot(foldLine(u, 286.0), foldLine(v, 254.0));
                yield Math.abs(superblock - 94.0) < 4.0
                        ? RoadClass.LOCAL_STREET : null;
            }
            case AMSTERDAM_CANAL_RINGS -> {
                double canalRing = Math.abs(foldLine(radius, 186.0));
                if (canalRing < 4.5) yield RoadClass.CANAL;
                yield angularDistance(angle, Math.rint(angle * 4.0 / Math.PI)
                        * Math.PI / 4.0) * Math.max(radius, 48.0) < 3.2
                        ? RoadClass.LOCAL_STREET : null;
            }
            case IBERIAN_BOULEVARDS -> angularDistance(
                    angle + 0.10 * Math.sin(radius / 92.0),
                    Math.rint(angle * 6.0 / Math.PI) * Math.PI / 6.0)
                    * Math.max(radius, 48.0) < 4.2
                    ? RoadClass.DISTRICT_BOULEVARD : null;
            case AUSTIN_GRID -> distanceToGrid(u, 176) < 4.2
                    || distanceToGrid(v, 132) < 3.6 ? RoadClass.LOCAL_STREET : null;
            case DUBAI_AXES -> Math.abs(foldLine(v + u * 0.28, 318.0)) < 6.0
                    || angularDistance(angle, Math.rint(angle * 3.0 / Math.PI)
                            * Math.PI / 3.0) * Math.max(radius, 64.0) < 4.5
                    ? RoadClass.DISTRICT_BOULEVARD : null;
        };
    }

    private static double foldLine(double value, double period) {
        double mod = floorMod(value + period * 0.5, (int) period);
        return mod - period * 0.5;
    }

    private static int terrainHeight(
            MegacityLayout activeLayout,
            MegacityLayout.Location location,
            int x,
            int z,
            RoadClass road) {
        if (road == RoadClass.OCEAN || road == RoadClass.PORTSHIP) {
            return UCorpPortGeneration.OCEAN_FLOOR_MIN_Y
                    + floorMod((int) mix(activeLayout.seed() ^ 0x554F4345414E464CL, x, z), 6);
        }
        if (road == RoadClass.CANAL || road == RoadClass.HARBOR) {
            return WATER_Y - 1;
        }
        if (road == RoadClass.BORDER_CLIFF) {
            return DistrictWorldFeatures.borderHillHeight(activeLayout.seed(), x, z);
        }
        if (road == RoadClass.FARM) {
            return CITY_GROUND_Y;
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

    private static boolean alleyAt(
            MegacityLayout activeLayout,
            int worldX,
            int worldZ,
            Map<Long, AlleyMaze.Plan> cache) {
        int sectorX = Math.floorDiv(worldX, AlleyMaze.SIZE);
        int sectorZ = Math.floorDiv(worldZ, AlleyMaze.SIZE);
        long key = ChunkPos.pack(sectorX, sectorZ);
        AlleyMaze.Plan plan = cache.computeIfAbsent(
                key, ignored -> AlleyMaze.generate(activeLayout.seed(), sectorX, sectorZ));
        return plan.isAlley(Math.floorMod(worldX, AlleyMaze.SIZE), Math.floorMod(worldZ, AlleyMaze.SIZE));
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
    static String generatorFingerprint() { return GENERATOR_FINGERPRINT; }
    public static District districtAt(int worldX, int worldZ) {
        return UCorpPortGeneration.plan(layout).isManagedAt(worldX, worldZ)
                ? District.U_CORP
                : layout.locate(worldX, worldZ).district();
    }
    static MegacityLayout.Location effectiveLocation(UrbanSample sample) {
        MegacityLayout.Location raw = sample.location();
        if (raw.district() == sample.district() && raw.zone() == sample.zone()) {
            return raw;
        }
        MegacityLayout.Node primary = layout.node(sample.district());
        MegacityLayout.Node secondary = raw.primary().district() == sample.district()
                ? raw.secondary()
                : raw.primary();
        return new MegacityLayout.Location(
                primary,
                secondary,
                sample.zone(),
                raw.normalizedDistance(),
                raw.boundaryGap(),
                raw.nearestConnection(),
                raw.connectionDistance());
    }
    public static MegacityLayout.Location effectiveLocationAt(int worldX, int worldZ) {
        return effectiveLocation(sample(worldX, worldZ));
    }
    public static RoadClass roadAt(int worldX, int worldZ) { return sample(worldX, worldZ).roadClass(); }
    public static boolean isUsableArnisChunk(ServerLevel level, int worldX, int worldZ) {
        return isMegacityWorld(level)
                && layoutWorldSeed == level.getSeed()
                && usableArnisPlacement(Math.floorDiv(worldX, 16), Math.floorDiv(worldZ, 16))
                        .isPresent();
    }
    public static boolean isCivilianPedestrianArea(ServerLevel level, int worldX, int worldZ) {
        if (!isMegacityWorld(level) || layoutWorldSeed != level.getSeed()) return false;
        return isCivilianPedestrianTarget(
                sample(worldX, worldZ).roadClass(),
                isUsableArnisChunk(level, worldX, worldZ));
    }
    public static boolean isCivilianPedestrianTarget(RoadClass roadClass,
                                                       boolean usableArnisChunk) {
        if (roadClass == RoadClass.PARK) return true;
        if (!usableArnisChunk) return false;
        return switch (roadClass) {
            case NONE, CENTRAL_PLAZA, DISTRICT_BOULEVARD, LOCAL_STREET, SERVICE_ALLEY -> true;
            default -> false;
        };
    }
    public static boolean isHighwayRoadClass(RoadClass roadClass) {
        return roadClass == RoadClass.INTERDISTRICT_ROAD
                || roadClass == RoadClass.BRIDGE
                || roadClass == RoadClass.ELEVATED_RAIL;
    }
    public static boolean isHighwayAt(ServerLevel level, int worldX, int worldZ) {
        if (!isMegacityWorld(level)) return false;
        MegacityLayout activeLayout = layoutWorldSeed == level.getSeed()
                ? layout : MegacityLayout.create(level.getSeed());
        return isHighwayAt(activeLayout, worldX, worldZ);
    }
    public static boolean isHighwayAt(MegacityLayout activeLayout, int worldX, int worldZ) {
        return activeLayout.locate(worldX, worldZ).onConnection();
    }
    public static boolean isInsideCity(int worldX, int worldZ) {
        return layout.locate(worldX, worldZ).insideCity()
                || UCorpPortGeneration.plan(layout).isManagedAt(worldX, worldZ)
                || PerimeterOutskirts.plan(layout).featureAt(worldX, worldZ)
                        != PerimeterOutskirts.Feature.NONE;
    }
    public static boolean isInsideCity(ServerLevel level, int worldX, int worldZ) {
        if (!isMegacityWorld(level)) return false;
        MegacityLayout activeLayout = layoutWorldSeed == level.getSeed()
                ? layout : MegacityLayout.create(level.getSeed());
        return activeLayout.locate(worldX, worldZ).insideCity()
                || UCorpPortGeneration.plan(activeLayout).isManagedAt(worldX, worldZ)
                || PerimeterOutskirts.plan(activeLayout).featureAt(worldX, worldZ)
                        != PerimeterOutskirts.Feature.NONE;
    }
    public static boolean isEnabled() { return enabled; }
    static boolean isGenerated(ChunkPos chunk) { return GENERATED.contains(chunk.pack()); }
    public static int pendingChunks() { return PENDING.size(); }
    public static int generatedChunks() { return GENERATED.size(); }
}
