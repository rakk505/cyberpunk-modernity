package dev.modernity.neoncity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/**
 * Runtime index of curated, provenance-audited Arnis atlases.
 *
 * <p>The bundled JSON catalog is the single source of truth. Three-by-three
 * neighborhoods are imported as nine independently placeable one-chunk tiles:
 * Minecraft never has to write through an unloaded chunk, while roads and
 * building footprints remain coherent across tile seams.</p>
 */
public final class ArnisPatchLibrary {
    private static final long SELECTION_SALT = 0x41524E4953504154L;
    private static final int ATLAS_CELL_CHUNKS = 23;
    private static final int MAX_SELECTION_CACHE = 65_536;
    private static final String CATALOG_RESOURCE = "/data/neoncity/arnis/catalog.json";
    private static final Pattern ATLAS_TILE = Pattern.compile("^(.+)_([0-9]+)_([0-9]+)$");

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

    private record IndexedPatch(Patch patch, int tileX, int tileZ) {}

    private record Atlas(
            String id,
            District district,
            int width,
            int depth,
            List<Patch> tiles
    ) {
        Patch tile(int x, int z) { return tiles.get(z * width + x); }
    }

    private record SelectionKey(long seed, int chunkX, int chunkZ) {}
    private record CellKey(long seed, District district, int cellX, int cellZ) {}
    private record AtlasPlacement(Atlas atlas, int originX, int originZ, long hash) {}

