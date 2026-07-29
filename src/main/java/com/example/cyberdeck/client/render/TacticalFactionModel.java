package com.example.cyberdeck.client.render;

import com.example.cyberdeck.client.movement.TacticalPoseAnimator;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;

/** Humanoid model that layers tactical locomotion and recoil over vanilla's base pose. */
public final class TacticalFactionModel extends HumanoidModel<FactionEnemyRenderState> {
    public TacticalFactionModel(ModelPart root) {
        super(root);
    }

    @Override
    public void setupAnim(FactionEnemyRenderState state) {
        super.setupAnim(state);
        TacticalPoseAnimator.apply(this, state, state.tacticalPose);
    }
}
