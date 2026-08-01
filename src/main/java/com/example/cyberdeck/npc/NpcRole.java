package com.example.cyberdeck.npc;

import net.minecraft.util.RandomSource;

/** Behavioral and economy profile for a city NPC. */
public enum NpcRole {
    RESIDENT("resident", 20.0, 0.0, 0.0, 5, 15),
    CORPO("corpo", 24.0, 2.0, 0.0, 20, 40),
    EXEC("exec", 100.0, 12.0, 6.0, 80, 150);

    private final String id;
    private final double maxHealth;
    private final double armor;
    private final double armorToughness;
    private final int minimumCredits;
    private final int maximumCredits;

    NpcRole(String id, double maxHealth, double armor, double armorToughness,
            int minimumCredits, int maximumCredits) {
        this.id = id;
        this.maxHealth = maxHealth;
        this.armor = armor;
        this.armorToughness = armorToughness;
        this.minimumCredits = minimumCredits;
        this.maximumCredits = maximumCredits;
    }

    public String id() {
        return id;
    }

    public double maxHealth() {
        return maxHealth;
    }

    public double armor() {
        return armor;
    }

    public double armorToughness() {
        return armorToughness;
    }

    public int minimumCredits() {
        return minimumCredits;
    }

    public int maximumCredits() {
        return maximumCredits;
    }

    public int rollCredits(RandomSource random) {
        return minimumCredits + random.nextInt(maximumCredits - minimumCredits + 1);
    }

    public static NpcRole byOrdinal(int ordinal) {
        NpcRole[] roles = values();
        return roles[Math.floorMod(ordinal, roles.length)];
    }
}
