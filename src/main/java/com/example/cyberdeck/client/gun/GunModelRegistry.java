package com.example.cyberdeck.client.gun;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.weapon.GunType;
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
    private static BedrockAnimationData crouchAnimation;
    private static boolean crouchAnimationLoaded;

    private GunModelRegistry() {}

    public record Entry(BedrockModel model, BedrockAnimationData animation, Identifier texture) {}

    /**
     * @return the compiled model for the gun, or {@code null} if no animated model exists.
     * Tech guns reuse their conventional counterpart's geometry and animation but load their own
     * cyan/gunmetal texture atlas.
     */
    public static Entry get(GunType gun) {
        return get(gun.id(), gun.baseGun().id());
    }

    /**
     * Loads any Bedrock rig by id, not only firearms. The mantis blade uses the same geometry,
     * animation and UV-atlas layout as the ported gun rigs, so it renders through this same path
     * even though it is a melee weapon rather than a {@link GunType}.
     *
     * @param itemId  selects the texture atlas, so tech variants can reskin a shared rig
     * @param modelId selects the geometry and animation clips
     */
    public static Entry get(String itemId, String modelId) {
        Entry cached = CACHE.get(itemId);
        if (cached != null) {
            return cached;
        }
        if (Boolean.TRUE.equals(MISSING.get(itemId))) {
            return null;
        }
        Entry loaded = load(itemId, modelId);
        if (loaded == null) {
            MISSING.put(itemId, Boolean.TRUE);
        } else {
            CACHE.put(itemId, loaded);
        }
        return loaded;
    }

    /** Clear caches (call on resource reload). */
    public static void clear() {
        CACHE.clear();
        MISSING.clear();
        crouchAnimation = null;
        crouchAnimationLoaded = false;
    }

    /** Shared Blockbench-authored stance layered over every animated first-person gun rig. */
    public static BedrockAnimationData crouchAnimation() {
        if (crouchAnimationLoaded) {
            return crouchAnimation;
        }
        crouchAnimationLoaded = true;
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Identifier id = Identifier.fromNamespaceAndPath(
                Cyberdeck.MODID, "gun_anim/player_crouch.animation.json");
        JsonObject json = readJson(resources, id);
        if (json == null) {
            crouchAnimation = new BedrockAnimationData();
            return crouchAnimation;
        }
        try {
            crouchAnimation = BedrockAnimationData.parse(json);
        } catch (RuntimeException exception) {
            Cyberdeck.LOGGER.error("Failed to parse shared crouch animation {}", id, exception);
            crouchAnimation = new BedrockAnimationData();
        }
        return crouchAnimation;
    }

    private static Entry load(String itemId, String modelId) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        Identifier geoId = Identifier.fromNamespaceAndPath(
                Cyberdeck.MODID, "gun_geo/" + modelId + ".geo.json");
        Identifier animId = Identifier.fromNamespaceAndPath(
                Cyberdeck.MODID, "gun_anim/" + modelId + ".animation.json");

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

        Identifier texture = Identifier.fromNamespaceAndPath(
                Cyberdeck.MODID, "textures/item/" + itemId + "_uv.png");
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
