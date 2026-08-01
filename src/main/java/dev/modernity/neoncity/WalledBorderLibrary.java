package dev.modernity.neoncity;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Deterministic walls, circulation, and low-rise slums inside walled district borders. */
final class WalledBorderLibrary {
    static final double PROMENADE_MAX_RATIO = 0.11;
    static final double INNER_ROW_MIN_RATIO = 0.16;
    static final double INNER_ROW_MAX_RATIO = 0.39;
    static final double OUTER_ROW_MIN_RATIO = 0.46;
    static final double OUTER_ROW_MAX_RATIO = 0.69;
    static final double WALL_MIN_RATIO = 0.74;
    static final double WALL_MAX_RATIO = 0.80;
    static final int GATE_PERIOD = 64;
    static final int LOT_PERIOD = 160;

    private static final double GATE_HALF_WIDTH = 2.5;
    private static final double LOT_HALF_WIDTH = 6.5;
    private static final double LOT_MIN_RATIO = 0.14;
    private static final double LOT_MAX_RATIO = 0.42;
    private static final double MODULE_PERIOD = 16.0;
    private static final double STORY_CROSS_INSET = 0.012;
    private static final double CROSS_EDGE_WIDTH = 0.019;
    static final int STORY_HEIGHT = 4;
    private static final int MAX_OWNED_HEIGHT = 20;
    private static final long PAIR_SALT = 0x57414C4C50414952L;
    private static final long GATE_SALT = 0x57414C4C47415445L;
    private static final long LOT_SALT = 0x57414C4C4C4F5453L;
    private static final long MODULE_SALT = 0x534C554D4D4F4455L;
    private static final long WINDOW_SALT = 0x534C554D57494E44L;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;

    private static final List<SlumPalette> SLUM_PALETTES = List.of(
            new SlumPalette(
                    Blocks.MUD_BRICKS.defaultBlockState(),
                    dyedTerracotta(DyeColor.BROWN),
                    stainedGlass(DyeColor.LIGHT_GRAY),
                    Blocks.SPRUCE_PLANKS.defaultBlockState(),
                    Blocks.POLISHED_BLACKSTONE.defaultBlockState()),
            new SlumPalette(
                    Blocks.STONE_BRICKS.defaultBlockState(),
                    concrete(DyeColor.GRAY),
                    stainedGlass(DyeColor.CYAN),
                    Blocks.POLISHED_ANDESITE.defaultBlockState(),
                    Blocks.DEEPSLATE_TILES.defaultBlockState()),
            new SlumPalette(
                    dyedTerracotta(DyeColor.BLACK),
                    dyedTerracotta(DyeColor.LIGHT_BLUE),
                    stainedGlass(DyeColor.LIGHT_BLUE),
                    Blocks.PACKED_MUD.defaultBlockState(),
                    Blocks.POLISHED_DEEPSLATE.defaultBlockState()),
            new SlumPalette(
                    Blocks.SANDSTONE.defaultBlockState(),
                    Blocks.BRICKS.defaultBlockState(),
                    stainedGlass(DyeColor.BROWN),
                    Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
                    Blocks.MUD_BRICKS.defaultBlockState()),
            new SlumPalette(
                    Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
                    dyedTerracotta(DyeColor.ORANGE),
                    stainedGlass(DyeColor.GRAY),
                    Blocks.DEEPSLATE_BRICKS.defaultBlockState(),
                    Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState()));

    private static final List<WallPalette> WALL_PALETTES = List.of(
            new WallPalette(
                    Blocks.DEEPSLATE_BRICKS.defaultBlockState(),
                    Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState(),
                    Blocks.POLISHED_DEEPSLATE.defaultBlockState()),
            new WallPalette(
                    Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
                    Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
                    Blocks.POLISHED_BLACKSTONE.defaultBlockState()),
            new WallPalette(
                    Blocks.STONE_BRICKS.defaultBlockState(),
                    Blocks.CRACKED_STONE_BRICKS.defaultBlockState(),
                    Blocks.CHISELED_STONE_BRICKS.defaultBlockState()));

    private WalledBorderLibrary() {
    }

    enum ColumnRole {
        OUTSIDE,
        PROMENADE,
        GATE_ALLEY,
        WALL,
        RESERVED_LOT,
        SLUM,
        SERVICE_ALLEY,
        VERGE
    }

