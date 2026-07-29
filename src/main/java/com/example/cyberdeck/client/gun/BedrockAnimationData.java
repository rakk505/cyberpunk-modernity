package com.example.cyberdeck.client.gun;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain data mirror of a Bedrock keyframe animation file ({@code format_version 1.8.0}). Parsed by
 * hand from Gson. Each clip maps bone names to per-channel keyframe timelines (position, rotation,
 * scale). Keyframe values may be a bare {@code [x,y,z]} array or an object with {@code "post"} and
 * {@code "lerp_mode"}.
 */
public final class BedrockAnimationData {
    public final Map<String, Clip> clips = new LinkedHashMap<>();

    public static final class Clip {
        public double length;
        public boolean loop;
        public final Map<String, BoneChannels> bones = new LinkedHashMap<>();
    }

    public static final class BoneChannels {
        public List<Keyframe> position;
        public List<Keyframe> rotation;
        public List<Keyframe> scale;
    }

    /** A single keyframe: a time in seconds and a target value (x,y,z). */
    public static final class Keyframe {
        public final double time;
        public final float[] pre;
        public final float[] post;
        public final boolean catmullRom;

        public Keyframe(double time, float[] pre, float[] post, boolean catmullRom) {
            this.time = time;
            this.pre = pre;
            this.post = post;
            this.catmullRom = catmullRom;
        }
    }

    public static BedrockAnimationData parse(JsonObject root) {
        BedrockAnimationData data = new BedrockAnimationData();
        JsonObject animations = root.getAsJsonObject("animations");
        if (animations == null) {
            return data;
        }
        for (Map.Entry<String, JsonElement> e : animations.entrySet()) {
            data.clips.put(stripPrefix(e.getKey()), parseClip(e.getValue().getAsJsonObject()));
        }
        return data;
    }

    private static String stripPrefix(String name) {
        // Clips may be keyed as "animation.<gun>.<clip>" or plain "<clip>".
        int last = name.lastIndexOf('.');
        return last >= 0 ? name.substring(last + 1) : name;
    }

    private static Clip parseClip(JsonObject o) {
        Clip clip = new Clip();
        if (o.has("animation_length")) {
            clip.length = o.get("animation_length").getAsDouble();
        }
        if (o.has("loop")) {
            JsonElement loop = o.get("loop");
            clip.loop = loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean() && loop.getAsBoolean();
        }
        JsonObject bones = o.getAsJsonObject("bones");
        if (bones != null) {
            for (Map.Entry<String, JsonElement> be : bones.entrySet()) {
                clip.bones.put(be.getKey(), parseBone(be.getValue().getAsJsonObject()));
            }
        }
        // Fall back: if animation_length wasn't declared, derive it from the latest keyframe.
        if (clip.length <= 0) {
            clip.length = deriveLength(clip);
        }
        return clip;
    }

    private static BoneChannels parseBone(JsonObject o) {
        BoneChannels ch = new BoneChannels();
        ch.position = parseChannel(o.get("position"));
        ch.rotation = parseChannel(o.get("rotation"));
        ch.scale = parseChannel(o.get("scale"));
        return ch;
    }

    /**
     * A channel is either a constant {@code [x,y,z]} (single keyframe at t=0) or a keyframe map
     * {@code {"time": value, ...}} where value is {@code [x,y,z]} or {@code {"post": [x,y,z]}}.
     */
    private static List<Keyframe> parseChannel(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        List<Keyframe> frames = new ArrayList<>();
        if (el.isJsonArray()) {
            float[] value = vec3(el.getAsJsonArray());
            frames.add(new Keyframe(0.0, value, value.clone(), false));
            return frames;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            float value = el.getAsFloat();
            float[] vector = new float[] {value, value, value};
            frames.add(new Keyframe(0.0, vector, vector.clone(), false));
            return frames;
        }
        JsonObject o = el.getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            double time = Double.parseDouble(e.getKey());
            JsonElement value = e.getValue();
            if (value.isJsonArray()) {
                float[] vector = vec3(value.getAsJsonArray());
                frames.add(new Keyframe(time, vector, vector.clone(), false));
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                float scalar = value.getAsFloat();
                float[] vector = new float[] {scalar, scalar, scalar};
                frames.add(new Keyframe(time, vector, vector.clone(), false));
            } else if (value.isJsonObject()) {
                JsonObject kf = value.getAsJsonObject();
                JsonElement preElement = kf.has("pre") ? kf.get("pre") : kf.get("post");
                JsonElement postElement = kf.has("post") ? kf.get("post") : kf.get("pre");
                boolean catmull = kf.has("lerp_mode") && "catmullrom".equals(kf.get("lerp_mode").getAsString());
                float[] pre = vector(preElement);
                float[] post = vector(postElement);
                if (pre != null && post != null) {
                    frames.add(new Keyframe(time, pre, post, catmull));
                }
            }
        }
        frames.sort((a, b) -> Double.compare(a.time, b.time));
        return frames;
    }

    private static float[] vec3(JsonArray a) {
        float x = a.size() > 0 ? a.get(0).getAsFloat() : 0f;
        float y = a.size() > 1 ? a.get(1).getAsFloat() : 0f;
        float z = a.size() > 2 ? a.get(2).getAsFloat() : 0f;
        return new float[] {x, y, z};
    }

    private static float[] vector(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            return vec3(element.getAsJsonArray());
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            float scalar = element.getAsFloat();
            return new float[] {scalar, scalar, scalar};
        }
        return null;
    }

    private static double deriveLength(Clip clip) {
        double max = 0.0;
        for (BoneChannels ch : clip.bones.values()) {
            max = Math.max(max, channelEnd(ch.position));
            max = Math.max(max, channelEnd(ch.rotation));
            max = Math.max(max, channelEnd(ch.scale));
        }
        return max;
    }

    private static double channelEnd(List<Keyframe> frames) {
        if (frames == null || frames.isEmpty()) {
            return 0.0;
        }
        return frames.get(frames.size() - 1).time;
    }
}
