package com.example.cyberdeck.faction;

/**
 * Short, server-authoritative combat movement performed by a {@link FactionEnemy}. The stable
 * numeric id is synchronized instead of the enum ordinal so future additions do not silently
 * reinterpret an action already in flight on a client.
 */
public enum TacticalManeuver {
    NONE(0),
    DASH_LEFT(1),
    DASH_RIGHT(2),
    SLIDE_FORWARD(3);

    private final int id;

    TacticalManeuver(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public boolean isDash() {
        return this == DASH_LEFT || this == DASH_RIGHT;
    }

    public static TacticalManeuver byId(int id) {
        return switch (id) {
            case 1 -> DASH_LEFT;
            case 2 -> DASH_RIGHT;
            case 3 -> SLIDE_FORWARD;
            default -> NONE;
        };
    }
}
