package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.city.CityWorlds;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Lazily compiles deterministic city road samples into a streamed directed lane graph. */
final class CityTrafficGraph {
    private static final int MAX_CACHED_NODES = 12_288;
    private static final int HEADING_BINS = 24;
    private static final double NODE_SPACING = 10.0;
    private static final double HIGHWAY_LANE_OFFSET = 4.5;
    private static final double HIGHWAY_JUNCTION_TAPER = 28.0;
    private static final int HALF_ROAD_SCAN = 9;
    private static final float[] SUCCESSOR_TURNS = {
            0.0F, -15.0F, 15.0F, -30.0F, 30.0F, -45.0F, 45.0F,
            -67.5F, 67.5F, -90.0F, 90.0F
    };
    private static final Map<NodeKey, LaneNode> NODES = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<NodeKey, LaneNode> eldest) {
            return size() > MAX_CACHED_NODES;
        }
    };
    private static final Map<NodeKey, List<LaneArc>> ARCS =
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<NodeKey, List<LaneArc>> eldest) {
                    return size() > MAX_CACHED_NODES;
                }
            };

    private CityTrafficGraph() {
    }

    enum Network {
        HIGHWAY,
        ATLAS
    }

    record NodeKey(int x, int z, int headingBin, Network network) {
    }

    record LaneNode(
            NodeKey key,
            Vec3 position,
            float yaw,
            float cruisingThrottle,
            NeonCityGenerator.RoadClass roadClass,
            Network network) {
    }

    record LaneArc(LaneNode target, int score, float turnDegrees) {
    }

    static LaneNode enter(ServerLevel level, Vec3 position, float preferredYaw) {
        RoadProfile origin = roadProfile(
                Mth.floor(position.x), Mth.floor(position.z));
        if (origin.network() == null) return null;
        LaneNode best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int step = 0; step < HEADING_BINS; step++) {
            float yaw = binYaw(step);
            float difference = Math.abs(Mth.wrapDegrees(yaw - preferredYaw));
            if (difference > 100.0F) continue;
            LaneNode candidate = compileNode(
                    level, position.x, position.z, yaw, origin.network());
            if (candidate == null) continue;
            int score = roadScore(level, candidate.position(), candidate.yaw(), 28)
                    - Math.round(difference * 0.08F);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    static LaneNode highwayNode(
            ServerLevel level,
            MegacityLayout.Edge edge,
            double progress,
            boolean forward) {
        MegacityLayout.CurvePoint point = MegacityLayout.curvePoint(edge, progress);
        double tangentLength = Math.max(1.0,
                Math.hypot(point.tangentX(), point.tangentZ()));
        double forwardX = point.tangentX() / tangentLength;
        double forwardZ = point.tangentZ() / tangentLength;
        if (!forward) {
            forwardX = -forwardX;
            forwardZ = -forwardZ;
        }
        double edgeLength = Math.max(1.0, Math.hypot(
                edge.second().x() - edge.first().x(),
                edge.second().z() - edge.first().z()));
        double endpointDistance = Math.min(progress, 1.0 - progress) * edgeLength;
        double taper = Mth.clamp(endpointDistance / HIGHWAY_JUNCTION_TAPER, 0.0, 1.0);
        double laneOffset = HIGHWAY_LANE_OFFSET * taper;
        double laneX = point.x() + forwardZ * laneOffset;
        double laneZ = point.z() - forwardX * laneOffset;
        NeonCityGenerator.UrbanSample sample = sample(laneX, laneZ);
        if (!NeonCityGenerator.isHighwayRoadClass(sample.roadClass())) return null;
        BlockPos loaded = new BlockPos(Mth.floor(laneX), sample.groundY() + 1, Mth.floor(laneZ));
        if (!CityWorlds.hasFullyLoadedChunk(level, loaded)) return null;
        float yaw = (float) Math.toDegrees(Math.atan2(-forwardX, forwardZ));
        NodeKey key = new NodeKey(
                loaded.getX(), loaded.getZ(), headingBin(yaw), Network.HIGHWAY);
        return new LaneNode(
                key,
                new Vec3(laneX, sample.groundY() + 1.0, laneZ),
                yaw,
                (float) Mth.lerp(taper, 0.30, 0.62),
                sample.roadClass(),
                Network.HIGHWAY);
    }

    static List<LaneArc> successors(ServerLevel level, LaneNode node) {
        List<LaneArc> cached = ARCS.get(node.key());
        if (cached != null) return cached;

        List<LaneArc> candidates = new ArrayList<>();
        Set<NodeKey> added = new LinkedHashSet<>();
        for (float turn : SUCCESSOR_TURNS) {
            float yaw = Mth.wrapDegrees(node.yaw() + turn);
            double radians = Math.toRadians(yaw);
            double rawX = node.position().x - Math.sin(radians) * NODE_SPACING;
            double rawZ = node.position().z + Math.cos(radians) * NODE_SPACING;
            LaneNode target = compileNode(level, rawX, rawZ, yaw, node.network());
            if (target == null || target.key().equals(node.key()) || !added.add(target.key())) {
                continue;
            }
            int connecting = connectingRoadScore(
                    level, node.position(), target.position(), node.network());
            if (connecting < 3) continue;
            int score = connecting * 12
                    + roadScore(level, target.position(), target.yaw(), 24)
                    - Math.round(Math.abs(turn) * 0.10F);
            if (score >= 20) candidates.add(new LaneArc(target, score, turn));
        }
        candidates.sort(Comparator.comparingInt(LaneArc::score).reversed());
        List<LaneArc> result = List.copyOf(candidates);
        // An empty result can mean the next chunk is not loaded yet, so do not make it sticky.
        if (!result.isEmpty()) ARCS.put(node.key(), result);
        return result;
    }

    static boolean isJunction(ServerLevel level, LaneNode node) {
        return node.network() == Network.ATLAS
                && successors(level, node).stream()
                        .anyMatch(arc -> Math.abs(arc.turnDegrees()) >= 45.0F);
    }

    static LaneNode chooseSuccessor(
            ServerLevel level,
            LaneNode node,
            NodeKey previous,
            Set<NodeKey> recent,
            Vec3 destination,
            boolean highwayTrip,
            RandomSource random) {
        List<LaneArc> arcs = successors(level, node);
        if (arcs.isEmpty()) return null;
        List<ScoredArc> viable = new ArrayList<>();
        for (LaneArc arc : arcs) {
            if (arc.target().key().equals(previous)) continue;
            int score = tripScore(node, arc, recent, destination, highwayTrip);
            viable.add(new ScoredArc(arc, score));
        }
        if (viable.isEmpty()) return arcs.getFirst().target();
        viable.sort(Comparator.comparingInt(ScoredArc::score).reversed());

        // Mostly follow the trip objective; small variation keeps equal junctions from cloning.
        ScoredArc selected = viable.getFirst();
        if (viable.size() > 1
                && viable.get(1).score() >= selected.score() - 5
                && random.nextInt(100) < 18) {
            selected = viable.get(1);
        }
        return selected.arc().target();
    }

    private record ScoredArc(LaneArc arc, int score) {
    }

    private static int tripScore(
            LaneNode from,
            LaneArc arc,
            Set<NodeKey> recent,
            Vec3 destination,
            boolean highwayTrip) {
        LaneNode target = arc.target();
        int score = arc.score();
        if (recent.contains(target.key())) score -= 160;

        double currentDistance = horizontalDistance(from.position(), destination);
        double targetDistance = horizontalDistance(target.position(), destination);
        score += Mth.floor((currentDistance - targetDistance) * 1.8);

        boolean highway = target.network() == Network.HIGHWAY;
        if (highwayTrip) {
            score += highway ? 52 : -70;
        } else if (currentDistance > 280.0 && highway) {
            score += 18;
        }
        return score;
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static LaneNode compileNode(
            ServerLevel level, double rawX, double rawZ, float yaw, Network network) {
        RoadProfile raw = roadProfile(Mth.floor(rawX), Mth.floor(rawZ));
        if (raw.network() != network) return null;

        double radians = Math.toRadians(yaw);
        double rightX = Math.cos(radians);
        double rightZ = Math.sin(radians);
        int minimum = 0;
        int maximum = 0;
        for (int offset = -1; offset >= -HALF_ROAD_SCAN; offset--) {
            if (!sameRoadDeck(
                    raw, rawX + rightX * offset, rawZ + rightZ * offset, network)) break;
            minimum = offset;
        }
        for (int offset = 1; offset <= HALF_ROAD_SCAN; offset++) {
            if (!sameRoadDeck(
                    raw, rawX + rightX * offset, rawZ + rightZ * offset, network)) break;
            maximum = offset;
        }
        double width = maximum - minimum;
        double laneOffset = (minimum + maximum) * 0.5
                + Mth.clamp(width * 0.20, 0.7, 2.4);
        double laneX = rawX + rightX * laneOffset;
        double laneZ = rawZ + rightZ * laneOffset;
        RoadProfile lane = roadProfile(Mth.floor(laneX), Mth.floor(laneZ));
        if (lane.network() != network) return null;

        NodeKey key = new NodeKey(
                Mth.floor(laneX + 0.5),
                Mth.floor(laneZ + 0.5),
                headingBin(yaw),
                network);
        LaneNode cached = NODES.get(key);
        if (cached != null) return cached;
        BlockPos loaded = new BlockPos(key.x(), lane.groundY() + 1, key.z());
        if (!CityWorlds.hasFullyLoadedChunk(level, loaded)) return null;
        LaneNode node = new LaneNode(
                key,
                new Vec3(laneX, lane.groundY() + 1.0, laneZ),
                binYaw(key.headingBin()),
                cruisingThrottle(lane),
                lane.roadClass(),
                network);
        NODES.put(key, node);
        return node;
    }

    private static int connectingRoadScore(
            ServerLevel level, Vec3 from, Vec3 to, Network network) {
        int score = 0;
        for (int step = 1; step <= 5; step++) {
            double progress = step / 5.0;
            int x = Mth.floor(Mth.lerp(progress, from.x, to.x));
            int z = Mth.floor(Mth.lerp(progress, from.z, to.z));
            RoadProfile sample = roadProfile(x, z);
            if (sample.network() != network
                    || !CityWorlds.hasFullyLoadedChunk(level, x, z)) {
                return 0;
            }
            score++;
        }
        return score;
    }

    private static int roadScore(
            ServerLevel level, Vec3 origin, float yaw, int distance) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        RoadProfile originRoad = roadProfile(Mth.floor(origin.x), Mth.floor(origin.z));
        if (originRoad.network() == null) return 0;
        int groundY = originRoad.groundY();
        int score = 0;
        for (int step = 4; step <= distance; step += 4) {
            int x = Mth.floor(origin.x + forwardX * step);
            int z = Mth.floor(origin.z + forwardZ * step);
            RoadProfile sample = roadProfile(x, z);
            if (sample.network() != originRoad.network()
                    || Math.abs(sample.groundY() - groundY) > 2
                    || !CityWorlds.hasFullyLoadedChunk(level, x, z)) {
                break;
            }
            score += 10;
        }
        return score;
    }

    private static boolean sameRoadDeck(
            RoadProfile origin, double x, double z, Network network) {
        RoadProfile sample = roadProfile(Mth.floor(x), Mth.floor(z));
        return sample.network() == network
                && Math.abs(sample.groundY() - origin.groundY()) <= 2;
    }

    static boolean isNavigableRoad(NeonCityGenerator.RoadClass roadClass) {
        return RoadsideVehicleSpawns.isMovingTrafficRoad(roadClass);
    }

    static boolean isNavigableAt(int x, int z) {
        return roadProfile(x, z).network() != null;
    }

    static boolean isAtlasTrafficAt(int x, int z) {
        return roadProfile(x, z).network() == Network.ATLAS;
    }

    private static NeonCityGenerator.UrbanSample sample(double x, double z) {
        return NeonCityGenerator.sample(Mth.floor(x), Mth.floor(z));
    }

    private record RoadProfile(
            NeonCityGenerator.RoadClass roadClass,
            NeonCityGenerator.AtlasRoadClass atlasRoadClass,
            int groundY,
            Network network
    ) {}

    private static RoadProfile roadProfile(int x, int z) {
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
        if (NeonCityGenerator.isHighwayRoadClass(sample.roadClass())) {
            return new RoadProfile(
                    sample.roadClass(), NeonCityGenerator.AtlasRoadClass.NONE,
                    sample.groundY(), Network.HIGHWAY);
        }
        NeonCityGenerator.AtlasRoadClass atlas = NeonCityGenerator.atlasRoadAt(x, z);
        return new RoadProfile(
                sample.roadClass(), atlas, sample.groundY(),
                NeonCityGenerator.isAtlasStreetColumnAt(x, z) && atlas.supportsTraffic()
                        ? Network.ATLAS : null);
    }

    private static float cruisingThrottle(RoadProfile road) {
        if (road.network() == Network.ATLAS) {
            return road.atlasRoadClass() == NeonCityGenerator.AtlasRoadClass.PRIMARY
                    ? 0.54F : 0.46F;
        }
        return switch (road.roadClass()) {
            case INTERDISTRICT_ROAD, BRIDGE -> 0.70F;
            case DISTRICT_BOULEVARD -> 0.60F;
            default -> 0.48F;
        };
    }

    private static int headingBin(float yaw) {
        return Math.floorMod(Math.round(yaw * HEADING_BINS / 360.0F), HEADING_BINS);
    }

    private static float binYaw(int bin) {
        return Math.floorMod(bin, HEADING_BINS) * (360.0F / HEADING_BINS);
    }
}
