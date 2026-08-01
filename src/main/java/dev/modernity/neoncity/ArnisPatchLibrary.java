package dev.modernity.neoncity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Runtime index and coherent mapper for district-scale Arnis atlases.
 *
 * <p>Every A-Z district owns a separate source atlas for its Nest and
 * Backstreets. Destination chunks map to source chunks as one
 * continuous reflected atlas: source neighbours remain neighbours, roads do
 * not get shuffled, and reflection makes repeated atlas edges meet their own
 * geometry. Procedural generation is reserved for district borders, graph
 * connections, and special infrastructure corridors.</p>
 */
public final class ArnisPatchLibrary {
    private static final long SELECTION_SALT = 0x41524E49535A4F4EL;
    private static final int MAX_SELECTION_CACHE = 131_072;
    private static final int EXPECTED_ATLAS_AXIS = 16;
    private static final int CONSERVATIVE_PARK_MAX_BLOCKS = 320;
    private static final int CONSERVATIVE_PARK_MAX_ABOVE_SURFACE = 2;
    private static final int PARK_ACCESS_LANE_WIDTH = 3;
    private static final int PARK_ACCESS_LANE_DEPTH = 4;
    private static final String CATALOG_RESOURCE =
            "/data/neoncity/arnis_districts/catalog.json";
    private static final String OPEN_PARK_AUDIT_RESOURCE =
            "/data/neoncity/arnis_districts/open_park_tiles.json";
    private static final Pattern ATLAS_TILE =
            Pattern.compile("^(.+)_([0-9]+)_([0-9]+)$");

    public record Connector(Edge edge, int offset, int width) {
        public enum Edge { WEST, EAST, NORTH, SOUTH }
    }

    public record Patch(
            String catalogId,
            Identifier templateId,
            District district,
            Set<MegacityLayout.Zone> placementZones,
            int sourceMinY,
            int sourceSurfaceY,
            int sizeX,
            int sizeY,
            int sizeZ,
            int blockCount,
            String sha256,
            List<Connector> connectors
    ) {
        public int surfaceOffset() { return sourceSurfaceY - sourceMinY; }
    }

    public record Placement(
            Patch patch,
            int chunkX,
            int chunkZ,
            int sourceTileX,
            int sourceTileZ,
            boolean flipX,
            boolean flipZ,
            Mirror mirror,
            Rotation rotation,
            long selectionHash
    ) {}

    private record IndexedPatch(Patch patch, int tileX, int tileZ) {}

    private record Atlas(
            String id,
            District district,
            Set<MegacityLayout.Zone> placementZones,
            int width,
            int depth,
            List<Patch> tiles
    ) {
        Patch tile(int x, int z) { return tiles.get(z * width + x); }
        boolean supports(MegacityLayout.Zone zone) { return placementZones.contains(zone); }
    }

    private record AxisMapping(int source, boolean flipped) {}
    private record SelectionKey(long seed, int chunkX, int chunkZ) {}
    private record OpenParkAudit(
            Set<String> catalogIds,
            Map<District, Integer> districtCounts,
            Map<Integer, Integer> heightCounts
    ) {}

    public static final List<Patch> PATCHES = loadCatalog();
    private static final OpenParkAudit OPEN_PARK_AUDIT = loadOpenParkAudit(PATCHES);
    private static final Map<District, List<Atlas>> ATLASES = buildAtlases(PATCHES);
    private static final ConcurrentHashMap<SelectionKey, Optional<Placement>> SELECTION_CACHE =
            new ConcurrentHashMap<>();

    private ArnisPatchLibrary() {}

    /** Select the exact coherent Arnis tile for a developed district chunk. */
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
        if (!isAtlasZone(location.zone())) return Optional.empty();

        List<Atlas> candidates = ATLASES.getOrDefault(location.district(), List.of())
                .stream().filter(atlas -> atlas.supports(location.zone())).toList();
        if (candidates.isEmpty()) return Optional.empty();
        long atlasHash = MegacityLayout.mix(
                layout.seed() ^ SELECTION_SALT,
                location.district().ordinal(), location.zone().ordinal());
        Atlas atlas = candidates.get(Math.floorMod((int) atlasHash, candidates.size()));

