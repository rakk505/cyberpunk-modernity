package dev.modernity.neoncity;

import dev.modernity.neoncity.CamouflagedSeaLanternBlock.SurfaceFinish;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Replaces imported Arnis glowstone once, immediately after its tile is placed. */
final class ArnisEmbeddedLighting {
    private static final Map<Block, SurfaceFinish> FLOOR_FINISHES = Map.ofEntries(
            Map.entry(Blocks.BLACKSTONE, SurfaceFinish.BLACKSTONE),
            Map.entry(Blocks.BLACKSTONE_SLAB, SurfaceFinish.BLACKSTONE),
            Map.entry(Blocks.BLACKSTONE_STAIRS, SurfaceFinish.BLACKSTONE),
            Map.entry(Blocks.CONCRETE.pick(DyeColor.GRAY), SurfaceFinish.GRAY_CONCRETE),
            Map.entry(
                    Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY),
                    SurfaceFinish.LIGHT_GRAY_CONCRETE),
            Map.entry(Blocks.MUD_BRICKS, SurfaceFinish.MUD_BRICKS),
            Map.entry(Blocks.MUD_BRICK_SLAB, SurfaceFinish.MUD_BRICKS),
            Map.entry(Blocks.MUD_BRICK_STAIRS, SurfaceFinish.MUD_BRICKS),
            Map.entry(Blocks.NETHER_BRICKS, SurfaceFinish.NETHER_BRICKS),
            Map.entry(Blocks.NETHER_BRICK_SLAB, SurfaceFinish.NETHER_BRICKS),
            Map.entry(Blocks.NETHER_BRICK_STAIRS, SurfaceFinish.NETHER_BRICKS),
            Map.entry(Blocks.OAK_PLANKS, SurfaceFinish.OAK_PLANKS),
            Map.entry(Blocks.OAK_SLAB, SurfaceFinish.OAK_PLANKS),
            Map.entry(Blocks.OAK_STAIRS, SurfaceFinish.OAK_PLANKS),
            Map.entry(Blocks.POLISHED_ANDESITE, SurfaceFinish.POLISHED_ANDESITE),
            Map.entry(Blocks.POLISHED_ANDESITE_SLAB, SurfaceFinish.POLISHED_ANDESITE),
            Map.entry(Blocks.POLISHED_ANDESITE_STAIRS, SurfaceFinish.POLISHED_ANDESITE),
            Map.entry(Blocks.SMOOTH_STONE, SurfaceFinish.SMOOTH_STONE),
            Map.entry(Blocks.SMOOTH_STONE_SLAB, SurfaceFinish.SMOOTH_STONE),
            Map.entry(Blocks.STONE_BRICKS, SurfaceFinish.STONE_BRICKS),
            Map.entry(Blocks.CHISELED_STONE_BRICKS, SurfaceFinish.STONE_BRICKS),
            Map.entry(Blocks.CRACKED_STONE_BRICKS, SurfaceFinish.STONE_BRICKS),
            Map.entry(Blocks.STONE_BRICK_SLAB, SurfaceFinish.STONE_BRICKS),
            Map.entry(Blocks.STONE_BRICK_STAIRS, SurfaceFinish.STONE_BRICKS),
            Map.entry(Blocks.CONCRETE.pick(DyeColor.WHITE), SurfaceFinish.WHITE_CONCRETE));

    private ArnisEmbeddedLighting() {
    }

    static int finish(
            ServerLevelAccessor level,
            List<BlockPos> importedGlowstone,
            BoundingBox tileBounds,
            int updateFlags) {
        int replaced = 0;
        for (BlockPos position : importedGlowstone) {
            if (!level.getBlockState(position).is(Blocks.GLOWSTONE)) {
                continue;
            }
            BlockState replacement = replacementAt(level, position, tileBounds);
            if (level.setBlock(position, replacement, updateFlags)) {
                replaced++;
            }
        }
        return replaced;
    }

    static BlockState replacementAt(
            LevelReader level, BlockPos position, BoundingBox tileBounds) {
        if (!level.getBlockState(position.above()).isAir()) {
            return Blocks.SEA_LANTERN.defaultBlockState();
        }
        SurfaceFinish finish = selectFinish(level, position, tileBounds);
        if (finish == null) {
            return Blocks.SEA_LANTERN.defaultBlockState();
        }
        return ArnisLightingBlocks.CAMOUFLAGED_SEA_LANTERN.get()
                .defaultBlockState()
                .setValue(CamouflagedSeaLanternBlock.SURFACE, finish);
    }

    static SurfaceFinish selectFinish(
            LevelReader level, BlockPos position, BoundingBox tileBounds) {
        EnumMap<SurfaceFinish, Integer> votes = new EnumMap<>(SurfaceFinish.class);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = position.relative(direction);
            if (!tileBounds.isInside(neighbour)) {
                continue;
            }
            SurfaceFinish finish = FLOOR_FINISHES.get(level.getBlockState(neighbour).getBlock());
            if (finish != null) {
                votes.merge(finish, 1, Integer::sum);
            }
        }

        SurfaceFinish selected = null;
        int selectedVotes = 0;
        for (SurfaceFinish candidate : SurfaceFinish.values()) {
            int candidateVotes = votes.getOrDefault(candidate, 0);
            if (candidateVotes > selectedVotes) {
                selected = candidate;
                selectedVotes = candidateVotes;
            }
        }
        return selected;
    }
}
