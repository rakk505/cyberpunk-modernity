package dev.modernity.neoncity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Deterministic compact vendor stalls for open spaces that cannot host a full truck. */
final class VendorStallLibrary {
    private static final long STALL_SALT = 0x56454E444F525354L;
    private static final long ROLE_SALT = 0x5354414C4C524F4CL;
    private static final int MAX_SEARCH_RINGS = 12;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final List<Direction> FACINGS = List.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final Map<CacheKey, Optional<StallCandidate>> CACHE = new HashMap<>();

    record StallCandidate(
            District district,
            BlockPos sitePos,
            Direction facing,
            long selectionHash) {
        BlockPos merchantPos() {
            return sitePos;
        }

        float yaw() {
            return switch (facing) {
                case NORTH -> 180.0F;
                case EAST -> -90.0F;
                case WEST -> 90.0F;
                default -> 0.0F;
            };
        }
    }

    private record CacheKey(long seed, District district) {
    }

    private VendorStallLibrary() {
    }

    static Optional<StallCandidate> canonical(District district) {
        CacheKey key = new CacheKey(NeonCityGenerator.layout().seed(), district);
        return CACHE.computeIfAbsent(key, ignored -> find(district));
    }

    static MerchantTruckLibrary.MerchantRole plannedRole(District district) {
        if (MerchantTruckLibrary.canonicalBlackTruck(district).isEmpty()) {
            return MerchantTruckLibrary.MerchantRole.QUEST;
        }
        MerchantTruckLibrary.MerchantRole[] roles = {
                MerchantTruckLibrary.MerchantRole.GUN,
                MerchantTruckLibrary.MerchantRole.CYBERWARE,
                MerchantTruckLibrary.MerchantRole.CLOTHING,
                MerchantTruckLibrary.MerchantRole.CONSUMABLE
        };
        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        long hash = MegacityLayout.mix(
                NeonCityGenerator.layout().seed() ^ ROLE_SALT, node.x(), node.z());
        return roles[Math.floorMod((int) (hash ^ (hash >>> 32)), roles.length)];
    }

