package com.example.cyberdeck.faction;

import com.example.cyberdeck.weapon.GunType;

import java.util.List;

/**
 * A hostile corporation. Faction identity drives an enemy's armor tint, its weapon loadout, and how
 * it groups when spawning. Colors are packed 0xRRGGBB and applied to the leather-based ballistic
 * armor via the {@code DYED_COLOR} component.
 */
public enum Faction {
    /**
     * Arasaka: red-and-black, wields the corp's full cyberware arsenal or a sword. Sidearms are the
     * Overture/Unity/3516 pistols.
     */
    ARASAKA("arasaka", 0x8B0000, List.of(
            GunType.OVERTURE, GunType.UNITY, GunType.THREE_FIVE_ONE_SIX,
            GunType.SARATOGA, GunType.YUKIMURA,
            GunType.M2038, GunType.CARNAGE,
            GunType.AJAX, GunType.COPPERHEAD, GunType.GRAD),
            List.of(GunType.OVERTURE, GunType.UNITY, GunType.THREE_FIVE_ONE_SIX),
            true, false),

    /** Militech: dark green, carries the same cyber arsenal and throws grenades. */
    MILITECH("militech", 0x1B3A1B, List.of(
            GunType.UNITY, GunType.THREE_FIVE_ONE_SIX,
            GunType.G58_DIAN, GunType.SARATOGA, GunType.YUKIMURA,
            GunType.M2038, GunType.CARNAGE,
            GunType.AJAX, GunType.COPPERHEAD, GunType.GRAD),
            List.of(GunType.UNITY, GunType.G58_DIAN, GunType.THREE_FIVE_ONE_SIX),
            false, true),

    /**
     * Kang Tao: orange, fields the Ajax/Copperhead smart rifles only, and calls in reinforcements
     * when 3+ trigger together. Sidearms are the Overture/Unity pistols.
     */
    KANG_TAO("kang_tao", 0xE07A00,
            List.of(GunType.AJAX, GunType.COPPERHEAD),
            List.of(GunType.OVERTURE, GunType.UNITY),
            false, false);

    private final String id;
    private final int color;
    private final List<GunType> weapons;
    private final List<GunType> sidearms;
    private final boolean canUseSword;
    private final boolean usesGrenades;

    Faction(String id, int color, List<GunType> weapons, List<GunType> sidearms,
            boolean canUseSword, boolean usesGrenades) {
        this.id = id;
        this.color = color;
        this.weapons = weapons;
        this.sidearms = sidearms;
        this.canUseSword = canUseSword;
        this.usesGrenades = usesGrenades;
    }

    public String id() {
        return id;
    }

    /** Packed 0xRRGGBB tint for this faction's ballistic armor. */
    public int color() {
        return color;
    }

    public List<GunType> weapons() {
        return weapons;
    }

    /** Pistol-class guns issued as a secondary sidearm to the off-hand. */
    public List<GunType> sidearms() {
        return sidearms;
    }

    public boolean canUseSword() {
        return canUseSword;
    }

    public boolean usesGrenades() {
        return usesGrenades;
    }

    public static final Faction[] VALUES = values();
}
