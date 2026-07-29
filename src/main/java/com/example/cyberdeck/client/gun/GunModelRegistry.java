package com.example.cyberdeck.client.gun;

import com.example.cyberdeck.Cyberdeck;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and caches animated Bedrock gun models. For a gun id {@code overture} it reads:
 * <ul>
 *   <li>{@code assets/cyberdeck/gun_geo/overture.geo.json} — the Bedrock geometry</li>
 *   <li>{@code assets/cyberdeck/gun_anim/overture.animation.json} — the keyframe clips</li>
 *   <li>{@code assets/cyberdeck/textures/item/overture_uv.png} — the UV texture</li>
 * </ul>
 * Each is compiled once and cached. A fresh {@link BedrockModel} instance is handed out per gun id
 * because bone animation state lives on the model tree (single-threaded client render, so one
 * shared instance per id is fine).
 */
public final class GunModelRegistry {
    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Map<String, Boolean> MISSING = new HashMap<>();

    private GunModelRegistry() {}

    public record Entry(BedrockModel model, BedrockAnimationData animation, Identifier texture) {}

    /** @return the compiled model for the gun id, or {@code null} if no animated model exists. */
    public static Entry get(String gunId) {
        Entry cached = CACHE.get(gunId);
        if (cached != null) {
            return cached;
        }
        if (Boolean.TRUE.equals(MISSING.get(gunId))) {
            return null;
        }
        Entry loaded = load(gunId);
        if (loaded == null) {
            MISSING.put(gunId, Boolean.TRUE);
        } else {
            CACHE.put(gunId, loaded);
        }
        return loaded;
    }

    /** Clear caches (call on resource reload). */
    public static void clear() {
        CACHE.clear();
        MISSING.clear();
    }

    private static Entry load(String gunId) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        Identifier geoId = Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "gun_geo/" + gunId + ".geo.json");
        Identifier animId = Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "gun_anim/" + gunId + ".animation.json");

        JsonObject geoJson = readJson(rm, geoId);
        if (geoJson == null) {
            return null;
        }
        BedrockGeoData geoData = BedrockGeoData.parse(geoJson);
        BedrockModel model = new BedrockModel(geoData);

        BedrockAnimationData animation = new BedrockAnimationData();
        JsonObject animJson = readJson(rm, animId);
        if (animJson != null) {
            try {
                animation = BedrockAnimationData.parse(animJson);
            } catch (RuntimeException e) {
                // Geometry should remain usable even if a third-party clip contains an unsupported
                // channel form. Falling back to a rest pose is safer than crashing the render loop.
                Cyberdeck.LOGGER.error("Failed to parse gun animation resource {}", animId, e);
            }
        }

        Identifier texture = Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "textures/item/" + gunId + "_uv.png");
        return new Entry(model, animation, texture);
    }

    private static JsonObject readJson(ResourceManager rm, Identifier id) {
        Optional<Resource> resource = rm.getResource(id);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream in = resource.get().open();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            Cyberdeck.LOGGER.error("Failed to read gun model resource {}", id, e);
            return null;
        }
    }
}
