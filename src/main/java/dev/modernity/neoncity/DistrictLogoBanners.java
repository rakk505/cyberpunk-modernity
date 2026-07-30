package dev.modernity.neoncity;

import java.util.List;
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
            BannerPatterns.STRIPE_DOWNLEFT);

    private DistrictLogoBanners() {
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
        };
        return new Design(
                colors.base(), colors.primary(), colors.secondary(), MARKS.get(district.ordinal()));
    }

    static boolean decorateArnisChunk(
            ServerLevel level,
            ChunkPos chunk,
            District district,
            long selectionHash) {
        int xOffset = Math.floorMod((int) selectionHash, 16);
        int zOffset = Math.floorMod((int) (selectionHash >>> 32), 16);
        int directionOffset = Math.floorMod((int) (selectionHash ^ (selectionHash >>> 29)), 4);

        for (int xStep = 0; xStep < 16; xStep++) {
            int x = chunk.getMinBlockX() + Math.floorMod(xOffset + xStep, 16);
            for (int zStep = 0; zStep < 16; zStep++) {
                int z = chunk.getMinBlockZ() + Math.floorMod(zOffset + zStep, 16);
                int topY = Math.min(
                        NeonCityGenerator.MAX_BUILD_Y,
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
                int bottomY = Math.max(NeonCityGenerator.CITY_GROUND_Y + 4, topY - 40);
                for (int y = topY - 1; y >= bottomY; y--) {
                    BlockPos support = new BlockPos(x, y, z);
                    if (!isFacadeSupport(level, support)) {
                        continue;
                    }
                    for (int directionStep = 0; directionStep < 4; directionStep++) {
                        Direction outward = HORIZONTAL_DIRECTIONS[
                                Math.floorMod(directionOffset + directionStep, 4)];
                        BlockPos bannerPos = support.relative(outward);
                        if (!insideChunk(chunk, bannerPos)
                                || !hasOpenExterior(level, bannerPos, outward)) {
                            continue;
                        }
                        if (placeBanner(level, support, outward, district)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
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
        if (!level.setBlock(bannerPos, bannerState, Block.UPDATE_ALL)) {
            return false;
        }
        if (!(level.getBlockEntity(bannerPos) instanceof BannerBlockEntity banner)) {
            level.removeBlock(bannerPos, false);
            return false;
        }

        ItemStack stack = new ItemStack(Blocks.BANNER.pick(design.base()));
        stack.set(DataComponents.BANNER_PATTERNS, design.layers(level.registryAccess()));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(district.label() + " Emblem"));
        banner.applyComponentsFromItemStack(stack);
        banner.setChanged();
        level.sendBlockUpdated(bannerPos, bannerState, bannerState, Block.UPDATE_CLIENTS);
        return true;
    }

    private static boolean isFacadeSupport(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isSolid()
                && level.getBlockState(pos.above()).isSolid()
                && level.getBlockState(pos.below()).isSolid();
    }

    private static boolean hasOpenExterior(
            ServerLevel level, BlockPos bannerPos, Direction outward) {
        if (bannerPos.getY() < level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                bannerPos.getX(), bannerPos.getZ()) + 2) {
            return false;
        }
        return level.isEmptyBlock(bannerPos)
                && level.isEmptyBlock(bannerPos.relative(outward))
                && level.isEmptyBlock(bannerPos.relative(outward, 2));
    }

    private static boolean insideChunk(ChunkPos chunk, BlockPos pos) {
        return pos.getX() >= chunk.getMinBlockX()
                && pos.getX() <= chunk.getMaxBlockX()
                && pos.getZ() >= chunk.getMinBlockZ()
                && pos.getZ() <= chunk.getMaxBlockZ();
    }

    private static Colors colors(DyeColor base, DyeColor primary, DyeColor secondary) {
        return new Colors(base, primary, secondary);
    }

    private record Colors(DyeColor base, DyeColor primary, DyeColor secondary) {
    }
}
