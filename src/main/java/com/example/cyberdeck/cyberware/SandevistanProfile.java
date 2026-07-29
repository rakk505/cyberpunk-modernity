package com.example.cyberdeck.cyberware;

/** Immutable tier-specific balance data for a Sandevistan operating system. */
public record SandevistanProfile(
        Cyberware cyberware,
        double slowFraction,
        int durationTicks,
        int cooldownTicks,
        boolean partialActivation,
        double damageBonus,
        double airborneSlowFraction,
        double airborneDamageBonus,
        double headshotBonus,
        double airborneHeadshotBonus,
        double critChance,
        double critDamageBonus,
        double killDurationFraction,
        double killHealthFraction,
        double killHungerFraction,
        double mitigationChance,
        double mitigationStrength,
        double elementalResistance) {

    public static final SandevistanProfile APOGEE = create(Cyberware.MILITECH_APOGEE);
    public static final SandevistanProfile FALCON = create(Cyberware.MILITECH_FALCON);
    public static final SandevistanProfile DYNALAR = create(Cyberware.DYNALAR_SANDEVISTAN);
    public static final SandevistanProfile ZETATECH = create(Cyberware.ZETATECH_SANDEVISTAN);
    public static final SandevistanProfile WARP_DANCER = create(Cyberware.QIANT_WARP_DANCER);

    public static SandevistanProfile forCyberware(Cyberware cyberware) {
        if (cyberware == null || !cyberware.isSandevistan()) {
            return null;
        }
        return create(cyberware);
    }

    private static SandevistanProfile create(Cyberware cyberware) {
        String family = cyberware.familyId();
        boolean partial = family.equals("militech_apogee") || family.equals("militech_falcon");
        return new SandevistanProfile(
                cyberware,
                fraction(cyberware, "time_slow_percent"),
                ticks(cyberware, "duration_seconds"),
                ticks(cyberware, "cooldown_seconds"),
                partial,
                fraction(cyberware, "active_damage_percent"),
                fraction(cyberware, "airborne_time_slow_percent"),
                fraction(cyberware, "airborne_damage_percent"),
                fraction(cyberware, "headshot_damage_percent"),
                fraction(cyberware, "airborne_headshot_percent"),
                fraction(cyberware, "crit_chance_percent"),
                fraction(cyberware, "crit_damage_percent"),
                fraction(cyberware, "kill_duration_percent"),
                fraction(cyberware, "kill_health_percent"),
                fraction(cyberware, "kill_stamina_percent"),
                fraction(cyberware, "mitigation_chance_percent"),
                fraction(cyberware, "mitigation_strength_percent"),
                fraction(cyberware, "elemental_resistance_percent"));
    }

    private static double fraction(Cyberware cyberware, String key) {
        return cyberware.value(key) / 100.0;
    }

    private static int ticks(Cyberware cyberware, String key) {
        return Math.max(1, (int) Math.round(cyberware.value(key) * 20.0));
    }

    public boolean isFamily(String familyId) {
        return cyberware.familyId().equals(familyId);
    }

    public double slowFraction(boolean airborne) {
        return airborne && airborneSlowFraction > 0.0 ? airborneSlowFraction : slowFraction;
    }

    public double damageBonus(boolean airborne) {
        return airborne && airborneDamageBonus > 0.0 ? airborneDamageBonus : damageBonus;
    }

    public double headshotBonus(boolean airborne) {
        return airborne && airborneHeadshotBonus > 0.0 ? airborneHeadshotBonus : headshotBonus;
    }
}
