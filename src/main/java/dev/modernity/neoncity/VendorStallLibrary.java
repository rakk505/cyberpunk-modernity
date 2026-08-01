package dev.modernity.neoncity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

/** Deterministic vendor counters installed only in verified open Arnis building faces. */
final class VendorStallLibrary {
    private static final long STALL_SALT = 0x56454E444F525354L;
    private static final long ROLE_SALT = 0x5354414C4C524F4CL;
    private static final int MAX_SEARCH_RINGS = 18;
    private static final int MAX_FACADE_CHUNKS = 12;
    private static final int MIN_BUILDING_BLOCKS = 700;
    private static final int MAX_SCAN_Y = NeonCityGenerator.CITY_GROUND_Y + 10;
    private static final int MIN_ANCHOR_SEPARATION = 8;
    private static final long PENDING_RETRY_TICKS = 20L * 30L;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final List<Direction> FACINGS = List.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final Map<PlanKey, StallPlan> PLAN_CACHE = new HashMap<>();
    private static final Map<PlanKey, List<ChunkCandidate>> CHUNK_CACHE = new HashMap<>();

    record StallCandidate(
            District district,
            BlockPos sitePos,
            Direction facing,
            long selectionHash) {
        StallCandidate {
            sitePos = sitePos.immutable();
            if (facing == null || facing.getAxis().isVertical()) {
                throw new IllegalArgumentException("vendor stall must face horizontally");
            }
        }

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

    private record PlanKey(long seed, District district) {
    }

    private record StallPlan(
            Map<MerchantTruckLibrary.MerchantRole, StallCandidate> candidates,
            long retryAfter,
            int nextChunkOffset) {
        StallPlan {
            candidates = Map.copyOf(candidates);
        }
    }

    private record ChunkCandidate(int chunkX, int chunkZ, int ring, long score) {
    }

    private record OriginalState(BlockPos position, BlockState state) {
    }

    private VendorStallLibrary() {
    }

    static MerchantTruckLibrary.MerchantRole plannedRole(District district) {
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

    static List<MerchantTruckLibrary.MerchantRole> plannedRoles(District ignoredDistrict) {
        return List.of(
                MerchantTruckLibrary.MerchantRole.QUEST,
                MerchantTruckLibrary.MerchantRole.GUN,
                MerchantTruckLibrary.MerchantRole.CYBERWARE,
                MerchantTruckLibrary.MerchantRole.CLOTHING,
                MerchantTruckLibrary.MerchantRole.CONSUMABLE);
    }

    /** Vendors are installed on district entry after live Arnis geometry can be verified. */
    static int decorateChunk(
            ServerLevel ignoredLevel,
            ChunkPos ignoredChunk,
            NeonCityGenerator.UrbanSample[][] ignoredSamples) {
        return 0;
    }

    static Optional<StallCandidate> canonical(
            ServerLevel level,
            District district,
            MerchantTruckLibrary.MerchantRole role) {
        PlanKey key = new PlanKey(NeonCityGenerator.layout().seed(), district);
        StallPlan plan = PLAN_CACHE.get(key);
        if (plan == null
                || (!plan.candidates().containsKey(role)
                && level.getGameTime() >= plan.retryAfter())) {
            int startOffset = plan == null ? 0 : plan.nextChunkOffset();
            plan = findPlan(level, district, Set.of(), startOffset);
            PLAN_CACHE.put(key, plan);
        }
        return Optional.ofNullable(plan.candidates().get(role));
    }

    static boolean ensure(
            ServerLevel level,
            District district,
            MerchantTruckLibrary.MerchantRole role) {
        PlanKey key = new PlanKey(NeonCityGenerator.layout().seed(), district);
        Set<BlockPos> rejected = new HashSet<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            StallCandidate candidate = attempt == 0
                    ? canonical(level, district, role).orElse(null)
                    : findAndCachePlan(level, district, rejected).candidates().get(role);
            if (candidate == null) {
                return false;
            }
            if (place(level, candidate, role)) {
                return true;
            }
            rejected.add(candidate.sitePos());
            PLAN_CACHE.remove(key);
        }
        return false;
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
        List<OriginalState> originals = new ArrayList<>(blocks.size());
        Villager merchant = null;
        boolean anchorRegistered = false;
        try {
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                originals.add(new OriginalState(
                        entry.getKey(), level.getBlockState(entry.getKey())));
                if (!level.setBlock(entry.getKey(), entry.getValue(), PLACE_FLAGS)) {
                    rollback(level, originals);
                    return false;
                }
            }

            // Persist before spawning: a loading entity is valid only if this v2 anchor exists.
            data.register(
                    siteId,
                    role,
                    candidate.district(),
                    candidate.sitePos(),
                    candidate.merchantPos(),
                    candidate.yaw(),
                    null);
            anchorRegistered = true;
            merchant = MerchantTruckLibrary.createMerchant(
                    level,
                    candidate.merchantPos(),
                    candidate.yaw(),
                    role,
                    candidate.district(),
                    candidate.sitePos());
            if (merchant == null || !level.addFreshEntity(merchant)) {
                if (merchant != null) {
                    merchant.discard();
                }
                data.remove(siteId);
                rollback(level, originals);
                return false;
            }
            VendorService.register(
                    level,
                    merchant,
                    role,
                    candidate.district(),
                    candidate.sitePos(),
                    candidate.merchantPos(),
                    candidate.yaw());
            return true;
        } catch (RuntimeException failure) {
            if (merchant != null) {
                merchant.discard();
            }
            if (anchorRegistered) {
                data.remove(siteId);
            }
            rollback(level, originals);
            return false;
        }
    }

