package com.example.cyberdeck.advertising;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.ArnisPatchLibrary;
import dev.modernity.neoncity.NeonCityGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Applies precomputed facade rectangles after their Arnis tile finishes decorating. */
public final class GeneratedAdPlacement {
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
                level, chunk, placement, template, minY) == Result.PLACED;
    }

    public static Result placeForArnisTileResult(
            ServerLevel level,
            ChunkPos chunk,
            ArnisPatchLibrary.Placement placement,
            StructureTemplate template,
            int minY) {
        return placeForArnisTileResult(level, chunk, placement, template, minY, false);
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
        List<GeneratedAdSurfaceCatalog.Surface> surfaces =
                GeneratedAdSurfaceCatalog.surfaces(placement.patch().catalogId());
        if (surfaces.isEmpty()) {
            return Result.NOT_APPLICABLE;
        }
        AdCampaign campaign = campaignForPlacement(placement).orElseThrow();

        BlockPos desiredMin = new BlockPos(chunk.getMinBlockX(), minY, chunk.getMinBlockZ());
        BlockPos templateAnchor = template.getZeroPositionWithTransform(
                desiredMin, placement.mirror(), placement.rotation());
        boolean placedAny = false;
        boolean blockedAny = false;
        boolean retryableFailure = false;
        for (GeneratedAdSurfaceCatalog.Surface surface : surfaces) {
            Result result = placeSurface(
                    level,
                    chunk,
                    placement,
                    templateAnchor,
                    surface,
                    campaign,
                    requireOriginalSupport);
            placedAny |= result == Result.PLACED;
            blockedAny |= result == Result.WORLD_BLOCKED;
            retryableFailure |= result == Result.RETRYABLE_FAILURE;
        }
        if (retryableFailure) return Result.RETRYABLE_FAILURE;
        if (placedAny) return Result.PLACED;
        return blockedAny ? Result.WORLD_BLOCKED : Result.NOT_APPLICABLE;
    }

    private static Result placeSurface(
            ServerLevel level,
            ChunkPos ownerChunk,
            ArnisPatchLibrary.Placement placement,
            BlockPos templateAnchor,
            GeneratedAdSurfaceCatalog.Surface surface,
            AdCampaign campaign,
            boolean requireOriginalSupport) {
        OrientedSurface oriented = orient(placement, templateAnchor, surface);
        if (oriented == null) {
            Cyberdeck.LOGGER.warn("Could not orient generated ad surface {}",
                    placement.patch().catalogId());
            return Result.RETRYABLE_FAILURE;
        }
        BlockPos anchor = oriented.support().relative(oriented.facing());
        if (!generationReady(
                ownerChunk,
                anchor,
                oriented.facing(),
                surface.width(),
                surface.height())) {
            return Result.RETRYABLE_FAILURE;
        }
        if (requireOriginalSupport
                && !matchesOriginalSupport(level, placement, templateAnchor, surface)) {
            Cyberdeck.LOGGER.debug("Skipped modified ad facade {} in {}",
                    placement.patch().catalogId(), ownerChunk);
            return Result.WORLD_BLOCKED;
        }

        Result existingAudit = auditExistingGeneratedGrid(
                level,
                anchor,
                oriented.facing(),
                surface.width(),
                surface.height(),
                campaign);
        if (existingAudit != Result.NOT_APPLICABLE) {
            if (existingAudit == Result.WORLD_BLOCKED) {
                Cyberdeck.LOGGER.info("Removed enclosed generated ad for {} at {}",
                        placement.patch().catalogId(), anchor);
            }
            return existingAudit;
        }

        LargeAdSurfaceValidator.Result validation = LargeAdSurfaceValidator.validateOverlay(
                level,
                anchor,
                oriented.facing(),
                surface.width(),
                surface.height());
        if (!validation.valid()) {
            return validation.failure() == LargeAdSurfaceValidator.Failure.CHUNK_UNLOADED
                    ? Result.RETRYABLE_FAILURE
                    : Result.WORLD_BLOCKED;
        }
        boolean placed = AdDisplayPlacement.placeOverlay(
                level,
                anchor,
                oriented.facing(),
                surface.width(),
                surface.height(),
                campaign);
        if (placed) {
            Cyberdeck.LOGGER.debug("Placed generated {}x{} ad for {} at {}",
                    surface.width(), surface.height(), placement.patch().catalogId(), anchor);
        }
        return placed ? Result.PLACED : Result.RETRYABLE_FAILURE;
    }

    /**
     * Re-audits one exact catalog-owned grid. Complete matching grids are adopted as generated;
     * enclosed grids are removed without touching their supporting building blocks.
     */
    public static Result auditExistingGeneratedGrid(
            ServerLevel level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height) {
        AdCampaign campaign = level.getBlockEntity(anchor)
                instanceof AdDisplayBlockEntity display
                ? display.campaign()
                : AdCampaign.GENERAL;
        return auditExistingGeneratedGrid(
                level, anchor, facing, width, height, campaign);
    }

    public static Result auditExistingGeneratedGrid(
            ServerLevel level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height,
            AdCampaign campaign) {
        Result resized = migrateResizedDisplay(
                level, anchor, facing, width, height, campaign);
        if (resized != Result.NOT_APPLICABLE) {
            return resized;
        }
        if (!matchesExistingGeneratedDisplay(level, anchor, facing, width, height)) {
            return Result.NOT_APPLICABLE;
        }
        LargeAdSurfaceValidator.Result exterior =
                LargeAdSurfaceValidator.validateGeneratedExterior(
                        level, anchor, facing, width, height);
        if (exterior.failure() == LargeAdSurfaceValidator.Failure.CHUNK_UNLOADED) {
            return Result.RETRYABLE_FAILURE;
        }
        AdDisplayBlockEntity display =
                (AdDisplayBlockEntity) level.getBlockEntity(anchor);
        if (exterior.valid()) {
            removeLegacyPanelCells(level, anchor, facing, width, height);
            if (!display.generatedPlacement() || display.campaign() != campaign) {
                display.configureGenerated(width, height, campaign);
            }
            return Result.PLACED;
        }
        removeExistingGeneratedGrid(level, anchor, facing, width, height);
        return Result.WORLD_BLOCKED;
    }

    private static Result migrateResizedDisplay(
            ServerLevel level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height,
            AdCampaign campaign) {
        BlockState anchorState = level.getBlockState(anchor);
        if (!anchorState.is(AdvertisingContent.AD_DISPLAY_ANCHOR.get())
                || anchorState.getValue(AdDisplayBlock.FACING) != facing
                || !(level.getBlockEntity(anchor) instanceof AdDisplayBlockEntity display)
                || (display.displayWidth() == width && display.displayHeight() == height)) {
            return Result.NOT_APPLICABLE;
        }

        int oldWidth = display.displayWidth();
        int oldHeight = display.displayHeight();
        LargeAdSurfaceValidator.Result validation =
                LargeAdSurfaceValidator.validateOverlayReplacement(
                        level, anchor, facing, width, height);
        if (!validation.valid()) {
            return validation.failure() == LargeAdSurfaceValidator.Failure.CHUNK_UNLOADED
                    ? Result.RETRYABLE_FAILURE
                    : Result.WORLD_BLOCKED;
        }
        if (!display.generatedPlacement()) {
            removeLegacyPanelCells(level, anchor, facing, oldWidth, oldHeight);
        }
        display.configureGenerated(width, height, campaign);
        Cyberdeck.LOGGER.info(
                "Resized legacy generated ad at {} from {}x{} to {}x{}",
                anchor, oldWidth, oldHeight, width, height);
        return Result.PLACED;
    }

    private static boolean matchesExistingGeneratedDisplay(
            ServerLevel level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height) {
        BlockState anchorState = level.getBlockState(anchor);
        if (!anchorState.is(AdvertisingContent.AD_DISPLAY_ANCHOR.get())
                || anchorState.getValue(AdDisplayBlock.FACING) != facing
                || !(level.getBlockEntity(anchor) instanceof AdDisplayBlockEntity display)
                || display.displayWidth() != width
                || display.displayHeight() != height) {
            return false;
        }
        if (display.generatedPlacement()) return true;

        List<BlockPos> targets = LargeAdSurfaceValidator.targets(
                anchor, facing, width, height);
        for (int index = 1; index < targets.size(); index++) {
            BlockState panel = level.getBlockState(targets.get(index));
            if (!panel.is(AdvertisingContent.AD_DISPLAY_PANEL.get())
                    || panel.getValue(AdPanelBlock.FACING) != facing) {
                return false;
            }
        }
        return true;
    }

    private static void removeLegacyPanelCells(
            ServerLevel level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height) {
        List<BlockPos> targets = LargeAdSurfaceValidator.targets(
                anchor, facing, width, height);
        for (int index = 1; index < targets.size(); index++) {
            BlockPos target = targets.get(index);
            if (level.getBlockState(target).is(AdvertisingContent.AD_DISPLAY_PANEL.get())) {
                level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void removeExistingGeneratedGrid(
            ServerLevel level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height) {
        for (BlockPos target : LargeAdSurfaceValidator.targets(
                anchor, facing, width, height)) {
            BlockState state = level.getBlockState(target);
            if (state.is(AdvertisingContent.AD_DISPLAY_ANCHOR.get())
                    || state.is(AdvertisingContent.AD_DISPLAY_PANEL.get())) {
                level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** Exact district campaign selected for one catalog-backed building placement. */
    public static Optional<AdCampaign> campaignForPlacement(
            ArnisPatchLibrary.Placement placement) {
        if (GeneratedAdSurfaceCatalog.surfaces(
                placement.patch().catalogId()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(AdCampaign.forDistrict(placement.patch().district()));
    }

    private static boolean matchesOriginalSupport(
            ServerLevel level,
            ArnisPatchLibrary.Placement placement,
            BlockPos templateAnchor,
            GeneratedAdSurfaceCatalog.Surface surface) {
        MessageDigest digest = sha256Digest();
        Direction originalRight = LargeAdSurfaceValidator.rightOf(surface.facing());
        boolean first = true;
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
                if (!first) digest.update((byte) '\n');
                digest.update(normalizeSupport(actual).getBytes(StandardCharsets.UTF_8));
                first = false;
            }
        }
        return HexFormat.of().formatHex(digest.digest()).equals(surface.supportHash());
    }

    private static String normalizeSupport(String blockId) {
        if (blockId.equals("minecraft:glowstone")
                || blockId.equals("minecraft:sea_lantern")
                || blockId.equals("cyberdeck:camouflaged_sea_lantern")) {
            return "cyberdeck:luminous_facade";
        }
        return blockId;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static OrientedSurface orient(
            ArnisPatchLibrary.Placement placement,
            BlockPos templateAnchor,
            GeneratedAdSurfaceCatalog.Surface surface) {
        BlockPos support = StructureTemplate.transform(
                        surface.support(),
                        placement.mirror(),
                        placement.rotation(),
                        BlockPos.ZERO)
                .offset(templateAnchor);
        Direction facing = placement.rotation().rotate(
                placement.mirror().mirror(surface.facing()));
        Direction transformedRight = placement.rotation().rotate(
                placement.mirror().mirror(LargeAdSurfaceValidator.rightOf(surface.facing())));
        Direction expectedRight = LargeAdSurfaceValidator.rightOf(facing);
        if (transformedRight == expectedRight.getOpposite()) {
            support = support.relative(transformedRight, surface.width() - 1);
        } else if (transformedRight != expectedRight) {
            return null;
        }
        return new OrientedSurface(support, facing);
    }

    private static boolean generationReady(
            ChunkPos ownerChunk,
            BlockPos anchor,
            Direction facing,
            int width,
            int height) {
        Direction right = LargeAdSurfaceValidator.rightOf(facing);
        for (int column = 0; column < width; column++) {
            BlockPos target = anchor.relative(right, column);
            for (int depth = -1;
                    depth <= LargeAdSurfaceValidator.GENERATED_EXTERIOR_SEARCH;
                    depth++) {
                if (!generationReady(
                        ownerChunk, chunkAt(target.relative(facing, depth)))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean generationReady(ChunkPos ownerChunk, ChunkPos affectedChunk) {
        return affectedChunk.equals(ownerChunk)
                || !NeonCityGenerator.chunkTouchesCity(
                        affectedChunk.x(), affectedChunk.z())
                || NeonCityGenerator.isGenerated(affectedChunk);
    }

    private static ChunkPos chunkAt(BlockPos position) {
        return new ChunkPos(
                Math.floorDiv(position.getX(), 16),
                Math.floorDiv(position.getZ(), 16));
    }

    private record OrientedSurface(BlockPos support, Direction facing) {
    }
}