        MegacityLayout.Node node = layout.node(location.district());
        int nodeChunkX = Math.floorDiv(node.x(), 16);
        int nodeChunkZ = Math.floorDiv(node.z(), 16);
        AxisMapping mappedX = mapAxis(chunkX - nodeChunkX, atlas.width());
        AxisMapping mappedZ = mapAxis(chunkZ - nodeChunkZ, atlas.depth());
        boolean flipX = mappedX.flipped();
        boolean flipZ = mappedZ.flipped();
        Mirror mirror;
        Rotation rotation;
        if (flipX && flipZ) {
            mirror = Mirror.NONE;
            rotation = Rotation.CLOCKWISE_180;
        } else if (flipX) {
            mirror = Mirror.FRONT_BACK;
            rotation = Rotation.NONE;
        } else if (flipZ) {
            mirror = Mirror.LEFT_RIGHT;
            rotation = Rotation.NONE;
        } else {
            mirror = Mirror.NONE;
            rotation = Rotation.NONE;
        }
        Patch patch = atlas.tile(mappedX.source(), mappedZ.source());
        long selectionHash = MegacityLayout.mix(
                atlasHash ^ node.identity(), chunkX, chunkZ);
        return Optional.of(new Placement(
                patch, chunkX, chunkZ,
                mappedX.source(), mappedZ.source(),
                flipX, flipZ, mirror, rotation, selectionHash));
    }

    private static AxisMapping mapAxis(int destinationRelative, int atlasSize) {
        int centered = destinationRelative + atlasSize / 2;
        int copy = Math.floorDiv(centered, atlasSize);
        int local = Math.floorMod(centered, atlasSize);
        boolean flipped = Math.floorMod(copy, 2) == 1;
        return new AxisMapping(flipped ? atlasSize - 1 - local : local, flipped);
    }

    private static boolean isAtlasZone(MegacityLayout.Zone zone) {
        return zone == MegacityLayout.Zone.NEST
                || zone == MegacityLayout.Zone.BACKSTREETS;
    }

    /** A deliberately sparse source tile that can host a park without carving a building. */
    public static boolean isConservativeOpenParkTile(Patch patch) {
        return patch.blockCount() <= CONSERVATIVE_PARK_MAX_BLOCKS
                && !patch.connectors().isEmpty()
                && OPEN_PARK_AUDIT.catalogIds().contains(patch.catalogId());
    }

    static Set<String> auditedOpenParkTileIds() {
        return OPEN_PARK_AUDIT.catalogIds();
    }

    static Map<District, Integer> auditedOpenParkDistrictCounts() {
        return OPEN_PARK_AUDIT.districtCounts();
    }

    static Map<Integer, Integer> auditedOpenParkHeightCounts() {
        return OPEN_PARK_AUDIT.heightCounts();
    }

    static int auditedOpenParkMaximumAboveSurface() {
        return CONSERVATIVE_PARK_MAX_ABOVE_SURFACE;
    }

    /**
     * Returns whether a world column belongs to a three-block entrance lane into
     * a selected sparse park tile. Lanes are narrowed in source space before the
     * atlas reflection is applied, so even-width connectors mirror coherently.
     */
    public static boolean isParkAccessLaneAt(Placement placement, int worldX, int worldZ) {
        if (!isConservativeOpenParkTile(placement.patch())) return false;
        int localX = worldX - (placement.chunkX() << 4);
        int localZ = worldZ - (placement.chunkZ() << 4);
        if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16) return false;

        Connector connector = placement.patch().connectors().stream()
                .max(Comparator.comparingInt(Connector::width)
                        .thenComparingInt(value -> -value.edge().ordinal())
                        .thenComparingInt(value -> -value.offset()))
                .orElseThrow();
        Connector transformed = transformConnector(
                parkAccessLane(connector), placement.flipX(), placement.flipZ());
        int across = switch (transformed.edge()) {
            case WEST, EAST -> localZ;
            case NORTH, SOUTH -> localX;
        };
        int depth = switch (transformed.edge()) {
            case WEST -> localX;
            case EAST -> 15 - localX;
            case NORTH -> localZ;
            case SOUTH -> 15 - localZ;
        };
        return across >= transformed.offset()
                && across < transformed.offset() + transformed.width()
                && depth < PARK_ACCESS_LANE_DEPTH;
    }

    private static Connector parkAccessLane(Connector connector) {
        int offset = connector.offset()
                + (connector.width() - PARK_ACCESS_LANE_WIDTH) / 2;
        return new Connector(connector.edge(), offset, PARK_ACCESS_LANE_WIDTH);
    }

    /**
     * Extend a transformed source-road connector one block into an adjacent
     * procedural infrastructure chunk. Internal atlas seams need no stitching.
     */
    public static boolean connectorApproachAt(MegacityLayout layout, int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        for (Connector.Edge destinationEdge : Connector.Edge.values()) {
            int candidateX = switch (destinationEdge) {
                case WEST -> chunkX + 1;
                case EAST -> chunkX - 1;
                default -> chunkX;
            };
            int candidateZ = switch (destinationEdge) {
                case NORTH -> chunkZ + 1;
                case SOUTH -> chunkZ - 1;
                default -> chunkZ;
            };
            Optional<Placement> selected = select(layout, candidateX, candidateZ);
            if (selected.isEmpty()) continue;
            Placement placement = selected.get();
            for (Connector connector : placement.patch().connectors()) {
                Connector transformed = transformConnector(
                        connector, placement.flipX(), placement.flipZ());
                if (transformed.edge() != destinationEdge) continue;
                if (destinationEdge == Connector.Edge.WEST
                        || destinationEdge == Connector.Edge.EAST) {
                    int start = (candidateZ << 4) + transformed.offset();
                    if (worldZ >= start && worldZ < start + transformed.width()) return true;
                } else {
                    int start = (candidateX << 4) + transformed.offset();
                    if (worldX >= start && worldX < start + transformed.width()) return true;
                }
            }
        }
        return false;
    }

    static Connector transformConnector(Connector connector, boolean flipX, boolean flipZ) {
        Connector.Edge edge = connector.edge();
        if (flipX) {
            edge = switch (edge) {
                case WEST -> Connector.Edge.EAST;
                case EAST -> Connector.Edge.WEST;
                default -> edge;
            };
        }
        if (flipZ) {
            edge = switch (edge) {
                case NORTH -> Connector.Edge.SOUTH;
                case SOUTH -> Connector.Edge.NORTH;
                default -> edge;
            };
        }
        boolean reverseOffset = switch (connector.edge()) {
            case NORTH, SOUTH -> flipX;
            case WEST, EAST -> flipZ;
        };
        int offset = reverseOffset
                ? 16 - connector.offset() - connector.width()
                : connector.offset();
        return new Connector(edge, offset, connector.width());
    }

    /** Returns whether adjacent placements preserve one continuous source-atlas edge. */
    static boolean continuesCoherently(
            Placement current, Placement neighbour, int deltaChunkX, int deltaChunkZ) {
        if (Math.abs(deltaChunkX) + Math.abs(deltaChunkZ) != 1
                || current.patch().district() != neighbour.patch().district()
                || !current.patch().placementZones().equals(
                        neighbour.patch().placementZones())) {
            return false;
        }
        if (deltaChunkX != 0) {
            if (current.sourceTileZ() != neighbour.sourceTileZ()
                    || current.flipZ() != neighbour.flipZ()) {
                return false;
            }
            int expectedTileX = current.flipX() == neighbour.flipX()
                    ? current.sourceTileX()
                            + deltaChunkX * (current.flipX() ? -1 : 1)
                    : current.sourceTileX();
            return neighbour.sourceTileX() == expectedTileX;
        }
        if (current.sourceTileX() != neighbour.sourceTileX()
                || current.flipX() != neighbour.flipX()) {
            return false;
        }
        int expectedTileZ = current.flipZ() == neighbour.flipZ()
                ? current.sourceTileZ()
                        + deltaChunkZ * (current.flipZ() ? -1 : 1)
                : current.sourceTileZ();
        return neighbour.sourceTileZ() == expectedTileZ;
    }

    public static void clearSelectionCache() {
        SELECTION_CACHE.clear();
    }

    public static int atlasCount() {
        return ATLASES.values().stream().mapToInt(List::size).sum();
    }

    public static int districtAtlasCount(District district) {
        return ATLASES.getOrDefault(district, List.of()).size();
    }

    public static int zoneAtlasCount(District district, MegacityLayout.Zone zone) {
        return (int) ATLASES.getOrDefault(district, List.of()).stream()
                .filter(atlas -> atlas.supports(zone)).count();
    }

    /** Find a nearby selected tile for operator diagnostics. */
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
                    if (placement.isPresent()
                            && placement.get().patch().district() == district) {
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
                String templatePath = "arnis_districts/"
                        + file.substring("structures/".length(), file.length() - ".nbt".length());
                JsonObject footprint = value.getAsJsonObject("footprint");
                JsonObject anchor = footprint.getAsJsonObject("anchor");
                JsonObject blocks = footprint.getAsJsonObject("blocks");
                int sourceMinY = anchor.get("source_y").getAsInt();
                int sourceSurfaceY = anchor.has("surface_y")
                        ? anchor.get("surface_y").getAsInt() : sourceMinY + 2;
                EnumSet<MegacityLayout.Zone> zones = EnumSet.noneOf(MegacityLayout.Zone.class);
                if (value.has("placement_zones")) {
                    for (JsonElement zone : value.getAsJsonArray("placement_zones")) {
                        MegacityLayout.Zone placementZone =
                                MegacityLayout.Zone.valueOf(zone.getAsString());
                        if (!isAtlasZone(placementZone)) {
                            throw new IllegalStateException(
                                    "Arnis atlas may target only NEST/BACKSTREETS: " + catalogId);
                        }
                        zones.add(placementZone);
                    }
                } else {
                    zones.add(MegacityLayout.Zone.NEST);
                    zones.add(MegacityLayout.Zone.BACKSTREETS);
                }
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
                        Collections.unmodifiableSet(EnumSet.copyOf(zones)),
                        sourceMinY,
                        sourceSurfaceY,
                        blocks.get("x").getAsInt(),
                        blocks.get("y").getAsInt(),
                        blocks.get("z").getAsInt(),
                        value.get("block_count").getAsInt(),
                        value.get("sha256").getAsString(),
                        List.copyOf(connectors));
                if (patch.sizeX() != 16 || patch.sizeZ() != 16
                        || patch.surfaceOffset() < 0 || patch.blockCount() <= 0
                        || patch.placementZones().isEmpty()) {
                    throw new IllegalStateException(
                            "runtime Arnis tile must be one complete anchored chunk: " + catalogId);
                }
                patches.add(patch);
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("cannot load audited Arnis district catalog", error);
        }
        patches.sort(Comparator.comparing(Patch::catalogId));
        return List.copyOf(patches);
    }

    private static OpenParkAudit loadOpenParkAudit(List<Patch> patches) {
        try (InputStream stream = ArnisPatchLibrary.class.getResourceAsStream(
                OPEN_PARK_AUDIT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "missing audited open-park allowlist " + OPEN_PARK_AUDIT_RESOURCE);
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema_version").getAsInt() != 1) {
                throw new IllegalStateException("unsupported open-park audit schema");
            }
            JsonObject generatedFrom = root.getAsJsonObject("generated_from");
            String auditedCatalogHash = generatedFrom.get("catalog_sha256").getAsString();
            if (!auditedCatalogHash.equals(resourceSha256(CATALOG_RESOURCE))) {
                throw new IllegalStateException(
                        "open-park audit was not generated from the bundled catalog");
            }
            JsonObject criteria = root.getAsJsonObject("criteria");
            if (criteria.get("maximum_block_count").getAsInt()
                            != CONSERVATIVE_PARK_MAX_BLOCKS
                    || !criteria.get("requires_road_connector").getAsBoolean()
                    || criteria.get("maximum_occupied_blocks_above_surface").getAsInt()
                            != CONSERVATIVE_PARK_MAX_ABOVE_SURFACE) {
                throw new IllegalStateException(
                        "open-park audit criteria disagree with runtime policy");
            }

            LinkedHashMap<String, Patch> byId = new LinkedHashMap<>();
            for (Patch patch : patches) {
                if (byId.put(patch.catalogId(), patch) != null) {
                    throw new IllegalStateException(
                            "duplicate Arnis catalog id " + patch.catalogId());
                }
            }
            long sparseConnectorCandidates = patches.stream()
                    .filter(patch -> patch.blockCount() <= CONSERVATIVE_PARK_MAX_BLOCKS)
                    .filter(patch -> !patch.connectors().isEmpty())
                    .count();
            if (generatedFrom.get("scanned_sparse_connector_tiles").getAsLong()
                    != sparseConnectorCandidates) {
                throw new IllegalStateException(
                        "open-park audit candidate count disagrees with the catalog");
            }

            LinkedHashSet<String> auditedIds = new LinkedHashSet<>();
            EnumMap<District, Integer> districtCounts = new EnumMap<>(District.class);
            LinkedHashMap<Integer, Integer> heightCounts = new LinkedHashMap<>();
            JsonObject groups = root.getAsJsonObject(
                    "tiles_by_max_occupied_blocks_above_surface");
            for (Map.Entry<String, JsonElement> group : groups.entrySet()) {
                int height = Integer.parseInt(group.getKey());
                if (height < 0 || height > CONSERVATIVE_PARK_MAX_ABOVE_SURFACE) {
                    throw new IllegalStateException(
                            "open-park audit contains out-of-policy height " + height);
                }
                int count = 0;
                for (JsonElement element : group.getValue().getAsJsonArray()) {
                    String catalogId = element.getAsString();
                    if (!auditedIds.add(catalogId)) {
                        throw new IllegalStateException(
                                "duplicate open-park audit id " + catalogId);
                    }
                    Patch patch = byId.get(catalogId);
                    if (patch == null
                            || patch.blockCount() > CONSERVATIVE_PARK_MAX_BLOCKS
                            || patch.connectors().isEmpty()) {
                        throw new IllegalStateException(
                                "invalid open-park audit tile " + catalogId);
                    }
                    districtCounts.merge(patch.district(), 1, Integer::sum);
                    count++;
                }
                heightCounts.put(height, count);
            }

            int declaredTotal = root.get("tile_count").getAsInt();
            if (auditedIds.size() != declaredTotal) {
                throw new IllegalStateException(
                        "open-park audit total mismatch: declared=" + declaredTotal
                                + ", listed=" + auditedIds.size());
            }
            verifyIntegerCounts(root.getAsJsonObject("height_counts"), heightCounts,
                    "open-park height");

            EnumMap<District, Integer> declaredDistrictCounts = new EnumMap<>(District.class);
            for (Map.Entry<String, JsonElement> entry
                    : root.getAsJsonObject("district_counts").entrySet()) {
                declaredDistrictCounts.put(
                        District.valueOf(entry.getKey() + "_CORP"), entry.getValue().getAsInt());
            }
            if (declaredDistrictCounts.size() != District.values().length
                    || !declaredDistrictCounts.equals(districtCounts)) {
                throw new IllegalStateException(
                        "open-park district counts disagree with listed tiles");
            }
            return new OpenParkAudit(
                    Collections.unmodifiableSet(auditedIds),
                    Collections.unmodifiableMap(districtCounts),
                    Collections.unmodifiableMap(heightCounts));
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("cannot load audited open-park allowlist", error);
        }
    }

    private static void verifyIntegerCounts(
            JsonObject declared,
            Map<Integer, Integer> actual,
            String label) {
        LinkedHashMap<Integer, Integer> declaredCounts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
            declaredCounts.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsInt());
        }
        if (!declaredCounts.equals(actual)) {
            throw new IllegalStateException(label + " counts disagree with listed tiles");
        }
    }

    private static String resourceSha256(String resource) throws IOException {
        try (InputStream stream = ArnisPatchLibrary.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing audited resource " + resource);
            }
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
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
            Patch first = indexed.getFirst().patch();
            District district = first.district();
            Set<MegacityLayout.Zone> zones = first.placementZones();
            int width = indexed.stream().mapToInt(value -> value.tileX() + 1).max().orElseThrow();
            int depth = indexed.stream().mapToInt(value -> value.tileZ() + 1).max().orElseThrow();
            if (width != EXPECTED_ATLAS_AXIS || depth != EXPECTED_ATLAS_AXIS) {
                throw new IllegalStateException(
                        "Arnis district atlas must be exactly 16x16: " + entry.getKey());
            }
            ArrayList<Patch> tiles = new ArrayList<>(Collections.nCopies(width * depth, null));
            for (IndexedPatch value : indexed) {
                if (value.patch().district() != district
                        || !value.patch().placementZones().equals(zones)) {
                    throw new IllegalStateException(
                            "Arnis atlas crosses district/zone contracts: " + entry.getKey());
                }
                int index = value.tileZ() * width + value.tileX();
                if (tiles.set(index, value.patch()) != null) {
                    throw new IllegalStateException("duplicate Arnis atlas tile: " + entry.getKey());
                }
            }
            if (tiles.stream().anyMatch(value -> value == null)) {
                throw new IllegalStateException("incomplete Arnis atlas: " + entry.getKey());
            }
            Atlas atlas = new Atlas(
                    entry.getKey(), district, zones, width, depth, List.copyOf(tiles));
            byDistrict.computeIfAbsent(district, ignored -> new ArrayList<>()).add(atlas);
        }
        for (Map.Entry<District, List<Atlas>> entry : byDistrict.entrySet()) {
            entry.getValue().sort(Comparator.comparing(Atlas::id));
            entry.setValue(List.copyOf(entry.getValue()));
        }
        for (District district : District.values()) {
            List<Atlas> districtAtlases = byDistrict.getOrDefault(district, List.of());
            long nest = districtAtlases.stream()
                    .filter(atlas -> atlas.supports(MegacityLayout.Zone.NEST)).count();
            long backstreets = districtAtlases.stream()
                    .filter(atlas -> atlas.supports(MegacityLayout.Zone.BACKSTREETS)).count();
            if (districtAtlases.size() != 2 || nest != 1 || backstreets != 1) {
                throw new IllegalStateException(
                        district + " must own exactly one Nest and one Backstreets Arnis atlas");
            }
        }
        return Collections.unmodifiableMap(byDistrict);
    }
}
