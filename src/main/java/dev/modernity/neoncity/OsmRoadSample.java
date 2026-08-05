package dev.modernity.neoncity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Catalog of compiled OSM road ribbons for every 16x16 Arnis atlas. */
final class OsmRoadSample {
    static final int ATLAS_CHUNKS = 16;
    static final int ATLAS_BLOCKS = ATLAS_CHUNKS * 16;
    private static final double CENTERLINE_SAMPLE_SPACING = 4.0;
    private static final String RESOURCE_ROOT = "/data/neoncity/osm_roads/";
    private static final Catalog CATALOG = loadCatalog();

    private OsmRoadSample() {}

    record Point(double x, double z) {}

    record Segment(Point first, Point second) {}

    record CenterlinePoint(
            double x,
            double z,
            double tangentX,
            double tangentZ,
            double width,
            RoadKind kind
    ) {}

    record Road(long id, String kind, boolean oneWay, int lanes, double width,
                List<Segment> segments) {
        Road {
            segments = List.copyOf(segments);
        }
    }

    enum RoadKind {
        NONE,
        LOCAL,
        SERVICE,
        SECONDARY,
        PRIMARY,
        MOTORWAY
    }

    record Sample(
            String id,
            String name,
            District district,
            MegacityLayout.Zone zone,
            List<Road> roads,
            byte[] roadRaster,
            Map<Integer, List<CenterlinePoint>> arterialCenterlines
    ) {
        Sample {
            roads = List.copyOf(roads);
            roadRaster = roadRaster.clone();
            LinkedHashMap<Integer, List<CenterlinePoint>> centerlines = new LinkedHashMap<>();
            arterialCenterlines.forEach(
                    (tile, points) -> centerlines.put(tile, List.copyOf(points)));
            arterialCenterlines = Map.copyOf(centerlines);
        }

        int segmentCount() {
            return roads.stream().mapToInt(road -> road.segments().size()).sum();
        }

        RoadKind roadAt(int sourceX, int sourceZ) {
            if (sourceX < 0 || sourceX >= ATLAS_BLOCKS
                    || sourceZ < 0 || sourceZ >= ATLAS_BLOCKS) {
                return RoadKind.NONE;
            }
            return RoadKind.values()[Byte.toUnsignedInt(
                    roadRaster[sourceZ * ATLAS_BLOCKS + sourceX])];
        }

        List<CenterlinePoint> arterialCenterlines(int tileX, int tileZ) {
            if (tileX < 0 || tileX >= ATLAS_CHUNKS
                    || tileZ < 0 || tileZ >= ATLAS_CHUNKS) {
                return List.of();
            }
            return arterialCenterlines.getOrDefault(
                    tileZ * ATLAS_CHUNKS + tileX, List.of());
        }
    }

    private record Catalog(
            List<Sample> samples,
            Map<String, Sample> names,
            Map<AtlasKey, Sample> atlases,
            List<String> suggestions
    ) {}

    private record AtlasKey(District district, MegacityLayout.Zone zone) {}

    static List<Sample> samples() {
        return CATALOG.samples();
    }

    static List<String> suggestions() {
        return CATALOG.suggestions();
    }

    static Optional<Sample> find(String value) {
        return Optional.ofNullable(CATALOG.names().get(normalize(value)));
    }

    static Optional<Sample> forAtlas(District district, MegacityLayout.Zone zone) {
        return Optional.ofNullable(CATALOG.atlases().get(new AtlasKey(district, zone)));
    }

    static Sample defaultSample() {
        return find("singapore").orElseThrow();
    }

