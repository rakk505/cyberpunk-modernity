package com.example.cyberdeck.advertising;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.ArnisPatchLibrary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Applies precomputed facade rectangles after their Arnis tile finishes decorating. */
public final class GeneratedAdPlacement {
    private static final long DENSITY_MASK = 3L;

    public enum Result {
        PLACED,
        NOT_APPLICABLE,
        WORLD_BLOCKED,
        RETRYABLE_FAILURE
    }

    private GeneratedAdPlacement() {
    }

    public static boolean placeForArnisTile(
            ServerLevel level,
            ChunkPos chunk,
            ArnisPatchLibrary.Placement placement,
            StructureTemplate template,
            int minY) {
        return placeForArnisTileResult(
                level, chunk, placement, template, minY, false) == Result.PLACED;
    }

    public static Result backfillForArnisTile(
            ServerLevel level,
            ChunkPos chunk,
            ArnisPatchLibrary.Placement placement,
            StructureTemplate template,
            int minY) {
        return placeForArnisTileResult(level, chunk, placement, template, minY, true);
    }

    private static Result placeForArnisTileResult(
            ServerLevel level,
            ChunkPos chunk,
            ArnisPatchLibrary.Placement placement,
            StructureTemplate template,
            int minY,
            boolean requireOriginalSupport) {
        if ((placement.selectionHash() & DENSITY_MASK) != 0L) {
            return Result.NOT_APPLICABLE;
        }
        var surface = GeneratedAdSurfaceCatalog.surface(placement.patch().catalogId());
        if (surface.isEmpty()) {
            return Result.NOT_APPLICABLE;
        }

        GeneratedAdSurfaceCatalog.Surface selected = surface.get();
        BlockPos desiredMin = new BlockPos(chunk.getMinBlockX(), minY, chunk.getMinBlockZ());
        BlockPos templateAnchor = template.getZeroPositionWithTransform(
                desiredMin, placement.mirror(), placement.rotation());
        if (requireOriginalSupport
                && !matchesOriginalSupport(level, placement, templateAnchor, selected)) {
            Cyberdeck.LOGGER.debug("Skipped ad backfill on modified facade {} in {}",
                    placement.patch().catalogId(), chunk);
            return Result.WORLD_BLOCKED;
        }
        BlockPos support = StructureTemplate.transform(
                        selected.support(), placement.mirror(), placement.rotation(), BlockPos.ZERO)
                .offset(templateAnchor);
        Direction facing = placement.rotation().rotate(
                placement.mirror().mirror(selected.facing()));
        Direction transformedRight = placement.rotation().rotate(
                placement.mirror().mirror(LargeAdSurfaceValidator.rightOf(selected.facing())));
        Direction expectedRight = LargeAdSurfaceValidator.rightOf(facing);
        if (transformedRight == expectedRight.getOpposite()) {
            support = support.relative(transformedRight, selected.width() - 1);
        } else if (transformedRight != expectedRight) {
            Cyberdeck.LOGGER.warn("Could not orient generated ad surface {}",
                    placement.patch().catalogId());
            return Result.RETRYABLE_FAILURE;
        }

        BlockPos anchor = support.relative(facing);
        if (!LargeAdSurfaceValidator.validate(
                level, anchor, facing, selected.width(), selected.height()).valid()) {
            return Result.WORLD_BLOCKED;
        }
        boolean placed = AdDisplayPlacement.place(
                level, anchor, facing, selected.width(), selected.height());
        if (placed) {
            Cyberdeck.LOGGER.debug("Placed generated {}x{} ad for {} at {}",
                    selected.width(), selected.height(), placement.patch().catalogId(), anchor);
        }
        return placed ? Result.PLACED : Result.RETRYABLE_FAILURE;
    }

    private static boolean matchesOriginalSupport(
            ServerLevel level,
            ArnisPatchLibrary.Placement placement,
            BlockPos templateAnchor,
            GeneratedAdSurfaceCatalog.Surface surface) {
        Direction originalRight = LargeAdSurfaceValidator.rightOf(surface.facing());
        for (int row = 0; row < surface.height(); row++) {
            for (int column = 0; column < surface.width(); column++) {
                BlockPos local = surface.support().relative(originalRight, column).above(row);
                BlockPos world = StructureTemplate.transform(
                                local,
                                placement.mirror(),
                                placement.rotation(),
                                BlockPos.ZERO)
                        .offset(templateAnchor);
                String actual = BuiltInRegistries.BLOCK.getKey(
                        level.getBlockState(world).getBlock()).toString();
                String expected = surface.supportBlocks().get(
                        row * surface.width() + column);
                if (!matchesSupportBlock(expected, actual)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesSupportBlock(String expected, String actual) {
        if (actual.equals(expected)) {
            return true;
        }
        return expected.equals("minecraft:glowstone")
                && (actual.equals("minecraft:sea_lantern")
                        || actual.equals("cyberdeck:camouflaged_sea_lantern"));
    }
}
