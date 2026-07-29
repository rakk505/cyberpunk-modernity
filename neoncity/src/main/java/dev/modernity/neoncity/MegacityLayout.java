package dev.modernity.neoncity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeded, finite graph of cultural district blobs.
 *
 * <p>The layout is intentionally pure Java. Runtime generation and GameTests
 * consume the exact same graph, which makes district placement stable across
 * chunk order and server restarts.</p>
 */
public final class MegacityLayout {
    public static final long DEFAULT_SEED = 0x50524F4A4543544DL;
    public static final int DISTRICT_COUNT = District.values().length;
    public static final int NOMINAL_CITY_RADIUS = 4_900;

    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));
    private static final long NODE_SALT = 0x4E4F444553414C54L;
    private static final long EDGE_SALT = 0x4544474553414C54L;

    public enum Zone {
        NEST,
        BACKSTREETS,
        OUTSKIRTS,
        BORDER_RIVER,
        BORDER_HILLS,
        WILDERNESS
    }

    public enum ConnectionKind {
        GRAND_BOULEVARD,
        ELEVATED_RAIL,
        SCENIC_ROAD
    }

    public record Node(
            District district,
            int x,
            int z,
            int radiusX,
            int radiusZ,
            double rotation,
            long identity
    ) {}

    public record Edge(Node first, Node second, ConnectionKind kind, double bend, long identity) {
        public boolean connects(District left, District right) {
            return (first.district() == left && second.district() == right)
                    || (first.district() == right && second.district() == left);
        }
    }

    public record Location(
            Node primary,
            Node secondary,
            Zone zone,
            double normalizedDistance,
            double boundaryGap,
            Edge nearestConnection,
            double connectionDistance
    ) {
        public District district() { return primary.district(); }
        public boolean insideCity() { return zone != Zone.WILDERNESS; }
        public boolean onConnection() { return nearestConnection != null && connectionDistance <= 13.0; }
    }

    private record Candidate(Node node, double score) {}

    private final long seed;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Map<District, Node> byDistrict;

    private MegacityLayout(long seed, List<Node> nodes, List<Edge> edges) {
        this.seed = seed;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        EnumMap<District, Node> index = new EnumMap<>(District.class);
        for (Node node : nodes) index.put(node.district(), node);
        this.byDistrict = Collections.unmodifiableMap(index);
    }

    public static MegacityLayout create(long worldSeed) {
        long seed = mix(worldSeed ^ DEFAULT_SEED, 0, 0);
        ArrayList<District> assignment = new ArrayList<>(List.of(District.values()));
        // A Corp is the monumental origin; every other culture changes place
        // with the world seed while preserving the same balanced point set.
        for (int index = assignment.size() - 1; index > 1; index--) {
            int swap = 1 + floorMod((int) mix(seed ^ NODE_SALT, index, 17), index);
            Collections.swap(assignment, index, swap);
        }

        ArrayList<Node> nodes = new ArrayList<>(DISTRICT_COUNT);
        double phase = unit(seed) * Math.PI * 2.0;
        for (int index = 0; index < DISTRICT_COUNT; index++) {
            District district = assignment.get(index);
            long identity = mix(seed ^ NODE_SALT, index, district.ordinal());
            if (index == 0) {
                nodes.add(new Node(district, 0, 0, 990, 900, phase, identity));
                continue;
            }
            double radial = 735.0 * Math.sqrt(index)
                    + signedUnit(Long.rotateRight(identity, 9)) * 95.0;
            double angle = phase + index * GOLDEN_ANGLE
                    + signedUnit(Long.rotateRight(identity, 23)) * 0.13;
            int x = (int) Math.round(Math.cos(angle) * radial);
            int z = (int) Math.round(Math.sin(angle) * radial);
            int radius = 960 + floorMod((int) (identity >>> 32), 281);
            int radiusX = radius + floorMod((int) identity, 151) - 75;
            int radiusZ = radius + floorMod((int) (identity >>> 16), 151) - 75;
            double rotation = angle * 0.31
                    + signedUnit(Long.rotateRight(identity, 41)) * 0.5;
            nodes.add(new Node(district, x, z, radiusX, radiusZ, rotation, identity));
        }
        return new MegacityLayout(seed, nodes, buildEdges(seed, nodes));
    }

    private static List<Edge> buildEdges(long seed, List<Node> nodes) {
        ArrayList<Edge> edges = new ArrayList<>();
        Set<Long> pairs = new HashSet<>();
        boolean[] connected = new boolean[nodes.size()];
        connected[0] = true;
        int connectedCount = 1;

        // Prim's tree guarantees that every district can be reached.
        while (connectedCount < nodes.size()) {
            int bestA = -1;
            int bestB = -1;
            long bestDistance = Long.MAX_VALUE;
            for (int a = 0; a < nodes.size(); a++) {
                if (!connected[a]) continue;
                for (int b = 0; b < nodes.size(); b++) {
                    if (connected[b]) continue;
                    long distance = distanceSquared(nodes.get(a), nodes.get(b));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            addEdge(seed, nodes, edges, pairs, bestA, bestB);
            connected[bestB] = true;
            connectedCount++;
        }

        // Nearest-neighbour chords create loops and alternate travel routes.
        for (int a = 0; a < nodes.size(); a++) {
            final int origin = a;
            ArrayList<Integer> nearest = new ArrayList<>();
            for (int b = 0; b < nodes.size(); b++) if (b != a) nearest.add(b);
            nearest.sort(Comparator.comparingLong(b -> distanceSquared(nodes.get(origin), nodes.get(b))));
            for (int rank = 0; rank < Math.min(3, nearest.size()); rank++) {
                int b = nearest.get(rank);
                if (rank < 2 || floorMod((int) mix(seed, a, b), 3) == 0) {
                    addEdge(seed, nodes, edges, pairs, a, b);
                }
            }
        }
        return edges;
    }

    private static void addEdge(long seed, List<Node> nodes, List<Edge> edges,
                                Set<Long> pairs, int a, int b) {
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        long pair = ((long) low << 32) | (high & 0xffffffffL);
        if (!pairs.add(pair)) return;
        long identity = mix(seed ^ EDGE_SALT, low, high);
        ConnectionKind kind = switch (floorMod((int) identity, 7)) {
            case 0, 1 -> ConnectionKind.ELEVATED_RAIL;
            case 2 -> ConnectionKind.SCENIC_ROAD;
            default -> ConnectionKind.GRAND_BOULEVARD;
        };
        double bend = signedUnit(Long.rotateRight(identity, 21)) * 0.22;
        edges.add(new Edge(nodes.get(low), nodes.get(high), kind, bend, identity));
    }

    public Location locate(int worldX, int worldZ) {
        Candidate primary = null;
        Candidate secondary = null;
        for (Node node : nodes) {
            double score = blobDistance(node, worldX, worldZ);
            Candidate candidate = new Candidate(node, score);
            if (primary == null || score < primary.score()) {
                secondary = primary;
                primary = candidate;
            } else if (secondary == null || score < secondary.score()) {
                secondary = candidate;
            }
        }
        if (primary == null || secondary == null) throw new IllegalStateException("layout has no districts");

        Edge nearestEdge = null;
        double nearestEdgeDistance = Double.MAX_VALUE;
        for (Edge edge : edges) {
            double distance = connectionDistance(edge, worldX, worldZ);
            if (distance < nearestEdgeDistance) {
                nearestEdgeDistance = distance;
                nearestEdge = edge;
            }
        }

        double boundaryGap = secondary.score() - primary.score();
        boolean inBlob = primary.score() <= 1.08;
        boolean onConnection = nearestEdgeDistance <= 13.0
                && betweenEndpoints(nearestEdge, worldX, worldZ, 1.15);
        Zone zone;
        if (!inBlob && !onConnection) {
            zone = Zone.WILDERNESS;
        } else if (inBlob && primary.score() <= 0.45) {
            zone = Zone.NEST;
        } else if (inBlob && primary.score() <= 0.67) {
            zone = Zone.BACKSTREETS;
        } else if (inBlob && primary.score() <= 1.08) {
            zone = Zone.OUTSKIRTS;
        } else {
            zone = Zone.OUTSKIRTS;
        }

        if (inBlob && secondary.score() <= 1.12 && boundaryGap < 0.055 && !onConnection) {
            long border = mix(seed ^ 0x424F52444552534CL,
                    Math.min(primary.node().district().ordinal(), secondary.node().district().ordinal()),
                    Math.max(primary.node().district().ordinal(), secondary.node().district().ordinal()));
            zone = floorMod((int) border, 5) <= 1 ? Zone.BORDER_RIVER : Zone.BORDER_HILLS;
        }
        return new Location(primary.node(), secondary.node(), zone, primary.score(),
                boundaryGap, nearestEdge, nearestEdgeDistance);
    }

    private double blobDistance(Node node, int x, int z) {
        double dx = x - node.x();
        double dz = z - node.z();
        double cosine = Math.cos(node.rotation());
        double sine = Math.sin(node.rotation());
        double u = (dx * cosine + dz * sine) / node.radiusX();
        double v = (-dx * sine + dz * cosine) / node.radiusZ();
        double angle = Math.atan2(v, u);
        double ripple = 1.0
                + 0.075 * Math.sin(angle * 3.0 + node.identity() * 0.000001)
                + 0.045 * Math.sin(angle * 7.0 - node.identity() * 0.000003)
                + 0.035 * Math.sin((x + z) / 173.0 + node.identity() * 0.00001);
        return Math.hypot(u, v) / ripple;
    }

    private static double connectionDistance(Edge edge, double x, double z) {
        Node first = edge.first();
        Node second = edge.second();
        double midX = (first.x() + second.x()) * 0.5;
        double midZ = (first.z() + second.z()) * 0.5;
        double dx = second.x() - first.x();
        double dz = second.z() - first.z();
        double length = Math.max(1.0, Math.hypot(dx, dz));
        double controlX = midX - dz / length * length * edge.bend();
        double controlZ = midZ + dx / length * length * edge.bend();
        double best = Double.MAX_VALUE;
        double previousX = first.x();
        double previousZ = first.z();
        for (int segment = 1; segment <= 10; segment++) {
            double t = segment / 10.0;
            double inverse = 1.0 - t;
            double currentX = inverse * inverse * first.x()
                    + 2.0 * inverse * t * controlX + t * t * second.x();
            double currentZ = inverse * inverse * first.z()
                    + 2.0 * inverse * t * controlZ + t * t * second.z();
            best = Math.min(best, pointSegmentDistance(x, z, previousX, previousZ, currentX, currentZ));
            previousX = currentX;
            previousZ = currentZ;
        }
        return best;
    }

    private static double pointSegmentDistance(double px, double pz,
                                               double ax, double az,
                                               double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double length = dx * dx + dz * dz;
        if (length == 0.0) return Math.hypot(px - ax, pz - az);
        double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (pz - az) * dz) / length));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    private static boolean betweenEndpoints(Edge edge, int x, int z, double slack) {
        double edgeLength = Math.sqrt(distanceSquared(edge.first(), edge.second()));
        double total = Math.hypot(x - edge.first().x(), z - edge.first().z())
                + Math.hypot(x - edge.second().x(), z - edge.second().z());
        return total <= edgeLength * slack + 64.0;
    }

    private static long distanceSquared(Node first, Node second) {
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    public long seed() { return seed; }
    public List<Node> nodes() { return nodes; }
    public List<Edge> edges() { return edges; }
    public Node node(District district) { return byDistrict.get(district); }

    public boolean isConnected() {
        Set<District> visited = new HashSet<>();
        visited.add(nodes.getFirst().district());
        boolean changed;
        do {
            changed = false;
            for (Edge edge : edges) {
                if (visited.contains(edge.first().district()) && visited.add(edge.second().district())) changed = true;
                if (visited.contains(edge.second().district()) && visited.add(edge.first().district())) changed = true;
            }
        } while (changed);
        return visited.size() == nodes.size();
    }

    private static int floorMod(int value, int divisor) {
        return Math.floorMod(value, divisor);
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double signedUnit(long value) {
        return unit(value) * 2.0 - 1.0;
    }

    static long mix(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L
                ^ (long) z * 0xC2B2AE3D27D4EB4FL;
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
