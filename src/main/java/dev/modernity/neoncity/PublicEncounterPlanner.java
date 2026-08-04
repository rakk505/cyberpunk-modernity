package dev.modernity.neoncity;

import com.example.cyberdeck.city.CityWorlds;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Deterministic, non-highway public arenas for single-target encounters. */
final class PublicEncounterPlanner {
    private static final String SITE_PREFIX = "public_encounter:";
    private static final int PLAN_ATTEMPTS = 4_096;
    private static final int PLAN_SPREAD = 192;
    private static final int LIVE_RELOCATION_RADIUS = 20;
    private static final List<Direction> CARDINAL_DIRECTIONS = List.of(
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    private PublicEncounterPlanner() {
    }

    static Optional<MissionBuildingPlanner.Site> plan(
            MegacityLayout layout,
            District district,
            long seed,
            String identity,
            Collection<MissionBuildingPlanner.Site> excludedSites) {
        if (layout == null || district == null) return Optional.empty();
        MegacityLayout.Node center = layout.node(district);
        List<MissionBuildingPlanner.Site> excluded = excludedSites == null
                ? List.of() : List.copyOf(excludedSites);
        for (int attempt = 0; attempt < PLAN_ATTEMPTS; attempt++) {
            long mixed = MegacityLayout.mix(seed, attempt, district.ordinal());
            int x = center.x() - PLAN_SPREAD
                    + Math.floorMod((int) mixed, PLAN_SPREAD * 2 + 1);
            int z = center.z() - PLAN_SPREAD
                    + Math.floorMod((int) Long.rotateLeft(mixed, 27), PLAN_SPREAD * 2 + 1);
            if (!isPublicTarget(layout, district, x, z)) continue;
            NeonCityGenerator.UrbanSample sample = NeonCityGenerator.topologySample(layout, x, z);
            BlockPos target = new BlockPos(x, sample.groundY() + 1, z);
            Direction approach = topologyApproach(layout, district, target);
            if (approach == null) continue;
            MissionBuildingPlanner.Site site = createSite(
                    siteId(identity, target), district, target, approach, mixed);
            if (excluded.stream().noneMatch(existing ->
                    MainlineQuestData.buildingConflicts(existing, site))) {
                return Optional.of(site);
            }
        }
        return Optional.empty();
    }

    static Optional<MissionBuildingPlanner.Site> resolve(
            ServerLevel level, MissionBuildingPlanner.Site planned) {
        if (level == null || !isPublicSite(planned)) return Optional.empty();
        BlockPos origin = planned.target();
        NeonCityGenerator.generateNow(
                level, Math.floorDiv(origin.getX(), 16), Math.floorDiv(origin.getZ(), 16), 1);
        for (int radius = 0; radius <= LIVE_RELOCATION_RADIUS; radius++) {
            for (int offset = -radius; offset <= radius; offset++) {
                BlockPos north = liveTarget(level, planned.district(),
                        origin.getX() + offset, origin.getZ() - radius, origin.getY());
                if (north != null) return Optional.ofNullable(relocate(level, planned, north));
                if (radius == 0) continue;
                BlockPos south = liveTarget(level, planned.district(),
                        origin.getX() - offset, origin.getZ() + radius, origin.getY());
                if (south != null) return Optional.ofNullable(relocate(level, planned, south));
                if (Math.abs(offset) == radius) continue;
                BlockPos west = liveTarget(level, planned.district(),
                        origin.getX() - radius, origin.getZ() - offset, origin.getY());
                if (west != null) return Optional.ofNullable(relocate(level, planned, west));
                BlockPos east = liveTarget(level, planned.district(),
                        origin.getX() + radius, origin.getZ() + offset, origin.getY());
                if (east != null) return Optional.ofNullable(relocate(level, planned, east));
            }
        }
        return Optional.empty();
    }

    static boolean isPublicTarget(
            MegacityLayout layout, District district, int x, int z) {
        if (layout == null || district == null || NeonCityGenerator.isHighwayAt(layout, x, z)) {
            return false;
        }
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.topologySample(layout, x, z);
        return sample.location().insideCity()
                && sample.zone() != MegacityLayout.Zone.WILDERNESS
                && sample.district() == district
                && isPublicRoad(sample.roadClass());
    }

    static boolean isPublicRoad(NeonCityGenerator.RoadClass road) {
        return switch (road) {
            case CENTRAL_PLAZA, DISTRICT_BOULEVARD, LOCAL_STREET, SERVICE_ALLEY, PARK -> true;
            default -> false;
        };
    }

    static boolean isPublicSite(MissionBuildingPlanner.Site site) {
        return site != null
                && site.id().startsWith(SITE_PREFIX)
                && site.floorYs().size() == 1
                && site.stairs().isEmpty()
                && site.decorations().isEmpty();
    }

    static MissionBuildingPlanner.Site createSite(
            String identity, District district, BlockPos target, long seed) {
        Direction approach = topologyApproach(
                NeonCityGenerator.layout(), district, target);
        if (approach == null) approach = Direction.NORTH;
        return createSite(siteId(identity, target), district, target, approach, seed);
    }

    private static String siteId(String identity, BlockPos target) {
        String safeIdentity = identity == null || identity.isBlank()
                ? "encounter"
                : identity.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return SITE_PREFIX + safeIdentity + ":"
                + target.getX() + ":" + target.getZ();
    }

    private static MissionBuildingPlanner.Site relocate(
            ServerLevel level, MissionBuildingPlanner.Site site, BlockPos target) {
        Direction approach = liveApproach(level, site.district(), target);
        if (approach == null) return null;
        return createSite(site.id(), site.district(), target, approach, site.planSeed());
    }

    private static MissionBuildingPlanner.Site createSite(
            String id,
            District district,
            BlockPos target,
            Direction approach,
            long seed) {
        int y = target.getY();
        ArrayList<BlockPos> cells = new ArrayList<>(81);
        for (int dz = -4; dz <= 4; dz++) {
            for (int dx = -4; dx <= 4; dx++) {
                cells.add(target.offset(dx, 0, dz));
            }
        }
        MissionBuildingPlanner.Entrance entrance = new MissionBuildingPlanner.Entrance(
                target.relative(approach, 3), approach, 1, true);
        MissionBuildingPlanner.PatrolRoute route = new MissionBuildingPlanner.PatrolRoute(
                y, List.of(
                        target.offset(-3, 0, -3),
                        target.offset(3, 0, -3),
                        target.offset(3, 0, 3),
                        target.offset(-3, 0, 3)));
        BoundingBox bounds = new BoundingBox(
                target.getX() - 7, y - 1, target.getZ() - 7,
                target.getX() + 7, y + 3, target.getZ() + 7);
        return new MissionBuildingPlanner.Site(
                id,
                district,
                bounds,
                List.of(y),
                target,
                entrance,
                List.of(),
                List.of(route),
                List.of(),
                List.of(new MissionBuildingPlanner.FloorMask(y, cells)),
                seed,
                id,
                bounds);
    }

    private static BlockPos liveTarget(
            ServerLevel level, District district, int x, int z, int preferredY) {
        if (!level.hasChunkAt(new BlockPos(x, preferredY, z))
                || !isPublicTarget(NeonCityGenerator.layout(), district, x, z)) {
            return null;
        }
        BlockPos feet = CityWorlds.resolveStreetFeet(level, x, z, preferredY);
        if (feet == null
                || level.getBlockState(feet).blocksMotion()
                || level.getBlockState(feet.above()).blocksMotion()
                || level.getBlockState(feet.above(2)).blocksMotion()
                || !level.getBlockState(feet.below()).blocksMotion()
                || !level.canSeeSky(feet.above())) {
            return null;
        }
        return liveApproach(level, district, feet) == null ? null : feet.immutable();
    }

    private static Direction topologyApproach(
            MegacityLayout layout, District district, BlockPos target) {
        for (Direction direction : CARDINAL_DIRECTIONS) {
            boolean open = true;
            for (int distance = 1; distance <= 4; distance++) {
                BlockPos point = target.relative(direction, distance);
                if (!isPublicTarget(layout, district, point.getX(), point.getZ())) {
                    open = false;
                    break;
                }
            }
            if (open) return direction;
        }
        return null;
    }

    private static Direction liveApproach(
            ServerLevel level, District district, BlockPos target) {
        for (Direction direction : CARDINAL_DIRECTIONS) {
            boolean open = true;
            for (int distance = 1; distance <= 4; distance++) {
                BlockPos point = target.relative(direction, distance);
                if (!isPublicTarget(
                                NeonCityGenerator.layout(), district,
                                point.getX(), point.getZ())) {
                    open = false;
                    break;
                }
                BlockPos feet = CityWorlds.resolveStreetFeet(
                        level, point.getX(), point.getZ(), target.getY());
                if (feet == null || Math.abs(feet.getY() - target.getY()) > 1) {
                    open = false;
                    break;
                }
            }
            if (open) return direction;
        }
        return null;
    }
}
