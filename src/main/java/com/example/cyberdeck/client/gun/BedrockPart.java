package com.example.cyberdeck.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A bone in the Bedrock model tree. Holds its rest-pose transform (position/rotation from the
 * geometry) plus per-frame animation offsets that the {@link GunAnimator} writes each frame:
 * {@link #offsetX}/{@link #offsetY}/{@link #offsetZ} (animated position), {@link #animRotation}
 * (animated rotation as a quaternion) and {@link #scaleX}/{@link #scaleY}/{@link #scaleZ}.
 *
 * <p>Transform order mirrors TaCZ's {@code BedrockPart.translateAndRotateAndScale}: animated
 * offset, rest position, rest rotation (z,y,x), animated rotation, animated scale.</p>
 */
public final class BedrockPart {
    public final String name;
    public final List<BedrockCube> cubes = new ArrayList<>();
    public final List<BedrockPart> children = new ArrayList<>();
    public BedrockPart parent;

    // Rest pose (from geometry).
    public float x, y, z;
    public float xRot, yRot, zRot; // radians
    public boolean visible = true;

    // Animation channels (reset to identity each frame before the animator runs).
    public float offsetX, offsetY, offsetZ;
    public float scaleX = 1.0f, scaleY = 1.0f, scaleZ = 1.0f;
    public final Quaternionf animRotation = new Quaternionf(0, 0, 0, 1);

    public BedrockPart(String name) {
        this.name = name;
    }

    public void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setRotation(float x, float y, float z) {
        this.xRot = x;
        this.yRot = y;
        this.zRot = z;
    }

    public void addChild(BedrockPart child) {
        children.add(child);
        child.parent = this;
    }

    /**
     * Accumulated rest-pose position of this bone in the model's render space (bedrock units),
     * summed up the parent chain. Only valid when the parent chain applies no rotation, which is
     * the case for the {@code idle_view} sight node used for first-person positioning.
     */
    public float viewX() {
        float v = x;
        for (BedrockPart p = parent; p != null; p = p.parent) {
            v += p.x;
        }
        return v;
    }

    public float viewY() {
        float v = y;
        for (BedrockPart p = parent; p != null; p = p.parent) {
            v += p.y;
        }
        return v;
    }

    public float viewZ() {
        float v = z;
        for (BedrockPart p = parent; p != null; p = p.parent) {
            v += p.z;
        }
        return v;
    }

    /** Reset the per-frame animation channels back to the rest pose. */
    public void resetAnimation() {
        offsetX = offsetY = offsetZ = 0.0f;
        scaleX = scaleY = scaleZ = 1.0f;
        animRotation.set(0, 0, 0, 1);
        for (BedrockPart child : children) {
            child.resetAnimation();
        }
    }

    public void render(PoseStack poseStack, VertexConsumer consumer, int light, int overlay, int color) {
        if (!visible || (cubes.isEmpty() && children.isEmpty())) {
            return;
        }
        poseStack.pushPose();
        applyTransform(poseStack);
        PoseStack.Pose pose = poseStack.last();
        for (BedrockCube cube : cubes) {
            cube.compile(pose, consumer, light, overlay, color);
        }
        for (BedrockPart child : children) {
            child.render(poseStack, consumer, light, overlay, color);
        }
        poseStack.popPose();
    }

    /**
     * Visits this bone and its children with the same accumulated rest and animation transforms
     * used by {@link #render}. Empty marker bones are intentionally included: Cyber Armorer rigs
     * use {@code righthand_pos}/{@code lefthand_pos} leaves as animated player-arm anchors.
     */
    public void visitAnimated(PoseStack poseStack, BiConsumer<String, PoseStack> visitor) {
        if (!visible) {
            return;
        }
        poseStack.pushPose();
        applyTransform(poseStack);
        visitor.accept(name, poseStack);
        for (BedrockPart child : children) {
            child.visitAnimated(poseStack, visitor);
        }
        poseStack.popPose();
    }

    private void applyTransform(PoseStack poseStack) {
        // Animated position offset (bedrock units / 16), then rest position.
        poseStack.translate(offsetX / 16.0f, offsetY / 16.0f, offsetZ / 16.0f);
        poseStack.translate(x / 16.0f, y / 16.0f, z / 16.0f);
        if (zRot != 0.0f) {
            poseStack.mulPose(Axis.ZP.rotation(zRot));
        }
        if (yRot != 0.0f) {
            poseStack.mulPose(Axis.YP.rotation(yRot));
        }
        if (xRot != 0.0f) {
            poseStack.mulPose(Axis.XP.rotation(xRot));
        }
        poseStack.mulPose(animRotation);
        poseStack.scale(scaleX, scaleY, scaleZ);
    }
}
