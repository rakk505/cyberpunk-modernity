package com.example.cyberdeck.client.movement;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;

/**
 * Original procedural full-body animation layered after vanilla's humanoid pose. The curves use
 * eased anticipation, travel and recovery phases so locomotion and gun handling remain readable at
 * Minecraft's low tick rate without snapping between poses.
 */
public final class TacticalPoseAnimator {
    private TacticalPoseAnimator() {
    }

    public static void apply(HumanoidModel<?> model, HumanoidRenderState state,
                             TacticalPoseData pose) {
        switch (pose.action()) {
            case DASH -> applyDash(model, pose);
            case SLIDE -> applySlide(model, pose);
            case NONE -> applyLocomotion(model, state, pose);
        }

        applyWeaponLayer(model, pose);
    }

    private static void applyDash(HumanoidModel<?> model, TacticalPoseData pose) {
        float progress = Mth.clamp(pose.actionProgress(), 0.0F, 1.0F);
        float travel = pulse(progress, 0.10F, 0.82F);
        float lateral = Mth.clamp(pose.lateralAmount(), -1.0F, 1.0F);
        float forward = Mth.clamp(pose.forwardAmount(), -1.0F, 1.0F);
        float stride = Mth.sin(progress * Mth.TWO_PI);

        model.body.xRot += radians(20.0F) * travel * Math.max(0.35F, forward);
        model.body.yRot += radians(8.0F) * lateral * travel;
        model.body.zRot -= radians(12.0F) * lateral * travel;
        model.head.xRot -= radians(10.0F) * travel;
        model.head.yRot -= radians(4.0F) * lateral * travel;
        model.head.zRot += radians(7.0F) * lateral * travel;

        model.rightLeg.xRot = Mth.lerp(travel, model.rightLeg.xRot,
                radians(-38.0F + stride * 12.0F));
        model.leftLeg.xRot = Mth.lerp(travel, model.leftLeg.xRot,
                radians(30.0F - stride * 12.0F));
        model.rightLeg.zRot += radians(8.0F) * lateral * travel;
        model.leftLeg.zRot += radians(8.0F) * lateral * travel;
        model.rightArm.zRot -= radians(5.0F) * lateral * travel;
        model.leftArm.zRot -= radians(5.0F) * lateral * travel;
    }

    private static void applySlide(HumanoidModel<?> model, TacticalPoseData pose) {
        float progress = Mth.clamp(pose.actionProgress(), 0.0F, 1.0F);
        float blend = pulse(progress, 0.16F, 0.84F);
        float side = Mth.clamp(pose.lateralAmount(), -1.0F, 1.0F);

        model.body.xRot = Mth.lerp(blend, model.body.xRot, radians(56.0F));
        model.body.yRot += radians(7.0F) * side * blend;
        model.body.zRot -= radians(5.0F) * side * blend;
        model.head.xRot = Mth.lerp(blend, model.head.xRot, radians(-32.0F));
        model.head.yRot -= radians(5.0F) * side * blend;

        // One leg drives forward while the other folds under the hips. Small positional offsets
        // sell the lowered center of mass without replacing the vanilla player renderer.
        model.rightLeg.xRot = Mth.lerp(blend, model.rightLeg.xRot, radians(-72.0F));
        model.rightLeg.yRot = Mth.lerp(blend, model.rightLeg.yRot, radians(12.0F));
        model.rightLeg.zRot = Mth.lerp(blend, model.rightLeg.zRot, radians(5.0F));
        model.leftLeg.xRot = Mth.lerp(blend, model.leftLeg.xRot, radians(54.0F));
        model.leftLeg.yRot = Mth.lerp(blend, model.leftLeg.yRot, radians(-18.0F));
        model.leftLeg.zRot = Mth.lerp(blend, model.leftLeg.zRot, radians(-8.0F));
        model.rightLeg.z += 2.5F * blend;
        model.leftLeg.z -= 1.0F * blend;

        if (!pose.holdingGun()) {
            model.rightArm.xRot = Mth.lerp(blend, model.rightArm.xRot, radians(-48.0F));
            model.leftArm.xRot = Mth.lerp(blend, model.leftArm.xRot, radians(-70.0F));
            model.rightArm.zRot += radians(14.0F) * blend;
            model.leftArm.zRot -= radians(18.0F) * blend;
        }
    }

