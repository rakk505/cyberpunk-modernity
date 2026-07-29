package com.example.cyberdeck.client.movement;

import com.example.cyberdeck.movement.TacticalAction;

/** Immutable animation sample copied onto an avatar render state for deferred model rendering. */
public record TacticalPoseData(
        TacticalAction action,
        float actionProgress,
        float forwardAmount,
        float lateralAmount,
        boolean sprinting,
        boolean holdingGun,
        float reloadProgress,
        float recoil,
        float movementSpeed) {

    public boolean isActive() {
        return action != TacticalAction.NONE;
    }
}
