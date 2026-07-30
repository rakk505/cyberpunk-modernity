package dev.modernity.neoncity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/** Bounded, collision-aware placement for the converted Exsilit park tree collection. */
final class ParkTreeLibrary {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CATALOG_RESOURCE = "/data/neoncity/park_trees/catalog.json";
    private static final long PARK_TREE_SALT = 0x455853494C49544CL;
    private static final int EXPECTED_TREE_COUNT = 68;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;

    private static final List<TreeAsset> TREES = loadCatalog();

    private ParkTreeLibrary() {
    }

    record TreeAsset(
            String catalogId,
            Identifier templateId,
            TreeForm form,
            int sizeX,
            int sizeY,
            int sizeZ,
            int blockCount,
            String sha256) {
    }

    enum TreeForm {
        BROADLEAF,
        CONIFER
    }

    private record TreeMaterials(Block trunk, Block leaves) {
    }

    static List<TreeAsset> templates() {
        return TREES;
    }

    static TreeAsset template(String catalogId) {
        return TREES.stream()
                .filter(tree -> tree.catalogId().equals(catalogId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown park tree " + catalogId));
    }

    static int decorateChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        int parkColumns = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                if (samples[localZ + 1][localX + 1].roadClass()
                        == NeonCityGenerator.RoadClass.PARK) {
                    parkColumns++;
                }
            }
        }
        if (parkColumns < 9) {
            return 0;
        }

        int targetTrees = parkColumns >= 112 ? 2 : 1;
        long chunkHash = MegacityLayout.mix(
                NeonCityGenerator.layout().seed() ^ PARK_TREE_SALT,
                chunk.x(), chunk.z());
        int startX = Math.floorMod((int) chunkHash, 16);
        int startZ = Math.floorMod((int) (chunkHash >>> 32), 16);
        List<BlockPos> placedCenters = new ArrayList<>();