    private static Catalog loadCatalog() {
        JsonObject index = readObject(RESOURCE_ROOT + "index.json");
        if (!"neoncity:osm_road_sample_index".equals(index.get("format").getAsString())
                || index.get("version").getAsInt() != 1) {
            throw new IllegalStateException("invalid OSM road sample index");
        }
        ArrayList<Sample> samples = new ArrayList<>();
        LinkedHashMap<String, Sample> names = new LinkedHashMap<>();
        LinkedHashMap<AtlasKey, Sample> atlases = new LinkedHashMap<>();
        for (JsonElement element : index.getAsJsonArray("samples")) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            String name = entry.get("name").getAsString();
            District district = District.fromCode(entry.get("district").getAsString())
                    .orElseThrow(() -> new IllegalStateException(
                            "unknown OSM sample district for " + id));
            MegacityLayout.Zone zone = MegacityLayout.Zone.valueOf(
                    entry.get("zone").getAsString().toUpperCase(Locale.ROOT));
            Sample sample = loadSample(
                    id, name, district, zone, entry.get("resource").getAsString());
            samples.add(sample);
            if (names.put(normalize(id), sample) != null) {
                throw new IllegalStateException("duplicate OSM sample " + id);
            }
            if (atlases.put(new AtlasKey(district, zone), sample) != null) {
                throw new IllegalStateException(
                        "duplicate OSM atlas sample " + district + "/" + zone);
            }
        }
        JsonObject aliases = index.getAsJsonObject("aliases");
        for (Map.Entry<String, JsonElement> entry : aliases.entrySet()) {
            Sample target = names.get(normalize(entry.getValue().getAsString()));
            if (target == null) {
                throw new IllegalStateException("unknown OSM sample alias target " + entry.getKey());
            }
            names.put(normalize(entry.getKey()), target);
        }
        if (samples.isEmpty()) throw new IllegalStateException("OSM road sample index is empty");
        return new Catalog(
                List.copyOf(samples), Map.copyOf(names), Map.copyOf(atlases),
                List.copyOf(names.keySet()));
    }

    private static Sample loadSample(
            String id,
            String name,
            District district,
            MegacityLayout.Zone zone,
            String resource
    ) {
        JsonObject root = readObject(RESOURCE_ROOT + resource);
        if (!"neoncity:osm_road_sample".equals(root.get("format").getAsString())
                || root.get("version").getAsInt() != 2
                || !id.equals(root.get("sample").getAsString())
                || root.get("atlas_chunks").getAsInt() != ATLAS_CHUNKS
                || !district.commandCode().equalsIgnoreCase(root.get("district").getAsString())
                || !zone.name().equalsIgnoreCase(root.get("zone").getAsString())) {
            throw new IllegalStateException("invalid OSM road sample header for " + id);
        }
        ArrayList<Road> roads = new ArrayList<>();
        for (JsonElement roadElement : root.getAsJsonArray("roads")) {
            JsonObject road = roadElement.getAsJsonObject();
            ArrayList<Segment> segments = new ArrayList<>();
            for (JsonElement segmentElement : road.getAsJsonArray("segments")) {
                var pair = segmentElement.getAsJsonArray();
                var first = pair.get(0).getAsJsonArray();
                var second = pair.get(1).getAsJsonArray();
                Point start = new Point(first.get(0).getAsDouble(), first.get(1).getAsDouble());
                Point end = new Point(second.get(0).getAsDouble(), second.get(1).getAsDouble());
                if (!inside(start) || !inside(end)) {
                    throw new IllegalStateException("OSM segment escaped sample atlas " + id);
                }
                segments.add(new Segment(start, end));
            }
            roads.add(new Road(
                    road.get("id").getAsLong(),
                    road.get("kind").getAsString(),
                    road.get("oneway").getAsBoolean(),
                    road.get("lanes").getAsInt(),
                    road.get("width").getAsDouble(),
                    segments));
        }
        if (roads.isEmpty()) throw new IllegalStateException("OSM road sample is empty: " + id);
        List<Road> immutableRoads = List.copyOf(roads);
        return new Sample(
                id,
                name,
                district,
                zone,
                immutableRoads,
                rasterize(immutableRoads),
                indexArterialCenterlines(immutableRoads));
    }

    private static Map<Integer, List<CenterlinePoint>> indexArterialCenterlines(
            List<Road> roads) {
        LinkedHashMap<Integer, List<CenterlinePoint>> indexed = new LinkedHashMap<>();
        for (Road road : roads) {
            RoadKind kind = classify(road.kind());
            if (kind != RoadKind.PRIMARY && kind != RoadKind.SECONDARY) continue;
            for (Segment segment : road.segments()) {
                double dx = segment.second().x() - segment.first().x();
                double dz = segment.second().z() - segment.first().z();
                double length = Math.hypot(dx, dz);
                if (length < 0.01) continue;
                int steps = Math.max(1, (int) Math.ceil(length / CENTERLINE_SAMPLE_SPACING));
                double tangentX = dx / length;
                double tangentZ = dz / length;
                for (int step = 0; step <= steps; step++) {
                    double progress = step / (double) steps;
                    double x = segment.first().x() + dx * progress;
                    double z = segment.first().z() + dz * progress;
                    int tileX = Math.clamp((int) Math.floor(x / 16.0), 0, ATLAS_CHUNKS - 1);
                    int tileZ = Math.clamp((int) Math.floor(z / 16.0), 0, ATLAS_CHUNKS - 1);
                    indexed.computeIfAbsent(
                                    tileZ * ATLAS_CHUNKS + tileX,
                                    ignored -> new ArrayList<>())
                            .add(new CenterlinePoint(
                                    x, z, tangentX, tangentZ, road.width(), kind));
                }
            }
        }
        return indexed;
    }

    private static byte[] rasterize(List<Road> roads) {
        byte[] raster = new byte[ATLAS_BLOCKS * ATLAS_BLOCKS];
        for (Road road : roads) {
            RoadKind kind = classify(road.kind());
            for (Segment segment : road.segments()) {
                double dx = segment.second().x() - segment.first().x();
                double dz = segment.second().z() - segment.first().z();
                double length = Math.hypot(dx, dz);
                if (length < 0.01) continue;
                double normalX = -dz / length;
                double normalZ = dx / length;
                int longitudinalSteps = Math.max(1, (int) Math.ceil(length / 0.45));
                int lateralSteps = Math.max(1, (int) Math.ceil(road.width() / 0.45));
                for (int along = 0; along <= longitudinalSteps; along++) {
                    double progress = along / (double) longitudinalSteps;
                    double centerX = segment.first().x() + dx * progress;
                    double centerZ = segment.first().z() + dz * progress;
                    for (int lateral = 0; lateral <= lateralSteps; lateral++) {
                        double offset = -road.width() * 0.5
                                + road.width() * lateral / lateralSteps;
                        int x = (int) Math.floor(centerX + normalX * offset);
                        int z = (int) Math.floor(centerZ + normalZ * offset);
                        if (x < 0 || x >= ATLAS_BLOCKS || z < 0 || z >= ATLAS_BLOCKS) continue;
                        int index = z * ATLAS_BLOCKS + x;
                        raster[index] = (byte) Math.max(
                                Byte.toUnsignedInt(raster[index]), kind.ordinal());
                    }
                }
            }
        }
        return raster;
    }

    private static RoadKind classify(String highway) {
        return switch (highway) {
            case "motorway", "motorway_link", "trunk", "trunk_link" -> RoadKind.MOTORWAY;
            case "primary", "primary_link" -> RoadKind.PRIMARY;
            case "secondary", "secondary_link", "tertiary", "tertiary_link" ->
                    RoadKind.SECONDARY;
            case "service" -> RoadKind.SERVICE;
            default -> RoadKind.LOCAL;
        };
    }

    private static JsonObject readObject(String resource) {
        try (InputStream stream = OsmRoadSample.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("missing " + resource);
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("cannot load " + resource, exception);
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static boolean inside(Point point) {
        return point.x() >= 0.0 && point.x() < ATLAS_BLOCKS
                && point.z() >= 0.0 && point.z() < ATLAS_BLOCKS;
    }
}