    record SlumModule(
            long identity,
            int side,
            int row,
            int cell,
            double localAlong,
            double halfAlong,
            double rowMinRatio,
            double rowMaxRatio,
            int stories,
            int paletteIndex,
            boolean roofShack) {
        boolean containsStory(double gapRatio, int story) {
            double crossInset = story * STORY_CROSS_INSET;
            double alongInset = Math.min(2.0, story);
            return gapRatio >= rowMinRatio + crossInset
                    && gapRatio <= rowMaxRatio - crossInset
                    && Math.abs(localAlong) <= halfAlong - alongInset;
        }

        int totalHeight() {
            return stories * STORY_HEIGHT + 1;
        }
    }

    record ColumnPlan(
            ColumnRole role,
            MegacityLayout.BoundaryFrame frame,
            int wallHeight,
            SlumModule module) {
        boolean traversableAtGround() {
            return role == ColumnRole.PROMENADE
                    || role == ColumnRole.GATE_ALLEY
                    || role == ColumnRole.RESERVED_LOT
                    || role == ColumnRole.SERVICE_ALLEY
                    || role == ColumnRole.VERGE;
        }
    }

    record PlacementMetrics(
            int wallColumns,
            int wallBlocks,
            int slumColumns,
            int slumBlocks,
            int multiStoryColumns,
            int promenadeColumns,
            int gateColumns,
            int reservedLotColumns,
            int serviceAlleyColumns,
            int vergeColumns) {
        int ownedColumns() {
            return wallColumns + slumColumns + promenadeColumns + gateColumns
                    + reservedLotColumns + serviceAlleyColumns + vergeColumns;
        }
    }

    private record SlumPalette(
            BlockState wall,
            BlockState accent,
            BlockState window,
            BlockState floor,
            BlockState roof) {
    }

    private record WallPalette(BlockState wall, BlockState weathered, BlockState cap) {
    }

    static ColumnPlan planAt(
            MegacityLayout layout,
            MegacityLayout.Location location,
            int worldX,
            int worldZ) {
        if (location.zone() != MegacityLayout.Zone.BORDER_WALLED) {
            return new ColumnPlan(ColumnRole.OUTSIDE, null, 0, null);
        }
        MegacityLayout.BoundaryFrame frame = layout.boundaryFrame(location, worldX, worldZ);
        return planFrame(layout.seed(), frame);
    }

    /** Pure frame-level planner for exhaustive topology tests without world mutation. */
    static ColumnPlan planFrame(long seed, MegacityLayout.BoundaryFrame frame) {
        long pairHash = pairHash(seed, frame);
        double along = frame.along();

        double gatePhase = phase(pairHash ^ GATE_SALT, GATE_PERIOD);
        if (distanceToGrid(along + gatePhase, GATE_PERIOD) <= GATE_HALF_WIDTH) {
            return new ColumnPlan(ColumnRole.GATE_ALLEY, frame, 0, null);
        }
        if (frame.gapRatio() <= PROMENADE_MAX_RATIO) {
            return new ColumnPlan(ColumnRole.PROMENADE, frame, 0, null);
        }
        if (isReservedLot(frame, pairHash)) {
            return new ColumnPlan(ColumnRole.RESERVED_LOT, frame, 0, null);
        }
        if (frame.gapRatio() >= WALL_MIN_RATIO && frame.gapRatio() <= WALL_MAX_RATIO) {
            int segment = floorCell(along + phase(pairHash, 12), 12.0);
            int side = side(frame);
            long wallHash = MegacityLayout.mix(
                    pairHash ^ Long.rotateLeft(PAIR_SALT, side > 0 ? 9 : 27),
                    segment,
                    side);
            int height = 5 + Math.floorMod((int) (wallHash ^ (wallHash >>> 32)), 3);
            return new ColumnPlan(ColumnRole.WALL, frame, height, null);
        }

        SlumModule module = moduleAt(frame, pairHash);
        if (module != null) {
            return new ColumnPlan(ColumnRole.SLUM, frame, 0, module);
        }
        if (frame.gapRatio() < WALL_MIN_RATIO) {
            return new ColumnPlan(ColumnRole.SERVICE_ALLEY, frame, 0, null);
        }
        return new ColumnPlan(ColumnRole.VERGE, frame, 0, null);
    }

