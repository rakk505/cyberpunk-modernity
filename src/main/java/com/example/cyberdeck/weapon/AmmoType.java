package com.example.cyberdeck.weapon;

/**
 * The three ammunition families. Each {@link GunType} consumes exactly one of these, and each ammo
 * item ({@code handgun_ammo}, {@code shotgun_ammo}, {@code heavy_ammo}) maps to one value here.
 */
public enum AmmoType {
    HANDGUN("handgun_ammo"),
    SHOTGUN("shotgun_ammo"),
    HEAVY("heavy_ammo");

    private final String itemId;

    AmmoType(String itemId) {
        this.itemId = itemId;
    }

    /** Registry path of the matching ammo item. */
    public String itemId() {
        return itemId;
    }
}
