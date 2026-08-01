package com.example.cyberdeck.client.render;

import com.example.cyberdeck.client.movement.TacticalPoseData;
import com.example.cyberdeck.movement.TacticalAction;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** Render snapshot for a faction soldier, including synchronized tactical animation inputs. */
public final class FactionEnemyRenderState extends HumanoidRenderState {
    public boolean cyberpsycho;
    public boolean traumaTeam;
    public TacticalPoseData tacticalPose = new TacticalPoseData(
            TacticalAction.NONE,
            0.0F,
            0.0F,
            0.0F,
            false,
            false,
            0.0F,
            0.0F,
            0.0F);
}
