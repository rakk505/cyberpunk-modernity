package com.example.cyberdeck.cyberware;

/** The ten Cyberpunk 2077 cyberware systems and their physical socket counts. */
public enum BodySlot {
    FRONTAL_CORTEX("Frontal Cortex", 3, 3),
    OPERATING_SYSTEM("Operating System", 1, 1),
    ARMS("Arms", 1, 1),
    FACE("Face", 1, 2),
    SKELETON("Skeleton", 2, 3),
    HANDS("Hands", 1, 2),
    NERVOUS_SYSTEM("Nervous System", 3, 3),
    CIRCULATORY_SYSTEM("Circulatory System", 3, 3),
    INTEGUMENTARY_SYSTEM("Integumentary System", 3, 3),
    LEGS("Legs", 1, 1);

    public static final BodySlot[] VALUES = values();

    private final String displayName;
    private final int baseSockets;
    private final int maximumSockets;

    BodySlot(String displayName, int baseSockets, int maximumSockets) {
        this.displayName = displayName;
        this.baseSockets = baseSockets;
        this.maximumSockets = maximumSockets;
    }

    public String displayName() {
        return displayName;
    }

    public int baseSockets() {
        return baseSockets;
    }

    public int maximumSockets() {
        return maximumSockets;
    }

    public SlotUnlock unlockForSocket(int socket) {
        if (socket < baseSockets || socket >= maximumSockets) {
            return null;
        }
        return switch (this) {
            case FACE -> SlotUnlock.BIRDS_WITH_BROKEN_WINGS;
            case SKELETON -> SlotUnlock.LICENSE_TO_CHROME;
            case HANDS -> SlotUnlock.AMBIDEXTROUS;
            default -> null;
        };
    }
}
