package dev.modernity.neoncity;

import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/** Deterministic cultural terrain and inhabitants layered over the shared city grammar. */
final class DistrictWorldFeatures {
    private static final long SNOW_SALT = 0x59434F5250534E4FL;
    private static final long HILL_SALT = 0x424F524445524849L;
    private static final long FARMER_SALT = 0x534641524D455253L;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final String S_CORP_FARMER_TAG = "cyberdeck_s_corp_farmer";

    private DistrictWorldFeatures() {
    }

    static boolean isFarmIrrigation(NeonCityGenerator.UrbanSample sample) {
        int row = (int) Math.floor(sample.parcelLocalU());
        return Math.floorMod(row, 13) == 0;
    }

    static BlockState farmSurface(NeonCityGenerator.UrbanSample sample) {
        return isFarmIrrigation(sample)
                ? Blocks.WATER.defaultBlockState()
                : Blocks.FARMLAND.defaultBlockState().setValue(
                        FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE);
    }

    static BlockState matureWheat() {
        return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE);
    }

    static int snowLayers(long seed, int x, int z) {
        double phaseX = phase(seed ^ SNOW_SALT);
        double phaseZ = phase(Long.rotateLeft(seed ^ SNOW_SALT, 21));
        double drift = Math.sin(x / 18.0 + phaseX)
                + Math.cos(z / 23.0 + phaseZ)
                + 0.65 * Math.sin((x + z) / 37.0 + phaseX - phaseZ);
        double normalized = (drift + 2.65) / 5.3;
        if (normalized < 0.18) {
            return 0;
        }
        return Math.max(1, Math.min(8,
                1 + (int) Math.floor((normalized - 0.18) * 8.6)));
    }

    static int borderHillHeight(long seed, int x, int z) {
        double phaseX = phase(seed ^ HILL_SALT);
        double phaseZ = phase(Long.rotateLeft(seed ^ HILL_SALT, 27));
        double broad = 3.8 * Math.sin(x / 53.0 + phaseX)
                + 3.1 * Math.cos(z / 61.0 + phaseZ);
        double ridge = 2.6 * Math.abs(Math.sin((x + z) / 79.0 + phaseX * 0.7));
        double detail = 1.4 * Math.sin(x / 21.0 + phaseZ)
                * Math.cos(z / 25.0 - phaseX);
        int rise = (int) Math.round(8.0 + broad + ridge + detail);
        return NeonCityGenerator.CITY_GROUND_Y + Math.max(4, Math.min(18, rise));
    }

    static BlockState borderHillSurface(long seed, int x, int z) {
        int center = borderHillHeight(seed, x, z);
        int slope = Math.max(
                Math.max(Math.abs(center - borderHillHeight(seed, x - 1, z)),
                        Math.abs(center - borderHillHeight(seed, x + 1, z))),
                Math.max(Math.abs(center - borderHillHeight(seed, x, z - 1)),
                        Math.abs(center - borderHillHeight(seed, x, z + 1))));
        double exposure = Math.sin(x / 29.0 + phase(seed ^ HILL_SALT))
                + Math.cos(z / 35.0 + phase(Long.rotateRight(seed ^ HILL_SALT, 19)))
                + 0.45 * Math.sin((x - z) / 47.0);
        if (slope >= 2 || exposure > 1.35) {
            return Blocks.STONE.defaultBlockState();
        }
        if (exposure < -1.2) {
            return Blocks.COARSE_DIRT.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    static boolean isHillTreeAnchor(long seed, int x, int z) {
        return ParkTreeLibrary.isForestTreeAnchor(seed, x, z);
    }

    static boolean isHillVillageCandidate(long seed, int chunkX, int chunkZ) {
        return BorderVillageLibrary.isCandidateChunk(seed, chunkX, chunkZ);
    }

    static boolean isSCorpFarmer(Entity entity) {
        return entity instanceof Villager
                && entity.getPersistentData().getBoolean(S_CORP_FARMER_TAG).orElse(false);
    }

    static void decorateChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        long seed = NeonCityGenerator.layout().seed();
        MerchantTruckLibrary.decorateChunk(level, chunk, samples);
        VendorStallLibrary.decorateChunk(level, chunk, samples);
        ParkTreeLibrary.decorateChunk(level, chunk, samples);
        WalledBorderLibrary.decorateChunk(level, chunk, samples);
        BorderVillageLibrary.decorateChunk(level, chunk, samples);
        CliffInfrastructureLibrary.decorateChunk(level, chunk);
        ParkTreeLibrary.decorateBorderChunk(level, chunk, samples);
        placeSnowDrifts(level, chunk, samples, seed);
        placeFarmWorker(level, chunk, samples, seed);
    }

    private static void placeSnowDrifts(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples,
            long seed) {
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (sample.district() != District.Y_CORP
                        || sample.zone() == MegacityLayout.Zone.WILDERNESS) {
                    continue;
                }
                int x = chunk.getMinBlockX() + localX;
                int z = chunk.getMinBlockZ() + localZ;
                int layers = snowLayers(seed, x, z);
                if (layers == 0) {
                    continue;
                }
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos snowPos = new BlockPos(x, y, z);
                BlockState snow = Blocks.SNOW.defaultBlockState().setValue(
                        SnowLayerBlock.LAYERS, layers);
                if (level.isEmptyBlock(snowPos) && snow.canSurvive(level, snowPos)) {
                    level.setBlock(snowPos, snow, PLACE_FLAGS);
                }
            }
        }
    }

    private static void placeFarmWorker(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples,
            long seed) {
        long hash = MegacityLayout.mix(seed ^ FARMER_SALT, chunk.x(), chunk.z());
        if (Math.floorMod((int) (hash ^ (hash >>> 32)), 5) != 0) {
            return;
        }
        BlockPos anchor = farmAnchor(chunk, samples);
        if (anchor == null) {
            return;
        }
        AABB chunkBounds = new AABB(
                chunk.getMinBlockX(),
                level.getMinY(),
                chunk.getMinBlockZ(),
                chunk.getMaxBlockX() + 1,
                level.getMaxY(),
                chunk.getMaxBlockZ() + 1);
        if (!level.getEntitiesOfClass(Villager.class, chunkBounds,
                DistrictWorldFeatures::isSCorpFarmer).isEmpty()) {
            return;
        }
        level.setBlock(anchor, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos spawn = anchor.east();
        Villager farmer = createFarmWorker(level, spawn);
        if (farmer != null) {
            level.addFreshEntity(farmer);
        }
    }

    private static BlockPos farmAnchor(
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        return java.util.stream.IntStream.range(0, 256)
                .mapToObj(index -> new int[]{index & 15, index >> 4})
                .filter(cell -> cell[0] >= 2 && cell[0] <= 13
                        && cell[1] >= 2 && cell[1] <= 13)
                .filter(cell -> {
                    NeonCityGenerator.UrbanSample sample = samples[cell[1] + 1][cell[0] + 1];
                    return sample.district() == District.S_CORP
                            && sample.roadClass() == NeonCityGenerator.RoadClass.FARM
                            && !isFarmIrrigation(sample);
                })
                .min(Comparator.comparingInt(cell ->
                        Math.abs(cell[0] - 8) + Math.abs(cell[1] - 8)))
                .map(cell -> {
                    NeonCityGenerator.UrbanSample sample = samples[cell[1] + 1][cell[0] + 1];
                    return new BlockPos(
                            chunk.getMinBlockX() + cell[0],
                            sample.groundY() + 1,
                            chunk.getMinBlockZ() + cell[1]);
                })
                .orElse(null);
    }

    static Villager createFarmWorker(ServerLevel level, BlockPos spawn) {
        Villager farmer = EntityTypes.VILLAGER.create(level, EntitySpawnReason.STRUCTURE);
        if (farmer == null) {
            return null;
        }
        farmer.snapTo(spawn, 0.0F, 0.0F);
        farmer.finalizeSpawn(
                level,
                level.getCurrentDifficultyAt(spawn),
                EntitySpawnReason.STRUCTURE,
                null);
        farmer.setVillagerData(farmer.getVillagerData()
                .withProfession(level.registryAccess(), VillagerProfession.FARMER));
        farmer.setVillagerDataFinalized(true);
        farmer.getPersistentData().putBoolean(S_CORP_FARMER_TAG, true);
        farmer.setPersistenceRequired();
        farmer.setHomeTo(spawn, 24);
        farmer.setCustomName(Component.literal("S Corp Farmer"));
        farmer.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 64));
        return farmer;
    }

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, PLACE_FLAGS);
    }

    private static double phase(long value) {
        return ((value >>> 11) & 0xFFFF) / 65535.0 * Math.PI * 2.0;
    }

}
