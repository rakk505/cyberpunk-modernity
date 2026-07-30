package dev.modernity.neoncity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A deterministic 96x96 block of two-to-four-block-wide service alleys.
 *
 * <p>The interior is a perfect depth-first-search maze. Each sector also has
 * four canonical portals whose identity is shared with its neighbour, so the
 * alleys remain connected across negative coordinates and sector seams.</p>
 */
public final class AlleyMaze {
    public static final int SIZE = 96;
    public static final int GRID = 8;
    public static final int PITCH = 12;
    public static final int FIRST_CENTER = 6;
    public static final int PERFECT_EDGE_COUNT = GRID * GRID - 1;

    private static final long DFS_SALT = 0xD1B54A32D192ED03L;
    private static final long WIDTH_SALT = 0x94D049BB133111EBL;
    private static final long PORTAL_SALT = 0x369DEA0F31A53F85L;

    private AlleyMaze() {}

    public enum Side { NORTH, EAST, SOUTH, WEST }

    public record Edge(int fromX, int fromZ, int toX, int toZ, int width) {}

    public record Portal(Side side, int center, int width, int nodeX, int nodeZ) {}

    public static final class Plan {
        private final int sectorX;
        private final int sectorZ;
        private final boolean[] alleys;
        private final List<Edge> edges;
        private final List<Portal> portals;

        private Plan(int sectorX, int sectorZ, boolean[] alleys,
                     List<Edge> edges, List<Portal> portals) {
            this.sectorX = sectorX;
            this.sectorZ = sectorZ;
            this.alleys = alleys;
            this.edges = List.copyOf(edges);
            this.portals = List.copyOf(portals);
        }

        public int sectorX() { return sectorX; }
        public int sectorZ() { return sectorZ; }
        public List<Edge> edges() { return edges; }
        public List<Portal> portals() { return portals; }

        public boolean isAlley(int localX, int localZ) {
            return localX >= 0 && localX < SIZE && localZ >= 0 && localZ < SIZE
                    && alleys[localZ * SIZE + localX];
        }

        public int alleyCells() {
            int count = 0;
            for (boolean alley : alleys) {
                if (alley) count++;
            }
            return count;
        }

        /** Flood-fill proof used by GameTests. */
        public boolean isConnected() {
            int start = -1;
            for (int index = 0; index < alleys.length; index++) {
                if (alleys[index]) {
                    start = index;
                    break;
                }
            }
            if (start < 0) return false;
            boolean[] visited = new boolean[alleys.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;
            int found = 0;
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                found++;
                int x = index % SIZE;
                int z = index / SIZE;
                if (x > 0) visit(index - 1, alleys, visited, queue);
                if (x + 1 < SIZE) visit(index + 1, alleys, visited, queue);
                if (z > 0) visit(index - SIZE, alleys, visited, queue);
                if (z + 1 < SIZE) visit(index + SIZE, alleys, visited, queue);
            }
            return found == alleyCells();
        }
    }