    static Map<BlockPos, BlockState> stallBlocks(
            StallCandidate candidate,
            MerchantTruckLibrary.MerchantRole role) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockState body = Blocks.CONCRETE.pick(role.stallColor()).defaultBlockState();
        BlockState accent = Blocks.CONCRETE.pick(
                role == MerchantTruckLibrary.MerchantRole.QUEST
                        ? DyeColor.YELLOW : DyeColor.WHITE).defaultBlockState();

        blocks.put(at(candidate, -2, 0, 0), Blocks.BARREL.defaultBlockState());
        blocks.put(at(candidate, -1, 1, 0), body);
        blocks.put(at(candidate, 0, 1, 0), Blocks.LECTERN.defaultBlockState());
        blocks.put(at(candidate, 1, 1, 0), body);
        blocks.put(at(candidate, 2, 0, 0), Blocks.BARREL.defaultBlockState());
        for (int across = -2; across <= 2; across++) {
            blocks.put(at(candidate, across, 0, 2),
                    across == 0 ? Blocks.SEA_LANTERN.defaultBlockState()
                            : Math.abs(across) == 2 ? accent : body);
        }
        return Map.copyOf(blocks);
    }

    static List<StallCandidate> findAttachedCandidates(
            ServerLevel level,
            District district,
            ChunkPos chunk,
            long selectionSalt) {
        List<StallCandidate> result = new ArrayList<>();
        int minX = chunk.getMinBlockX() + 3;
        int maxX = chunk.getMaxBlockX() - 3;
        int minZ = chunk.getMinBlockZ() + 3;
        int maxZ = chunk.getMaxBlockZ() - 3;
        for (int y = NeonCityGenerator.CITY_GROUND_Y + 1; y <= MAX_SCAN_Y; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos site = new BlockPos(x, y, z);
                    if (!supportedAir(level, site) || !level.isEmptyBlock(site.above())) {
                        continue;
                    }
                    for (Direction facing : FACINGS) {
                        long score = MegacityLayout.mix(
                                NeonCityGenerator.layout().seed() ^ STALL_SALT ^ selectionSalt,
                                x * 31 + facing.ordinal(),
                                z * 31 + y);
                        StallCandidate candidate = new StallCandidate(
                                district, site, facing, score);
                        if (isAttachedFacade(level, candidate)) {
                            result.add(candidate);
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparingLong(StallCandidate::selectionHash)
                .thenComparingInt(candidate -> candidate.sitePos().getY())
                .thenComparingInt(candidate -> candidate.sitePos().getX())
                .thenComparingInt(candidate -> candidate.sitePos().getZ())
                .thenComparingInt(candidate -> candidate.facing().ordinal()));
        return List.copyOf(result);
    }

    static boolean isAttachedFacade(ServerLevel level, StallCandidate candidate) {
        // The merchant bay and exterior approach must remain walkable and fully supported.
        for (int depth = -2; depth <= 3; depth++) {
            int width = depth >= 2 ? 1 : 2;
            for (int across = -width; across <= width; across++) {
                BlockPos feet = at(candidate, across, depth, 0);
                if (!supportedAir(level, feet) || !level.isEmptyBlock(feet.above())) {
                    return false;
                }
            }
        }

        for (BlockPos position : stallBlocks(
                candidate, MerchantTruckLibrary.MerchantRole.QUEST).keySet()) {
            if (!level.isEmptyBlock(position)
                    || level.getBlockState(position).hasBlockEntity()) {
                return false;
            }
        }

        // Existing roof and jamb evidence distinguishes an Arnis facade from roadside ground.
        int roofEvidence = 0;
        for (int up = 3; up <= 6; up++) {
            for (int depth = -2; depth <= 0; depth++) {
                for (int across = -2; across <= 2; across++) {
                    if (structural(level, at(candidate, across, depth, up))) {
                        roofEvidence++;
                    }
                }
            }
        }
        return roofEvidence >= 5
                && facadeSideEvidence(level, candidate, -3) >= 3
                && facadeSideEvidence(level, candidate, 3) >= 3;
    }

    static void clearCache() {
        PLAN_CACHE.clear();
        CHUNK_CACHE.clear();
    }

    private static StallPlan findAndCachePlan(
            ServerLevel level, District district, Set<BlockPos> rejected) {
        PlanKey key = new PlanKey(NeonCityGenerator.layout().seed(), district);
        StallPlan plan = findPlan(level, district, rejected, 0);
        PLAN_CACHE.put(key, plan);
        return plan;
    }

    private static StallPlan findPlan(
            ServerLevel level,
            District district,
            Set<BlockPos> rejected,
            int requestedStartOffset) {
        VendorAnchorData data = VendorAnchorData.get(level);
        List<MerchantTruckLibrary.MerchantRole> missing = plannedRoles(district).stream()
                .filter(role -> VendorService.currentAnchor(data, district, role).isEmpty())
                .toList();
        EnumMap<MerchantTruckLibrary.MerchantRole, StallCandidate> result =
                new EnumMap<>(MerchantTruckLibrary.MerchantRole.class);
        if (missing.isEmpty()) {
            return new StallPlan(result, Long.MAX_VALUE, 0);
        }

        List<StallCandidate> selected = new ArrayList<>();
        List<ChunkCandidate> chunks = candidateChunks(district);
        int cursor = requestedStartOffset >= 0 && requestedStartOffset < chunks.size()
                ? requestedStartOffset : 0;
        int scannedChunks = 0;
        outer:
        while (cursor < chunks.size() && scannedChunks < MAX_FACADE_CHUNKS) {
            ChunkCandidate candidateChunk = chunks.get(cursor++);
            ChunkPos chunk = new ChunkPos(candidateChunk.chunkX(), candidateChunk.chunkZ());
            if (!NeonCityGenerator.isGenerated(chunk)
                    || level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null
                    || !NeonCityGenerator.isUsableArnisChunk(
                    level, chunk.getMinBlockX(), chunk.getMinBlockZ())) {
                continue;
            }
            scannedChunks++;
            for (StallCandidate candidate : findAttachedCandidates(
                    level, district, chunk, candidateChunk.score())) {
                if (rejected.contains(candidate.sitePos())
                        || !farFromAnchorsAndSelected(level, candidate.sitePos(), selected)) {
                    continue;
                }
                selected.add(candidate);
                if (selected.size() == missing.size()) {
                    break outer;
                }
            }
        }

        for (int index = 0; index < Math.min(missing.size(), selected.size()); index++) {
            result.put(missing.get(index), selected.get(index));
        }
        long retryAfter = result.size() == missing.size()
                ? Long.MAX_VALUE
                : level.getGameTime() + PENDING_RETRY_TICKS;
        int nextOffset = cursor >= chunks.size() ? 0 : cursor;
        return new StallPlan(result, retryAfter, nextOffset);
    }

    private static List<ChunkCandidate> candidateChunks(District district) {
        PlanKey key = new PlanKey(NeonCityGenerator.layout().seed(), district);
        return CHUNK_CACHE.computeIfAbsent(key, ignored -> {
            MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
            int centerChunkX = Math.floorDiv(node.x(), 16);
            int centerChunkZ = Math.floorDiv(node.z(), 16);
            List<ChunkCandidate> candidates = new ArrayList<>();
            for (int ring = 0; ring <= MAX_SEARCH_RINGS; ring++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        int chunkX = centerChunkX + dx;
                        int chunkZ = centerChunkZ + dz;
                        ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                                NeonCityGenerator.layout(), chunkX, chunkZ).orElse(null);
                        if (placement == null
                                || placement.patch().district() != district
                                || placement.patch().blockCount() < MIN_BUILDING_BLOCKS
                                || !NeonCityGenerator.isArnisCompatibleChunk(
                                        NeonCityGenerator.sampleChunk(chunkX << 4, chunkZ << 4),
                                        district)) {
                            continue;
                        }
                        long score = MegacityLayout.mix(
                                NeonCityGenerator.layout().seed() ^ STALL_SALT,
                                chunkX,
                                chunkZ);
                        candidates.add(new ChunkCandidate(chunkX, chunkZ, ring, score));
                    }
                }
            }
            candidates.sort(Comparator.comparingInt(ChunkCandidate::ring)
                    .thenComparingLong(ChunkCandidate::score)
                    .thenComparingInt(ChunkCandidate::chunkX)
                    .thenComparingInt(ChunkCandidate::chunkZ));
            return List.copyOf(candidates);
        });
    }

    private static boolean farFromAnchorsAndSelected(
            ServerLevel level,
            BlockPos site,
            List<StallCandidate> selected) {
        int minimumDistanceSquared = MIN_ANCHOR_SEPARATION * MIN_ANCHOR_SEPARATION;
        for (VendorAnchorData.Anchor anchor : VendorAnchorData.get(level).anchors()) {
            if (horizontalDistanceSquared(anchor.sitePos(), site) < minimumDistanceSquared) {
                return false;
            }
        }
        for (StallCandidate candidate : selected) {
            if (horizontalDistanceSquared(candidate.sitePos(), site) < minimumDistanceSquared) {
                return false;
            }
        }
        return true;
    }

    private static int horizontalDistanceSquared(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static int facadeSideEvidence(
            ServerLevel level, StallCandidate candidate, int across) {
        int evidence = 0;
        for (int depth = -2; depth <= 0; depth++) {
            for (int up = 0; up <= 6; up++) {
                if (structural(level, at(candidate, across, depth, up))) {
                    evidence++;
                }
            }
        }
        return evidence;
    }

    private static boolean supportedAir(ServerLevel level, BlockPos position) {
        return level.isEmptyBlock(position)
                && level.getBlockState(position.below()).blocksMotion()
                && !level.getBlockState(position.below()).hasBlockEntity();
    }

    private static boolean structural(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && state.blocksMotion();
    }

    private static boolean volumeClear(
            ServerLevel level,
            StallCandidate candidate,
            Map<BlockPos, BlockState> blocks) {
        if (!isAttachedFacade(level, candidate)) {
            return false;
        }
        for (BlockPos position : blocks.keySet()) {
            if (!level.isEmptyBlock(position)) {
                return false;
            }
        }
        BlockPos center = candidate.sitePos();
        AABB occupied = new AABB(
                center.getX() - 3.0,
                center.getY(),
                center.getZ() - 3.0,
                center.getX() + 4.0,
                center.getY() + 3.0,
                center.getZ() + 4.0);
        return level.getEntities(null, occupied).isEmpty();
    }

    private static void rollback(ServerLevel level, List<OriginalState> originals) {
        for (int index = originals.size() - 1; index >= 0; index--) {
            OriginalState original = originals.get(index);
            level.setBlock(original.position(), original.state(), PLACE_FLAGS);
        }
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