        for (int attempt = 0; attempt < 256 && placedCenters.size() < targetTrees; attempt++) {
            int localX = Math.floorMod(startX + (attempt % 16) * 5, 16);
            int localZ = Math.floorMod(startZ + (attempt / 16) * 7, 16);
            NeonCityGenerator.UrbanSample centerSample = samples[localZ + 1][localX + 1];
            if (centerSample.roadClass() != NeonCityGenerator.RoadClass.PARK) {
                continue;
            }

            int worldX = chunk.getMinBlockX() + localX;
            int worldZ = chunk.getMinBlockZ() + localZ;
            BlockPos center = new BlockPos(worldX, centerSample.groundY() + 1, worldZ);
            if (placedCenters.stream().anyMatch(
                    existing -> horizontalDistanceSquared(existing, center) < 64)) {
                continue;
            }

            long treeHash = MegacityLayout.mix(chunkHash, worldX, worldZ);
            int treeStart = Math.floorMod((int) (treeHash ^ (treeHash >>> 32)), TREES.size());
            TreeForm desiredForm = form(centerSample.district());
            for (int treeOffset = 0; treeOffset < TREES.size(); treeOffset++) {
                TreeAsset tree = TREES.get(Math.floorMod(treeStart + treeOffset, TREES.size()));
                if (tree.form() != desiredForm) {
                    continue;
                }
                int minLocalX = localX - tree.sizeX() / 2;
                int minLocalZ = localZ - tree.sizeZ() / 2;
                if (!isParkFootprint(
                        samples,
                        minLocalX,
                        minLocalZ,
                        tree.sizeX(),
                        tree.sizeZ(),
                        centerSample.groundY())) {
                    continue;
                }
                BlockPos base = new BlockPos(
                        chunk.getMinBlockX() + minLocalX,
                        centerSample.groundY() + 1,
                        chunk.getMinBlockZ() + minLocalZ);
                if (!isVolumeClear(level, base, tree)) {
                    continue;
                }
                if (placeTree(level, base, centerSample.district(), tree, treeHash)) {
                    placedCenters.add(center);
                    LOGGER.debug(
                            "[NeonCity] placed Exsilit park tree {} for {} at {}",
                            tree.catalogId(), centerSample.district().label(), base);
                    break;
                }
            }
        }
        return placedCenters.size();
    }

    static boolean placeTree(
            ServerLevel level,
            BlockPos base,
            District district,
            TreeAsset tree,
            long placementHash) {
        StructureTemplate template = level.getStructureManager().get(tree.templateId()).orElse(null);
        if (template == null) {
            LOGGER.error("[NeonCity] missing park tree template {}", tree.templateId());
            return false;
        }
        Vec3i size = template.getSize();
        if (size.getX() != tree.sizeX()
                || size.getY() != tree.sizeY()
                || size.getZ() != tree.sizeZ()) {
            LOGGER.error(
                    "[NeonCity] park tree {} size {} disagrees with catalog {}x{}x{}",
                    tree.catalogId(), size, tree.sizeX(), tree.sizeY(), tree.sizeZ());
            return false;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                .addProcessor(new DistrictTreeMaterialProcessor(district));
        return template.placeInWorld(
                level,
                base,
                base,
                settings,
                RandomSource.create(placementHash),
                PLACE_FLAGS);
    }

    private static boolean isParkFootprint(
            NeonCityGenerator.UrbanSample[][] samples,
            int minLocalX,
            int minLocalZ,
            int sizeX,
            int sizeZ,
            int groundY) {
        if (minLocalX < 0
                || minLocalZ < 0
                || minLocalX + sizeX > 16
                || minLocalZ + sizeZ > 16) {
            return false;
        }
        for (int localZ = minLocalZ; localZ < minLocalZ + sizeZ; localZ++) {
            for (int localX = minLocalX; localX < minLocalX + sizeX; localX++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (sample.roadClass() != NeonCityGenerator.RoadClass.PARK
                        || sample.groundY() != groundY) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isVolumeClear(ServerLevel level, BlockPos base, TreeAsset tree) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < tree.sizeY(); y++) {
            for (int z = 0; z < tree.sizeZ(); z++) {
                for (int x = 0; x < tree.sizeX(); x++) {
                    cursor.set(base.getX() + x, base.getY() + y, base.getZ() + z);
                    if (!level.isEmptyBlock(cursor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int horizontalDistanceSquared(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static List<TreeAsset> loadCatalog() {
        try (InputStream stream = ParkTreeLibrary.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing park tree catalog " + CATALOG_RESOURCE);
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray templates = root.getAsJsonArray("templates");
            List<TreeAsset> result = new ArrayList<>(templates.size());
            Set<String> ids = new HashSet<>();
            for (JsonElement element : templates) {
                JsonObject tree = element.getAsJsonObject();
                String id = tree.get("id").getAsString();
                if (!ids.add(id)) {
                    throw new IllegalStateException("duplicate park tree id " + id);
                }
                JsonArray size = tree.getAsJsonArray("size");
                result.add(new TreeAsset(
                        id,
                        Identifier.fromNamespaceAndPath(NeonCityGenerator.NAMESPACE, "park_trees/" + id),
                        TreeForm.valueOf(tree.get("form").getAsString().toUpperCase(Locale.ROOT)),
                        size.get(0).getAsInt(),
                        size.get(1).getAsInt(),
                        size.get(2).getAsInt(),
                        tree.get("blocks").getAsInt(),
                        tree.get("sha256").getAsString()));
            }
            if (result.size() != EXPECTED_TREE_COUNT) {
                throw new IllegalStateException(
                        "expected " + EXPECTED_TREE_COUNT + " park trees, found " + result.size());
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("could not load park tree catalog", exception);
        }
    }

    private static TreeMaterials materials(District district) {
        return switch (district.treeStyle()) {
            case FORMAL -> new TreeMaterials(Blocks.DARK_OAK_LOG, Blocks.AZALEA_LEAVES);
            case BROADLEAF -> new TreeMaterials(Blocks.OAK_LOG, Blocks.OAK_LEAVES);
            case EVERGREEN, ALPINE, WINTER ->
                    new TreeMaterials(Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
            case ARID, MEDITERRANEAN ->
                    new TreeMaterials(Blocks.ACACIA_LOG, Blocks.ACACIA_LEAVES);
            case TROPICAL -> new TreeMaterials(Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES);
            case CHERRY -> new TreeMaterials(Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES);
            case INDUSTRIAL -> new TreeMaterials(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES);
        };
    }

    private static TreeForm form(District district) {
        return switch (district.treeStyle()) {
            case EVERGREEN, ALPINE, WINTER -> TreeForm.CONIFER;
            default -> TreeForm.BROADLEAF;
        };
    }

    private static final class DistrictTreeMaterialProcessor implements StructureProcessor {
        private final TreeMaterials materials;

        private DistrictTreeMaterialProcessor(District district) {
            this.materials = materials(district);
        }

        @Override
        public MapCodec<? extends StructureProcessor> codec() {
            return MapCodec.unit(this);
        }

        @Override
        public StructureTemplate.StructureBlockInfo processBlock(
                LevelReader level,
                BlockPos targetPosition,
                BlockPos referencePos,
                BlockPos placementPosition,
                StructureTemplate.StructureBlockInfo processedBlockInfo,
                StructurePlaceSettings settings) {
            BlockState source = processedBlockInfo.state();
            BlockState replacement;
            if (source.is(Blocks.OAK_LOG)) {
                replacement = materials.trunk().defaultBlockState().setValue(
                        RotatedPillarBlock.AXIS,
                        source.getValue(RotatedPillarBlock.AXIS));
            } else if (source.is(Blocks.OAK_LEAVES)) {
                replacement = materials.leaves().defaultBlockState()
                        .setValue(LeavesBlock.PERSISTENT, true);
            } else {
                return processedBlockInfo;
            }
            return new StructureTemplate.StructureBlockInfo(
                    processedBlockInfo.pos(), replacement, processedBlockInfo.nbt());
        }
    }
}
