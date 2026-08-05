package dev.modernity.neoncity;

import com.example.cyberdeck.city.CityWorlds;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Transient, player-local diagnostics for the Arnis building atlas. */
final class BuildingInspectionService {
    static final int DEFAULT_RADIUS = 1;
    static final int MAX_RADIUS = 2;
    static final int OVERLAY_LIFETIME_TICKS = 200;

    private static final int MAX_DEBUG_POINTS = 128;
    private static final long INSPECTION_SALT = 0x4255494C44494E53L;
    private static final int[] FLOOR_COLORS = {
            0x29C7D8, 0x6ED36E, 0xE6C84F, 0xD671D6, 0xF08A4B, 0x7BA5F5
    };
    private static final int ENTRANCE_COLOR = 0x38F27A;
    private static final int STAIR_COLOR = 0xFFD34D;
    private static final int ROUTE_COLOR = 0x4D8DFF;
    private static final int TARGET_COLOR = 0xFF3B3B;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private BuildingInspectionService() {
    }

    record ScanView(
            District district,
            MissionBuildingPlanner.AtlasScan scan,
            List<MissionBuildingPlanner.BuildingLabel> buildings,
            boolean cached,
            int radius) {
        ScanView {
            buildings = List.copyOf(buildings);
        }

        Optional<MissionBuildingPlanner.Site> siteFor(
                MissionBuildingPlanner.BuildingLabel building) {
            if (building == null || building.siteId().isBlank()) return Optional.empty();
            return scan.sites().stream()
                    .filter(site -> site.id().equals(building.siteId()))
                    .findFirst();
        }
    }

    record Inspection(
            ScanView view,
            MissionBuildingPlanner.BuildingLabel building,
            Optional<MissionBuildingPlanner.Site> site,
            int debugPointCount) {
    }

    record DebugPoint(BlockPos position, int color, float scale) {
        DebugPoint {
            position = position.immutable();
        }
    }

    static ScanView scan(ServerPlayer player, int requestedRadius) {
        if (player == null || !(player.level() instanceof ServerLevel level)
                || !NeonCityGenerator.isEnabled()
                || !NeonCityGenerator.isMegacityWorld(level)) {
            throw new IllegalArgumentException(
                    "Building inspection requires a Project Moon Megacity world.");
        }
        int radius = Math.max(0, Math.min(MAX_RADIUS, requestedRadius));
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(
                player.getBlockX(), player.getBlockZ());
        MegacityLayout.Location location = NeonCityGenerator.effectiveLocation(sample);
        if (!location.insideCity() || location.zone() == MegacityLayout.Zone.WILDERNESS) {
            throw new IllegalArgumentException("No district building atlas exists here.");
        }

        District district = location.district();
        int chunkX = Math.floorDiv(player.getBlockX(), 16);
        int chunkZ = Math.floorDiv(player.getBlockZ(), 16);
        HorizontalBounds requested = HorizontalBounds.around(chunkX, chunkZ, radius);
        MissionBuildingPlanner.AtlasScan scan = cachedScan(district, requested).orElse(null);
        boolean cached = scan != null;
        if (scan == null) {
            long seed = MegacityLayout.mix(
                    NeonCityGenerator.layout().seed() ^ INSPECTION_SALT, chunkX, chunkZ);
            scan = MissionBuildingPlanner.scanArnisRegion(
                    level, district, chunkX, chunkZ, radius, seed, 1, 5);
        }

        List<MissionBuildingPlanner.BuildingLabel> buildings = scan.buildings().stream()
                .filter(building -> requested.intersects(building.bounds()))
                .sorted(Comparator
                        .comparingLong((MissionBuildingPlanner.BuildingLabel building) ->
                                horizontalDistanceSquared(player.blockPosition(), building.bounds()))
                        .thenComparing(MissionBuildingPlanner.BuildingLabel::id))
                .toList();
        return new ScanView(district, scan, buildings, cached, radius);
    }

    static Inspection inspect(ServerPlayer player, int radius) {
        ScanView view = scan(player, radius);
        MissionBuildingPlanner.BuildingLabel building = nearestBuilding(
                view.buildings(), player.blockPosition()).orElseThrow(() ->
                        new IllegalArgumentException(
                                "No segmented Arnis building intersects the loaded scan area."));
        Optional<MissionBuildingPlanner.Site> site = view.siteFor(building);
        List<DebugPoint> points = debugPoints(building, site.orElse(null));
        int expiresAt = player.level().getServer().getTickCount() + OVERLAY_LIFETIME_TICKS;
        Session session = new Session(player.level().dimension().identifier().toString(),
                expiresAt, points);
        SESSIONS.put(player.getUUID(), session);
        emit(player, session);
        return new Inspection(view, building, site, points.size());
    }

