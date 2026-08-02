package dev.modernity.neoncity;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Places one non-destructive, district-specific emblem on each usable Arnis chunk. */
final class DistrictLogoBanners {
    static final int MAX_FACADE_PROBES = 128;
    static final int HEIGHT_QUERIES_PER_CHUNK = 16 * 16;
    static final int MAX_PENDING_BANNERS = 2_048;
    static final long MAX_DEFERRED_AVERAGE_TICK_NANOS = 20_000_000L;
    private static final int MAX_UNLOADED_CHECKS_PER_TICK = 32;

    private static final ArrayDeque<PendingBanner> PENDING = new ArrayDeque<>();
    private static final Set<Long> PENDING_KEYS = new HashSet<>();
    private static final Map<District, ItemStack> PROTOTYPES = new EnumMap<>(District.class);
    private static NeonCitySavedData savedData;

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final List<ResourceKey<BannerPattern>> MARKS = List.of(
            BannerPatterns.MOJANG,
            BannerPatterns.FLOWER,
            BannerPatterns.BRICKS,
            BannerPatterns.GUSTER,
            BannerPatterns.RHOMBUS_MIDDLE,
            BannerPatterns.GRADIENT,
            BannerPatterns.FLOW,
            BannerPatterns.STRIPE_SMALL,
            BannerPatterns.GLOBE,
            BannerPatterns.CREEPER,
            BannerPatterns.STRAIGHT_CROSS,
            BannerPatterns.HALF_VERTICAL,
            BannerPatterns.STRIPE_CENTER,
            BannerPatterns.CURLY_BORDER,
            BannerPatterns.CIRCLE_MIDDLE,
            BannerPatterns.TRIANGLES_TOP,
            BannerPatterns.STRIPE_DOWNRIGHT,
            BannerPatterns.CROSS,
            BannerPatterns.TRIANGLE_TOP,
            BannerPatterns.PIGLIN,
            BannerPatterns.STRIPE_BOTTOM,
            BannerPatterns.DIAGONAL_LEFT,
            BannerPatterns.GRADIENT_UP,
            BannerPatterns.SKULL,
            BannerPatterns.HALF_HORIZONTAL,
            BannerPatterns.STRIPE_DOWNLEFT,
            BannerPatterns.MOJANG,
            BannerPatterns.FLOWER,
            BannerPatterns.BRICKS,
            BannerPatterns.GUSTER,
            BannerPatterns.RHOMBUS_MIDDLE,
            BannerPatterns.GRADIENT,
            BannerPatterns.FLOW,
            BannerPatterns.STRIPE_SMALL,
            BannerPatterns.GLOBE);

    private DistrictLogoBanners() {
    }

    record BannerSite(BlockPos support, Direction outward) {
    }

    record SearchResult(Optional<BannerSite> site, int facadeProbes, int heightQueries) {
    }

    private record PendingBanner(BlockPos support, Direction outward, District district) {
        long key() {
            return support.asLong();
        }

        BlockPos bannerPos() {
            return support.relative(outward);
        }

        NeonCitySavedData.DeferredBanner serialized() {
            return new NeonCitySavedData.DeferredBanner(
                    support.getX(), support.getY(), support.getZ(),
                    outward.ordinal(), district.ordinal());
        }
    }

    static void initialize(ServerLevel level) {
        reset();
        savedData = level.getDataStorage().computeIfAbsent(NeonCitySavedData.TYPE);
        List<NeonCitySavedData.DeferredBanner> restored = savedData.pendingBanners();
        int first = Math.max(0, restored.size() - MAX_PENDING_BANNERS);
        for (int index = 0; index < restored.size(); index++) {
            NeonCitySavedData.DeferredBanner serialized = restored.get(index);
            PendingBanner pending = decode(serialized);
            if (pending == null || index < first) {
                savedData.removePendingBanner(serialized.key());
                continue;
            }
            if (PENDING_KEYS.add(pending.key())) PENDING.addLast(pending);
        }
        for (District district : District.values()) prototype(level, district);
    }

    static void reset() {
        PENDING.clear();
        PENDING_KEYS.clear();
        PROTOTYPES.clear();
        savedData = null;
    }

    static int pendingCount() {
        return PENDING.size();
    }

    record Design(
            DyeColor base,
            DyeColor primary,
            DyeColor secondary,
            ResourceKey<BannerPattern> mark) {
        Set<DyeColor> colors() {
            return Set.of(base, primary, secondary);
        }

        BannerPatternLayers layers(HolderLookup.Provider registries) {
            return new BannerPatternLayers.Builder()
                    .add(registries.getOrThrow(mark), primary)
                    .add(registries.getOrThrow(BannerPatterns.BORDER), secondary)
                    .build();
        }
    }

