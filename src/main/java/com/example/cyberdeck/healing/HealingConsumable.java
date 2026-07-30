package com.example.cyberdeck.healing;

import java.util.Optional;

/** Infinite quick-slot healing consumables with distinct recovery profiles. */
public enum HealingConsumable {
    BOUNCE_BACK("bounce_back", 2.0F, 1.0F, 10 * 20, 25 * 20),
    MAXDOC("maxdoc", 10.0F, 0.0F, 0, 30 * 20);

    public static final int REGENERATION_INTERVAL_TICKS = 20;
    public static final HealingConsumable[] VALUES = values();

    private final String id;
    private final float instantHealing;
    private final float regenerationPerPulse;
    private final int regenerationDurationTicks;
    private final int cooldownTicks;

    HealingConsumable(
            String id,
            float instantHealing,
            float regenerationPerPulse,
            int regenerationDurationTicks,
            int cooldownTicks) {
        this.id = id;
        this.instantHealing = instantHealing;
        this.regenerationPerPulse = regenerationPerPulse;
        this.regenerationDurationTicks = regenerationDurationTicks;
        this.cooldownTicks = cooldownTicks;
    }

    public String id() {
        return id;
    }

    public int networkId() {
        return ordinal();
    }

    public float instantHealing() {
        return instantHealing;
    }

    public float regenerationPerPulse() {
        return regenerationPerPulse;
    }

    public int regenerationDurationTicks() {
        return regenerationDurationTicks;
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public boolean regenerates() {
        return regenerationPerPulse > 0.0F && regenerationDurationTicks > 0;
    }

    public float totalHealing() {
        int pulses = regenerationDurationTicks / REGENERATION_INTERVAL_TICKS;
        return instantHealing + pulses * regenerationPerPulse;
    }

    public static Optional<HealingConsumable> fromNetworkId(int networkId) {
        return networkId >= 0 && networkId < VALUES.length
                ? Optional.of(VALUES[networkId])
                : Optional.empty();
    }
}
