package com.example.cyberdeck.client.gun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data mirror of a Bedrock geometry file ({@code format_version 1.12.0},
 * {@code minecraft:geometry}). Parsed by hand from Gson so we depend on nothing beyond the
 * geometry we actually use (bones, cubes, per-face and box UVs, pivots and rotations).
 *
 * <p>This is a faithful re-implementation of the data TaCZ's {@code BedrockModelPOJO} carries,
 * rebuilt against the vanilla JSON so it can be compiled and rendered on Minecraft 26.2 where the
 * original 1.20.1/Forge jar cannot load.</p>
 */
public final class BedrockGeoData {
    public int textureWidth = 64;
    public int textureHeight = 64;
    public final List<Bone> bones = new ArrayList<>();

    public static final class Bone {
        public String name;
        public String parent;
        public float[] pivot = {0, 0, 0};
        public float[] rotation; // nullable, degrees
        public boolean mirror;
        public final List<Cube> cubes = new ArrayList<>();
    }

    public static final class Cube {
        public float[] origin = {0, 0, 0};
        public float[] size = {0, 0, 0};
        public float[] pivot;     // nullable
        public float[] rotation;  // nullable, degrees
        public float inflate;
        public boolean mirror;
        // Either boxUv (uv is [u,v]) or perFace (uv is an object of Face).
        public float[] boxUv;     // nullable
        public FaceSet faceUv;    // nullable
    }

    /** North/South/East/West/Up/Down face UVs for a per-face cube. */
    public static final class FaceSet {
        public Face north, south, east, west, up, down;

        public Face get(String dir) {
            return switch (dir) {
                case "north" -> north;
                case "south" -> south;
                case "east" -> east;
                case "west" -> west;
                case "up" -> up;
                case "down" -> down;
                default -> null;
            };
        }
    }

    public static final class Face {
        public float[] uv = {0, 0};
        public float[] uvSize = {0, 0};
    }

    /** Parse a Bedrock geometry JSON document into a {@link BedrockGeoData}. */
    public static BedrockGeoData parse(JsonObject root) {
        BedrockGeoData data = new BedrockGeoData();
        JsonArray geometry = root.getAsJsonArray("minecraft:geometry");
        if (geometry == null || geometry.isEmpty()) {
            return data;
        }
        JsonObject geo = geometry.get(0).getAsJsonObject();
        JsonObject desc = geo.getAsJsonObject("description");
        if (desc != null) {
            if (desc.has("texture_width")) {
                data.textureWidth = desc.get("texture_width").getAsInt();
            }
            if (desc.has("texture_height")) {
                data.textureHeight = desc.get("texture_height").getAsInt();
            }
        }
        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null) {
            return data;
        }
        for (JsonElement be : bones) {
            data.bones.add(parseBone(be.getAsJsonObject()));
        }
        return data;
    }

    private static Bone parseBone(JsonObject o) {
        Bone bone = new Bone();
        bone.name = o.get("name").getAsString();
        if (o.has("parent")) {
            bone.parent = o.get("parent").getAsString();
        }
        if (o.has("pivot")) {
            bone.pivot = floats(o.getAsJsonArray("pivot"));
        }
        if (o.has("rotation")) {
            bone.rotation = floats(o.getAsJsonArray("rotation"));
        }
        if (o.has("mirror")) {
            bone.mirror = o.get("mirror").getAsBoolean();
        }
        JsonArray cubes = o.getAsJsonArray("cubes");
        if (cubes != null) {
            for (JsonElement ce : cubes) {
                bone.cubes.add(parseCube(ce.getAsJsonObject()));
            }
        }
        return bone;
    }

    private static Cube parseCube(JsonObject o) {
        Cube cube = new Cube();
        if (o.has("origin")) {
            cube.origin = floats(o.getAsJsonArray("origin"));
        }
        if (o.has("size")) {
            cube.size = floats(o.getAsJsonArray("size"));
        }
        if (o.has("pivot")) {
            cube.pivot = floats(o.getAsJsonArray("pivot"));
        }
        if (o.has("rotation")) {
            cube.rotation = floats(o.getAsJsonArray("rotation"));
        }
        if (o.has("inflate")) {
            cube.inflate = o.get("inflate").getAsFloat();
        }
        if (o.has("mirror")) {
            cube.mirror = o.get("mirror").getAsBoolean();
        }
        JsonElement uv = o.get("uv");
        if (uv != null) {
            if (uv.isJsonArray()) {
                cube.boxUv = floats(uv.getAsJsonArray());
            } else if (uv.isJsonObject()) {
                cube.faceUv = parseFaceSet(uv.getAsJsonObject());
            }
        }
        return cube;
    }

    private static FaceSet parseFaceSet(JsonObject o) {
        FaceSet set = new FaceSet();
        set.north = parseFace(o, "north");
        set.south = parseFace(o, "south");
        set.east = parseFace(o, "east");
        set.west = parseFace(o, "west");
        set.up = parseFace(o, "up");
        set.down = parseFace(o, "down");
        return set;
    }

    private static Face parseFace(JsonObject o, String dir) {
        if (!o.has(dir)) {
            return null;
        }
        JsonObject f = o.getAsJsonObject(dir);
        Face face = new Face();
        if (f.has("uv")) {
            face.uv = floats(f.getAsJsonArray("uv"));
        }
        if (f.has("uv_size")) {
            face.uvSize = floats(f.getAsJsonArray("uv_size"));
        }
        return face;
    }

    private static float[] floats(JsonArray a) {
        float[] out = new float[a.size()];
        for (int i = 0; i < a.size(); i++) {
            out[i] = a.get(i).getAsFloat();
        }
        return out;
    }
}
