package com.example.cyberdeck.weapon;

/**
 * Static balance definition for each firearm. Guns are hitscan: firing traces a ray from the
 * shooter's eyes along their look vector and applies damage to the first entity hit, so accurate
 * aiming is the core skill. Values are tuned relative to each other rather than to vanilla weapons.
 *
 * @param id            registry / model / lang id
 * @param ammo          which ammunition family this gun consumes
 * @param damage        base damage per bullet (per pellet for shotguns)
 * @param pellets       bullets fired per shot (1 for everything except the shotgun)
 * @param spreadDegrees random cone half-angle applied to each pellet/shot (accuracy)
 * @param range         maximum hitscan distance in blocks
 * @param cooldownTicks minimum ticks between shots (rate of fire)
 * @param reloadTicks   wind-up applied before the shot actually fires (0 = instant)
 * @param falloffStart  distance at which damage begins to drop off
 * @param falloffEnd    distance at which damage reaches its minimum (25% of base)
 * @param magazineSize  rounds the magazine holds before a reload is required
 * @param reloadTimeTicks ticks the reload takes to refill the magazine
 */
public enum GunType {
    // ammo, dmg, pellets, spread, range, cooldown, reload, falloffStart, falloffEnd, mag, reloadTime
    PISTOL("pistol", AmmoType.HANDGUN, 6.0f, 1, 1.5f, 48.0, 8, 0, 24.0, 40.0, 10, 100),
    SMG("smg", AmmoType.HANDGUN, 3.5f, 1, 3.0f, 40.0, 3, 0, 16.0, 32.0, 25, 100),
    SHOTGUN("shotgun", AmmoType.SHOTGUN, 3.0f, 8, 8.0f, 24.0, 14, 0, 6.0, 16.0, 4, 100),
    ASSAULT_RIFLE("assault_rifle", AmmoType.HEAVY, 5.5f, 1, 2.5f, 64.0, 5, 0, 32.0, 56.0, 35, 100),
    SNIPER("sniper", AmmoType.HEAVY, 18.0f, 1, 0.2f, 128.0, 40, 30, 96.0, 128.0, 3, 100),

    // --- Cyber Armorer pack (ported from TaCZ ballistics: cooldown=1200/rpm, mag=ammo_amount,
    // reloadTime=empty_reload*20 ticks). Damage adapted from TaCZ base values for hitscan balance. ---
    OVERTURE("overture", AmmoType.HANDGUN, 10.0f, 1, 1.2f, 56.0, 8, 0, 28.0, 48.0, 6, 54),
    UNITY("unity", AmmoType.HANDGUN, 7.0f, 1, 1.6f, 48.0, 3, 0, 22.0, 40.0, 11, 53),
    YUKIMURA("yukimura", AmmoType.HANDGUN, 5.5f, 1, 2.2f, 44.0, 3, 0, 18.0, 34.0, 30, 62),
    THREE_FIVE_ONE_SIX("3516", AmmoType.HANDGUN, 13.0f, 1, 0.9f, 64.0, 6, 0, 32.0, 52.0, 10, 75),
    SARATOGA("saratoga", AmmoType.HANDGUN, 4.0f, 1, 3.0f, 40.0, 2, 0, 16.0, 30.0, 40, 50),
    G58_DIAN("g58_dian", AmmoType.HANDGUN, 4.0f, 1, 3.2f, 40.0, 2, 0, 16.0, 30.0, 45, 59),
    AJAX("ajax", AmmoType.HEAVY, 7.0f, 1, 2.2f, 68.0, 3, 0, 34.0, 58.0, 30, 58),
    COPPERHEAD("copperhead", AmmoType.HEAVY, 5.0f, 1, 2.6f, 60.0, 2, 0, 30.0, 52.0, 30, 60),
    M2038("m2038", AmmoType.SHOTGUN, 4.0f, 6, 7.0f, 26.0, 4, 0, 8.0, 18.0, 7, 20),
    CARNAGE("carnage", AmmoType.SHOTGUN, 3.0f, 20, 9.0f, 22.0, 10, 0, 5.0, 14.0, 3, 73),
    GRAD("grad", AmmoType.HEAVY, 22.0f, 1, 0.15f, 128.0, 13, 30, 100.0, 128.0, 3, 110),
    MANTIS_BLADE("mantis_blade", AmmoType.HEAVY, 6.0f, 7, 5.0f, 14.0, 12, 0, 4.0, 10.0, 1, 8);

    private final String id;
    private final AmmoType ammo;
    private final float damage;
    private final int pellets;
    private final float spreadDegrees;
    private final double range;
    private final int cooldownTicks;
    private final int reloadTicks;
    private final double falloffStart;
    private final double falloffEnd;
    private final int magazineSize;
    private final int reloadTimeTicks;

    GunType(String id, AmmoType ammo, float damage, int pellets, float spreadDegrees, double range,
            int cooldownTicks, int reloadTicks, double falloffStart, double falloffEnd,
            int magazineSize, int reloadTimeTicks) {
        this.id = id;
        this.ammo = ammo;
        this.damage = damage;
        this.pellets = pellets;
        this.spreadDegrees = spreadDegrees;
        this.range = range;
        this.cooldownTicks = cooldownTicks;
        this.reloadTicks = reloadTicks;
        this.falloffStart = falloffStart;
        this.falloffEnd = falloffEnd;
        this.magazineSize = magazineSize;
        this.reloadTimeTicks = reloadTimeTicks;
    }

    public int magazineSize() {
        return magazineSize;
    }

    public int reloadTimeTicks() {
        return reloadTimeTicks;
    }

    public String id() {
        return id;
    }

    public AmmoType ammo() {
        return ammo;
    }

    public float damage() {
        return damage;
    }

    public int pellets() {
        return pellets;
    }

    public float spreadDegrees() {
        return spreadDegrees;
    }

    public double range() {
        return range;
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public int reloadTicks() {
        return reloadTicks;
    }

    /**
     * Damage after distance falloff. Full damage up to {@link #falloffStart}, linearly interpolated
     * down to 25% at {@link #falloffEnd}. The shotgun's short falloff window is what makes it a
     * close-range weapon.
     */
    public float damageAtDistance(double distance) {
        if (distance <= falloffStart) {
            return damage;
        }
        if (distance >= falloffEnd) {
            return damage * 0.25f;
        }
        double t = (distance - falloffStart) / (falloffEnd - falloffStart);
        return (float) (damage * (1.0 - 0.75 * t));
    }
}
