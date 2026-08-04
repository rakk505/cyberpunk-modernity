package com.example.cyberdeck.advertising;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.ArnisPatchLibrary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Applies precomputed facade rectangles after their Arnis tile finishes decorating. */
public final class GeneratedAdPlacement {
    private static final long DENSITY_MASK = 3L;

    private GeneratedAdPlacement() {
    }

    public static boolean placeForArnisTile(
            ServerLevel level,
            ChunkPos chunk,
            ArnisPatchLibrary.Placement placement,
            StructureTemplate template,
            int minY) {
        if ((placement.selectionHash() & DENSITY_MASK) != 0L) {
            return false;
        }
        var surface = GeneratedAdSurfaceCatalog.surface(placement.patch().catalogId());
        if (surface.isEmpty()) {
            return false;
        }

        GeneratedAdSurfaceCatalog.Surface selected = surface.get();
        BlockPos desiredMin = new BlockPos(chunk.getMinBlockX(), minY, chunk.getMinBlockZ());
        BlockPos templateAnchor = template.getZeroPositionWithTransform(
                desiredMin, placement.mirror(), placement.rotation());
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
            return false;
        }

        BlockPos anchor = support.relative(facing);
        boolean placed = AdDisplayPlacement.place(
                level, anchor, facing, selected.width(), selected.height());
        if (placed) {
            Cyberdeck.LOGGER.debug("Placed generated {}x{} ad for {} at {}",
                    selected.width(), selected.height(), placement.patch().catalogId(), anchor);
        }
        return placed;
    }
}
