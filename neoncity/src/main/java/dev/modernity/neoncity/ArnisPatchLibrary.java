package dev.modernity.neoncity;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Runtime index of curated, provenance-audited Arnis patches.
 *
 * <p>The source catalog remains the authority for hashes and licensing. This
 * compact index contains only placement facts needed in the hot sampling path.
 * New imports must pass {@code tools/arnis/arnis_import.py validate} before an
 * entry is added here.</p>
 */
public final class ArnisPatchLibrary {
    private static final long SELECTION_SALT = 0x41524E4953504154L;

    public record Connector(Edge edge, int offset, int width) {
        public enum Edge { WEST, EAST, NORTH, SOUTH }
    }

    public record Patch(
            String catalogId,
            Identifier templateId,
            District district,
            int sourceMinY,
            int sourceSurfaceY,
            int sizeX,
            int sizeY,
            int sizeZ,
            String sha256,
            List<Connector> connectors
    ) {
        public int surfaceOffset() { return sourceSurfaceY - sourceMinY; }
    }

    public record Placement(Patch patch, int chunkX, int chunkZ, long selectionHash) {}

    public static final Patch SHINJUKU_CORE = new Patch(
            "z/shinjuku_core",
            Identifier.fromNamespaceAndPath("neoncity", "arnis/z/shinjuku_core"),
            District.Z_CORP,
            -64,
            68,
            16,
            162,
            16,
            "90b320fa82731f6715f63b128cc7d7e18dfeb30e2854fa49ee7dd9fc7f4e47ea",
            List.of(
                    new Connector(Connector.Edge.WEST, 3, 3),
                    new Connector(Connector.Edge.EAST, 6, 3)));

    public static final List<Patch> PATCHES = List.of(SHINJUKU_CORE);

    private ArnisPatchLibrary() {}

    /** Deterministically selects a compatible patch for a complete chunk. */
    public static Optional<Placement> select(MegacityLayout layout, int chunkX, int chunkZ) {
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        MegacityLayout.Location location = layout.locate(centerX, centerZ);
        if (location.district() != District.Z_CORP
                || (location.zone() != MegacityLayout.Zone.NEST
                && location.zone() != MegacityLayout.Zone.BACKSTREETS)
                || location.onConnection()) return Optional.empty();
        long hash = MegacityLayout.mix(layout.seed() ^ SELECTION_SALT, chunkX, chunkZ);
        if (Math.floorMod((int) hash, 53) != 0) return Optional.empty();
        return Optional.of(new Placement(SHINJUKU_CORE, chunkX, chunkZ, hash));
    }

    /**
     * Extends catalogued edge connectors through one neighbouring chunk so an
     * imported patch joins the procedural street fabric instead of becoming an
     * isolated diorama.
     */
    public static boolean connectorApproachAt(MegacityLayout layout, int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        for (Patch patch : PATCHES) {
            for (Connector connector : patch.connectors()) {
                int candidateX = switch (connector.edge()) {
                    case WEST -> chunkX + 1;
                    case EAST -> chunkX - 1;
                    default -> chunkX;
                };
                int candidateZ = switch (connector.edge()) {
                    case NORTH -> chunkZ + 1;
                    case SOUTH -> chunkZ - 1;
                    default -> chunkZ;
                };
                Optional<Placement> placement = select(layout, candidateX, candidateZ);
                if (placement.isEmpty() || placement.get().patch() != patch) continue;
                int before = (connector.width() - 1) / 2;
                int after = connector.width() / 2;
                if (connector.edge() == Connector.Edge.WEST || connector.edge() == Connector.Edge.EAST) {
                    int roadZ = (candidateZ << 4) + connector.offset();
                    if (worldZ >= roadZ - before && worldZ <= roadZ + after) return true;
                } else {
                    int roadX = (candidateX << 4) + connector.offset();
                    if (worldX >= roadX - before && worldX <= roadX + after) return true;
                }
            }
        }
        return false;
    }
}
