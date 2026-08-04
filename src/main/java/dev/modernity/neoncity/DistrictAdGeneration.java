package dev.modernity.neoncity;

import com.example.cyberdeck.advertising.AdDisplayBlockEntity;
import com.example.cyberdeck.advertising.FreestandingAdPlacement;
import com.example.cyberdeck.advertising.FreestandingAdType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Deterministic freestanding-ad sites in each district's central owner chunk. */
final class DistrictAdGeneration {
    static final int QUICKTIME_CLEARANCE_RADIUS = 2;
    static final int A_CORP_SPAWN_CLEARANCE_RADIUS = 4;
    static final int STRUCTURE_CLEARANCE = 1;
    static final int OWNER_CHUNK_SEARCH_RADIUS = 2;

    private static final long SITE_SALT = 0x4449535452494354L;
    private static final long MEDIUM_SALT = 0x4D454449554D4144L;
    private static final long SMALL_SALT = 0x534D414C4C414453L;
    private static final BlockPos A_CORP_SPAWN =
            new BlockPos(0, NeonCityGenerator.CITY_GROUND_Y + 2, 0);
    private static final List<Direction.Axis> MEDIUM_AXES =
            List.of(Direction.Axis.X, Direction.Axis.Z);
    private static final List<Direction.Axis> SMALL_AXES = List.of(Direction.Axis.X);
    private static final ConcurrentHashMap<PlanKey, DistrictPlan> PLAN_CACHE =
            new ConcurrentHashMap<>();

    private record PlanKey(long layoutSeed, District district) {
    }

    /** A pure, ranked location. The origin is the minimum corner above the floor. */
    record Candidate(
            District district,
            FreestandingAdType type,
            BlockPos origin,
            Direction.Axis longAxis,
            ChunkPos ownerChunk,
            BoundingBox bounds,
            long rank) {
    }

    /** The canonical unobstructed schematic choice before live blocks are consulted. */
    record DistrictPlan(
            District district,
            ChunkPos ownerChunk,
            Optional<Candidate> medium,
            Optional<Candidate> small) {
    }

    /**
     * Backfill-safe outcome. An applicable owner chunk is complete after its bounded candidate
     * scan even when live/player blocks leave no valid site; callers must not retry forever.
     */
    record DecorationResult(
            boolean applicable,
            boolean complete,
            int presentStructures,
            int placedStructures) {
        private static final DecorationResult NOT_APPLICABLE =
                new DecorationResult(false, false, 0, 0);
    }

    private DistrictAdGeneration() {
    }

    /**
     * Places at most one medium and one small ad. Ranked alternatives are tried when another
     * decorator or a player-owned block occupies the canonical schematic site.
     */
    static DecorationResult decorateChunk(ServerLevel level, ChunkPos chunk) {
        MegacityLayout layout = NeonCityGenerator.layout();
        DistrictPlan districtPlan = planOwning(layout, chunk).orElse(null);
        if (districtPlan == null) {
            return DecorationResult.NOT_APPLICABLE;
        }
        District district = districtPlan.district();

        List<Candidate> mediumCandidates = candidatesInChunk(
                layout, district, FreestandingAdType.MEDIUM, chunk);
        PlacementResult medium = placeFirst(
                level, mediumCandidates, List.of(), FreestandingAdType.MEDIUM);

        List<BoundingBox> occupied = medium.candidate() == null
                ? List.of()
                : List.of(medium.candidate().bounds());
        List<Candidate> smallCandidates = candidatesInChunk(
                layout, district, FreestandingAdType.SMALL, chunk);
        PlacementResult small = placeFirst(
                level, smallCandidates, occupied, FreestandingAdType.SMALL);
        int present = (medium.candidate() == null ? 0 : 1)
                + (small.candidate() == null ? 0 : 1);
        int placed = (medium.placed() ? 1 : 0) + (small.placed() ? 1 : 0);
        return new DecorationResult(true, true, present, placed);
    }

    /** Pure plan used by topology tests and diagnostics. */
    static DistrictPlan plan(MegacityLayout layout, District district) {
        if (PLAN_CACHE.size() > 128) {
            PLAN_CACHE.clear();
        }
        return PLAN_CACHE.computeIfAbsent(
                new PlanKey(layout.seed(), district),
                ignored -> buildPlan(layout, district));
    }

    private static DistrictPlan buildPlan(MegacityLayout layout, District district) {
        ChunkPos nodeChunk = nodeChunk(layout, district);
        for (ChunkPos owner : ownerChunkCandidates(nodeChunk)) {
            List<Candidate> mediumCandidates = candidatesInChunk(
                    layout, district, FreestandingAdType.MEDIUM, owner);
            if (mediumCandidates.isEmpty()) {
                continue;
            }
            List<Candidate> smallCandidates = candidatesInChunk(
                    layout, district, FreestandingAdType.SMALL, owner);
            for (Candidate medium : mediumCandidates) {
                Candidate small = smallCandidates.stream()
                        .filter(candidate -> !intersectsWithClearance(
                                candidate.bounds(), medium.bounds(), STRUCTURE_CLEARANCE))
                        .findFirst()
                        .orElse(null);
                if (small != null) {
                    return new DistrictPlan(
                            district, owner, Optional.of(medium), Optional.of(small));
                }
            }
        }
        return new DistrictPlan(district, nodeChunk, Optional.empty(), Optional.empty());
    }

