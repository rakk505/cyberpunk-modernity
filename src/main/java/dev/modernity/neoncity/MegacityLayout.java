package dev.modernity.neoncity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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
    public static final double BORDER_SECONDARY_LIMIT = 1.18;
    public static final double BORDER_GAP_LIMIT = 0.11;
    private static final int CONNECTION_PROJECTION_SEGMENTS = 12;
    private static final int CONNECTION_PROJECTION_REFINEMENTS = 5;
    private static final long NODE_SALT = 0x4E4F444553414C54L;
    private static final long EDGE_SALT = 0x4544474553414C54L;

    public enum Zone {
        NEST,
        BACKSTREETS,
        /** Travel-corridor land between blobs; ordinary district land never uses this zone. */
        OUTSKIRTS,
        BORDER_WALLED,
        BORDER_FOREST,
        BORDER_CLIFF,
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

    public record Edge(
            Node first,
            Node second,
            ConnectionKind kind,
            double bend,
            long identity,
            boolean elevatedBackbone
    ) {
        public boolean connects(District left, District right) {
            return (first.district() == left && second.district() == right)
                    || (first.district() == right && second.district() == left);
        }

        /** Prim-tree routes form the backbone; legacy elevated-style chords add optional loops. */
        public boolean hasElevatedLayer() {
            return elevatedBackbone || kind == ConnectionKind.ELEVATED_RAIL;
        }
    }

    /** Position and derivative of one point on a connection's quadratic Bezier curve. */
    public record CurvePoint(double x, double z, double tangentX, double tangentZ) {}

    /** Closest curve point to a world position, including progress and travel direction. */
    public record ConnectionProjection(
            Edge edge,
            double x,
            double z,
            double distance,
            double progress,
            double tangentX,
            double tangentZ
    ) {}

    /** Pair-stable coordinates across and along one district boundary. */
    public record BoundaryFrame(
            District first,
            District second,
            double signedGap,
            double gapRatio,
            double normalX,
            double normalZ,
            double tangentX,
            double tangentZ,
            double along
    ) {}

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
    private final List<Edge> elevatedEdges;
    private final Map<District, Node> byDistrict;

    private MegacityLayout(long seed, List<Node> nodes, List<Edge> edges) {
        this.seed = seed;
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        this.elevatedEdges = this.edges.stream().filter(Edge::hasElevatedLayer).toList();
        EnumMap<District, Node> index = new EnumMap<>(District.class);
        for (Node node : nodes) index.put(node.district(), node);
        this.byDistrict = Collections.unmodifiableMap(index);
    }

    public static MegacityLayout create(long worldSeed) {
        return createFromLayoutSeed(mix(worldSeed ^ DEFAULT_SEED, 0, 0));
    }

    /** Recreates a plan from its already-mixed layout seed without needing the world seed. */
    public static MegacityLayout createFromLayoutSeed(long layoutSeed) {
        long seed = layoutSeed;
        ArrayList<District> assignment = new ArrayList<>(List.of(District.values()));
        // A Corp is the monumental origin. U Corp is reserved for the outermost point so its
        // seeded port can always open toward wilderness and a real ocean biome. Every other
        // culture changes place with the world seed while preserving the balanced point set.
        int outermostIndex = assignment.size() - 1;
        Collections.swap(assignment, assignment.indexOf(District.U_CORP), outermostIndex);
        for (int index = outermostIndex - 1; index > 1; index--) {
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
            double radial = district == District.U_CORP
                    ? 3_820.0 + signedUnit(Long.rotateRight(identity, 9)) * 35.0
                    : 735.0 * Math.sqrt(index)
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
            addEdge(seed, nodes, edges, pairs, bestA, bestB, true);
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
                    addEdge(seed, nodes, edges, pairs, a, b, false);
                }
            }
        }
        return edges;
    }

    private static void addEdge(long seed, List<Node> nodes, List<Edge> edges,
                                Set<Long> pairs, int a, int b, boolean elevatedBackbone) {
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
        edges.add(new Edge(
                nodes.get(low), nodes.get(high), kind, bend, identity, elevatedBackbone));
    }

    public Location locate(int worldX, int worldZ) {
        return locate(worldX, worldZ, true);
    }

    /** Fast district-only lookup for map rasterization; graph routes are drawn as vector overlays. */
    public Location locateDistrict(int worldX, int worldZ) {
        return locate(worldX, worldZ, false);
    }

    private Location locate(int worldX, int worldZ, boolean includeConnections) {
        Candidate primary = null;
        Candidate secondary = null;
        for (Node node : nodes) {
            double score = normalizedDistanceTo(node, worldX, worldZ);
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
        if (includeConnections) {
            Optional<ConnectionProjection> nearest = nearestConnection(worldX, worldZ);
            if (nearest.isPresent()) {
                nearestEdge = nearest.get().edge();
                nearestEdgeDistance = nearest.get().distance();
            }
        }

        double boundaryGap = secondary.score() - primary.score();
        boolean inBlob = primary.score() <= 1.08;
        boolean onConnection = includeConnections && nearestEdgeDistance <= 13.0
                && betweenEndpoints(nearestEdge, worldX, worldZ, 1.15);
        Zone zone;
        if (!inBlob && !onConnection) {
            zone = Zone.WILDERNESS;
        } else if (inBlob && primary.score() <= 0.45) {
            zone = Zone.NEST;
        } else if (inBlob) {
            // A district has exactly two inhabited zones. The Backstreets continue to the
            // irregular blob edge; land beyond it is wilderness except for graph corridors.
            zone = Zone.BACKSTREETS;
        } else {
            // Keep interdistrict roads and bridges generatable without inventing a third
            // district biome. Runtime atlas selection explicitly excludes this corridor zone.
            zone = Zone.OUTSKIRTS;
        }

        if (isDistrictBorder(primary.score(), secondary.score()) && !onConnection) {
            zone = boundaryZone(primary.node().district(), secondary.node().district());
        }
        return new Location(primary.node(), secondary.node(), zone, primary.score(),
                boundaryGap, nearestEdge, nearestEdgeDistance);
    }

    /** Exact irregular-ellipse distance used to classify a point against one district. */
    public double normalizedDistanceTo(Node node, int x, int z) {
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

    /** Stable boundary terrain for an overlapping pair of district blobs. */
    public Zone boundaryZone(District first, District second) {
        long border = mix(seed ^ 0x424F52444552534CL,
                Math.min(first.ordinal(), second.ordinal()),
                Math.max(first.ordinal(), second.ordinal()));
        return switch (floorMod((int) (border ^ (border >>> 32)), 3)) {
            case 0 -> Zone.BORDER_WALLED;
            case 1 -> Zone.BORDER_FOREST;
            default -> Zone.BORDER_CLIFF;
        };
    }

    /** Shared runtime/map predicate for the full widened district-border band. */
    public static boolean isDistrictBorder(double primaryScore, double secondaryScore) {
        return primaryScore <= 1.08
                && secondaryScore <= BORDER_SECONDARY_LIMIT
                && secondaryScore - primaryScore < BORDER_GAP_LIMIT;
    }

    /** Stable signed frame used by border walls, trails, structures, and utilities. */
    public BoundaryFrame boundaryFrame(Location location, int worldX, int worldZ) {
        District low = location.primary().district().ordinal()
                <= location.secondary().district().ordinal()
                ? location.primary().district() : location.secondary().district();
        District high = low == location.primary().district()
                ? location.secondary().district() : location.primary().district();
        Node first = node(low);
        Node second = node(high);
        double signedGap = normalizedDistanceTo(first, worldX, worldZ)
                - normalizedDistanceTo(second, worldX, worldZ);
        double centerDx = second.x() - first.x();
        double centerDz = second.z() - first.z();
        double length = Math.max(1.0, Math.hypot(centerDx, centerDz));
        double normalX = centerDx / length;
        double normalZ = centerDz / length;
        double tangentX = -normalZ;
        double tangentZ = normalX;
        return new BoundaryFrame(
                low,
                high,
                signedGap,
                Math.min(1.0, Math.abs(signedGap) / BORDER_GAP_LIMIT),
                normalX,
                normalZ,
                tangentX,
                tangentZ,
                worldX * tangentX + worldZ * tangentZ);
    }

    /** Evaluate an edge using the same quadratic curve used by generation and map rendering. */
    public static CurvePoint curvePoint(Edge edge, double progress) {
        double t = Math.max(0.0, Math.min(1.0, progress));
        Node first = edge.first();
        Node second = edge.second();
        double dx = second.x() - first.x();
        double dz = second.z() - first.z();
        double controlX = (first.x() + second.x()) * 0.5 - dz * edge.bend();
        double controlZ = (first.z() + second.z()) * 0.5 + dx * edge.bend();
        double inverse = 1.0 - t;
        double x = inverse * inverse * first.x()
                + 2.0 * inverse * t * controlX + t * t * second.x();
        double z = inverse * inverse * first.z()
                + 2.0 * inverse * t * controlZ + t * t * second.z();
        double tangentX = 2.0 * inverse * (controlX - first.x())
                + 2.0 * t * (second.x() - controlX);
        double tangentZ = 2.0 * inverse * (controlZ - first.z())
                + 2.0 * t * (second.z() - controlZ);
        return new CurvePoint(x, z, tangentX, tangentZ);
    }

    /** Project a world position onto one edge with sub-segment progress. */
    public static ConnectionProjection projectConnection(Edge edge, double worldX, double worldZ) {
        Node first = edge.first();
        Node second = edge.second();
        double endpointX = second.x() - first.x();
        double endpointZ = second.z() - first.z();
        double controlX = (first.x() + second.x()) * 0.5 - endpointZ * edge.bend();
        double controlZ = (first.z() + second.z()) * 0.5 + endpointX * edge.bend();
        double quadraticX = first.x() - 2.0 * controlX + second.x();
        double quadraticZ = first.z() - 2.0 * controlZ + second.z();
        double linearX = 2.0 * (controlX - first.x());
        double linearZ = 2.0 * (controlZ - first.z());
        double bestDistanceSquared = Double.MAX_VALUE;
        double bestProgress = 0.0;
        double previousX = first.x();
        double previousZ = first.z();
        for (int segment = 1; segment <= CONNECTION_PROJECTION_SEGMENTS; segment++) {
            double segmentEndProgress = segment / (double) CONNECTION_PROJECTION_SEGMENTS;
            double currentX = quadraticX * segmentEndProgress * segmentEndProgress
                    + linearX * segmentEndProgress + first.x();
            double currentZ = quadraticZ * segmentEndProgress * segmentEndProgress
                    + linearZ * segmentEndProgress + first.z();
            double segmentX = currentX - previousX;
            double segmentZ = currentZ - previousZ;
            double segmentLengthSquared = segmentX * segmentX + segmentZ * segmentZ;
            double localProgress = segmentLengthSquared == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0,
                    ((worldX - previousX) * segmentX
                            + (worldZ - previousZ) * segmentZ) / segmentLengthSquared));
            double projectedX = previousX + segmentX * localProgress;
            double projectedZ = previousZ + segmentZ * localProgress;
            double offsetX = worldX - projectedX;
            double offsetZ = worldZ - projectedZ;
            double distanceSquared = offsetX * offsetX + offsetZ * offsetZ;
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestProgress = (segment - 1 + localProgress) / CONNECTION_PROJECTION_SEGMENTS;
            }
            previousX = currentX;
            previousZ = currentZ;
        }

        double segmentRadius = 1.0 / CONNECTION_PROJECTION_SEGMENTS;
        double lower = Math.max(0.0, bestProgress - segmentRadius);
        double upper = Math.min(1.0, bestProgress + segmentRadius);
        for (int iteration = 0; iteration < CONNECTION_PROJECTION_REFINEMENTS; iteration++) {
            double curveX = quadraticX * bestProgress * bestProgress
                    + linearX * bestProgress + first.x();
            double curveZ = quadraticZ * bestProgress * bestProgress
                    + linearZ * bestProgress + first.z();
            double tangentX = 2.0 * quadraticX * bestProgress + linearX;
            double tangentZ = 2.0 * quadraticZ * bestProgress + linearZ;
            double offsetX = curveX - worldX;
            double offsetZ = curveZ - worldZ;
            double firstDerivative = offsetX * tangentX + offsetZ * tangentZ;
            double secondDerivative = tangentX * tangentX + tangentZ * tangentZ
                    + 2.0 * offsetX * quadraticX + 2.0 * offsetZ * quadraticZ;
            if (Math.abs(secondDerivative) < 1.0e-9) break;
            double refined = bestProgress - firstDerivative / secondDerivative;
            bestProgress = Math.max(lower, Math.min(upper, refined));
        }
        CurvePoint closest = curvePoint(edge, bestProgress);
        double distance = Math.hypot(worldX - closest.x(), worldZ - closest.z());
        return new ConnectionProjection(
                edge,
                closest.x(),
                closest.z(),
                distance,
                bestProgress,
                closest.tangentX(),
                closest.tangentZ());
    }

    /** Nearest ground route; every graph edge is retained in the connected road layer. */
    public Optional<ConnectionProjection> nearestConnection(double worldX, double worldZ) {
        return nearestConnection(edges, worldX, worldZ);
    }

    /** Nearest route in the connected elevated backbone plus elevated-style chord loops. */
    public Optional<ConnectionProjection> nearestElevatedConnection(double worldX, double worldZ) {
        return nearestConnection(elevatedEdges, worldX, worldZ);
    }

    private static Optional<ConnectionProjection> nearestConnection(
            List<Edge> candidates, double worldX, double worldZ) {
        ConnectionProjection nearest = null;
        for (Edge edge : candidates) {
            ConnectionProjection projection = projectConnection(edge, worldX, worldZ);
            if (nearest == null || projection.distance() < nearest.distance()) {
                nearest = projection;
            }
        }
        return Optional.ofNullable(nearest);
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
    public List<Edge> groundEdges() { return edges; }
    public List<Edge> edges() { return edges; }
    public List<Edge> elevatedEdges() { return elevatedEdges; }
    public Node node(District district) { return byDistrict.get(district); }

    public boolean isConnected() {
        return isGroundConnected();
    }

    public boolean isGroundConnected() {
        return isConnectedBy(edge -> true);
    }

    public boolean isElevatedConnected() {
        return isConnectedBy(Edge::hasElevatedLayer);
    }

    private boolean isConnectedBy(Predicate<Edge> included) {
        Set<District> visited = new HashSet<>();
        visited.add(nodes.getFirst().district());
        boolean changed;
        do {
            changed = false;
            for (Edge edge : edges) {
                if (!included.test(edge)) continue;
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
