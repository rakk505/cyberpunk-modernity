package com.example.cyberdeck.cyberware;

import java.util.EnumMap;
import java.util.Map;

/**
 * All available cyberware augmentations. Each cyberware belongs to exactly one {@link BodySlot}.
 * Cyberware within the same slot are mutually exclusive: installing one replaces the other.
 *
 * <p>The {@code id} is a stable string persisted in the player's {@code CyberwareData} attachment
 * and used to resolve the matching item / behavior. Never rename an id without a migration.
 */
public enum Cyberware {
    // Operating System (mutually exclusive)
    SANDEVISTAN("sandevistan", BodySlot.OPERATING_SYSTEM, "Sandevistan"),
    CYBERDECK_OS("cyberdeck_os", BodySlot.OPERATING_SYSTEM, "Cyberdeck"),

    // Arms (mutually exclusive)
    GORILLA_ARMS("gorilla_arms", BodySlot.ARMS, "Gorilla Arms"),
    MANTIS_BLADES("mantis_blades", BodySlot.ARMS, "Mantis Blades"),
    ARM_CANNON("arm_cannon", BodySlot.ARMS, "Arm Cannon"),

    // Hands
    SMART_LINK("smart_link", BodySlot.HANDS, "Smart Link"),

    // Legs (mutually exclusive)
    FROG_LEGS("frog_legs", BodySlot.LEGS, "Frog Legs"),
    HYENA_LEGS("hyena_legs", BodySlot.LEGS, "Hyena Legs"),

    // Nervous System
    THRETEVAC("thretevac", BodySlot.NERVOUS_SYSTEM, "Thretevac"),

    // Integumentary System (mutually exclusive)
    NANO_PLATING("nano_plating", BodySlot.INTEGUMENTARY_SYSTEM, "Nano Plating"),
    OPTICAL_CAMO("optical_camo", BodySlot.INTEGUMENTARY_SYSTEM, "Optical Camo");

    public static final Cyberware[] VALUES = values();

    private static final Map<String, Cyberware> BY_ID = new java.util.HashMap<>();
    private static final Map<BodySlot, java.util.List<Cyberware>> BY_SLOT = new EnumMap<>(BodySlot.class);

    static {
        for (BodySlot slot : BodySlot.VALUES) {
            BY_SLOT.put(slot, new java.util.ArrayList<>());
        }
        for (Cyberware cw : VALUES) {
            BY_ID.put(cw.id, cw);
            BY_SLOT.get(cw.slot).add(cw);
        }
    }

    private final String id;
    private final BodySlot slot;
    private final String displayName;

    Cyberware(String id, BodySlot slot, String displayName) {
        this.id = id;
        this.slot = slot;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public BodySlot slot() {
        return slot;
    }

    public String displayName() {
        return displayName;
    }

    /** Resolves a cyberware by its stable id, or {@code null} if unknown (e.g. from stale save data). */
    public static Cyberware byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /** All cyberware options available for the given body slot, in declaration order. */
    public static java.util.List<Cyberware> forSlot(BodySlot slot) {
        return BY_SLOT.getOrDefault(slot, java.util.List.of());
    }
}