    static PlacementMetrics decorateChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        MegacityLayout layout = NeonCityGenerator.layout();
        int wallColumns = 0;
        int wallBlocks = 0;
        int slumColumns = 0;
        int slumBlocks = 0;
        int multiStoryColumns = 0;
        int promenadeColumns = 0;
        int gateColumns = 0;
        int reservedLotColumns = 0;
        int serviceAlleyColumns = 0;
        int vergeColumns = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (sample.roadClass() != NeonCityGenerator.RoadClass.BORDER_WALLED) {
                    continue;
                }
                int worldX = chunk.getMinBlockX() + localX;
                int worldZ = chunk.getMinBlockZ() + localZ;
                ColumnPlan plan = planAt(layout, sample.location(), worldX, worldZ);
                int groundY = sample.groundY();
                clearOwnedColumn(level, cursor, worldX, groundY, worldZ);

                switch (plan.role()) {
                    case OUTSIDE -> {
                    }
                    case PROMENADE -> {
                        promenadeColumns++;
                        set(level, cursor, worldX, groundY, worldZ,
                                promenadeSurface(worldX, worldZ));
                    }
                    case GATE_ALLEY -> {
                        gateColumns++;
                        set(level, cursor, worldX, groundY, worldZ,
                                Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    }
                    case RESERVED_LOT -> {
                        reservedLotColumns++;
                        set(level, cursor, worldX, groundY, worldZ,
                                reservedLotSurface(worldX, worldZ));
                    }
                    case SERVICE_ALLEY -> {
                        serviceAlleyColumns++;
                        set(level, cursor, worldX, groundY, worldZ,
                                alleySurface(worldX, worldZ));
                    }
                    case VERGE -> {
                        vergeColumns++;
                        set(level, cursor, worldX, groundY, worldZ,
                                Blocks.PACKED_MUD.defaultBlockState());
                    }
                    case WALL -> {
                        wallColumns++;
                        int placed = placeWallColumn(
                                level, cursor, plan, worldX, groundY, worldZ, layout.seed());
                        wallBlocks += placed;
                    }
                    case SLUM -> {
                        slumColumns++;
                        if (plan.module().containsStory(plan.frame().gapRatio(), 1)) {
                            multiStoryColumns++;
                        }
                        slumBlocks += placeSlumColumn(
                                level, cursor, plan, worldX, groundY, worldZ);
                    }
                }
            }
        }
        return new PlacementMetrics(
                wallColumns,
                wallBlocks,
                slumColumns,
                slumBlocks,
                multiStoryColumns,
                promenadeColumns,
                gateColumns,
                reservedLotColumns,
                serviceAlleyColumns,
                vergeColumns);
    }

    private static boolean isReservedLot(
            MegacityLayout.BoundaryFrame frame, long pairHash) {
        if (frame.gapRatio() < LOT_MIN_RATIO || frame.gapRatio() > LOT_MAX_RATIO) {
            return false;
        }
        double shifted = frame.along() + phase(pairHash ^ LOT_SALT, LOT_PERIOD);
        int cell = floorCell(shifted + LOT_PERIOD * 0.5, LOT_PERIOD);
        double localAlong = centeredMod(shifted, LOT_PERIOD);
        if (Math.abs(localAlong) > LOT_HALF_WIDTH) {
            return false;
        }
        long lotHash = MegacityLayout.mix(pairHash ^ LOT_SALT, cell, 31);
        int lotSide = (lotHash & 1L) == 0L ? -1 : 1;
        return side(frame) == lotSide;
    }

    private static SlumModule moduleAt(
            MegacityLayout.BoundaryFrame frame, long pairHash) {
        int row;
        double rowMin;
        double rowMax;
        if (frame.gapRatio() >= INNER_ROW_MIN_RATIO
                && frame.gapRatio() <= INNER_ROW_MAX_RATIO) {
            row = 0;
            rowMin = INNER_ROW_MIN_RATIO;
            rowMax = INNER_ROW_MAX_RATIO;
        } else if (frame.gapRatio() >= OUTER_ROW_MIN_RATIO
                && frame.gapRatio() <= OUTER_ROW_MAX_RATIO) {
            row = 1;
            rowMin = OUTER_ROW_MIN_RATIO;
            rowMax = OUTER_ROW_MAX_RATIO;
        } else {
            return null;
        }

        int side = side(frame);
        double modulePhase = phase(
                pairHash ^ Long.rotateLeft(MODULE_SALT, row * 11 + (side > 0 ? 5 : 23)),
                (int) MODULE_PERIOD);
        double shifted = frame.along() + modulePhase;
        int cell = floorCell(shifted + MODULE_PERIOD * 0.5, MODULE_PERIOD);
        double localAlong = centeredMod(shifted, MODULE_PERIOD);
        long moduleHash = MegacityLayout.mix(
                pairHash ^ MODULE_SALT,
                cell,
                row * 2 + (side > 0 ? 1 : 0));
        double halfAlong = 4.5 + Math.floorMod((int) moduleHash, 3);
        if (Math.abs(localAlong) > halfAlong) {
            return null;
        }
        int stories = 2 + Math.floorMod((int) (moduleHash >>> 17), 3);
        int palette = Math.floorMod((int) (moduleHash >>> 32), SLUM_PALETTES.size());
        boolean roofShack = Math.floorMod((int) Long.rotateRight(moduleHash, 11), 7) == 0;
        return new SlumModule(
                moduleHash,
                side,
                row,
                cell,
                localAlong,
                halfAlong,
                rowMin,
                rowMax,
                stories,
                palette,
                roofShack);
    }

    private static int placeWallColumn(
            ServerLevel level,
            BlockPos.MutableBlockPos cursor,
            ColumnPlan plan,
            int worldX,
            int groundY,
            int worldZ,
            long seed) {
        long hash = pairHash(seed, plan.frame());
        WallPalette palette = WALL_PALETTES.get(Math.floorMod(
                (int) (hash ^ (hash >>> 32)), WALL_PALETTES.size()));
        set(level, cursor, worldX, groundY, worldZ,
                Blocks.COBBLED_DEEPSLATE.defaultBlockState());
        int placed = 0;
        for (int relativeY = 1; relativeY <= plan.wallHeight(); relativeY++) {
            BlockState state;
            if (relativeY == plan.wallHeight()) {
                state = palette.cap();
            } else if (Math.floorMod(worldX * 13 + worldZ * 7 + relativeY, 11) == 0) {
                state = palette.weathered();
            } else {
                state = palette.wall();
            }
            set(level, cursor, worldX, groundY + relativeY, worldZ, state);
            placed++;
        }
        return placed;
    }

    private static int placeSlumColumn(
            ServerLevel level,
            BlockPos.MutableBlockPos cursor,
            ColumnPlan plan,
            int worldX,
            int groundY,
            int worldZ) {
        SlumModule module = plan.module();
        SlumPalette palette = SLUM_PALETTES.get(module.paletteIndex());
        set(level, cursor, worldX, groundY, worldZ, palette.floor());
        int placed = 1;

        for (int story = 0; story < module.stories(); story++) {
            if (!module.containsStory(plan.frame().gapRatio(), story)) {
                continue;
            }
            int floorY = groundY + story * STORY_HEIGHT;
            double rowMin = module.rowMinRatio() + story * STORY_CROSS_INSET;
            double rowMax = module.rowMaxRatio() - story * STORY_CROSS_INSET;
            double halfAlong = module.halfAlong() - Math.min(2.0, story);
            boolean crossEdge = plan.frame().gapRatio() - rowMin <= CROSS_EDGE_WIDTH
                    || rowMax - plan.frame().gapRatio() <= CROSS_EDGE_WIDTH;
            boolean alongEdge = halfAlong - Math.abs(module.localAlong()) <= 1.15;
            boolean boundary = crossEdge || alongEdge;
            boolean door = story == 0
                    && plan.frame().gapRatio() - rowMin <= CROSS_EDGE_WIDTH
                    && Math.abs(module.localAlong()) <= 1.5;
            for (int offsetY = 1; offsetY < STORY_HEIGHT; offsetY++) {
                if (!boundary || (door && offsetY <= 2)) {
                    continue;
                }
                BlockState state = wallState(
                        palette, module, worldX, worldZ, story, offsetY, crossEdge, alongEdge);
                set(level, cursor, worldX, floorY + offsetY, worldZ, state);
                placed++;
            }
            boolean supportsNextStory = story + 1 < module.stories()
                    && module.containsStory(plan.frame().gapRatio(), story + 1);
            set(level, cursor, worldX, floorY + STORY_HEIGHT, worldZ,
                    supportsNextStory ? palette.floor() : palette.roof());
            placed++;
        }

        int roofStory = module.stories() - 1;
        if (module.containsStory(plan.frame().gapRatio(), roofStory)) {
            int roofY = groundY + module.stories() * STORY_HEIGHT;
            double rowMin = module.rowMinRatio() + roofStory * STORY_CROSS_INSET;
            double rowMax = module.rowMaxRatio() - roofStory * STORY_CROSS_INSET;
            double halfAlong = module.halfAlong() - Math.min(2.0, roofStory);
            boolean roofEdge = plan.frame().gapRatio() - rowMin <= CROSS_EDGE_WIDTH
                    || rowMax - plan.frame().gapRatio() <= CROSS_EDGE_WIDTH
                    || halfAlong - Math.abs(module.localAlong()) <= 1.15;
            if (roofEdge) {
                set(level, cursor, worldX, roofY + 1, worldZ, palette.accent());
                placed++;
            } else if (module.roofShack()
                    && Math.abs(module.localAlong()) <= 1.5
                    && Math.abs(plan.frame().gapRatio() - (rowMin + rowMax) * 0.5) <= 0.025) {
                set(level, cursor, worldX, roofY + 1, worldZ, Blocks.IRON_BLOCK.defaultBlockState());
                placed++;
            }
        }
        return placed;
    }

    private static BlockState wallState(
            SlumPalette palette,
            SlumModule module,
            int worldX,
            int worldZ,
            int story,
            int offsetY,
            boolean crossEdge,
            boolean alongEdge) {
        long hash = MegacityLayout.mix(
                module.identity() ^ WINDOW_SALT,
                worldX + story * 17,
                worldZ - story * 31);
        if (offsetY == 2 && Math.floorMod((int) (hash ^ (hash >>> 32)), 4) == 0) {
            return palette.window();
        }
        if ((crossEdge && alongEdge) || Math.floorMod((int) hash, 13) == 0) {
            return palette.accent();
        }
        return palette.wall();
    }

    private static BlockState promenadeSurface(int worldX, int worldZ) {
        return Math.floorMod(worldX * 5 + worldZ * 7, 19) <= 1
                ? Blocks.SMOOTH_STONE.defaultBlockState()
                : Blocks.POLISHED_ANDESITE.defaultBlockState();
    }

    private static BlockState reservedLotSurface(int worldX, int worldZ) {
        return Math.floorMod(worldX + worldZ, 8) == 0
                ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                : Blocks.SMOOTH_STONE.defaultBlockState();
    }

    private static BlockState alleySurface(int worldX, int worldZ) {
        return Math.floorMod(worldX * 3 - worldZ * 5, 11) == 0
                ? Blocks.GRAVEL.defaultBlockState()
                : Blocks.DEEPSLATE_TILES.defaultBlockState();
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

    private static void clearOwnedColumn(
            ServerLevel level,
            BlockPos.MutableBlockPos cursor,
            int worldX,
            int groundY,
            int worldZ) {
        int maxY = Math.min(NeonCityGenerator.MAX_BUILD_Y, groundY + MAX_OWNED_HEIGHT);
        for (int y = groundY + 1; y <= maxY; y++) {
            set(level, cursor, worldX, y, worldZ, Blocks.AIR.defaultBlockState());
        }
    }

    private static long pairHash(long seed, MegacityLayout.BoundaryFrame frame) {
        return MegacityLayout.mix(
                seed ^ PAIR_SALT,
                frame.first().ordinal(),
                frame.second().ordinal());
    }

    private static int side(MegacityLayout.BoundaryFrame frame) {
        return frame.signedGap() < 0.0 ? -1 : 1;
    }

    private static double phase(long hash, int period) {
        return Math.floorMod((int) (hash ^ (hash >>> 32)), period);
    }

    private static int floorCell(double value, double period) {
        return (int) Math.floor(value / period);
    }

    private static double distanceToGrid(double value, double period) {
        return Math.abs(centeredMod(value, period));
    }

    private static double centeredMod(double value, double period) {
        double wrapped = value - Math.floor(value / period) * period;
        return wrapped >= period * 0.5 ? wrapped - period : wrapped;
    }

    private static void set(
            ServerLevel level,
            BlockPos.MutableBlockPos cursor,
            int x,
            int y,
            int z,
            BlockState state) {
        level.setBlock(cursor.set(x, y, z), state, PLACE_FLAGS);
    }
}
