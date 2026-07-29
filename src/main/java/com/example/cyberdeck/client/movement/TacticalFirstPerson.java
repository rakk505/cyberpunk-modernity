package com.example.cyberdeck.client.movement;

import com.example.cyberdeck.movement.TacticalAction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/** Shared camera-relative gun transform for both animated Bedrock and static firearm renderers. */
public final class TacticalFirstPerson {
    private TacticalFirstPerson() {
    }

    public static void apply(PoseStack stack, Player player, float partialTick) {
        TacticalPoseData pose = TacticalPlayerAnimations.sample(player, partialTick);
        float age = player.tickCount + partialTick;

        if (pose.action() == TacticalAction.DASH) {
            float blend = TacticalPoseAnimator.pulse(pose.actionProgress(), 0.08F, 0.84F);
            stack.translate(-pose.lateralAmount() * 0.055F * blend,
                    -0.035F * blend, 0.16F * blend);
            stack.mulPose(Axis.ZP.rotationDegrees(-pose.lateralAmount() * 7.0F * blend));
            stack.mulPose(Axis.XP.rotationDegrees(-5.0F * blend));
        } else if (pose.action() == TacticalAction.SLIDE) {
            float blend = TacticalPoseAnimator.pulse(pose.actionProgress(), 0.14F, 0.86F);
            stack.translate(pose.lateralAmount() * 0.025F * blend,
                    0.12F * blend, 0.11F * blend);
            stack.mulPose(Axis.XP.rotationDegrees(8.0F * blend));
            stack.mulPose(Axis.ZP.rotationDegrees(-pose.lateralAmount() * 3.5F * blend));
        } else if (pose.sprinting() && pose.movementSpeed() > 0.12F) {
            float strength = Mth.clamp(pose.movementSpeed() / 0.32F, 0.0F, 1.0F);
            float gait = Mth.sin(age * 0.82F);
            stack.translate(gait * 0.014F * strength,
                    0.075F * strength + Math.abs(gait) * 0.008F,
                    0.09F * strength);
            stack.mulPose(Axis.ZP.rotationDegrees(gait * 1.6F * strength));
            stack.mulPose(Axis.XP.rotationDegrees(5.0F * strength));
        }
    }
}
