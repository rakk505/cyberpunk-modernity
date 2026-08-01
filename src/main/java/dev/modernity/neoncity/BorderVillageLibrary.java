package dev.modernity.neoncity;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/** Sparse, chunk-bounded vanilla village houses for flat forested district borders. */
final class BorderVillageLibrary {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CANDIDATE_SALT = 0x464F524553545649L;
    private static final long ATTEMPT_SALT = 0x56494C4C41474553L;
    private static final int REGION_CHUNKS = 4;
    private static final int ATTEMPTS = 16;
    private static final int EDGE_MARGIN = 1;
    private static final double REGION_DENSITY = 0.70;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;

    private static final List<VillageAsset> ASSETS = List.of(
            house("plains", "plains_small_house_1", 7, 7, 7),
            house("plains", "plains_small_house_3", 7, 7, 7),
            house("plains", "plains_small_house_7", 7, 7, 8),
            house("plains", "plains_small_house_8", 8, 9, 9),
            house("taiga", "taiga_small_house_1", 7, 8, 9),
            house("taiga", "taiga_small_house_2", 7, 7, 7),
            house("taiga", "taiga_small_house_4", 7, 6, 8),
            house("taiga", "taiga_small_house_5", 9, 7, 7),
            house("snowy", "snowy_small_house_1", 7, 5, 6),
            house("snowy", "snowy_small_house_2", 7, 8, 7),
            house("savanna", "savanna_small_house_1", 7, 7, 7),
            house("savanna", "savanna_small_house_2", 7, 7, 7),
            house("desert", "desert_small_house_1", 6, 6, 5),
            house("desert", "desert_small_house_2", 7, 6, 5));

    private BorderVillageLibrary() {
    }

    record VillageAsset(
            String catalogId,
            Identifier templateId,
            int sizeX,
            int sizeY,
            int sizeZ) {
        int sizeX(Rotation rotation) {
            return swapsAxes(rotation) ? sizeZ : sizeX;
        }

        int sizeZ(Rotation rotation) {
            return swapsAxes(rotation) ? sizeX : sizeZ;
        }
    }

    record VillageCandidate(
            VillageAsset asset,
            int chunkX,
            int chunkZ,
            int localX,
            int localZ,
            Rotation rotation,
            long selectionHash) {
        int sizeX() {
            return asset.sizeX(rotation);
        }

        int sizeZ() {
            return asset.sizeZ(rotation);
        }

        int minX() {
            return (chunkX << 4) + localX;
        }

        int minZ() {
            return (chunkZ << 4) + localZ;
        }

        BoundingBox bounds() {
            return new BoundingBox(
                    minX(),
                    NeonCityGenerator.CITY_GROUND_Y,
                    minZ(),
                    minX() + sizeX() - 1,
                    NeonCityGenerator.CITY_GROUND_Y + asset.sizeY() - 1,
                    minZ() + sizeZ() - 1);
        }
    }

    static List<VillageAsset> templates() {
        return ASSETS;
    }

    static int regionChunks() {
        return REGION_CHUNKS;
    }

    static double regionDensity() {
        return REGION_DENSITY;
    }

