package com.example.cyberdeck.advertising;

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
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Offline-generated index of maximal large-ad rectangles in Arnis structure tiles. */
public final class GeneratedAdSurfaceCatalog {
    public static final int MIN_GENERATED_WIDTH = 8;
    public static final int MIN_GENERATED_HEIGHT = 4;

    private static final String RESOURCE =
            "/data/cyberdeck/advertising/large_ad_surfaces.json";
    private static final Catalog CATALOG = load();

    private GeneratedAdSurfaceCatalog() {
    }

    /** All non-overlapping maximal facade components audited for one Arnis tile. */
    public static List<Surface> surfaces(String catalogId) {
        return CATALOG.surfaces().getOrDefault(catalogId, List.of());
    }

    /** Compatibility helper for callers that need only the highest-ranked facade. */
    public static Optional<Surface> surface(String catalogId) {
        return surfaces(catalogId).stream().findFirst();
    }

    /** Number of source templates with at least one eligible surface. */
    public static int size() {
        return CATALOG.surfaces().size();
    }

    /** Total number of independently placeable exterior rectangles. */
    public static int surfaceCount() {
        return CATALOG.surfaceCount();
    }

    public static int boundarySurfaceCount() {
        return CATALOG.boundarySurfaceCount();
    }

    public static int multiSurfaceTemplateCount() {
        return CATALOG.multiSurfaceTemplateCount();
    }

    public static boolean validGeneratedDimensions(int width, int height) {
        return width >= MIN_GENERATED_WIDTH
                && height >= MIN_GENERATED_HEIGHT
                && LargeAdSurfaceValidator.validDimensions(width, height);
    }

    private static Catalog load() {
        InputStream stream = GeneratedAdSurfaceCatalog.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Missing generated ad surface catalog " + RESOURCE);
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"cyberdeck:large_ad_surfaces".equals(root.get("format").getAsString())
                    || root.get("version").getAsInt() != 3) {
                throw new IllegalStateException("Unsupported generated ad surface catalog");
            }
            Map<String, List<Surface>> surfaces = new LinkedHashMap<>();
            int surfaceCount = 0;
            int boundarySurfaceCount = 0;
            int multiSurfaceTemplateCount = 0;
            for (Map.Entry<String, JsonElement> entry
                    : root.getAsJsonObject("placements").entrySet()) {
                if (!entry.getValue().isJsonArray()
                        || entry.getValue().getAsJsonArray().isEmpty()) {
                    throw new IllegalStateException(
                            "Invalid generated ad surface " + entry.getKey());
                }
                List<Surface> templateSurfaces = new ArrayList<>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    JsonObject value = element.getAsJsonObject();
                    var support = value.getAsJsonArray("support");
                    Direction facing = Direction.byName(value.get("facing").getAsString());
                    int width = value.get("width").getAsInt();
                    int height = value.get("height").getAsInt();
                    String supportHash = value.get("support_hash").getAsString();
                    boolean boundaryFace = value.get("boundary_face").getAsBoolean();
                    if (support.size() != 3
                            || facing == null
                            || facing.getAxis().isVertical()
                            || !LargeAdSurfaceValidator.validDimensions(width, height)
                            || !supportHash.matches("[0-9a-f]{64}")) {
                        throw new IllegalStateException(
                                "Invalid generated ad surface " + entry.getKey());
                    }
                    templateSurfaces.add(new Surface(
                            new BlockPos(
                                    support.get(0).getAsInt(),
                                    support.get(1).getAsInt(),
                                    support.get(2).getAsInt()),
                            facing,
                            width,
                            height,
                            supportHash,
                            boundaryFace));
                    surfaceCount++;
                    if (boundaryFace) boundarySurfaceCount++;
                }
                if (templateSurfaces.size() > 1) multiSurfaceTemplateCount++;
                surfaces.put(entry.getKey(), List.copyOf(templateSurfaces));
            }
            if (surfaces.size() != root.get("template_placement_count").getAsInt()
                    || surfaceCount != root.get("surface_count").getAsInt()) {
                throw new IllegalStateException("Generated ad surface count is inconsistent");
            }
            return new Catalog(
                    Map.copyOf(surfaces),
                    surfaceCount,
                    boundarySurfaceCount,
                    multiSurfaceTemplateCount);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + RESOURCE, exception);
        }
    }

    private record Catalog(
            Map<String, List<Surface>> surfaces,
            int surfaceCount,
            int boundarySurfaceCount,
            int multiSurfaceTemplateCount) {
    }

    public record Surface(
            BlockPos support,
            Direction facing,
            int width,
            int height,
            String supportHash,
            boolean boundaryFace) {
        public Surface {
            support = support.immutable();
        }
    }
}
