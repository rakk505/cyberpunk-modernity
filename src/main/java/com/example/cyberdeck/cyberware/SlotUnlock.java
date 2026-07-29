package com.example.cyberdeck.cyberware;

/** Progress gates for the three optional body sockets. */
public enum SlotUnlock {
    BIRDS_WITH_BROKEN_WINGS(
            BodySlot.FACE,
            "Birds with Broken Wings",
            "cyberdeck.quest.birds_with_broken_wings"),
    LICENSE_TO_CHROME(
            BodySlot.SKELETON,
            "License To Chrome",
            "cyberdeck.perk.license_to_chrome"),
    AMBIDEXTROUS(
            BodySlot.HANDS,
            "Ambidextrous",
            "cyberdeck.perk.ambidextrous");

    public static final SlotUnlock[] VALUES = values();

    private final BodySlot slot;
    private final String displayName;
    private final String progressTag;

    SlotUnlock(BodySlot slot, String displayName, String progressTag) {
        this.slot = slot;
        this.displayName = displayName;
        this.progressTag = progressTag;
    }

    public BodySlot slot() {
        return slot;
    }

    public String displayName() {
        return displayName;
    }

    /** Persistent player-data boolean that quest/perk integrations can grant. */
    public String progressTag() {
        return progressTag;
    }
}
