package com.example.cyberdeck.client.gun;

import com.mojang.math.Axis;

import org.joml.Quaternionf;

import java.util.List;

/**
 * Applies a Bedrock keyframe clip to a {@link BedrockModel} at a given playback time. A normal
 * application resets every bone first; {@link #applyLayered} seeds a base pose and then lets an
 * action clip override only the channels it actually authors. Interpolation is linear or
 * Catmull-Rom, matching the {@code lerp_mode} in the clip.
 *
 * <p>Bedrock rotation keyframes are in degrees and use Z * Y * X composition. Positions are in
 * model units. TaCZ's model listener maps animated translation as {@code (x, -y, z)} while keeping
 * animation rotation signs unchanged.</p>
 */
public final class GunAnimator {
    private GunAnimator() {}

    public static void apply(BedrockModel model, BedrockAnimationData.Clip clip, double time) {
        model.resetAnimation();
        applyClip(model, clip, time);
    }

    /** Reset once, sample the static base at time zero, then overlay an action clip. */
    public static void applyLayered(BedrockModel model, BedrockAnimationData.Clip base,
                                    BedrockAnimationData.Clip action, double actionTime) {
        model.resetAnimation();
        applyClip(model, base, 0.0);
        applyClip(model, action, actionTime);
    }

    private static void applyClip(BedrockModel model, BedrockAnimationData.Clip clip, double time) {
        if (clip == null) {
            return;
        }
        double t = clip.length > 0 && clip.loop ? time % clip.length : Math.min(time, clip.length);

        for (var entry : clip.bones.entrySet()) {
            BedrockPart bone = model.getBone(entry.getKey());
            if (bone == null) {
                continue;
            }
            BedrockAnimationData.BoneChannels ch = entry.getValue();

            if (ch.position != null) {
                float[] p = sample(ch.position, t);
                // Exact TaCZ Bedrock listener conversion: X/Z unchanged, Y inverted.
                bone.offsetX = p[0];
                bone.offsetY = -p[1];
                bone.offsetZ = p[2];
            }
            if (ch.scale != null) {
                float[] s = sample(ch.scale, t);
                bone.scaleX = s[0];
                bone.scaleY = s[1];
                bone.scaleZ = s[2];
            }
            if (ch.rotation != null) {
                float[] r = sample(ch.rotation, t);
                // Degrees -> quaternion using TaCZ's unchanged Z * Y * X animation signs.
                Quaternionf q = new Quaternionf();
                q.mul(Axis.ZP.rotationDegrees(r[2]));
                q.mul(Axis.YP.rotationDegrees(r[1]));
                q.mul(Axis.XP.rotationDegrees(r[0]));
                bone.animRotation.set(q);
            }
        }
    }

    private static float[] sample(List<BedrockAnimationData.Keyframe> frames, double time) {
        if (frames.isEmpty()) {
            return new float[] {0, 0, 0};
        }
        if (frames.size() == 1) {
            return frames.get(0).post.clone();
        }
        // A Bedrock keyframe's pre value applies immediately before its timestamp and its post
        // value immediately after. This is how authored visibility swaps avoid interpolation.
        if (time < frames.get(0).time) {
            return frames.get(0).pre.clone();
        }
        int last = frames.size() - 1;
        if (time >= frames.get(last).time) {
            return frames.get(last).post.clone();
        }
        int i = 0;
        while (i < last && frames.get(i + 1).time <= time) {
            i++;
        }
        if (i == last || time == frames.get(i).time) {
            return frames.get(i).post.clone();
        }
        BedrockAnimationData.Keyframe a = frames.get(i);
        BedrockAnimationData.Keyframe b = frames.get(i + 1);
        double span = b.time - a.time;
        double alpha = span <= 0 ? 0 : (time - a.time) / span;

        if (b.catmullRom && frames.size() >= 3) {
            BedrockAnimationData.Keyframe p0 = frames.get(Math.max(0, i - 1));
            BedrockAnimationData.Keyframe p3 = frames.get(Math.min(last, i + 2));
            return catmullRom(p0.post, a.post, b.pre, p3.pre, (float) alpha);
        }
        return lerp(a.post, b.pre, (float) alpha);
    }

    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[] {
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t,
        };
    }

    private static float[] catmullRom(float[] p0, float[] p1, float[] p2, float[] p3, float t) {
        float[] out = new float[3];
        float t2 = t * t;
        float t3 = t2 * t;
        for (int k = 0; k < 3; k++) {
            out[k] = 0.5f * ((2 * p1[k])
                    + (-p0[k] + p2[k]) * t
                    + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
        }
        return out;
    }
}
