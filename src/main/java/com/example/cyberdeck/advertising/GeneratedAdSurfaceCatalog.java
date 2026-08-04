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
    private static final Map<String, Surface> SURFACES = load();

    private GeneratedAdSurfaceCatalog() {
    }

    public static Optional<Surface> surface(String catalogId) {
        return Optional.ofNullable(SURFACES.get(catalogId));
    }

    public static int size() {
        return SURFACES.size();
    }

    private static Map<String, Surface> load() {
        InputStream stream = GeneratedAdSurfaceCatalog.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Missing generated ad surface catalog " + RESOURCE);
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!"cyberdeck:large_ad_surfaces".equals(root.get("format").getAsString())
                    || root.get("version").getAsInt() != 2) {
                throw new IllegalStateException("Unsupported generated ad surface catalog");
            }
            Map<String, Surface> surfaces = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry
                    : root.getAsJsonObject("placements").entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                var support = value.getAsJsonArray("support");
                Direction facing = Direction.byName(value.get("facing").getAsString());
                int width = value.get("width").getAsInt();
                int height = value.get("height").getAsInt();
                List<String> supportBlocks = new ArrayList<>();
                value.getAsJsonArray("support_blocks").forEach(
                        element -> supportBlocks.add(element.getAsString()));
                if (facing == null || facing.getAxis().isVertical()
                        || !LargeAdSurfaceValidator.validDimensions(width, height)
                        || !validGeneratedDimensions(width, height)
                        || supportBlocks.size() != width * height
                        || supportBlocks.stream().anyMatch(block ->
                                block.isBlank() || !block.contains(":"))) {
                    throw new IllegalStateException(
                            "Invalid generated ad surface " + entry.getKey());
                }
                surfaces.put(entry.getKey(), new Surface(
                        new BlockPos(
                                support.get(0).getAsInt(),
                                support.get(1).getAsInt(),
                                support.get(2).getAsInt()),
                        facing,
                        width,
                        height,
                        supportBlocks));
            }
            if (surfaces.size() != root.get("placement_count").getAsInt()) {
                throw new IllegalStateException("Generated ad surface count is inconsistent");
            }
            return Map.copyOf(surfaces);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + RESOURCE, exception);
        }
    }

    public static boolean validGeneratedDimensions(int width, int height) {
        return width >= MIN_GENERATED_WIDTH && height >= MIN_GENERATED_HEIGHT;
    }

    public record Surface(
            BlockPos support,
            Direction facing,
            int width,
            int height,
            List<String> supportBlocks) {
        public Surface {
            support = support.immutable();
            supportBlocks = List.copyOf(supportBlocks);
        }
    }
}
