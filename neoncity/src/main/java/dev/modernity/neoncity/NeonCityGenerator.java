package dev.modernity.neoncity;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.storage.LevelData;
import org.slf4j.Logger;

/**
 * Infinite deterministic cyberpunk city construction for the dedicated preset.
 *
 * <p>The city is not a shuffled set of isolated towers. Jittered metropolitan
 * centres establish large-scale identity; curved radial avenues and warped
 * ring roads cross cultural wedges; rotated local streets and matching DFS
 * alley portals provide the fine grain. Every building is computed in global
 * coordinates, so shells, floors, roads, and bridges cross chunk borders
 * without seams.</p>
 */
public final class NeonCityGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String NAMESPACE = "neoncity";
    public static final String GENERATOR_FINGERPRINT =
            "neon-megacity-v2-dense-facades-continuous-transit-20260728";
    public static final int GROUND_Y = 0;
    public static final int CITY_SPACING = 1536;
    public static final int ENQUEUE_RADIUS_CHUNKS = 7;
    public static final int SPAWN_PREWARM_RADIUS_CHUNKS = 1;
    public static final int MAX_PENDING_CHUNKS = 768;

    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final long CITY_SEED = 0x4E454F4E43495459L;
    private static final BlockPos DEFAULT_SPAWN = new BlockPos(0, 2, 0);
    private static final int MAX_BUILD_Y = 304;

    private static final ArrayDeque<ChunkPos> PENDING = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static final Set<Long> GENERATED = new HashSet<>();
    private static NeonCitySavedData savedData;
    private static boolean enabled;

    private NeonCityGenerator() {}

    public enum District {
        CROWN_CORE("Crown Core", 48, 132, 292, 0),
        KAIROCHO("Kairocho", 28, 34, 108, 18),
        LONGWEI_HARBOR("Longwei Harbor", 50, 82, 238, 42),
        HANEUL_TECH("Haneul Tech Quarter", 42, 72, 208, -31),
        FOUNDRY_BELT("Foundry Belt", 58, 18, 58, 8),
        UNDERSTACKS("Understacks", 24, 22, 82, -12);

        private final String label;
        private final int parcelSize;
        private final int minHeight;
        private final int maxHeight;
        private final int orientationDegrees;

        District(String label, int parcelSize, int minHeight, int maxHeight,
                 int orientationDegrees) {
            this.label = label;
            this.parcelSize = parcelSize;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.orientationDegrees = orientationDegrees;
        }

        public String label() { return label; }
        public int parcelSize() { return parcelSize; }
        public int minHeight() { return minHeight; }
        public int maxHeight() { return maxHeight; }
        public int orientationDegrees() { return orientationDegrees; }
    }

    public enum RoadClass {
        NONE,
        CENTRAL_PLAZA,
        ARTERIAL,
        LOCAL_STREET,
        SERVICE_ALLEY,
        EXPRESSWAY,
        ELEVATED_RAIL,
        CANAL,
        PARK
    }

    public record CityCenter(int tileX, int tileZ, int x, int z, long identity) {}

    /** Pure diagnostic sample used by tests, preview tooling, and runtime. */
    public record UrbanSample(
            CityCenter center,
            District district,
            RoadClass roadClass,
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

    private record LocalCoordinates(double u, double v) {}

    public static boolean initialize(ServerLevel level) {
        clearTransientState();
        savedData = null;
        if (!isNeonCityWorld(level)) {
            LOGGER.info("[NeonCity] dedicated black/cyan flat signature not detected; disabled");
            return false;
        }
        savedData = level.getDataStorage().computeIfAbsent(NeonCitySavedData.TYPE);
        if (!savedData.isCompatible(GENERATOR_FINGERPRINT)) {
            LOGGER.error(
                    "[NeonCity] generator fingerprint mismatch (world={}, bundled={}); disabled",
                    savedData.generatorFingerprint(), GENERATOR_FINGERPRINT);
            return false;
        }
        GENERATED.addAll(savedData.snapshot());
        enabled = true;
        LOGGER.info("[NeonCity] restored {} generated chunks for {}",
                GENERATED.size(), GENERATOR_FINGERPRINT);
        return true;
    }

    public static void reset() {
        clearTransientState();
        savedData = null;
    }

    private static void clearTransientState() {
        PENDING.clear();
        QUEUED.clear();
        GENERATED.clear();
        enabled = false;
    }

    /** Only the dedicated two-layer black/cyan flat preset may be modified. */
    public static boolean isNeonCityWorld(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof FlatLevelSource flat)) return false;
        List<BlockState> layers = flat.settings().getLayers();
        BlockState last = null;
        BlockState beforeLast = null;
        int nonAirLayers = 0;
        for (BlockState state : layers) {
            if (state == null || state.isAir()) continue;
            nonAirLayers++;
            beforeLast = last;
            last = state;
        }
        return nonAirLayers == 2 && last != null && beforeLast != null
                && last.is(Blocks.CONCRETE.pick(DyeColor.CYAN))
                && beforeLast.is(Blocks.CONCRETE.pick(DyeColor.BLACK));
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

    /** Generate at most one already-loaded chunk per tick. */
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

    /** Synchronously prepares only a 3x3 spawn neighbourhood. */
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
                if (GENERATED.contains(key)) continue;
                if (generateChunk(level, chunk)) {
                    recordGenerated(key);
                    placed++;
                }
            }
        }
        if (GENERATED.contains(ChunkPos.ZERO.pack())) {
            level.setRespawnData(LevelData.RespawnData.of(
                    level.dimension(), DEFAULT_SPAWN, 0.0F, 0.0F));
            LOGGER.info("[NeonCity] spawn plaza prepared at {}", DEFAULT_SPAWN);
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
            Map<Long, AlleyMaze.Plan> alleyPlans = new HashMap<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int minX = chunk.getMinBlockX();
            int minZ = chunk.getMinBlockZ();
            UrbanSample[][] samples = new UrbanSample[18][18];
            for (int sampleZ = 0; sampleZ < 18; sampleZ++) {
                for (int sampleX = 0; sampleX < 18; sampleX++) {
                    samples[sampleZ][sampleX] = sample(
                            minX + sampleX - 1,
                            minZ + sampleZ - 1,
                            alleyPlans);
                }
            }
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int x = minX + localX;
                    int z = minZ + localZ;
                    UrbanSample sample = samples[localZ + 1][localX + 1];
                    boolean facadeBoundary = !sameBuilding(
                            sample, samples[localZ][localX + 1])
                            || !sameBuilding(sample, samples[localZ + 2][localX + 1])
                            || !sameBuilding(sample, samples[localZ + 1][localX])
                            || !sameBuilding(sample, samples[localZ + 1][localX + 2]);
                    buildColumn(level, pos, x, z, sample, facadeBoundary);
                }
            }
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("[NeonCity] failed generating chunk {}", chunk, exception);
            return false;
        }
    }

    private static void buildColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                    int x, int z, UrbanSample sample,
                                    boolean facadeBoundary) {
        // A shallow solid city deck keeps generation bounded while preserving
        // an undercity void below for later transit/utility expansion.
        for (int y = GROUND_Y - 4; y < GROUND_Y; y++) {
            set(level, pos, x, y, z, y == GROUND_Y - 4
                    ? Blocks.REINFORCED_DEEPSLATE.defaultBlockState()
                    : Blocks.DEEPSLATE.defaultBlockState());
        }

        BlockState surface = surface(sample, x, z);
        set(level, pos, x, GROUND_Y, z, surface);
        decorateTransit(level, pos, x, z, sample);

        if (!sample.insideFootprint() || sample.buildingHeight() <= 0
                || sample.roadClass() != RoadClass.NONE) {
            decorateOpenGround(level, pos, x, z, sample);
            return;
        }
        buildBuilding(level, pos, x, z, sample, facadeBoundary);
    }

    private static BlockState surface(UrbanSample sample, int x, int z) {
        return switch (sample.roadClass()) {
            case CENTRAL_PLAZA -> floorMod(x, 8) == 0 || floorMod(z, 8) == 0
                    ? concrete(DyeColor.CYAN) : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case ARTERIAL, EXPRESSWAY -> laneMark(x, z)
                    ? concrete(DyeColor.YELLOW) : concrete(DyeColor.BLACK);
            case LOCAL_STREET -> laneMark(x * 3, z * 5)
                    ? concrete(DyeColor.LIGHT_GRAY) : concrete(DyeColor.GRAY);
            case SERVICE_ALLEY -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case ELEVATED_RAIL -> Blocks.SMOOTH_STONE.defaultBlockState();
            case CANAL -> Blocks.WATER.defaultBlockState();
            case PARK -> floorMod(x + z, 7) == 0
                    ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            case NONE -> sidewalk(sample.district());
        };
    }

    private static boolean laneMark(int x, int z) {
        return floorMod(x + z, 13) <= 1 && floorMod(x - z, 9) == 0;
    }

    private static void decorateOpenGround(ServerLevel level, BlockPos.MutableBlockPos pos,
                                           int x, int z, UrbanSample sample) {
        if (sample.roadClass() == RoadClass.CENTRAL_PLAZA
                && (Math.abs(x) == 22 || Math.abs(z) == 22)
                && floorMod(x + z, 7) == 0) {
            set(level, pos, x, 1, z, Blocks.SEA_LANTERN.defaultBlockState());
        }
        if (sample.roadClass() == RoadClass.PARK && floorMod(x * 17 + z * 31, 97) == 0) {
            for (int y = 1; y <= 4; y++) {
                set(level, pos, x, y, z, Blocks.DARK_OAK_LOG.defaultBlockState());
            }
            set(level, pos, x, 5, z, Blocks.AZALEA_LEAVES.defaultBlockState());
        }
        if (sample.roadClass() == RoadClass.SERVICE_ALLEY
                && floorMod(x * 11 + z * 7, 89) == 0) {
            set(level, pos, x, 1, z, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        }
    }

    private static void decorateTransit(ServerLevel level, BlockPos.MutableBlockPos pos,
                                        int x, int z, UrbanSample sample) {
        double dx = x - sample.center().x();
        double dz = z - sample.center().z();
        double radius = Math.hypot(dx, dz);
        boolean expressway = isExpressway(
                sample.center(), dx, dz, radius, Math.atan2(dz, dx));
        boolean rail = isRail(sample.center(), dx, dz);
        if (expressway) {
            boolean edge = !isExpressway(x + 1, z) || !isExpressway(x - 1, z)
                    || !isExpressway(x, z + 1) || !isExpressway(x, z - 1);
            if (floorMod(x * 31 + z * 17, 41) == 0) {
                for (int y = 1; y <= 21; y++) {
                    set(level, pos, x, y, z, Blocks.POLISHED_BASALT.defaultBlockState());
                }
            }
            set(level, pos, x, 22, z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            set(level, pos, x, 23, z, laneMark(x, z)
                    ? concrete(DyeColor.YELLOW) : concrete(DyeColor.BLACK));
            if (edge) set(level, pos, x, 24, z, Blocks.IRON_BARS.defaultBlockState());
        }
        if (rail) {
            if (floorMod(x + 2 * z, 29) == 0) {
                for (int y = 1; y <= 31; y++) {
                    set(level, pos, x, y, z, Blocks.IRON_BLOCK.defaultBlockState());
                }
            }
            set(level, pos, x, 32, z, Blocks.SMOOTH_STONE.defaultBlockState());
            BlockState railState = floorMod(x + z, 3) == 0
                    ? Blocks.POWERED_RAIL.defaultBlockState()
                            .setValue(PoweredRailBlock.SHAPE, RailShape.EAST_WEST)
                    : Blocks.RAIL.defaultBlockState()
                            .setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
            set(level, pos, x, 33, z, railState);
        }
    }

    private static void buildBuilding(ServerLevel level, BlockPos.MutableBlockPos pos,
                                      int x, int z, UrbanSample sample,
                                      boolean facadeBoundary) {
        Palette palette = palette(sample.district());
        int top = Math.min(MAX_BUILD_Y, sample.buildingHeight());
        for (int y = 1; y <= top; y++) {
            int tier = tierInset(sample.district(), y, top);
            if (!insideTier(sample, tier)) continue;
            boolean boundary = facadeBoundary || tierBoundary(sample, tier);
            boolean floor = y == 1 || y == top || floorMod(y - 1, 5) == 0;
            if (!boundary && !floor) continue;

            BlockState state;
            if (y == top) {
                state = palette.roof();
            } else if (!boundary) {
                state = palette.secondary();
            } else if (isNeonStrip(sample, y)) {
                state = palette.accent();
            } else if (isWindow(sample, y)) {
                state = palette.glass();
            } else if (isFrame(sample, y)) {
                state = palette.frame();
            } else {
                state = palette.wall();
            }
            set(level, pos, x, y, z, state);
        }

        // Rooftop light crowns give distant skylines a legible silhouette.
        if (top >= 70 && floorMod((int) sample.parcelHash(), 3) == 0
                && nearParcelCenter(sample)) {
            for (int y = top + 1; y <= Math.min(MAX_BUILD_Y, top + 8); y++) {
                set(level, pos, x, y, z, palette.accent());
            }
        }
    }

    private static int tierInset(District district, int y, int top) {
        if (district == District.CROWN_CORE) {
            if (y > top * 4 / 5) return 6;
            if (y > top * 3 / 5) return 3;
        } else if (district == District.LONGWEI_HARBOR) {
            return Math.min(7, y / 42);
        } else if (district == District.HANEUL_TECH && y > top * 3 / 4) {
            return 4;
        } else if (district == District.KAIROCHO && y > top - 7) {
            return Math.min(4, top - y + 1);
        }
        return 0;
    }

    private static boolean insideTier(UrbanSample sample, int extraInset) {
        double min = footprintInset(sample.parcelHash(), sample.district()) + extraInset;
        double max = sample.parcelSize() - min;
        if (sample.parcelLocalU() < min || sample.parcelLocalU() >= max
                || sample.parcelLocalV() < min || sample.parcelLocalV() >= max) return false;
        int shape = floorMod((int) (sample.parcelHash() >>> 24), 5);
        if (shape == 1) {
            return !(sample.parcelLocalU() > sample.parcelSize() * 0.58
                    && sample.parcelLocalV() > sample.parcelSize() * 0.58);
        }
        if (shape == 2 && sample.district() != District.CROWN_CORE) {
            return !(sample.parcelLocalU() < sample.parcelSize() * 0.38
                    && sample.parcelLocalV() > sample.parcelSize() * 0.62);
        }
        return true;
    }

    private static boolean tierBoundary(UrbanSample sample, int extraInset) {
        if (!insideTier(sample, extraInset)) return false;
        double min = footprintInset(sample.parcelHash(), sample.district()) + extraInset;
        double max = sample.parcelSize() - min;
        double u = sample.parcelLocalU();
        double v = sample.parcelLocalV();
        if (u < min + 1.35 || u >= max - 1.35 || v < min + 1.35 || v >= max - 1.35) {
            return true;
        }
        // Boundaries of L-shaped courtyard cuts.
        int shape = floorMod((int) (sample.parcelHash() >>> 24), 5);
        if (shape == 1) {
            double cut = sample.parcelSize() * 0.58;
            return (Math.abs(u - cut) < 1.25 && v >= cut)
                    || (Math.abs(v - cut) < 1.25 && u >= cut);
        }
        return false;
    }

    private static boolean isWindow(UrbanSample sample, int y) {
        if (floorMod(y, 5) < 2) return false;
        int rhythm = switch (sample.district()) {
            case KAIROCHO, UNDERSTACKS -> 4;
            case LONGWEI_HARBOR -> 6;
            case HANEUL_TECH -> 7;
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
        int spacing = sample.district() == District.KAIROCHO ? 13 : 23;
        return floorMod(y + (int) sample.parcelHash(), spacing) == 0
                && (floorMod((int) Math.floor(sample.parcelLocalU()), 5) == 0
                || floorMod((int) Math.floor(sample.parcelLocalV()), 5) == 0);
    }

    private static boolean nearParcelCenter(UrbanSample sample) {
        double center = sample.parcelSize() * 0.5;
        return Math.abs(sample.parcelLocalU() - center) < 0.75
                && Math.abs(sample.parcelLocalV() - center) < 0.75;
    }

    private static Palette palette(District district) {
        return switch (district) {
            case CROWN_CORE -> new Palette(
                    Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
                    concrete(DyeColor.LIGHT_GRAY),
                    Blocks.TINTED_GLASS.defaultBlockState(),
                    Blocks.SEA_LANTERN.defaultBlockState(),
                    Blocks.IRON_BLOCK.defaultBlockState(),
                    concrete(DyeColor.BLACK));
            case KAIROCHO -> new Palette(
                    Blocks.DEEPSLATE_TILES.defaultBlockState(),
                    Blocks.DARK_OAK_PLANKS.defaultBlockState(),
                    stainedGlass(DyeColor.RED),
                    Blocks.OCHRE_FROGLIGHT.defaultBlockState(),
                    concrete(DyeColor.RED),
                    Blocks.POLISHED_BLACKSTONE.defaultBlockState());
            case LONGWEI_HARBOR -> new Palette(
                    dyedTerracotta(DyeColor.RED),
                    cutCopper(WeatheringCopper.WeatherState.UNAFFECTED),
                    stainedGlass(DyeColor.CYAN),
                    Blocks.SHROOMLIGHT.defaultBlockState(),
                    concrete(DyeColor.YELLOW),
                    cutCopper(WeatheringCopper.WeatherState.WEATHERED));
            case HANEUL_TECH -> new Palette(
                    concrete(DyeColor.WHITE),
                    Blocks.SMOOTH_QUARTZ.defaultBlockState(),
                    stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.SEA_LANTERN.defaultBlockState(),
                    concrete(DyeColor.PURPLE),
                    concrete(DyeColor.LIGHT_GRAY));
            case FOUNDRY_BELT -> new Palette(
                    Blocks.TUFF_BRICKS.defaultBlockState(),
                    cutCopper(WeatheringCopper.WeatherState.EXPOSED),
                    stainedGlass(DyeColor.ORANGE),
                    Blocks.OCHRE_FROGLIGHT.defaultBlockState(),
                    Blocks.IRON_BLOCK.defaultBlockState(),
                    cutCopper(WeatheringCopper.WeatherState.WEATHERED));
            case UNDERSTACKS -> new Palette(
                    Blocks.MUD_BRICKS.defaultBlockState(),
                    copper(WeatheringCopper.WeatherState.WEATHERED),
                    stainedGlass(DyeColor.MAGENTA),
                    Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(),
                    concrete(DyeColor.BROWN),
                    Blocks.DEEPSLATE_TILES.defaultBlockState());
        };
    }

    private static BlockState sidewalk(District district) {
        return switch (district) {
            case KAIROCHO -> Blocks.POLISHED_BLACKSTONE.defaultBlockState();
            case LONGWEI_HARBOR -> cutCopper(WeatheringCopper.WeatherState.UNAFFECTED);
            case HANEUL_TECH -> Blocks.SMOOTH_STONE.defaultBlockState();
            case FOUNDRY_BELT -> Blocks.TUFF_BRICKS.defaultBlockState();
            case UNDERSTACKS -> Blocks.MUD_BRICKS.defaultBlockState();
            default -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        };
    }

    private static void set(ServerLevel level, BlockPos.MutableBlockPos pos,
                            int x, int y, int z, BlockState state) {
        level.setBlock(pos.set(x, y, z), state, PLACE_FLAGS);
    }

    public static UrbanSample sample(int worldX, int worldZ) {
        return sample(worldX, worldZ, new HashMap<>());
    }

    private static UrbanSample sample(int worldX, int worldZ,
                                      Map<Long, AlleyMaze.Plan> alleyPlans) {
        CityCenter center = nearestCenter(worldX, worldZ);
        double dx = worldX - center.x();
        double dz = worldZ - center.z();
        double radius = Math.hypot(dx, dz);
        double angle = Math.atan2(dz, dx);
        District district = district(center, radius, angle, worldX, worldZ);
        RoadClass road = roadClass(center, district, dx, dz, radius, angle, worldX, worldZ);

        LocalCoordinates local = parcelCoordinates(district, worldX, worldZ);
        int parcelSize = district.parcelSize();
        int parcelX = floorDiv(local.u(), parcelSize);
        int parcelZ = floorDiv(local.v(), parcelSize);
        double localU = floorMod(local.u(), parcelSize);
        double localV = floorMod(local.v(), parcelSize);
        long parcelHash = mix(CITY_SEED ^ center.identity(), parcelX, parcelZ);
        int height = buildingHeight(district, radius, parcelHash);
        UrbanSample provisional = new UrbanSample(
                center, district, road, height, parcelSize, false,
                parcelX, parcelZ, localU, localV, parcelHash);

        if (road == RoadClass.NONE && alleyAt(worldX, worldZ, alleyPlans)) {
            road = RoadClass.SERVICE_ALLEY;
            provisional = new UrbanSample(
                    center, district, road, 0, parcelSize, false,
                    parcelX, parcelZ, localU, localV, parcelHash);
        }
        boolean footprint = road == RoadClass.NONE && insideTier(provisional, 0);
        return new UrbanSample(
                center, district, road, footprint ? height : 0, parcelSize, footprint,
                parcelX, parcelZ, localU, localV, parcelHash);
    }

    public static CityCenter nearestCenter(int worldX, int worldZ) {
        int baseTileX = Math.floorDiv(worldX, CITY_SPACING);
        int baseTileZ = Math.floorDiv(worldZ, CITY_SPACING);
        CityCenter nearest = null;
        long bestDistance = Long.MAX_VALUE;
        for (int tileZ = baseTileZ - 1; tileZ <= baseTileZ + 1; tileZ++) {
            for (int tileX = baseTileX - 1; tileX <= baseTileX + 1; tileX++) {
                long identity = mix(CITY_SEED, tileX, tileZ);
                int jitterX = tileX == 0 && tileZ == 0
                        ? 0 : floorMod((int) identity, 401) - 200;
                int jitterZ = tileX == 0 && tileZ == 0
                        ? 0 : floorMod((int) (identity >>> 32), 401) - 200;
                int centerX = tileX * CITY_SPACING + jitterX;
                int centerZ = tileZ * CITY_SPACING + jitterZ;
                long dx = (long) worldX - centerX;
                long dz = (long) worldZ - centerZ;
                long distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = new CityCenter(tileX, tileZ, centerX, centerZ, identity);
                }
            }
        }
        return nearest;
    }

    public static District districtAt(int worldX, int worldZ) {
        CityCenter center = nearestCenter(worldX, worldZ);
        double dx = worldX - center.x();
        double dz = worldZ - center.z();
        return district(center, Math.hypot(dx, dz), Math.atan2(dz, dx), worldX, worldZ);
    }

    private static District district(CityCenter center, double radius, double angle,
                                     int worldX, int worldZ) {
        if (radius < 188.0 + 18.0 * Math.sin(angle * 5.0 + center.identity())) {
            return District.CROWN_CORE;
        }
        if (radius > 635.0 + 62.0 * Math.sin(angle * 3.0 + center.identity() * 0.001)) {
            return (mix(center.identity(), worldX >> 7, worldZ >> 7) & 1L) == 0
                    ? District.FOUNDRY_BELT : District.UNDERSTACKS;
        }
        double phase = ((center.identity() >>> 8) & 1023L) / 1024.0 * Math.PI * 2.0;
        double warped = normalizeAngle(angle + phase + 0.16 * Math.sin(radius / 97.0));
        int wedge = (int) Math.floor(warped / (Math.PI * 2.0 / 5.0));
        return switch (wedge) {
            case 0 -> District.KAIROCHO;
            case 1 -> District.HANEUL_TECH;
            case 2 -> District.LONGWEI_HARBOR;
            case 3 -> District.UNDERSTACKS;
            default -> District.FOUNDRY_BELT;
        };
    }

    public static RoadClass roadAt(int worldX, int worldZ) {
        return sample(worldX, worldZ).roadClass();
    }

    private static RoadClass roadClass(CityCenter center, District district,
                                       double dx, double dz, double radius, double angle,
                                       int worldX, int worldZ) {
        if (radius < 27.0) return RoadClass.CENTRAL_PLAZA;
        if (isRail(center, dx, dz)) return RoadClass.ELEVATED_RAIL;
        if (isExpressway(center, dx, dz, radius, angle)) return RoadClass.EXPRESSWAY;

        double innerRing = 246.0 + 22.0 * Math.sin(angle * 4.0 + center.identity() * 0.0001);
        if (Math.abs(radius - innerRing) < 9.0) return RoadClass.ARTERIAL;

        int spokeCount = 7;
        double spokeAngle = Math.PI * 2.0 / spokeCount;
        double curvedAngle = normalizeAngle(angle
                + 0.18 * Math.sin(radius / 105.0 + center.identity() * 0.00003));
        double spokeDistance = angularDistance(curvedAngle, Math.rint(curvedAngle / spokeAngle) * spokeAngle)
                * Math.max(48.0, radius);
        if (spokeDistance < 8.5) return RoadClass.ARTERIAL;

        if (district == District.LONGWEI_HARBOR) {
            double canal = dz - 0.29 * dx - 58.0 * Math.sin(dx / 137.0);
            if (Math.abs(canal) < 6.0 && radius > 260.0) return RoadClass.CANAL;
        }

        LocalCoordinates local = parcelCoordinates(district, worldX, worldZ);
        int parcel = district.parcelSize();
        double lineU = distanceToGrid(local.u(), parcel);
        double lineV = distanceToGrid(local.v(), parcel);
        double streetWidth = district == District.KAIROCHO ? 3.0
                : district == District.FOUNDRY_BELT ? 5.5 : 4.0;
        if (lineU < streetWidth || lineV < streetWidth) return RoadClass.LOCAL_STREET;

        long patch = mix(center.identity() ^ 0x5041524B53414C54L, worldX >> 6, worldZ >> 6);
        if (radius > 300.0 && floorMod((int) patch, 41) == 0) return RoadClass.PARK;
        return RoadClass.NONE;
    }

    public static boolean isExpressway(int worldX, int worldZ) {
        CityCenter center = nearestCenter(worldX, worldZ);
        double dx = worldX - center.x();
        double dz = worldZ - center.z();
        double radius = Math.hypot(dx, dz);
        return isExpressway(center, dx, dz, radius, Math.atan2(dz, dx));
    }

    private static boolean isExpressway(CityCenter center, double dx, double dz,
                                        double radius, double angle) {
        double outerRing = 486.0
                + 54.0 * Math.sin(angle * 2.0 + center.identity() * 0.00002)
                + 18.0 * Math.sin(angle * 7.0);
        return Math.abs(radius - outerRing) < 10.5;
    }

    private static boolean isRail(CityCenter center, double dx, double dz) {
        double phase = (center.identity() & 255L) / 255.0 * Math.PI * 2.0;
        double rail = dz - 0.34 * dx - 72.0 * Math.sin(dx / 155.0 + phase);
        return Math.abs(rail) < 3.2;
    }

    private static LocalCoordinates parcelCoordinates(District district, int x, int z) {
        double radians = Math.toRadians(district.orientationDegrees());
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double u = x * cosine + z * sine;
        double v = -x * sine + z * cosine;
        // Two low-frequency warps break rigid grids while keeping streets and
        // parcel walls derived from the exact same continuous coordinates.
        double warpedU = u + 13.0 * Math.sin(v / 79.0) + 6.0 * Math.sin((u + v) / 151.0);
        double warpedV = v + 11.0 * Math.sin(u / 91.0) + 5.0 * Math.sin((u - v) / 133.0);
        return new LocalCoordinates(warpedU, warpedV);
    }

    private static boolean alleyAt(int worldX, int worldZ,
                                   Map<Long, AlleyMaze.Plan> cache) {
        int sectorX = Math.floorDiv(worldX, AlleyMaze.SIZE);
        int sectorZ = Math.floorDiv(worldZ, AlleyMaze.SIZE);
        long key = ChunkPos.pack(sectorX, sectorZ);
        AlleyMaze.Plan plan = cache.computeIfAbsent(
                key, ignored -> AlleyMaze.generate(CITY_SEED, sectorX, sectorZ));
        return plan.isAlley(
                Math.floorMod(worldX, AlleyMaze.SIZE),
                Math.floorMod(worldZ, AlleyMaze.SIZE));
    }

    private static int buildingHeight(District district, double radius, long hash) {
        int range = district.maxHeight() - district.minHeight() + 1;
        int height = district.minHeight() + floorMod((int) hash, range);
        if (district == District.CROWN_CORE) {
            height += Math.max(0, (int) ((210.0 - radius) * 0.32));
        } else if (district == District.LONGWEI_HARBOR
                && floorMod((int) (hash >>> 17), 9) == 0) {
            height += 48;
        } else if (district == District.HANEUL_TECH) {
            height = (height / 5) * 5;
        }
        return Math.min(MAX_BUILD_Y, height);
    }

    private static int footprintInset(long hash, District district) {
        int base = switch (district) {
            case KAIROCHO, UNDERSTACKS -> 3;
            case FOUNDRY_BELT -> 5;
            default -> 4;
        };
        return base + floorMod((int) (hash >>> 12), 2);
    }

    private static boolean sameBuilding(UrbanSample first, UrbanSample second) {
        return first.insideFootprint()
                && second.insideFootprint()
                && first.center().identity() == second.center().identity()
                && first.district() == second.district()
                && first.parcelX() == second.parcelX()
                && first.parcelZ() == second.parcelZ();
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

    private static BlockState copper(WeatheringCopper.WeatherState state) {
        return Blocks.COPPER_BLOCK.waxed().pick(state).defaultBlockState();
    }

    private static long mix(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L
                ^ (long) z * 0xC2B2AE3D27D4EB4FL;
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    public static boolean isEnabled() { return enabled; }
    public static int pendingChunks() { return PENDING.size(); }
    public static int generatedChunks() { return GENERATED.size(); }
}