    static boolean clear(ServerPlayer player) {
        return player != null && SESSIONS.remove(player.getUUID()) != null;
    }

    static void forget(UUID playerId) {
        if (playerId != null) SESSIONS.remove(playerId);
    }

    static void reset() {
        SESSIONS.clear();
    }

    static void tick(ServerLevel level) {
        if (level == null || level.getServer().getTickCount() % 20 != 0) return;
        int tick = level.getServer().getTickCount();
        var iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null || !player.isAlive() || player.level() != level
                    || !session.dimension().equals(level.dimension().identifier().toString())
                    || tick > session.expiresAt()) {
                iterator.remove();
                continue;
            }
            emit(player, session);
        }
    }

    static Optional<MissionBuildingPlanner.BuildingLabel> nearestBuilding(
            List<MissionBuildingPlanner.BuildingLabel> buildings, BlockPos origin) {
        if (buildings == null || origin == null) return Optional.empty();
        return buildings.stream().min(Comparator
                .comparingLong((MissionBuildingPlanner.BuildingLabel building) ->
                        horizontalDistanceSquared(origin, building.bounds()))
                .thenComparing(building -> !building.missionReady())
                .thenComparing(MissionBuildingPlanner.BuildingLabel::id));
    }

    static List<DebugPoint> debugPoints(
            MissionBuildingPlanner.BuildingLabel building,
            MissionBuildingPlanner.Site site) {
        if (building == null) return List.of();
        LinkedHashMap<BlockPos, DebugPoint> priority = new LinkedHashMap<>();
        List<List<DebugPoint>> floorGroups = new ArrayList<>();
        if (site != null) {
            addPoint(priority, site.target(), TARGET_COLOR, 1.3F);
            addPoint(priority, site.entrance().position(), ENTRANCE_COLOR, 1.2F);
            for (MissionBuildingPlanner.StairRun stair : site.stairs()) {
                addPoint(priority, stair.start(), STAIR_COLOR, 1.0F);
                int lastStep = Math.max(0, stair.rise() - 1);
                addPoint(priority, stair.start().relative(stair.ascending(), lastStep)
                        .above(lastStep), STAIR_COLOR, 1.0F);
            }
            for (MissionBuildingPlanner.PatrolRoute route : site.patrolRoutes()) {
                for (BlockPos waypoint : route.waypoints()) {
                    addPoint(priority, waypoint, ROUTE_COLOR, 0.8F);
                }
            }
            for (int floorIndex = 0; floorIndex < site.floorMasks().size(); floorIndex++) {
                MissionBuildingPlanner.FloorMask mask = site.floorMasks().get(floorIndex);
                floorGroups.add(maskOutline(mask, FLOOR_COLORS[floorIndex % FLOOR_COLORS.length]));
            }
        } else {
            for (int floorIndex = 0; floorIndex < building.floorYs().size(); floorIndex++) {
                floorGroups.add(boundsOutline(
                        building.bounds(), building.floorYs().get(floorIndex),
                        FLOOR_COLORS[floorIndex % FLOOR_COLORS.length]));
            }
        }

        ArrayList<DebugPoint> result = new ArrayList<>(MAX_DEBUG_POINTS);
        result.addAll(priority.values().stream().limit(MAX_DEBUG_POINTS).toList());
        int cursor = 0;
        boolean added;
        do {
            added = false;
            for (List<DebugPoint> floor : floorGroups) {
                if (result.size() >= MAX_DEBUG_POINTS) break;
                if (cursor < floor.size()) {
                    DebugPoint point = floor.get(cursor);
                    if (!priority.containsKey(point.position())) result.add(point);
                    added = true;
                }
            }
            cursor++;
        } while (added && result.size() < MAX_DEBUG_POINTS);
        return List.copyOf(result);
    }

    private static Optional<MissionBuildingPlanner.AtlasScan> cachedScan(
            District district, HorizontalBounds requested) {
        return ArnisBuildingAtlas.latest(district).stream()
                .flatMap(compilation -> compilation.scans().stream())
                .filter(scan -> requested.coveredBy(scan.scanBounds()))
                .min(Comparator
                        .comparingLong((MissionBuildingPlanner.AtlasScan scan) ->
                                horizontalArea(scan.scanBounds()))
                        .thenComparingInt(scan -> scan.scanBounds().minX())
                        .thenComparingInt(scan -> scan.scanBounds().minZ()));
    }

    private static List<DebugPoint> maskOutline(
            MissionBuildingPlanner.FloorMask mask, int color) {
        Set<BlockPos> cells = Set.copyOf(mask.cells());
        return cells.stream()
                .filter(cell -> Direction.Plane.HORIZONTAL.stream()
                        .anyMatch(direction -> !cells.contains(cell.relative(direction))))
                .sorted(Comparator.comparingInt((BlockPos position) -> position.getX())
                        .thenComparingInt(BlockPos::getZ))
                .map(cell -> new DebugPoint(cell, color, 0.65F))
                .toList();
    }

    private static List<DebugPoint> boundsOutline(BoundingBox bounds, int y, int color) {
        ArrayList<DebugPoint> points = new ArrayList<>();
        int step = 2;
        for (int x = bounds.minX(); x <= bounds.maxX(); x += step) {
            points.add(new DebugPoint(new BlockPos(x, y, bounds.minZ()), color, 0.65F));
            if (bounds.maxZ() != bounds.minZ()) {
                points.add(new DebugPoint(new BlockPos(x, y, bounds.maxZ()), color, 0.65F));
            }
        }
        for (int z = bounds.minZ() + step; z < bounds.maxZ(); z += step) {
            points.add(new DebugPoint(new BlockPos(bounds.minX(), y, z), color, 0.65F));
            if (bounds.maxX() != bounds.minX()) {
                points.add(new DebugPoint(new BlockPos(bounds.maxX(), y, z), color, 0.65F));
            }
        }
        return List.copyOf(points);
    }

    private static void addPoint(
            Map<BlockPos, DebugPoint> points, BlockPos position, int color, float scale) {
        points.putIfAbsent(position.immutable(), new DebugPoint(position, color, scale));
    }

    private static void emit(ServerPlayer player, Session session) {
        if (!(player.level() instanceof ServerLevel level)) return;
        for (DebugPoint point : session.points()) {
            if (!CityWorlds.hasFullyLoadedChunk(level, point.position())) continue;
            level.sendParticles(
                    player,
                    new DustParticleOptions(point.color(), point.scale()),
                    true,
                    true,
                    point.position().getX() + 0.5,
                    point.position().getY() + 0.15,
                    point.position().getZ() + 0.5,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0);
        }
    }

    private static long horizontalDistanceSquared(BlockPos origin, BoundingBox bounds) {
        long dx = origin.getX() < bounds.minX()
                ? (long) bounds.minX() - origin.getX()
                : origin.getX() > bounds.maxX() ? (long) origin.getX() - bounds.maxX() : 0L;
        long dz = origin.getZ() < bounds.minZ()
                ? (long) bounds.minZ() - origin.getZ()
                : origin.getZ() > bounds.maxZ() ? (long) origin.getZ() - bounds.maxZ() : 0L;
        return dx * dx + dz * dz;
    }

    private static long horizontalArea(BoundingBox bounds) {
        return (long) bounds.getXSpan() * bounds.getZSpan();
    }

    private record Session(String dimension, int expiresAt, List<DebugPoint> points) {
        private Session {
            points = List.copyOf(points);
        }
    }

    private record HorizontalBounds(int minX, int minZ, int maxX, int maxZ) {
        static HorizontalBounds around(int chunkX, int chunkZ, int radius) {
            return new HorizontalBounds(
                    (chunkX - radius) << 4,
                    (chunkZ - radius) << 4,
                    ((chunkX + radius) << 4) + 15,
                    ((chunkZ + radius) << 4) + 15);
        }

        boolean intersects(BoundingBox bounds) {
            return minX <= bounds.maxX() && maxX >= bounds.minX()
                    && minZ <= bounds.maxZ() && maxZ >= bounds.minZ();
        }

        boolean coveredBy(BoundingBox bounds) {
            return bounds.minX() <= minX && bounds.maxX() >= maxX
                    && bounds.minZ() <= minZ && bounds.maxZ() >= maxZ;
        }
    }
}
