package com.example.cyberdeck.weapon;

/**
 * The two throwable grenade variants. Both detonate on impact, creating a lingering area effect:
 * incendiary sets fire to the ground and burns entities, poison leaves a toxic cloud that inflicts
 * poison. Values here drive {@link com.example.cyberdeck.weapon.ThrownGrenade}.
 */
public enum GrenadeType {
    INCENDIARY("incendiary_grenade", 4.0, 120),
    POISON("poison_grenade", 4.5, 140);

    private final String id;
    private final double radius;
    private final int effectDurationTicks;

    GrenadeType(String id, double radius, int effectDurationTicks) {
        this.id = id;
        this.radius = radius;
        this.effectDurationTicks = effectDurationTicks;
    }

    public String id() {
        return id;
    }

    public double radius() {
        return radius;
    }

    public int effectDurationTicks() {
        return effectDurationTicks;
    }
}