    static Design design(District district) {
        Colors colors = switch (district) {
            case A_CORP -> colors(DyeColor.BLACK, DyeColor.WHITE, DyeColor.RED);
            case B_CORP -> colors(DyeColor.GREEN, DyeColor.WHITE, DyeColor.LIGHT_BLUE);
            case C_CORP -> colors(DyeColor.BROWN, DyeColor.ORANGE, DyeColor.BLACK);
            case D_CORP -> colors(DyeColor.GREEN, DyeColor.LIGHT_GRAY, DyeColor.BLUE);
            case E_CORP -> colors(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.CYAN);
            case F_CORP -> colors(DyeColor.PINK, DyeColor.CYAN, DyeColor.WHITE);
            case G_CORP -> colors(DyeColor.GRAY, DyeColor.LIME, DyeColor.ORANGE);
            case H_CORP -> colors(DyeColor.BLACK, DyeColor.CYAN, DyeColor.RED);
            case I_CORP -> colors(DyeColor.WHITE, DyeColor.RED, DyeColor.YELLOW);
            case J_CORP -> colors(DyeColor.PURPLE, DyeColor.YELLOW, DyeColor.BLACK);
            case K_CORP -> colors(DyeColor.WHITE, DyeColor.LIGHT_BLUE, DyeColor.GRAY);
            case L_CORP -> colors(DyeColor.LIGHT_GRAY, DyeColor.BLUE, DyeColor.PURPLE);
            case M_CORP -> colors(DyeColor.GRAY, DyeColor.RED, DyeColor.WHITE);
            case N_CORP -> colors(DyeColor.WHITE, DyeColor.BLUE, DyeColor.RED);
            case O_CORP -> colors(DyeColor.WHITE, DyeColor.YELLOW, DyeColor.CYAN);
            case P_CORP -> colors(DyeColor.BLACK, DyeColor.YELLOW, DyeColor.LIGHT_BLUE);
            case Q_CORP -> colors(DyeColor.WHITE, DyeColor.ORANGE, DyeColor.BLUE);
            case R_CORP -> colors(DyeColor.RED, DyeColor.YELLOW, DyeColor.CYAN);
            case S_CORP -> colors(DyeColor.WHITE, DyeColor.RED, DyeColor.LIGHT_BLUE);
            case T_CORP -> colors(DyeColor.BROWN, DyeColor.ORANGE, DyeColor.GRAY);
            case U_CORP -> colors(DyeColor.GRAY, DyeColor.ORANGE, DyeColor.CYAN);
            case V_CORP -> colors(DyeColor.RED, DyeColor.WHITE, DyeColor.LIGHT_BLUE);
            case W_CORP -> colors(DyeColor.LIGHT_GRAY, DyeColor.LIME, DyeColor.CYAN);
            case X_CORP -> colors(DyeColor.BROWN, DyeColor.YELLOW, DyeColor.GREEN);
            case Y_CORP -> colors(DyeColor.LIGHT_BLUE, DyeColor.WHITE, DyeColor.RED);
            case Z_CORP -> colors(DyeColor.BLACK, DyeColor.CYAN, DyeColor.MAGENTA);
            case AE_DISTRICT -> colors(DyeColor.BLUE, DyeColor.WHITE, DyeColor.LIGHT_GRAY);
            case YI_DISTRICT -> colors(DyeColor.GRAY, DyeColor.RED, DyeColor.WHITE);
            case WANG_DISTRICT -> colors(DyeColor.RED, DyeColor.BLACK, DyeColor.YELLOW);
            case XI_DISTRICT -> colors(DyeColor.MAGENTA, DyeColor.YELLOW, DyeColor.CYAN);
            case UI_DISTRICT -> colors(DyeColor.WHITE, DyeColor.LIME, DyeColor.CYAN);
            case UANG_DISTRICT -> colors(DyeColor.ORANGE, DyeColor.BLUE, DyeColor.WHITE);
            case PON_DISTRICT -> colors(DyeColor.RED, DyeColor.YELLOW, DyeColor.WHITE);
            case POK_DISTRICT -> colors(DyeColor.ORANGE, DyeColor.BLACK, DyeColor.LIGHT_BLUE);
            case PAK_DISTRICT -> colors(DyeColor.WHITE, DyeColor.YELLOW, DyeColor.BLACK);
        };
        return new Design(
                colors.base(), colors.primary(), colors.secondary(), MARKS.get(district.ordinal()));
    }

