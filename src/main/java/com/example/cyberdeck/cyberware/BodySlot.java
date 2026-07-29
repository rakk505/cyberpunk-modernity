package com.example.cyberdeck.cyberware;

/**
 * The cyberware body slots a player can install augmentations into.
 * Each slot holds at most one {@link Cyberware} at a time (mutually exclusive within a slot).
 * The order here defines the display order in the cyberware screen and the index used for storage.
 */
public enum BodySlot {
    OPERATING_SYSTEM("Operating System"),
    ARMS("Arms"),
    LEGS("Legs"),
    NERVOUS_SYSTEM("Nervous System"),
    INTEGUMENTARY_SYSTEM("Integumentary System"),

    // Appended to preserve every existing slot ordinal used by install/remove packets.
    HANDS("Hands");

    public static final BodySlot[] VALUES = values();

    private final String displayName;

    BodySlot(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