    private static void applyLocomotion(HumanoidModel<?> model, HumanoidRenderState state,
                                        TacticalPoseData pose) {
        float speed = Mth.clamp(pose.movementSpeed() / 0.32F, 0.0F, 1.25F);
        float lateral = Mth.clamp(pose.lateralAmount(), -1.0F, 1.0F);
        if (pose.sprinting() && speed > 0.15F) {
            float stride = Mth.cos(state.walkAnimationPos * 0.82F);
            float sprintBlend = Mth.clamp(speed, 0.0F, 1.0F);
            model.body.xRot += radians(15.0F) * sprintBlend;
            model.body.yRot += stride * radians(2.2F) * sprintBlend;
            model.body.zRot -= radians(5.5F) * lateral * sprintBlend;
            model.head.xRot -= radians(7.0F) * sprintBlend;
            model.head.zRot += radians(3.0F) * lateral * sprintBlend;
            model.rightLeg.xRot = stride * 1.35F * sprintBlend;
            model.leftLeg.xRot = -stride * 1.35F * sprintBlend;
            if (!pose.holdingGun()) {
                model.rightArm.xRot = -stride * 1.15F * sprintBlend;
                model.leftArm.xRot = stride * 1.15F * sprintBlend;
            } else {
                // Keep a two-hand low-ready posture while the lower body runs independently.
                model.rightArm.xRot += radians(19.0F) * sprintBlend;
                model.leftArm.xRot += radians(22.0F) * sprintBlend;
                model.rightArm.zRot += stride * radians(2.0F) * sprintBlend;
                model.leftArm.zRot -= stride * radians(2.0F) * sprintBlend;
            }
        } else if (speed > 0.08F) {
            model.body.zRot -= radians(4.0F) * lateral * Mth.clamp(speed, 0.0F, 1.0F);
            model.head.zRot += radians(2.0F) * lateral * Mth.clamp(speed, 0.0F, 1.0F);
        }
    }

    private static void applyWeaponLayer(HumanoidModel<?> model, TacticalPoseData pose) {
        if (!pose.holdingGun()) {
            return;
        }

        float recoil = easeOutCubic(Mth.clamp(pose.recoil(), 0.0F, 1.0F));
        model.body.xRot -= radians(4.5F) * recoil;
        model.head.xRot += radians(2.0F) * recoil;
        model.rightArm.xRot -= radians(12.0F) * recoil;
        model.leftArm.xRot -= radians(8.0F) * recoil;
        model.rightArm.zRot += radians(2.5F) * recoil;

        float reloadArc = Mth.sin(Mth.clamp(pose.reloadProgress(), 0.0F, 1.0F) * Mth.PI);
        model.body.yRot += radians(7.0F) * reloadArc;
        model.body.zRot += radians(3.0F) * reloadArc;
        model.head.yRot -= radians(4.0F) * reloadArc;
        model.leftArm.zRot -= radians(9.0F) * reloadArc;
    }

    public static float pulse(float progress, float enterEnd, float exitStart) {
        float enter = smoothstep(0.0F, enterEnd, progress);
        float exit = 1.0F - smoothstep(exitStart, 1.0F, progress);
        return Mth.clamp(Math.min(enter, exit), 0.0F, 1.0F);
    }

    public static float smoothstep(float edge0, float edge1, float value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0F : 0.0F;
        }
        float t = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse;
    }

    private static float radians(float degrees) {
        return degrees * Mth.DEG_TO_RAD;
    }
}