    static boolean decorateArnisChunk(
            ServerLevel level,
            ChunkPos chunk,
            District district,
            long selectionHash) {
        return findArnisBannerSite(level, chunk, selectionHash).site()
                .map(site -> enqueue(level, site, district))
                .orElse(false);
    }

    static boolean enqueue(ServerLevel level, BannerSite site, District district) {
        if (savedData == null) {
            savedData = level.getDataStorage().computeIfAbsent(NeonCitySavedData.TYPE);
        }
        PendingBanner pending = new PendingBanner(site.support(), site.outward(), district);
        if (!PENDING_KEYS.add(pending.key())) return false;
        while (PENDING.size() >= MAX_PENDING_BANNERS) {
            PendingBanner evicted = PENDING.removeFirst();
            PENDING_KEYS.remove(evicted.key());
            savedData.removePendingBanner(evicted.key());
        }
        PENDING.addLast(pending);
        savedData.addPendingBanner(pending.serialized());
        return true;
    }

    static boolean tickDeferred(
            ServerLevel level, boolean foregroundGenerated, boolean activeTravel) {
        if (PENDING.isEmpty()
                || foregroundGenerated
                || activeTravel
                || level.getServer().getAverageTickTimeNanos()
                        > MAX_DEFERRED_AVERAGE_TICK_NANOS) {
            return false;
        }
        int checks = Math.min(PENDING.size(), MAX_UNLOADED_CHECKS_PER_TICK);
        while (checks-- > 0) {
            PendingBanner pending = PENDING.removeFirst();
            if (!level.isLoaded(pending.bannerPos())) {
                PENDING.addLast(pending);
                continue;
            }
            long started = System.nanoTime();
            boolean placed = alreadyPlaced(level, pending)
                    || placeBanner(
                            level, pending.support(), pending.outward(), pending.district());
            removePending(pending);
            CityGenerationTrace.deferredBannerPlacement(
                    System.nanoTime() - started, placed, PENDING.size());
            return true;
        }
        return false;
    }

    private static void removePending(PendingBanner pending) {
        PENDING_KEYS.remove(pending.key());
        if (savedData != null) savedData.removePendingBanner(pending.key());
    }

    private static PendingBanner decode(NeonCitySavedData.DeferredBanner serialized) {
        if (serialized.outwardOrdinal() < 0
                || serialized.outwardOrdinal() >= Direction.values().length
                || serialized.districtOrdinal() < 0
                || serialized.districtOrdinal() >= District.values().length) {
            return null;
        }
        Direction outward = Direction.values()[serialized.outwardOrdinal()];
        if (!outward.getAxis().isHorizontal()) return null;
        return new PendingBanner(
                new BlockPos(serialized.x(), serialized.y(), serialized.z()),
                outward,
                District.values()[serialized.districtOrdinal()]);
    }

    private static boolean alreadyPlaced(ServerLevel level, PendingBanner pending) {
        return level.getBlockState(pending.bannerPos()).getBlock() instanceof WallBannerBlock;
    }