    static int decorateChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples) {
        VendorAnchorData data = VendorAnchorData.get(level);
        District district = samples[9][9].district();
        StallCandidate candidate = canonical(district).orElse(null);
        if (candidate == null || !ChunkPos.containing(candidate.sitePos()).equals(chunk)) {
            return 0;
        }
        MerchantTruckLibrary.MerchantRole role = plannedRole(district);
        if (role == MerchantTruckLibrary.MerchantRole.QUEST
                && data.fixer(district).isPresent()) {
            return 0;
        }
        return data.anchor(VendorService.siteId(candidate.sitePos())).isEmpty()
                && place(level, candidate, role) ? 1 : 0;
    }

    static boolean place(
            ServerLevel level,
            StallCandidate candidate,
            MerchantTruckLibrary.MerchantRole role) {
        String siteId = VendorService.siteId(candidate.sitePos());
        VendorAnchorData data = VendorAnchorData.get(level);
        if (data.anchor(siteId).isPresent()) {
            return false;
        }

        Map<BlockPos, BlockState> blocks = stallBlocks(candidate, role);
        if (!volumeClear(level, candidate, blocks)) {
            return false;
        }
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), PLACE_FLAGS);
        }

        data.register(
                siteId,
                role,
                candidate.district(),
                candidate.sitePos(),
                candidate.merchantPos(),
                candidate.yaw(),
                null);
        Villager merchant = MerchantTruckLibrary.createMerchant(
                level,
                candidate.merchantPos(),
                candidate.yaw(),
                role,
                candidate.district(),
                candidate.sitePos());
        if (merchant != null && level.addFreshEntity(merchant)) {
            VendorService.register(
                    level,
                    merchant,
                    role,
                    candidate.district(),
                    candidate.sitePos(),
                    candidate.merchantPos(),
                    candidate.yaw());
        }
        return true;
    }

    static Map<BlockPos, BlockState> stallBlocks(
            StallCandidate candidate,
            MerchantTruckLibrary.MerchantRole role) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockState canopy = Blocks.CONCRETE.pick(role.truckColor()).defaultBlockState();
        BlockState accent = Blocks.CONCRETE.pick(
                role == MerchantTruckLibrary.MerchantRole.QUEST
                        ? DyeColor.YELLOW : DyeColor.WHITE).defaultBlockState();

        for (int depth = -1; depth <= 2; depth++) {
            for (int across = -2; across <= 2; across++) {
                blocks.put(at(candidate, across, depth, 3),
                        across == 0 || (depth == -1 && Math.abs(across) == 2)
                                ? accent : canopy);
            }
        }
        blocks.put(at(candidate, 0, 0, 3), Blocks.SEA_LANTERN.defaultBlockState());

        for (int depth : List.of(-1, 2)) {
            for (int across : List.of(-2, 2)) {
                for (int y = 0; y <= 2; y++) {
                    blocks.put(at(candidate, across, depth, y),
                            Blocks.IRON_BARS.defaultBlockState());
                }
            }
        }
        blocks.put(at(candidate, -2, 1, 0), Blocks.BARREL.defaultBlockState());
        blocks.put(at(candidate, 0, 1, 0), Blocks.LECTERN.defaultBlockState());
        blocks.put(at(candidate, 2, 1, 0), Blocks.BARREL.defaultBlockState());
        return Map.copyOf(blocks);
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static Optional<StallCandidate> find(District district) {
        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        Optional<StallCandidate> plaza = findCentralPlaza(district, node, null);
        if (plaza.isPresent()) return plaza;

        MerchantTruckLibrary.TruckCandidate fixerTruck =
                MerchantTruckLibrary.canonicalBlackTruck(district).orElse(null);
        int centerChunkX = Math.floorDiv(node.x(), 16);
        int centerChunkZ = Math.floorDiv(node.z(), 16);

        for (int ring = 0; ring <= MAX_SEARCH_RINGS; ring++) {
            List<StallCandidate> candidates = new ArrayList<>();
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    collectCandidates(district, chunkX, chunkZ, fixerTruck, candidates);
                }
            }
            if (!candidates.isEmpty()) {
                return candidates.stream().min(Comparator
                        .comparingLong(StallCandidate::selectionHash)
                        .thenComparingInt(candidate -> candidate.sitePos().getX())
                        .thenComparingInt(candidate -> candidate.sitePos().getZ()));
            }
        }
        return Optional.empty();
    }

    private static Optional<StallCandidate> findCentralPlaza(
            District district,
            MegacityLayout.Node node,
            MerchantTruckLibrary.TruckCandidate fixerTruck) {
        List<StallCandidate> candidates = new ArrayList<>();
        for (int dz = -24; dz <= 24; dz += 6) {
            for (int dx = -24; dx <= 24; dx += 6) {
                int worldX = node.x() + dx;
                int worldZ = node.z() + dz;
                long hash = MegacityLayout.mix(
                        NeonCityGenerator.layout().seed() ^ STALL_SALT ^ district.ordinal(),
                        worldX,
                        worldZ);
                Direction facing = FACINGS.get(Math.floorMod((int) hash, FACINGS.size()));
                NeonCityGenerator.UrbanSample center = NeonCityGenerator.sample(worldX, worldZ);
                if (isCentralPlazaFootprint(worldX, worldZ, facing, district, center)
                        && !overlapsTruck(worldX, worldZ, fixerTruck)) {
                    candidates.add(new StallCandidate(
                            district,
                            new BlockPos(worldX, center.groundY() + 1, worldZ),
                            facing,
                            hash));
                }
            }
        }
        return candidates.stream().min(Comparator
                .comparingLong(StallCandidate::selectionHash)
                .thenComparingInt(candidate -> candidate.sitePos().getX())
                .thenComparingInt(candidate -> candidate.sitePos().getZ()));
    }

    private static boolean isCentralPlazaFootprint(
            int centerX,
            int centerZ,
            Direction facing,
            District district,
            NeonCityGenerator.UrbanSample center) {
        if (center.roadClass() != NeonCityGenerator.RoadClass.CENTRAL_PLAZA
                || center.district() != district) {
            return false;
        }
        for (int depth = -1; depth <= 3; depth++) {
            int minAcross = depth == 3 ? -1 : -2;
            int maxAcross = depth == 3 ? 1 : 2;
            for (int across = minAcross; across <= maxAcross; across++) {
                int worldX = centerX + facing.getStepX() * depth
                        + facing.getClockWise().getStepX() * across;
                int worldZ = centerZ + facing.getStepZ() * depth
                        + facing.getClockWise().getStepZ() * across;
                NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(worldX, worldZ);
                if (sample.roadClass() != NeonCityGenerator.RoadClass.CENTRAL_PLAZA
                        || sample.district() != district
                        || sample.groundY() != center.groundY()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void collectCandidates(
            District district,
            int chunkX,
            int chunkZ,
            MerchantTruckLibrary.TruckCandidate fixerTruck,
            List<StallCandidate> result) {
        NeonCityGenerator.UrbanSample[][] samples = NeonCityGenerator.sampleChunk(
                chunkX << 4, chunkZ << 4);
        for (int localZ = 3; localZ <= 12; localZ += 3) {
            for (int localX = 3; localX <= 12; localX += 3) {
                int worldX = (chunkX << 4) + localX;
                int worldZ = (chunkZ << 4) + localZ;
                long hash = MegacityLayout.mix(
                        NeonCityGenerator.layout().seed() ^ STALL_SALT ^ district.ordinal(),
                        worldX,
                        worldZ);
                Direction facing = FACINGS.get(Math.floorMod((int) hash, FACINGS.size()));
                NeonCityGenerator.UrbanSample center = samples[localZ + 1][localX + 1];
                if (!isOpenFootprint(samples, localX, localZ, facing, district, center)
                        || overlapsTruck(worldX, worldZ, fixerTruck)) {
                    continue;
                }
                result.add(new StallCandidate(
                        district,
                        new BlockPos(worldX, center.groundY() + 1, worldZ),
                        facing,
                        hash));
            }
        }
    }

    private static boolean isOpenFootprint(
            NeonCityGenerator.UrbanSample[][] samples,
            int centerX,
            int centerZ,
            Direction facing,
            District district,
            NeonCityGenerator.UrbanSample center) {
        boolean park = center.roadClass() == NeonCityGenerator.RoadClass.PARK;
        boolean plaza = center.roadClass() == NeonCityGenerator.RoadClass.CENTRAL_PLAZA;
        if ((!park && !plaza)
                || center.district() != district
                || park && center.zone() != MegacityLayout.Zone.NEST
                        && center.zone() != MegacityLayout.Zone.BACKSTREETS) {
            return false;
        }
        for (int depth = -1; depth <= 3; depth++) {
            int minAcross = depth == 3 ? -1 : -2;
            int maxAcross = depth == 3 ? 1 : 2;
            for (int across = minAcross; across <= maxAcross; across++) {
                int localX = centerX + facing.getStepX() * depth
                        + facing.getClockWise().getStepX() * across;
                int localZ = centerZ + facing.getStepZ() * depth
                        + facing.getClockWise().getStepZ() * across;
                if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) {
                    return false;
                }
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (sample.roadClass() != center.roadClass()
                        || sample.district() != district
                        || sample.groundY() != center.groundY()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean overlapsTruck(
            int centerX, int centerZ, MerchantTruckLibrary.TruckCandidate truck) {
        if (truck == null) {
            return false;
        }
        int padding = 2;
        return centerX - 2 - padding < truck.minX() + truck.sizeX()
                && centerX + 3 + padding > truck.minX()
                && centerZ - 2 - padding < truck.minZ() + truck.sizeZ()
                && centerZ + 3 + padding > truck.minZ();
    }

    private static boolean volumeClear(
            ServerLevel level,
            StallCandidate candidate,
            Map<BlockPos, BlockState> blocks) {
        for (BlockPos position : blocks.keySet()) {
            if (!level.isEmptyBlock(position)) {
                return false;
            }
        }
        for (int depth = -1; depth <= 2; depth++) {
            for (int across = -2; across <= 2; across++) {
                BlockPos feet = at(candidate, across, depth, 0);
                if (!level.getBlockState(feet.below()).blocksMotion()) {
                    return false;
                }
            }
        }
        BlockPos center = candidate.sitePos();
        AABB occupied = new AABB(
                center.getX() - 4.0,
                center.getY(),
                center.getZ() - 4.0,
                center.getX() + 5.0,
                center.getY() + 5.0,
                center.getZ() + 5.0);
        return level.getEntities(null, occupied).isEmpty();
    }

    private static BlockPos at(
            StallCandidate candidate, int across, int depth, int up) {
        Direction facing = candidate.facing();
        Direction right = facing.getClockWise();
        return candidate.sitePos().offset(
                right.getStepX() * across + facing.getStepX() * depth,
                up,
                right.getStepZ() * across + facing.getStepZ() * depth);
    }
}
