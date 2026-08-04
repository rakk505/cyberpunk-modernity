package com.example.cyberdeck.faction;

/** Visual/company identity layered over the legacy combat faction contract. */
public enum EnemyArchetype {
    CORPORATE("corporate"),
    R_CORP("r_corp");

    private final String id;

    EnemyArchetype(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static EnemyArchetype byId(String id) {
        for (EnemyArchetype archetype : values()) {
            if (archetype.id.equals(id)) {
                return archetype;
            }
        }
        return CORPORATE;
    }

    public static EnemyArchetype byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : CORPORATE;
    }
}
