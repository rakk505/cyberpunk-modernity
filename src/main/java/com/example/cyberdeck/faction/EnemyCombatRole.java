package com.example.cyberdeck.faction;

/** Specialized battlefield role for an otherwise shared faction-soldier entity. */
public enum EnemyCombatRole {
    STANDARD("standard"),
    ASSAULT("assault"),
    SAPPER("sapper"),
    NETRUNNER("netrunner");

    private final String id;

    EnemyCombatRole(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static EnemyCombatRole byId(String id) {
        for (EnemyCombatRole role : values()) {
            if (role.id.equals(id)) {
                return role;
            }
        }
        return STANDARD;
    }

    public static EnemyCombatRole byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : STANDARD;
    }
}