    /**
     * Returns every topology-valid candidate in stable rank order. This method never reads the
     * live level, so generation order and block edits cannot change the candidate sequence.
     */
    static List<Candidate> candidates(
            MegacityLayout layout,
            District district,
            FreestandingAdType type) {
        DistrictPlan plan = plan(layout, district);
        if (plan.medium().isEmpty() || plan.small().isEmpty()) {
            return List.of();
        }
        return candidatesInChunk(layout, district, type, plan.ownerChunk());
    }

    private static List<Candidate> candidatesInChunk(
            MegacityLayout layout,
            District district,
            FreestandingAdType type,
            ChunkPos owner) {
        NeonCityGenerator.UrbanSample[][] samples = ownerSamples(layout, owner);
        List<Candidate> candidates = new ArrayList<>();
        List<Direction.Axis> axes = type == FreestandingAdType.MEDIUM
                ? MEDIUM_AXES
                : SMALL_AXES;
        for (Direction.Axis axis : axes) {
            int sizeX = type.sizeX(axis);
            int sizeZ = type.sizeZ(axis);
            int lastLocalX = 16 - sizeX;
            int lastLocalZ = 16 - sizeZ;
            for (int localZ = 0; localZ <= lastLocalZ; localZ++) {
                for (int localX = 0; localX <= lastLocalX; localX++) {
                    int groundY = footprintGround(
                            samples, localX, localZ, sizeX, sizeZ, district);
                    if (groundY == Integer.MIN_VALUE) {
                        continue;
                    }
                    BlockPos origin = new BlockPos(
                            owner.getMinBlockX() + localX,
                            groundY + 1,
                            owner.getMinBlockZ() + localZ);
                    BoundingBox bounds = bounds(origin, type, axis);
                    if (!insideOwnerChunk(bounds, owner)
                            || intersectsDistrictClearance(layout, district, bounds)) {
                        continue;
                    }
                    long typeSalt = type == FreestandingAdType.MEDIUM
                            ? MEDIUM_SALT
                            : SMALL_SALT;
                    long axisSalt = axis == Direction.Axis.X ? 0L : Long.MIN_VALUE;
                    long rank = MegacityLayout.mix(
                            layout.seed() ^ SITE_SALT ^ typeSalt ^ axisSalt,
                            origin.getX(), origin.getZ());
                    candidates.add(new Candidate(
                            district, type, origin, axis, owner, bounds, rank));
                }
            }
        }
        candidates.sort(Comparator
                .comparing(Candidate::rank, Long::compareUnsigned)
                .thenComparingInt(candidate -> candidate.origin().getX())
                .thenComparingInt(candidate -> candidate.origin().getZ())
                .thenComparingInt(candidate -> candidate.longAxis().ordinal()));
        return List.copyOf(candidates);
    }

    private static ChunkPos nodeChunk(MegacityLayout layout, District district) {
        MegacityLayout.Node node = layout.node(district);
        return new ChunkPos(Math.floorDiv(node.x(), 16), Math.floorDiv(node.z(), 16));
    }

    private static Optional<DistrictPlan> planOwning(
            MegacityLayout layout, ChunkPos chunk) {
        for (District district : District.values()) {
            ChunkPos node = nodeChunk(layout, district);
            if (Math.abs(chunk.x() - node.x()) > OWNER_CHUNK_SEARCH_RADIUS
                    || Math.abs(chunk.z() - node.z()) > OWNER_CHUNK_SEARCH_RADIUS) {
                continue;
            }
            DistrictPlan plan = plan(layout, district);
            if (plan.ownerChunk().equals(chunk)
                    && plan.medium().isPresent()
                    && plan.small().isPresent()) {
                return Optional.of(plan);
            }
        }
        return Optional.empty();
    }

