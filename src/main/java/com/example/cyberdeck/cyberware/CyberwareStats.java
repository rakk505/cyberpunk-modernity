package com.example.cyberdeck.cyberware;

/**
 * Aggregated non-vanilla statistics supplied by an installed cyberware loadout.
 *
 * <p>Vanilla attributes remain the source of truth for armor, health, movement, and basic melee
 * damage. These values cover mechanics that Minecraft does not expose as attributes, so weapon,
 * stealth, healing, and quickhack systems all consume the same tier-aware numbers.</p>
 */
public record CyberwareStats(
        double meleeDamageBonus,
        double meleeAttackSpeedBonus,
        double carryCapacityBonus,
        double recoilReduction,
        double spreadReduction,
        double attackStaminaReduction,
        double rangedStaminaReduction,
        double criticalChance,
        double bladeCriticalChance,
        double quickhackCriticalChance,
        double smartCriticalDamageBonus,
        double smartLockDurationBonus,
        double quietMovement,
        double visibilityReduction,
        double healthItemEffectiveness,
        double explosionResistance,
        double techWeaponDamageBonus,
        double healthItemCooldownReduction,
        double grenadeCooldownReduction) {

    public static final CyberwareStats EMPTY = new CyberwareStats(
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    public static CyberwareStats from(CyberwareData data) {
        if (data == null) {
            return EMPTY;
        }
        double meleeDamage = 0.0;
        double meleeAttackSpeed = 0.0;
        double carryCapacity = 0.0;
        double recoil = 0.0;
        double spread = 0.0;
        double attackStamina = 0.0;
        double rangedStamina = 0.0;
        double criticalChance = 0.0;
        double bladeCriticalChance = 0.0;
        double quickhackCriticalChance = 0.0;
        double smartCriticalDamage = 0.0;
        double smartLockDuration = 0.0;
        double quietMovement = 0.0;
        double visibilityReduction = 0.0;
        double healthItemEffectiveness = 0.0;
        double explosionResistance = 0.0;
        double techWeaponDamage = 0.0;
        double healthItemCooldownReduction = 0.0;
        double grenadeCooldownReduction = 0.0;

        for (Cyberware cyberware : data.allInstalled()) {
            meleeDamage += fraction(cyberware, "melee_damage_percent");
            meleeAttackSpeed += fraction(cyberware, "attack_speed_percent");
            carryCapacity += fraction(cyberware, "carry_capacity_percent");
            recoil += fraction(cyberware, "recoil_reduction_percent");
            spread += fraction(cyberware, "spread_reduction_percent");
            attackStamina += fraction(cyberware, "stamina_reduction_percent");
            rangedStamina += fraction(cyberware, "ranged_stamina_reduction_percent");
            criticalChance += fraction(cyberware, "crit_chance_percent");
            bladeCriticalChance += fraction(cyberware, "blade_crit_chance_percent");
            quickhackCriticalChance += fraction(cyberware, "quickhack_crit_chance_percent");
            smartCriticalDamage += fraction(cyberware, "smart_crit_damage_percent");
            smartLockDuration += fraction(cyberware, "smart_lock_duration_percent");
            quietMovement += fraction(cyberware, "quiet_movement_percent");
            visibilityReduction += fraction(cyberware, "visibility_reduction_percent");
            healthItemEffectiveness += fraction(cyberware, "health_item_effectiveness_percent");
            explosionResistance += fraction(cyberware, "explosion_resistance_percent");
            techWeaponDamage += fraction(cyberware, "tech_weapon_damage_percent");
            healthItemCooldownReduction += fraction(
                    cyberware, "health_item_cooldown_reduction_percent");
            grenadeCooldownReduction += fraction(cyberware, "grenade_cooldown_reduction_percent");
        }

        return new CyberwareStats(
                meleeDamage,
                meleeAttackSpeed,
                carryCapacity,
                clamp(recoil, 0.0, 0.9),
                clamp(spread, 0.0, 0.9),
                clamp(attackStamina, 0.0, 1.0),
                clamp(rangedStamina, 0.0, 1.0),
                clamp(criticalChance, 0.0, 1.0),
                clamp(bladeCriticalChance, 0.0, 1.0),
                clamp(quickhackCriticalChance, 0.0, 1.0),
                smartCriticalDamage,
                smartLockDuration,
                clamp(quietMovement, 0.0, 0.95),
                clamp(visibilityReduction, 0.0, 0.95),
                healthItemEffectiveness,
                clamp(explosionResistance, 0.0, 0.9),
                techWeaponDamage,
                clamp(healthItemCooldownReduction, 0.0, 0.9),
                clamp(grenadeCooldownReduction, 0.0, 0.9));
    }

    public double rangedStaminaCostMultiplier() {
        return 1.0 - clamp(attackStaminaReduction + rangedStaminaReduction, 0.0, 1.0);
    }

    public double meleeStaminaCostMultiplier() {
        return 1.0 - clamp(attackStaminaReduction, 0.0, 1.0);
    }

    public double carryingCapacityMultiplier() {
        return 1.0 + carryCapacityBonus;
    }

    private static double fraction(Cyberware cyberware, String key) {
        return cyberware.value(key) / 100.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
