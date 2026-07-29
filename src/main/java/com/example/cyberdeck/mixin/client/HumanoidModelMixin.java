package com.example.cyberdeck.mixin.client;

import com.example.cyberdeck.client.movement.TacticalPlayerAnimations;
import com.example.cyberdeck.client.movement.TacticalPoseAnimator;
import com.example.cyberdeck.client.movement.TacticalPoseData;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies tactical bone posing after vanilla resets and animates each player/armor model. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
    @Inject(
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("TAIL"))
    private void cyberdeck$applyTacticalPose(HumanoidRenderState state, CallbackInfo callback) {
        TacticalPoseData pose = state.getRenderData(TacticalPlayerAnimations.POSE_DATA);
        if (pose != null) {
            TacticalPoseAnimator.apply((HumanoidModel<?>) (Object) this, state, pose);
        }
    }
}
