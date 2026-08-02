package com.example.cyberdeck.city;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Generates a Cyberpunk city once per world, on the flat "Cyberpunk City" world type.
 *
 * <p>The city is a {@link #CITY_DIM}x{@link #CITY_DIM} grid of <em>city blocks</em>. Each city block
 * holds a {@link #BLOCK_DIM}x{@link #BLOCK_DIM} cluster of buildings separated by narrow
 * <em>alleyways</em>, and the city blocks themselves are separated by wider <em>roads</em> paved in
 * grey concrete. Buildings are chosen at random from the extracted templates so the skyline varies,
 * and every city block recolours its buildings' dyed blocks by a per-block hue shift (see
 * {@link ColorSwapProcessor}) so repeated buildings still read as unique.
 *
 * <p>The city is written exactly once; a {@link CityData} flag on the overworld's persistent storage
 * guards against rebuilding on subsequent loads.
 *
 * <h2>Performance</h2>
 * Work is spread across ticks: one job (a building stamp or a road strip) is processed per level tick
 * via a queue, and every block is written with {@link Block#UPDATE_SKIP_ALL_SIDEEFFECTS} plus a
 * known-shape place setting, so world load stays responsive and chunks stream in progressively.
 */
public final class CityBuilder {
    /**
     * A building template plus the metadata the generator needs to place it flush with the road.
     *
     * @param id        template resource path (also the .nbt file name)
     * @param footprint the building's horizontal extent (max of its X and Z size) in blocks; used to
     *                  size its cell so tightly-packed buildings never overlap
     * @param surfaceY  the y (0-based, within the template) of the original terrain <em>surface</em>
     *                  the build sat on. The template is dropped by this many blocks so that surface
     *                  lands at the road level and the building's ground floor sits flush with the
     *                  street instead of on a raised plinth of dirt/grass.
     */
    private record BuildingType(String id, int footprint, int surfaceY) {
    }

    private record CacheCandidate(BlockPos position, Direction facing) {
    }

    /**
     * Building templates the generator draws from (extracted from the source world saves).
     *
     * <p>The oversized {@code cp_garage} (82x82, ~4x the footprint of anything else) is deliberately
     * excluded from this grid pool so it can't blow the cell size up and make the whole city sparse;
     * the remaining builds are all similarly sized and pack tightly.
     */
    private static final BuildingType[] BUILDINGS = {
            // footprint = max(sizeX, sizeZ); surfaceY = terrain-top layer inside the template.
            new BuildingType("cp_tower", 33, 1),
            new BuildingType("cp_shack", 20, 4),
            new BuildingType("cp_house", 27, 0),
            new BuildingType("cp_shop", 23, 3),
    };

    /** City is CITY_DIM x CITY_DIM city blocks. */
    private static final int CITY_DIM = 5;
    /** Each city block is BLOCK_DIM x BLOCK_DIM buildings. */
    private static final int BLOCK_DIM = 3;
    /** Narrow gap in blocks between buildings within a city block (alleyways). */
    private static final int ALLEY = 2;
    /** Wider gap in blocks between city blocks, paved as a road. */
    private static final int ROAD = 5;
    /**
     * Fixed cell size (blocks) a single building is allotted. Sized to the largest footprint in
     * {@link #BUILDINGS} (the 33-wide tower) plus a small margin, so buildings sit close together
     * with only the alley between them. Smaller builds are centred inside the cell (see
     * {@link #enqueueBuilding}) so the streetscape stays dense and even.
     */
    private static final int CELL = 34;

    /** Y level of the concrete ground surface top (bedrock at minY -64, +1 bedrock +4 concrete). */
    private static final int GROUND_TOP_Y = -60;
    /** Where the city grid begins, in blocks from world origin. */
    private static final int ORIGIN_X = 0;
    private static final int ORIGIN_Z = 0;
    /** How many jobs (building stamps / road strips) to process per level tick. */
    private static final int JOBS_PER_TICK = 1;
    /** Bulk-generation update flags: skip neighbour updates, drops, and on-place side effects. */
    private static final int PLACE_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    /** Deterministic per-world layout so the same world always regenerates identically if needed. */
    private static final long LAYOUT_SEED = 0x0CE7B0DEL;

    /** Pending jobs, drained a few per tick. Non-empty only while a city is building. */
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    /** The level currently being built, or {@code null} when idle. */
    private ServerLevel building;

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        LevelAccessor accessor = event.getLevel();
        if (!(accessor instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (!isCyberpunkCity(level)) {
            return;
        }

        CityData data = level.getDataStorage().computeIfAbsent(CityData.TYPE);
        if (data.isBuilt()) {
            return;
        }

        enqueueCity(level);
    }

    /** Drains the job queue a little at a time so world load stays responsive. */
    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (building == null || event.getLevel() != building) {
            return;
        }
        for (int i = 0; i < JOBS_PER_TICK && !queue.isEmpty(); i++) {
            queue.poll().run();
        }
        if (queue.isEmpty()) {
            building.getDataStorage().computeIfAbsent(CityData.TYPE).markBuilt();
            Cyberdeck.LOGGER.info("Cyberpunk city generated.");
            building = null;
        }
    }

    /**
     * Detects the Cyberpunk City world type: a flat generator whose top solid layer is black concrete.
     * This avoids stamping the city into ordinary flat or normal worlds.
     */
    private static boolean isCyberpunkCity(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof FlatLevelSource flat)) {
            return false;
        }
        Block blackConcrete = Blocks.CONCRETE.pick(DyeColor.BLACK);
        List<BlockState> layers = flat.settings().getLayers();
        for (int i = layers.size() - 1; i >= 0; i--) {
            BlockState state = layers.get(i);
            if (state == null || state.isAir()) {
                continue;
            }
            return state.getBlock() == blackConcrete;
        }
        return false;
    }

    /**
     * Lays out the whole city and queues one job per building stamp and per road strip.
     *
     * <p>The world is tiled into (building-cell + alley) sub-cells within each city block, and city
     * blocks are separated by {@link #ROAD}-wide grey-concrete roads. Buildings are picked at random
     * per sub-cell, and each city block gets a distinct colour-swap shift.
     */
    private void enqueueCity(ServerLevel level) {
        StructureTemplateManager templates = level.getStructureManager();
        StructureTemplate[] loaded = new StructureTemplate[BUILDINGS.length];
        for (int i = 0; i < BUILDINGS.length; i++) {
            Identifier id = Identifier.fromNamespaceAndPath(Cyberdeck.MODID, BUILDINGS[i].id());
            Optional<StructureTemplate> template = templates.get(id);
            if (template.isEmpty()) {
                Cyberdeck.LOGGER.warn("Missing city structure template: {}", id);
                continue;
            }
            loaded[i] = template.get();
        }

        Random rng = new Random(LAYOUT_SEED);
        BlockState road = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();

        // Size of one city block (its buildings + the alleys between them).
        int cityBlockSpan = BLOCK_DIM * CELL + (BLOCK_DIM - 1) * ALLEY;
        // Size of one city block plus the road that follows it.
        int stride = cityBlockSpan + ROAD;
        int cityExtent = CITY_DIM * stride; // buildings + alleys + roads across the whole city

        // Pave every road strip: the grid lines between city blocks (and a border road).
        for (int bx = 0; bx <= CITY_DIM; bx++) {
            int x0 = ORIGIN_X + bx * stride - ROAD;
            enqueueRoadStrip(level, road, x0, ORIGIN_Z - ROAD, ROAD, cityExtent + ROAD);
        }
        for (int bz = 0; bz <= CITY_DIM; bz++) {
            int z0 = ORIGIN_Z + bz * stride - ROAD;
            enqueueRoadStrip(level, road, ORIGIN_X - ROAD, z0, cityExtent + ROAD, ROAD);
        }

        // Place buildings city block by city block, each with its own colour shift.
        int shift = 1;
        for (int bx = 0; bx < CITY_DIM; bx++) {
            for (int bz = 0; bz < CITY_DIM; bz++) {
                int blockOriginX = ORIGIN_X + bx * stride;
                int blockOriginZ = ORIGIN_Z + bz * stride;
                StructurePlaceSettings settings = new StructurePlaceSettings()
                        .setIgnoreEntities(true)
                        .setKnownShape(true)
                        .addProcessor(new ColorSwapProcessor(shift));
                shift++;
                List<CacheCandidate> cacheCandidates = new ArrayList<>();

                for (int ix = 0; ix < BLOCK_DIM; ix++) {
                    for (int iz = 0; iz < BLOCK_DIM; iz++) {
                        int pick = rng.nextInt(BUILDINGS.length);
                        StructureTemplate template = loaded[pick];
                        if (template == null) {
                            continue;
                        }
                        BuildingType type = BUILDINGS[pick];
                        // Cell origin for this sub-cell, then centre the (possibly smaller) building
                        // within its CELL so tightly-packed streets read evenly instead of hugging
                        // one corner with a big empty gap on the other side.
                        int cellX = blockOriginX + ix * (CELL + ALLEY);
                        int cellZ = blockOriginZ + iz * (CELL + ALLEY);
                        int x = cellX + Math.max(0, (CELL - type.footprint()) / 2);
                        int z = cellZ + Math.max(0, (CELL - type.footprint()) / 2);
                        enqueueBuilding(level, template, settings, type, x, z);
                        addCacheCandidates(cacheCandidates, x, z, type.footprint());
                    }
                }

                // Try one deterministic facade-backed cache per city block after its structures
                // have been stamped. Live geometry rejects entrances, obstructions, and gaps.
                CityLootGeneration.CacheKind cacheKind = rng.nextInt(3) == 0
                        ? CityLootGeneration.CacheKind.BLACK_LOOT
                        : CityLootGeneration.CacheKind.AMMO;
                List<CacheCandidate> candidates = List.copyOf(cacheCandidates);
                int cacheStart = candidates.isEmpty() ? 0 : rng.nextInt(candidates.size());
                long cacheSeed = rng.nextLong();
                queue.add(() -> placeCityBlockCache(
                        level, candidates, cacheStart, cacheKind, cacheSeed));
            }
        }

        if (!queue.isEmpty()) {
            building = level;
        }
    }

    private static void addCacheCandidates(
            List<CacheCandidate> candidates, int x, int z, int footprint) {
        for (int offset = 1; offset < footprint - 1; offset += 2) {
            candidates.add(new CacheCandidate(
                    new BlockPos(x + offset, GROUND_TOP_Y + 1, z - 1), Direction.NORTH));
            candidates.add(new CacheCandidate(
                    new BlockPos(x + offset, GROUND_TOP_Y + 1, z + footprint), Direction.SOUTH));
            candidates.add(new CacheCandidate(
                    new BlockPos(x - 1, GROUND_TOP_Y + 1, z + offset), Direction.WEST));
            candidates.add(new CacheCandidate(
                    new BlockPos(x + footprint, GROUND_TOP_Y + 1, z + offset), Direction.EAST));
        }
    }

    private static void placeCityBlockCache(
            ServerLevel level,
            List<CacheCandidate> candidates,
            int start,
            CityLootGeneration.CacheKind kind,
            long seed) {
        for (int offset = 0; offset < candidates.size(); offset++) {
            CacheCandidate candidate = candidates.get((start + offset) % candidates.size());
            if (CityLootGeneration.place(
                    level, candidate.position(), kind, candidate.facing(), seed)) {
                return;
            }
        }
    }

    /**
     * Queues a single building stamp. The template is dropped by {@link BuildingType#surfaceY()} so
     * the original terrain surface baked into the template lands at the road level, leaving the
     * building's ground floor flush with the street instead of raised on a dirt plinth.
     */
    private void enqueueBuilding(ServerLevel level, StructureTemplate template,
                                 StructurePlaceSettings settings, BuildingType type, int x, int z) {
        int y = GROUND_TOP_Y - type.surfaceY();
        queue.add(() -> {
            BlockPos pos = new BlockPos(x, y, z);
            template.placeInWorld(level, pos, pos, settings, level.getRandom(), PLACE_FLAGS);
        });
    }

    /** Queues paving of one flat road rectangle at the ground surface. */
    private void enqueueRoadStrip(ServerLevel level, BlockState road, int x0, int z0, int sx, int sz) {
        queue.add(() -> {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dx = 0; dx < sx; dx++) {
                for (int dz = 0; dz < sz; dz++) {
                    pos.set(x0 + dx, GROUND_TOP_Y, z0 + dz);
                    level.setBlock(pos, road, PLACE_FLAGS);
                }
            }
        });
    }
}