    public static Plan generate(long worldSeed, int sectorX, int sectorZ) {
        boolean[] carved = new boolean[SIZE * SIZE];
        boolean[] visited = new boolean[GRID * GRID];
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        ArrayList<Edge> edges = new ArrayList<>(PERFECT_EDGE_COUNT);
        long state = mix(worldSeed ^ DFS_SALT, sectorX, sectorZ);
        int start = Math.floorMod((int) state, GRID * GRID);
        stack.push(start);
        visited[start] = true;

        while (!stack.isEmpty()) {
            int cell = stack.peek();
            int column = cell % GRID;
            int row = cell / GRID;
            int[] neighbours = new int[4];
            int count = 0;
            if (row > 0 && !visited[cell - GRID]) neighbours[count++] = cell - GRID;
            if (column + 1 < GRID && !visited[cell + 1]) neighbours[count++] = cell + 1;
            if (row + 1 < GRID && !visited[cell + GRID]) neighbours[count++] = cell + GRID;
            if (column > 0 && !visited[cell - 1]) neighbours[count++] = cell - 1;
            if (count == 0) {
                stack.pop();
                continue;
            }
            state = splitMix64(state);
            int next = neighbours[Math.floorMod((int) state, count)];
            visited[next] = true;
            stack.push(next);
            int nextColumn = next % GRID;
            int nextRow = next / GRID;
            int width = 2 + Math.floorMod(
                    (int) mix(worldSeed ^ WIDTH_SALT,
                            sectorX * GRID + Math.min(column, nextColumn),
                            sectorZ * GRID + Math.min(row, nextRow)), 3);
            Edge edge = new Edge(
                    center(column), center(row), center(nextColumn), center(nextRow), width);
            edges.add(edge);
            carveLine(carved, edge.fromX(), edge.fromZ(), edge.toX(), edge.toZ(), width);
        }

        ArrayList<Portal> portals = new ArrayList<>(4);
        portals.add(portal(worldSeed, sectorX, sectorZ, Side.WEST));
        portals.add(portal(worldSeed, sectorX, sectorZ, Side.EAST));
        portals.add(portal(worldSeed, sectorX, sectorZ, Side.NORTH));
        portals.add(portal(worldSeed, sectorX, sectorZ, Side.SOUTH));
        for (Portal portal : portals) {
            int nodeCenterX = center(portal.nodeX());
            int nodeCenterZ = center(portal.nodeZ());
            switch (portal.side()) {
                case WEST -> carveLine(carved, 0, portal.center(), nodeCenterX, nodeCenterZ, portal.width());
                case EAST -> carveLine(carved, SIZE - 1, portal.center(), nodeCenterX, nodeCenterZ, portal.width());
                case NORTH -> carveLine(carved, portal.center(), 0, nodeCenterX, nodeCenterZ, portal.width());
                case SOUTH -> carveLine(carved, portal.center(), SIZE - 1, nodeCenterX, nodeCenterZ, portal.width());
            }
        }

        return new Plan(sectorX, sectorZ, carved, edges, portals);
    }

    private static Portal portal(long seed, int sectorX, int sectorZ, Side side) {
        boolean vertical = side == Side.WEST || side == Side.EAST;
        int boundaryX = side == Side.WEST ? sectorX - 1 : sectorX;
        int boundaryZ = side == Side.NORTH ? sectorZ - 1 : sectorZ;
        long hash = mix(seed ^ PORTAL_SALT,
                vertical ? boundaryX : sectorX,
                vertical ? sectorZ : boundaryZ);
        int nodeIndex = Math.floorMod((int) hash, GRID);
        int width = 2 + Math.floorMod((int) (hash >>> 32), 3);
        int center = center(nodeIndex);
        return switch (side) {
            case WEST -> new Portal(side, center, width, 0, nodeIndex);
            case EAST -> new Portal(side, center, width, GRID - 1, nodeIndex);
            case NORTH -> new Portal(side, center, width, nodeIndex, 0);
            case SOUTH -> new Portal(side, center, width, nodeIndex, GRID - 1);
        };
    }

    private static void carveLine(boolean[] mask, int x1, int z1, int x2, int z2, int width) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        if (x1 == x2) {
            for (int z = minZ; z <= maxZ; z++) carveBand(mask, x1, z, width);
        } else if (z1 == z2) {
            for (int x = minX; x <= maxX; x++) carveBand(mask, x, z1, width);
        } else {
            // Portal connectors can be offset by a few blocks: use an L bend.
            for (int x = minX; x <= maxX; x++) carveBand(mask, x, z1, width);
            for (int z = minZ; z <= maxZ; z++) carveBand(mask, x2, z, width);
        }
    }

    private static void carveBand(boolean[] mask, int centerX, int centerZ, int width) {
        int before = (width - 1) / 2;
        int after = width / 2;
        for (int z = centerZ - before; z <= centerZ + after; z++) {
            for (int x = centerX - before; x <= centerX + after; x++) {
                if (x >= 0 && x < SIZE && z >= 0 && z < SIZE) {
                    mask[z * SIZE + x] = true;
                }
            }
        }
    }

    private static int center(int cell) {
        return FIRST_CENTER + cell * PITCH;
    }

    private static void visit(int index, boolean[] mask, boolean[] visited,
                              ArrayDeque<Integer> queue) {
        if (mask[index] && !visited[index]) {
            visited[index] = true;
            queue.addLast(index);
        }
    }

    private static long mix(long seed, int x, int z) {
        long value = seed ^ (long) x * 0x9E3779B97F4A7C15L
                ^ (long) z * 0xC2B2AE3D27D4EB4FL;
        return splitMix64(value);
    }

    private static long splitMix64(long value) {
        value += 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
