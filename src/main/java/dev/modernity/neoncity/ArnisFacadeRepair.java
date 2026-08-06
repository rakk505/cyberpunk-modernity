package dev.modernity.neoncity;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Seals building cross-sections where an Arnis atlas is interrupted by procedural terrain. */
final class ArnisFacadeRepair {
    private static final int INWARD_SCAN_DEPTH = 4;
    private static final int MIN_STRUCTURAL_LAYERS = 3;
    private static final int MIN_BUILDING_HEIGHT = 6;
    private static final int MAX_STORY_GAP = 7;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;

    private ArnisFacadeRepair() {
    }

    static int sealInterruptedEdges(
            ServerLevelAccessor level,
            ChunkPos chunk,
            Set<ArnisPatchLibrary.Connector.Edge> interruptedEdges) {
        int changed = 0;
        for (ArnisPatchLibrary.Connector.Edge edge : interruptedEdges) {
            changed += sealEdge(level, chunk, edge);
        }
        return changed;
    }

    static int sealInfrastructureCuts(
            ServerLevelAccessor level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples,
            District selectedDistrict) {
        int changed = 0;
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (!NeonCityGenerator.keepsArnisColumn(sample, selectedDistrict)
                        || NeonCityGenerator.overridesArnis(sample.roadClass())) {
                    continue;
                }
                for (int[] direction : directions) {
                    NeonCityGenerator.UrbanSample neighbour = samples[
                            localZ + 1 + direction[1]][localX + 1 + direction[0]];
                    if (NeonCityGenerator.keepsArnisColumn(neighbour, selectedDistrict)
                            && !NeonCityGenerator.overridesArnis(neighbour.roadClass())) {
                        continue;
                    }
                    changed += sealCutFace(
                            level,
                            chunk,
                            chunk.getMinBlockX() + localX,
                            chunk.getMinBlockZ() + localZ,
                            -direction[0],
                            -direction[1]);
                }
            }
        }
        return changed;
    }

    private static int sealCutFace(
            ServerLevelAccessor level,
            ChunkPos chunk,
            int boundaryX,
            int boundaryZ,
            int inwardX,
            int inwardZ) {
        int minY = NeonCityGenerator.CITY_GROUND_Y + 1;
        int height = NeonCityGenerator.MAX_BUILD_Y - minY + 1;
        boolean[] evidence = new boolean[height];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int yIndex = 0; yIndex < height; yIndex++) {
            int structuralDepth = 0;
            for (int depth = 0; depth < INWARD_SCAN_DEPTH; depth++) {
                int x = boundaryX + inwardX * depth;
                int z = boundaryZ + inwardZ * depth;
                if (!insideChunk(chunk, x, z)) break;
                if (isStructuralEvidence(level.getBlockState(cursor.set(x, minY + yIndex, z)))) {
                    structuralDepth++;
                }
            }
            evidence[yIndex] = structuralDepth >= 2;
        }
        if (!isBuildingCrossSection(evidence)) return 0;

        int changed = 0;
        boolean[] completion = completionMask(evidence);
        for (int yIndex = 0; yIndex < completion.length; yIndex++) {
            if (!completion[yIndex]) continue;
            int y = minY + yIndex;
            cursor.set(boundaryX, y, boundaryZ);
            if (!level.isEmptyBlock(cursor)) continue;
            BlockState source = findCutSourceState(
                    level, chunk, boundaryX, boundaryZ, inwardX, inwardZ, y);
            if (source == null) continue;
            level.setBlock(cursor, source, PLACE_FLAGS);
            changed++;
        }
        return changed;
    }

    private static BlockState findCutSourceState(
            ServerLevelAccessor level,
            ChunkPos chunk,
            int boundaryX,
            int boundaryZ,
            int inwardX,
            int inwardZ,
            int y) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int verticalDistance = 0;
             verticalDistance <= MAX_STORY_GAP;
             verticalDistance++) {
            int variants = verticalDistance == 0 ? 1 : 2;
            for (int variant = 0; variant < variants; variant++) {
                int candidateY = y + (variant == 0 ? -verticalDistance : verticalDistance);
                for (int depth = 1; depth < INWARD_SCAN_DEPTH; depth++) {
                    int x = boundaryX + inwardX * depth;
                    int z = boundaryZ + inwardZ * depth;
                    if (!insideChunk(chunk, x, z)) break;
                    BlockState state = level.getBlockState(cursor.set(x, candidateY, z));
                    if (isStructuralEvidence(state) && !state.hasBlockEntity()) {
                        return state;
                    }
                }
            }
        }
        return null;
    }

    private static boolean insideChunk(ChunkPos chunk, int x, int z) {
        return x >= chunk.getMinBlockX() && x <= chunk.getMaxBlockX()
                && z >= chunk.getMinBlockZ() && z <= chunk.getMaxBlockZ();
    }

    private static int sealEdge(
            ServerLevelAccessor level,
            ChunkPos chunk,
            ArnisPatchLibrary.Connector.Edge edge) {
        int minY = NeonCityGenerator.CITY_GROUND_Y + 1;
        int height = NeonCityGenerator.MAX_BUILD_Y - minY + 1;
        boolean[][] evidence = new boolean[16][height];
        boolean[] crossSections = new boolean[16];

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int along = 0; along < 16; along++) {
            for (int yIndex = 0; yIndex < height; yIndex++) {
                int y = minY + yIndex;
                int structuralDepth = 0;
                for (int depth = 0; depth < INWARD_SCAN_DEPTH; depth++) {
                    setEdgePosition(cursor, chunk, edge, along, depth, y);
                    if (isStructuralEvidence(level.getBlockState(cursor))) {
                        structuralDepth++;
                    }
                }
                evidence[along][yIndex] = structuralDepth >= 2;
            }
            crossSections[along] = isBuildingCrossSection(evidence[along]);
        }

        int changed = 0;
        for (int along = 0; along < 16; along++) {
            if (!crossSections[along] || !hasHorizontalSupport(crossSections, along)) {
                continue;
            }
            boolean[] completion = completionMask(evidence[along]);
            for (int yIndex = 0; yIndex < completion.length; yIndex++) {
                if (!completion[yIndex]) {
                    continue;
                }
                int y = minY + yIndex;
                setEdgePosition(cursor, chunk, edge, along, 0, y);
                if (!level.isEmptyBlock(cursor)) {
                    continue;
                }
                BlockState sourceState = findSourceState(level, chunk, edge, along, y);
                if (sourceState == null) {
                    continue;
                }
                level.setBlock(cursor, sourceState, PLACE_FLAGS);
                changed++;
            }
        }
        return changed;
    }

    private static BlockState findSourceState(
            ServerLevelAccessor level,
            ChunkPos chunk,
            ArnisPatchLibrary.Connector.Edge edge,
            int along,
            int y) {
        BlockState sameLayer = findSourceState(level, chunk, edge, along, y, 2);
        if (sameLayer != null) {
            return sameLayer;
        }
        for (int verticalDistance = 1; verticalDistance <= MAX_STORY_GAP; verticalDistance++) {
            BlockState below = findSourceState(
                    level, chunk, edge, along, y - verticalDistance, 2);
            if (below != null) {
                return below;
            }
            BlockState above = findSourceState(
                    level, chunk, edge, along, y + verticalDistance, 2);
            if (above != null) {
                return above;
            }
        }
        return null;
    }

    private static BlockState findSourceState(
            ServerLevelAccessor level,
            ChunkPos chunk,
            ArnisPatchLibrary.Connector.Edge edge,
            int along,
            int y,
            int alongRadius) {
        BlockPos.MutableBlockPos source = new BlockPos.MutableBlockPos();
        for (int alongDistance = 0; alongDistance <= alongRadius; alongDistance++) {
            int directionCount = alongDistance == 0 ? 1 : 2;
            for (int directionIndex = 0; directionIndex < directionCount; directionIndex++) {
                int candidateAlong = along + (directionIndex == 0 ? -alongDistance : alongDistance);
                if (candidateAlong < 0 || candidateAlong >= 16) {
                    continue;
                }
                int firstDepth = alongDistance == 0 ? 1 : 0;
                for (int depth = firstDepth; depth < INWARD_SCAN_DEPTH; depth++) {
                    setEdgePosition(source, chunk, edge, candidateAlong, depth, y);
                    BlockState state = level.getBlockState(source);
                    if (isStructuralEvidence(state) && !state.hasBlockEntity()) {
                        return state;
                    }
                }
            }
        }
        return null;
    }

    private static void setEdgePosition(
            BlockPos.MutableBlockPos cursor,
            ChunkPos chunk,
            ArnisPatchLibrary.Connector.Edge edge,
            int along,
            int depth,
            int y) {
        int minX = chunk.getMinBlockX();
        int minZ = chunk.getMinBlockZ();
        switch (edge) {
            case WEST -> cursor.set(minX + depth, y, minZ + along);
            case EAST -> cursor.set(chunk.getMaxBlockX() - depth, y, minZ + along);
            case NORTH -> cursor.set(minX + along, y, minZ + depth);
            case SOUTH -> cursor.set(minX + along, y, chunk.getMaxBlockZ() - depth);
        }
    }

    private static boolean isStructuralEvidence(BlockState state) {
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && !(state.getBlock() instanceof LeavesBlock)
                && state.blocksMotion();
    }

    private static boolean hasHorizontalSupport(boolean[] crossSections, int along) {
        return (along > 0 && crossSections[along - 1])
                || (along + 1 < crossSections.length && crossSections[along + 1]);
    }

    static boolean isBuildingCrossSection(boolean[] evidence) {
        int first = -1;
        int last = -1;
        int layers = 0;
        for (int index = 0; index < evidence.length; index++) {
            if (!evidence[index]) {
                continue;
            }
            if (first < 0) {
                first = index;
            }
            last = index;
            layers++;
        }
        return layers >= MIN_STRUCTURAL_LAYERS
                && last - first >= MIN_BUILDING_HEIGHT;
    }

    static boolean[] completionMask(boolean[] evidence) {
        boolean[] completion = new boolean[evidence.length];
        if (!isBuildingCrossSection(evidence)) {
            return completion;
        }
        for (int index = 0; index < evidence.length; index++) {
            if (evidence[index]) {
                completion[index] = true;
                continue;
            }
            int below = nearestEvidence(evidence, index, -1);
            int above = nearestEvidence(evidence, index, 1);
            completion[index] = below >= 0
                    && above >= 0
                    && above - below <= MAX_STORY_GAP;
        }
        return completion;
    }

    private static int nearestEvidence(boolean[] evidence, int origin, int direction) {
        for (int distance = 1; distance <= MAX_STORY_GAP; distance++) {
            int index = origin + distance * direction;
            if (index < 0 || index >= evidence.length) {
                return -1;
            }
            if (evidence[index]) {
                return index;
            }
        }
        return -1;
    }

}
