package com.example.cyberdeck.cyberware;

/** All quality grades used by Cyberpunk 2077 cyberware upgrades. */
public enum CyberwareTier {
    T1("T1", "Tier 1", 0),
    T1_PLUS("T1+", "Tier 1+", 1),
    T2("T2", "Tier 2", 2),
    T2_PLUS("T2+", "Tier 2+", 3),
    T3("T3", "Tier 3", 4),
    T3_PLUS("T3+", "Tier 3+", 5),
    T4("T4", "Tier 4", 6),
    T4_PLUS("T4+", "Tier 4+", 7),
    T5("T5", "Tier 5", 8),
    T5_PLUS("T5+", "Tier 5+", 9),
    T5_PLUS_PLUS("T5++", "Tier 5++", 10);

    private final String id;
    private final String displayName;
    private final int rank;

    CyberwareTier(String id, String displayName, int rank) {
        this.id = id;
        this.displayName = displayName;
        this.rank = rank;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public int rank() {
        return rank;
    }

    public static CyberwareTier byId(String id) {
        for (CyberwareTier tier : values()) {
            if (tier.id.equals(id)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown cyberware tier: " + id);
    }
}