    /** At most one chunk in each region can own a village house. */
    static boolean isCandidateChunk(long seed, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, REGION_CHUNKS);
        int regionZ = Math.floorDiv(chunkZ, REGION_CHUNKS);
        long hash = MegacityLayout.mix(seed ^ CANDIDATE_SALT, regionX, regionZ);
        if (unit(Long.rotateLeft(hash, 17)) >= REGION_DENSITY) {
            return false;
        }
        int ownerX = regionX * REGION_CHUNKS
                + Math.floorMod((int) hash, REGION_CHUNKS);
        int ownerZ = regionZ * REGION_CHUNKS
                + Math.floorMod((int) (hash >>> 32), REGION_CHUNKS);
        return chunkX == ownerX && chunkZ == ownerZ;
    }

    /** Resolves the first deterministic house whose entire footprint is usable forest. */
    static Optional<VillageCandidate> eligibleCandidate(
            long seed,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        if (!isCandidateChunk(seed, chunk.x(), chunk.z())) {
            return Optional.empty();
        }
        long baseHash = MegacityLayout.mix(
                seed ^ ATTEMPT_SALT, chunk.x(), chunk.z());
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long hash = MegacityLayout.mix(
                    baseHash ^ Long.rotateLeft(ATTEMPT_SALT, attempt + 1),
                    chunk.x() + attempt * 31,
                    chunk.z() - attempt * 17);
            VillageAsset asset = ASSETS.get(Math.floorMod((int) hash, ASSETS.size()));
            Rotation rotation = Rotation.values()[Math.floorMod(
                    (int) (hash >>> 16), Rotation.values().length)];
            int sizeX = asset.sizeX(rotation);
            int sizeZ = asset.sizeZ(rotation);
            int originChoicesX = 16 - EDGE_MARGIN * 2 - sizeX + 1;
            int originChoicesZ = 16 - EDGE_MARGIN * 2 - sizeZ + 1;
            if (originChoicesX <= 0 || originChoicesZ <= 0) {
                continue;
            }
            int localX = EDGE_MARGIN + Math.floorMod(
                    (int) (hash >>> 32), originChoicesX);
            int localZ = EDGE_MARGIN + Math.floorMod(
                    (int) Long.rotateRight(hash, 11), originChoicesZ);
            if (isEligibleFootprint(samples, localX, localZ, sizeX, sizeZ)) {
                return Optional.of(new VillageCandidate(
                        asset,
                        chunk.x(),
                        chunk.z(),
                        localX,
                        localZ,
                        rotation,
                        hash));
            }
        }
        return Optional.empty();
    }

    static boolean isEligibleFootprint(
            NeonCityGenerator.UrbanSample[][] samples,
            int minLocalX,
            int minLocalZ,
            int sizeX,
            int sizeZ) {
        if (minLocalX < 0
                || minLocalZ < 0
                || minLocalX + sizeX > 16
                || minLocalZ + sizeZ > 16) {
            return false;
        }
        for (int localZ = minLocalZ; localZ < minLocalZ + sizeZ; localZ++) {
            for (int localX = minLocalX; localX < minLocalX + sizeX; localX++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (sample.roadClass() != NeonCityGenerator.RoadClass.BORDER_FOREST
                        || sample.groundY() != NeonCityGenerator.CITY_GROUND_Y) {
                    return false;
                }
            }
        }
        return true;
    }

    static int decorateChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        long seed = NeonCityGenerator.layout().seed();
        Optional<VillageCandidate> planned = eligibleCandidate(seed, chunk, samples);
        if (planned.isEmpty()) {
            return 0;
        }
        VillageCandidate candidate = planned.orElseThrow();
        StructureTemplate template = level.getStructureManager()
                .get(candidate.asset().templateId()).orElse(null);
        if (template == null) {
            LOGGER.error("[NeonCity] missing forest-border village template {}",
                    candidate.asset().templateId());
            return 0;
        }
        Vec3i catalogSize = template.getSize();
        if (catalogSize.getX() != candidate.asset().sizeX()
                || catalogSize.getY() != candidate.asset().sizeY()
                || catalogSize.getZ() != candidate.asset().sizeZ()) {
            LOGGER.error(
                    "[NeonCity] village template {} size {} disagrees with catalog {}x{}x{}",
                    candidate.asset().templateId(),
                    catalogSize,
                    candidate.asset().sizeX(),
                    candidate.asset().sizeY(),
                    candidate.asset().sizeZ());
            return 0;
        }
        if (!isVolumeClear(level, candidate)) {
            return 0;
        }

        BlockPos desiredMin = new BlockPos(
                candidate.minX(), NeonCityGenerator.CITY_GROUND_Y, candidate.minZ());
        BlockPos anchor = template.getZeroPositionWithTransform(
                desiredMin, Mirror.NONE, candidate.rotation());
        BoundingBox destinationBounds = candidate.bounds();
        StructurePlaceSettings settings = placementSettings(candidate, destinationBounds);
        BoundingBox transformedBounds = template.getBoundingBox(settings, anchor);
        if (!sameBounds(destinationBounds, transformedBounds)) {
            LOGGER.error(
                    "[NeonCity] transformed village template {} escaped chunk {}: expected {}, got {}",
                    candidate.asset().templateId(), chunk, destinationBounds, transformedBounds);
            return 0;
        }
        boolean placed = template.placeInWorld(
                level,
                anchor,
                anchor,
                settings,
                RandomSource.create(candidate.selectionHash()),
                PLACE_FLAGS);
        if (placed) {
            LOGGER.debug(
                    "[NeonCity] placed forest-border village house {} at {} in {}",
                    candidate.asset().catalogId(), desiredMin, chunk);
            return 1;
        }
        LOGGER.error("[NeonCity] forest-border village template {} refused placement in {}",
                candidate.asset().templateId(), chunk);
        return 0;
    }

    static StructurePlaceSettings placementSettings(
            VillageCandidate candidate,
            BoundingBox destinationBounds) {
        // Vanilla village houses use LegacySinglePoolElement: replace jigsaws first, then
        // preserve the destination wherever the template contains structure blocks or air.
        return new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setMirror(Mirror.NONE)
                .setRotation(candidate.rotation())
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                .setBoundingBox(destinationBounds)
                .addProcessor(JigsawReplacementProcessor.INSTANCE)
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_AND_AIR);
    }

    static boolean isVolumeClear(ServerLevel level, VillageCandidate candidate) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = 0; z < candidate.sizeZ(); z++) {
            for (int x = 0; x < candidate.sizeX(); x++) {
                cursor.set(
                        candidate.minX() + x,
                        NeonCityGenerator.CITY_GROUND_Y,
                        candidate.minZ() + z);
                if (!isForestGround(level, cursor)) {
                    return false;
                }
                for (int y = 1; y < candidate.asset().sizeY(); y++) {
                    cursor.setY(NeonCityGenerator.CITY_GROUND_Y + y);
                    if (!level.isEmptyBlock(cursor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isForestGround(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MOSS_BLOCK);
    }

    private static VillageAsset house(
            String biome,
            String catalogId,
            int sizeX,
            int sizeY,
            int sizeZ) {
        return new VillageAsset(
                catalogId,
                Identifier.fromNamespaceAndPath(
                        "minecraft", "village/" + biome + "/houses/" + catalogId),
                sizeX,
                sizeY,
                sizeZ);
    }

    private static boolean swapsAxes(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90
                || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    private static boolean sameBounds(BoundingBox left, BoundingBox right) {
        return left.minX() == right.minX()
                && left.minY() == right.minY()
                && left.minZ() == right.minZ()
                && left.maxX() == right.maxX()
                && left.maxY() == right.maxY()
                && left.maxZ() == right.maxZ();
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }
}