    private static List<ChunkPos> ownerChunkCandidates(ChunkPos center) {
        List<ChunkPos> candidates = new ArrayList<>();
        candidates.add(center);
        for (int ring = 1; ring <= OWNER_CHUNK_SEARCH_RADIUS; ring++) {
            for (int deltaZ = -ring; deltaZ <= ring; deltaZ++) {
                for (int deltaX = -ring; deltaX <= ring; deltaX++) {
                    if (Math.max(Math.abs(deltaX), Math.abs(deltaZ)) == ring) {
                        candidates.add(new ChunkPos(center.x() + deltaX, center.z() + deltaZ));
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    static boolean isOpenGround(NeonCityGenerator.UrbanSample sample) {
        return sample.roadClass() == NeonCityGenerator.RoadClass.CENTRAL_PLAZA;
    }

    private static PlacementResult placeFirst(
            ServerLevel level,
            List<Candidate> candidates,
            List<BoundingBox> occupied,
            FreestandingAdType type) {
        Candidate existing = findExisting(level, candidates, occupied, type);
        if (existing != null) {
            return new PlacementResult(existing, false);
        }
        for (Candidate candidate : candidates) {
            if (intersectsAny(candidate.bounds(), occupied)) {
                continue;
            }
            if (!FreestandingAdPlacement.validate(
                    level, candidate.origin(), candidate.type(), candidate.longAxis())) {
                continue;
            }
            if (FreestandingAdPlacement.place(
                    level, candidate.origin(), candidate.type(), candidate.longAxis())) {
                return new PlacementResult(candidate, true);
            }
        }
        return new PlacementResult(null, false);
    }

    private static Candidate findExisting(
            ServerLevel level,
            List<Candidate> candidates,
            List<BoundingBox> occupied,
            FreestandingAdType type) {
        for (Candidate candidate : candidates) {
            if (intersectsAny(candidate.bounds(), occupied)) {
                continue;
            }
            if (level.getBlockEntity(candidate.origin()) instanceof AdDisplayBlockEntity display
                    && display.freestandingType().orElse(null) == type
                    && display.longAxis() == candidate.longAxis()
                    && FreestandingAdPlacement.validate(
                            level,
                            candidate.origin(),
                            candidate.type(),
                            candidate.longAxis())) {
                return candidate;
            }
        }
        return null;
    }

    private static NeonCityGenerator.UrbanSample[][] ownerSamples(
            MegacityLayout layout, ChunkPos owner) {
        NeonCityGenerator.UrbanSample[][] samples = new NeonCityGenerator.UrbanSample[16][16];
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                samples[localZ][localX] = NeonCityGenerator.topologySample(
                        layout,
                        owner.getMinBlockX() + localX,
                        owner.getMinBlockZ() + localZ);
            }
        }
        return samples;
    }

    private static int footprintGround(
            NeonCityGenerator.UrbanSample[][] samples,
            int localX,
            int localZ,
            int sizeX,
            int sizeZ,
            District district) {
        int groundY = samples[localZ][localX].groundY();
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + z][localX + x];
                if (sample.district() != district
                        || !isOpenGround(sample)
                        || sample.groundY() != groundY) {
                    return Integer.MIN_VALUE;
                }
            }
        }
        return groundY;
    }

    private static BoundingBox bounds(
            BlockPos origin, FreestandingAdType type, Direction.Axis longAxis) {
        return new BoundingBox(
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                origin.getX() + type.sizeX(longAxis) - 1,
                origin.getY() + type.height() - 1,
                origin.getZ() + type.sizeZ(longAxis) - 1);
    }

    private static boolean insideOwnerChunk(BoundingBox bounds, ChunkPos owner) {
        return bounds.minX() >= owner.getMinBlockX()
                && bounds.maxX() <= owner.getMaxBlockX()
                && bounds.minZ() >= owner.getMinBlockZ()
                && bounds.maxZ() <= owner.getMaxBlockZ();
    }

    private static boolean intersectsDistrictClearance(
            MegacityLayout layout, District district, BoundingBox bounds) {
        BlockPos station = canonicalStation(layout, district);
        if (intersectsPointClearance(
                bounds, station.getX(), station.getZ(), QUICKTIME_CLEARANCE_RADIUS)) {
            return true;
        }
        return district == District.A_CORP
                && intersectsPointClearance(
                        bounds,
                        A_CORP_SPAWN.getX(),
                        A_CORP_SPAWN.getZ(),
                        A_CORP_SPAWN_CLEARANCE_RADIUS);
    }

    private static BlockPos canonicalStation(MegacityLayout layout, District district) {
        MegacityLayout.Node node = layout.node(district);
        int stationX = insetFromChunkBorder(node.x());
        int stationZ = insetFromChunkBorder(node.z());
        int groundY = NeonCityGenerator.topologySample(layout, stationX, stationZ).groundY();
        return new BlockPos(stationX, groundY + 1, stationZ);
    }

    private static int insetFromChunkBorder(int coordinate) {
        int chunkStart = Math.floorDiv(coordinate, 16) * 16;
        int local = coordinate - chunkStart;
        return chunkStart + Math.max(2, Math.min(13, local));
    }

    private static boolean intersectsPointClearance(
            BoundingBox bounds, int x, int z, int clearance) {
        return bounds.minX() <= x + clearance
                && bounds.maxX() >= x - clearance
                && bounds.minZ() <= z + clearance
                && bounds.maxZ() >= z - clearance;
    }

    private static boolean intersectsAny(
            BoundingBox candidate, List<BoundingBox> occupied) {
        for (BoundingBox bounds : occupied) {
            if (intersectsWithClearance(candidate, bounds, STRUCTURE_CLEARANCE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsWithClearance(
            BoundingBox first, BoundingBox second, int clearance) {
        return first.minX() <= second.maxX() + clearance
                && first.maxX() + clearance >= second.minX()
                && first.minZ() <= second.maxZ() + clearance
                && first.maxZ() + clearance >= second.minZ();
    }

    private record PlacementResult(Candidate candidate, boolean placed) {
    }
}