    public static final List<Patch> PATCHES = loadCatalog();
    public static final Patch SHINJUKU_CORE = PATCHES.stream()
            .filter(patch -> patch.catalogId().equals("z/shinjuku_core"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("audited Shinjuku patch is missing"));

    private static final Map<District, List<Atlas>> ATLASES = buildAtlases(PATCHES);
    private static final ConcurrentHashMap<SelectionKey, Optional<Placement>> SELECTION_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CellKey, Optional<AtlasPlacement>> CELL_CACHE =
            new ConcurrentHashMap<>();

    private ArnisPatchLibrary() {}

    /** Deterministically selects a compatible tile from a coherent atlas. */
    public static Optional<Placement> select(MegacityLayout layout, int chunkX, int chunkZ) {
        if (SELECTION_CACHE.size() > MAX_SELECTION_CACHE) clearSelectionCache();
        SelectionKey key = new SelectionKey(layout.seed(), chunkX, chunkZ);
        return SELECTION_CACHE.computeIfAbsent(
                key, ignored -> computeSelection(layout, chunkX, chunkZ));
    }

    private static Optional<Placement> computeSelection(
            MegacityLayout layout, int chunkX, int chunkZ) {
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        MegacityLayout.Location location = layout.locate(centerX, centerZ);
        if (!allowsAtlas(location)) return Optional.empty();
        List<Atlas> candidates = ATLASES.getOrDefault(location.district(), List.of());
        if (candidates.isEmpty()) return Optional.empty();

        int cellX = Math.floorDiv(chunkX, ATLAS_CELL_CHUNKS);
        int cellZ = Math.floorDiv(chunkZ, ATLAS_CELL_CHUNKS);
        CellKey key = new CellKey(layout.seed(), location.district(), cellX, cellZ);
        Optional<AtlasPlacement> plan = CELL_CACHE.computeIfAbsent(
                key, ignored -> planCell(layout, location.district(), cellX, cellZ, candidates));
        if (plan.isEmpty()) return Optional.empty();

        AtlasPlacement placement = plan.get();
        int tileX = chunkX - placement.originX();
        int tileZ = chunkZ - placement.originZ();
        if (tileX < 0 || tileZ < 0
                || tileX >= placement.atlas().width()
                || tileZ >= placement.atlas().depth()) return Optional.empty();
        Patch patch = placement.atlas().tile(tileX, tileZ);
        return Optional.of(new Placement(
                patch, chunkX, chunkZ,
                placement.hash() ^ ((long) tileX << 32) ^ tileZ));
    }

    private static Optional<AtlasPlacement> planCell(
            MegacityLayout layout,
            District district,
            int cellX,
            int cellZ,
            List<Atlas> candidates) {
        long hash = MegacityLayout.mix(
                layout.seed() ^ SELECTION_SALT ^ ((long) district.ordinal() * 0x9E3779B97F4A7C15L),
                cellX, cellZ);
        Atlas atlas = candidates.get(Math.floorMod((int) hash, candidates.size()));
        int roomX = ATLAS_CELL_CHUNKS - atlas.width() + 1;
        int roomZ = ATLAS_CELL_CHUNKS - atlas.depth() + 1;
        int originX = cellX * ATLAS_CELL_CHUNKS
                + Math.floorMod((int) (hash >>> 17), roomX);
        int originZ = cellZ * ATLAS_CELL_CHUNKS
                + Math.floorMod((int) (hash >>> 37), roomZ);
        if (!atlasFits(layout, atlas, originX, originZ)) return Optional.empty();
        return Optional.of(new AtlasPlacement(atlas, originX, originZ, hash));
    }

    private static boolean atlasFits(
            MegacityLayout layout, Atlas atlas, int originX, int originZ) {
        for (int tileZ = 0; tileZ < atlas.depth(); tileZ++) {
            for (int tileX = 0; tileX < atlas.width(); tileX++) {
                int minX = (originX + tileX) << 4;
                int minZ = (originZ + tileZ) << 4;
                int[] offsets = {0, 8, 15};
                for (int dz : offsets) {
                    for (int dx : offsets) {
                        MegacityLayout.Location location = layout.locate(minX + dx, minZ + dz);
                        if (location.district() != atlas.district() || !allowsAtlas(location)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean allowsAtlas(MegacityLayout.Location location) {
        return (location.zone() == MegacityLayout.Zone.NEST
                || location.zone() == MegacityLayout.Zone.BACKSTREETS)
                && !location.onConnection();
    }

    /**
     * Extends catalogued edge connectors through one neighbouring chunk so an
     * imported neighborhood joins the procedural street fabric.
     */
    public static boolean connectorApproachAt(MegacityLayout layout, int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        for (Connector.Edge edge : Connector.Edge.values()) {
            int candidateX = switch (edge) {
                case WEST -> chunkX + 1;
                case EAST -> chunkX - 1;
                default -> chunkX;
            };
            int candidateZ = switch (edge) {
                case NORTH -> chunkZ + 1;
                case SOUTH -> chunkZ - 1;
                default -> chunkZ;
            };
            Optional<Placement> placement = select(layout, candidateX, candidateZ);
            if (placement.isEmpty()) continue;
            for (Connector connector : placement.get().patch().connectors()) {
                if (connector.edge() != edge) continue;
                if (edge == Connector.Edge.WEST || edge == Connector.Edge.EAST) {
                    int roadStartZ = (candidateZ << 4) + connector.offset();
                    if (worldZ >= roadStartZ && worldZ < roadStartZ + connector.width()) return true;
                } else {
                    int roadStartX = (candidateX << 4) + connector.offset();
                    if (worldX >= roadStartX && worldX < roadStartX + connector.width()) return true;
                }
            }
        }
        return false;
    }

    public static void clearSelectionCache() {
        SELECTION_CACHE.clear();
        CELL_CACHE.clear();
    }

    public static int atlasCount() {
        return ATLASES.values().stream().mapToInt(List::size).sum();
    }

    /** Finds the closest selected atlas tile for operator diagnostics. */
    public static Optional<Placement> findNearest(
            MegacityLayout layout, District district, int maxRadiusChunks) {
        if (!ATLASES.containsKey(district)) return Optional.empty();
        MegacityLayout.Node node = layout.node(district);
        int centerChunkX = Math.floorDiv(node.x(), 16);
        int centerChunkZ = Math.floorDiv(node.z(), 16);
        for (int ring = 0; ring <= maxRadiusChunks; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    Optional<Placement> placement = select(
                            layout, centerChunkX + dx, centerChunkZ + dz);
                    if (placement.isPresent() && placement.get().patch().district() == district) {
                        return placement;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static List<Patch> loadCatalog() {
        InputStream stream = ArnisPatchLibrary.class.getResourceAsStream(CATALOG_RESOURCE);
        if (stream == null) throw new IllegalStateException("missing " + CATALOG_RESOURCE);
        ArrayList<Patch> patches = new ArrayList<>();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("patches")) {
                JsonObject value = element.getAsJsonObject();
                String catalogId = value.get("id").getAsString();
                String file = value.get("file").getAsString();
                if (!file.startsWith("structures/") || !file.endsWith(".nbt")) {
                    throw new IllegalStateException("unsafe Arnis catalog path " + file);
                }
                String templatePath = "arnis/"
                        + file.substring("structures/".length(), file.length() - ".nbt".length());
                JsonObject footprint = value.getAsJsonObject("footprint");
                JsonObject anchor = footprint.getAsJsonObject("anchor");
                JsonObject blocks = footprint.getAsJsonObject("blocks");
                int sourceMinY = anchor.get("source_y").getAsInt();
                int sourceSurfaceY = anchor.has("surface_y")
                        ? anchor.get("surface_y").getAsInt() : sourceMinY + 2;
                ArrayList<Connector> connectors = new ArrayList<>();
                for (JsonElement connectorElement : value.getAsJsonArray("road_connectors")) {
                    JsonObject connector = connectorElement.getAsJsonObject();
                    connectors.add(new Connector(
                            Connector.Edge.valueOf(
                                    connector.get("edge").getAsString().toUpperCase(Locale.ROOT)),
                            connector.get("offset").getAsInt(),
                            connector.get("width").getAsInt()));
                }
                Patch patch = new Patch(
                        catalogId,
                        Identifier.fromNamespaceAndPath("neoncity", templatePath),
                        District.valueOf(value.get("district").getAsString() + "_CORP"),
                        sourceMinY,
                        sourceSurfaceY,
                        blocks.get("x").getAsInt(),
                        blocks.get("y").getAsInt(),
                        blocks.get("z").getAsInt(),
                        value.get("sha256").getAsString(),
                        List.copyOf(connectors));
                if (patch.sizeX() != 16 || patch.sizeZ() != 16 || patch.surfaceOffset() < 0) {
                    throw new IllegalStateException(
                            "runtime Arnis tile must be one complete anchored chunk: " + catalogId);
                }
                patches.add(patch);
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("cannot load audited Arnis catalog", error);
        }
        patches.sort(Comparator.comparing(Patch::catalogId));
        return List.copyOf(patches);
    }

    private static Map<District, List<Atlas>> buildAtlases(List<Patch> patches) {
        LinkedHashMap<String, List<IndexedPatch>> groups = new LinkedHashMap<>();
        for (Patch patch : patches) {
            Matcher matcher = ATLAS_TILE.matcher(patch.catalogId());
            String atlasId = patch.catalogId();
            int tileX = 0;
            int tileZ = 0;
            if (matcher.matches()) {
                atlasId = matcher.group(1);
                tileX = Integer.parseInt(matcher.group(2));
                tileZ = Integer.parseInt(matcher.group(3));
            }
            groups.computeIfAbsent(atlasId, ignored -> new ArrayList<>())
                    .add(new IndexedPatch(patch, tileX, tileZ));
        }

        EnumMap<District, List<Atlas>> byDistrict = new EnumMap<>(District.class);
        for (Map.Entry<String, List<IndexedPatch>> entry : groups.entrySet()) {
            List<IndexedPatch> indexed = entry.getValue();
            District district = indexed.getFirst().patch().district();
            int width = indexed.stream().mapToInt(value -> value.tileX() + 1).max().orElseThrow();
            int depth = indexed.stream().mapToInt(value -> value.tileZ() + 1).max().orElseThrow();
            ArrayList<Patch> tiles = new ArrayList<>(Collections.nCopies(width * depth, null));
            for (IndexedPatch value : indexed) {
                if (value.patch().district() != district) {
                    throw new IllegalStateException("Arnis atlas crosses district cultures: " + entry.getKey());
                }
                int index = value.tileZ() * width + value.tileX();
                if (tiles.set(index, value.patch()) != null) {
                    throw new IllegalStateException("duplicate Arnis atlas tile: " + entry.getKey());
                }
            }
            if (tiles.stream().anyMatch(value -> value == null)) {
                throw new IllegalStateException("incomplete Arnis atlas: " + entry.getKey());
            }
            Atlas atlas = new Atlas(entry.getKey(), district, width, depth, List.copyOf(tiles));
            byDistrict.computeIfAbsent(district, ignored -> new ArrayList<>()).add(atlas);
        }
        for (Map.Entry<District, List<Atlas>> entry : byDistrict.entrySet()) {
            entry.getValue().sort(Comparator.comparing(Atlas::id));
            entry.setValue(List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(byDistrict);
    }
}