    static SearchResult findArnisBannerSite(
            ServerLevel level,
            ChunkPos chunk,
            long selectionHash) {
        int xOffset = Math.floorMod((int) selectionHash, 16);
        int zOffset = Math.floorMod((int) (selectionHash >>> 32), 16);
        int directionOffset = Math.floorMod((int) (selectionHash ^ (selectionHash >>> 29)), 4);

        int[][] surfaceHeights = new int[16][16];
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                surfaceHeights[localZ][localX] = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        chunk.getMinBlockX() + localX,
                        chunk.getMinBlockZ() + localZ);
            }
        }

        int facadeProbes = 0;
        BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
        for (int xStep = 0; xStep < 16; xStep++) {
            int localX = Math.floorMod(xOffset + xStep, 16);
            int x = chunk.getMinBlockX() + localX;
            for (int zStep = 0; zStep < 16; zStep++) {
                int localZ = Math.floorMod(zOffset + zStep, 16);
                int z = chunk.getMinBlockZ() + localZ;
                int topY = Math.min(
                        NeonCityGenerator.MAX_BUILD_Y,
                        surfaceHeights[localZ][localX] - 1);
                int bottomY = Math.max(NeonCityGenerator.CITY_GROUND_Y + 4, topY - 40);
                for (int directionStep = 0; directionStep < 4; directionStep++) {
                    Direction outward = HORIZONTAL_DIRECTIONS[
                            Math.floorMod(directionOffset + directionStep, 4)];
                    if (!hasContainedExterior(chunk, x, z, outward)) {
                        continue;
                    }
                    int bannerLocalX = localX + outward.getStepX();
                    int bannerLocalZ = localZ + outward.getStepZ();
                    int outsideSurfaceY = surfaceHeights[bannerLocalZ][bannerLocalX];
                    for (int y = topY - 1; y >= bottomY; y--) {
                        if (y < outsideSurfaceY + 2) break;
                        if (facadeProbes >= MAX_FACADE_PROBES) {
                            return new SearchResult(
                                    Optional.empty(), facadeProbes,
                                    HEIGHT_QUERIES_PER_CHUNK);
                        }
                        facadeProbes++;
                        support.set(x, y, z);
                        if (!isFacadeSupport(level, support)) continue;
                        BlockPos immutableSupport = support.immutable();
                        BlockPos bannerPos = immutableSupport.relative(outward);
                        if (hasOpenExterior(level, bannerPos, outward)) {
                            return new SearchResult(
                                    Optional.of(new BannerSite(immutableSupport, outward)),
                                    facadeProbes,
                                    HEIGHT_QUERIES_PER_CHUNK);
                        }
                    }
                }
            }
        }
        return new SearchResult(
                Optional.empty(), facadeProbes, HEIGHT_QUERIES_PER_CHUNK);
    }

    static boolean placeBanner(
            ServerLevel level,
            BlockPos support,
            Direction outward,
            District district) {
        if (!outward.getAxis().isHorizontal()) {
            return false;
        }
        BlockPos bannerPos = support.relative(outward);
        Design design = design(district);
        BlockState bannerState = Blocks.WALL_BANNER.pick(design.base())
                .defaultBlockState()
                .setValue(WallBannerBlock.FACING, outward);
        if (!level.isEmptyBlock(bannerPos) || !bannerState.canSurvive(level, bannerPos)) {
            return false;
        }
        if (!level.setBlock(bannerPos, bannerState, Block.UPDATE_SKIP_ALL_SIDEEFFECTS)) {
            return false;
        }
        if (!(level.getBlockEntity(bannerPos) instanceof BannerBlockEntity banner)) {
            level.removeBlock(bannerPos, false);
            return false;
        }

        banner.applyComponentsFromItemStack(prototype(level, district));
        banner.setChanged();
        // Deferred placement runs after the initial chunk packet, so publish the finished
        // block entity explicitly without enabling neighbor-update side effects.
        level.sendBlockUpdated(bannerPos, bannerState, bannerState, Block.UPDATE_CLIENTS);
        return true;
    }

    private static ItemStack prototype(ServerLevel level, District district) {
        return PROTOTYPES.computeIfAbsent(district, ignored -> {
            Design design = design(district);
            ItemStack stack = new ItemStack(Blocks.BANNER.pick(design.base()));
            stack.set(DataComponents.BANNER_PATTERNS, design.layers(level.registryAccess()));
            stack.set(
                    DataComponents.CUSTOM_NAME,
                    Component.literal(district.label() + " Emblem"));
            return stack;
        });
    }

    private static boolean isFacadeSupport(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isSolid()
                && level.getBlockState(pos.above()).isSolid()
                && level.getBlockState(pos.below()).isSolid();
    }

    private static boolean hasOpenExterior(
            ServerLevel level, BlockPos bannerPos, Direction outward) {
        return level.isEmptyBlock(bannerPos)
                && level.isEmptyBlock(bannerPos.relative(outward))
                && level.isEmptyBlock(bannerPos.relative(outward, 2));
    }

    static boolean hasContainedExterior(
            ChunkPos chunk, int supportX, int supportZ, Direction outward) {
        if (!outward.getAxis().isHorizontal()) return false;
        int farX = supportX + outward.getStepX() * 3;
        int farZ = supportZ + outward.getStepZ() * 3;
        return farX >= chunk.getMinBlockX()
                && farX <= chunk.getMaxBlockX()
                && farZ >= chunk.getMinBlockZ()
                && farZ <= chunk.getMaxBlockZ();
    }

    private static Colors colors(DyeColor base, DyeColor primary, DyeColor secondary) {
        return new Colors(base, primary, secondary);
    }

    private record Colors(DyeColor base, DyeColor primary, DyeColor secondary) {
    }
}
