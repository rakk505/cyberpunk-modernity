package com.example.cyberdeck.movement;

import java.util.Optional;

/** Server-authoritative movement actions that can be requested by a player. */
public enum TacticalAction {
    NONE(0, 0, 0),
    DASH(1, 6, 14),
    SLIDE(2, 18, 10);

    private static final TacticalAction[] VALUES = values();

    private final int networkId;
    private final int durationTicks;
    private final int recoveryTicks;

    TacticalAction(int networkId, int durationTicks, int recoveryTicks) {
        this.networkId = networkId;
        this.durationTicks = durationTicks;
        this.recoveryTicks = recoveryTicks;
    }

    public int networkId() {
        return networkId;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public int recoveryTicks() {
        return recoveryTicks;
    }

    public boolean isMovementAction() {
        return this == DASH || this == SLIDE;
    }

    public static Optional<TacticalAction> fromNetworkId(int id) {
        for (TacticalAction action : VALUES) {
            if (action.networkId == id) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
