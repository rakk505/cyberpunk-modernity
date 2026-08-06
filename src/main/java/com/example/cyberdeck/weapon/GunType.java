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

    // --- Cyber Armorer iconics (1.1.5). Each is scaled against the base weapon it shares a
    // frame with using the pack's own ratios, so a variant sits relative to its sibling
    // exactly as the pack intends without re-tuning the mod's hitscan balance. ---
    OVERTURE_AMNESTY("overture_amnesty", OVERTURE, AmmoType.HANDGUN, 10.0f, 1, 1.2f, 56.0, 4, 0, 12.0, 20.6, 6, 50),
    OVERTURE_ARCHANGEL("overture_archangel", OVERTURE, AmmoType.HANDGUN, 10.3f, 1, 1.2f, 57.6, 11, 0, 28.0, 48.0, 6, 46),
    OVERTURE_CRASH("overture_crash", OVERTURE, AmmoType.HANDGUN, 10.0f, 1, 1.2f, 57.6, 10, 0, 28.0, 48.0, 6, 54),
    OVERTURE_RELIABLE("overture_reliable", OVERTURE, AmmoType.HANDGUN, 10.0f, 1, 1.2f, 86.4, 8, 0, 42.0, 72.0, 6, 57),
    OVERTURE_ROSCO("overture_rosco", OVERTURE, AmmoType.HANDGUN, 10.6f, 1, 1.2f, 57.6, 8, 0, 28.0, 48.0, 6, 54),
    UNITY_CHEETAH("unity_cheetah", UNITY, AmmoType.HANDGUN, 7.6f, 1, 1.6f, 48.0, 3, 0, 22.0, 40.0, 11, 53),
    UNITY_HER_MAJESTY("unity_her_majesty", UNITY, AmmoType.HANDGUN, 5.1f, 1, 1.6f, 48.0, 4, 0, 22.0, 40.0, 9, 53),
    YUKIMURA_GENJIROH("yukimura_genjiroh", YUKIMURA, AmmoType.HANDGUN, 5.8f, 1, 2.2f, 44.0, 2, 0, 21.6, 36.3, 40, 62),
    YUKIMURA_SKIPPY("yukimura_skippy", YUKIMURA, AmmoType.HANDGUN, 5.7f, 1, 2.2f, 44.0, 2, 0, 18.0, 34.0, 30, 62),
    SARATOGA_FENRIR("saratoga_fenrir", SARATOGA, AmmoType.HANDGUN, 4.3f, 1, 3.0f, 40.0, 2, 0, 16.0, 30.0, 40, 50),
    SARATOGA_PROBLEM_SOLVER("saratoga_problem_solver", SARATOGA, AmmoType.HANDGUN, 3.9f, 1, 3.0f, 40.0, 2, 0, 16.0, 30.0, 90, 40),
    G58_DIAN_YINGLONG("g58_dian_yinglong", G58_DIAN, AmmoType.HANDGUN, 4.3f, 1, 3.2f, 40.0, 2, 0, 16.0, 30.0, 45, 59),
    AJAX_MORON_LABE("ajax_moron_labe", AJAX, AmmoType.HEAVY, 7.0f, 1, 2.2f, 69.6, 2, 0, 34.0, 58.0, 30, 58),
    AJAX_PIT_BULL("ajax_pit_bull", AJAX, AmmoType.HEAVY, 7.0f, 1, 2.2f, 69.6, 2, 0, 34.0, 58.0, 20, 58),
    COPPERHEAD_PSALM("copperhead_psalm", COPPERHEAD, AmmoType.HEAVY, 3.1f, 1, 2.6f, 62.4, 2, 0, 30.0, 52.0, 50, 50),
    M2038_BLOODY_MARIA("m2038_bloody_maria", M2038, AmmoType.SHOTGUN, 4.8f, 6, 7.0f, 26.0, 4, 0, 6.9, 15.4, 7, 20),
    M2038_THE_HEADSMAN("m2038_the_headsman", M2038, AmmoType.SHOTGUN, 19.2f, 1, 1.75f, 40.1, 4, 0, 14.9, 33.4, 5, 20),
    CARNAGE_GUTS("carnage_guts", CARNAGE, AmmoType.SHOTGUN, 3.6f, 20, 9.0f, 22.0, 13, 0, 5.0, 14.0, 5, 73),
    GRAD_05("grad_05", GRAD, AmmoType.HEAVY, 11.0f, 1, 0.15f, 128.0, 12, 30, 44.4, 56.9, 2, 110),
    GRAD_BORZAYA("grad_borzaya", GRAD, AmmoType.HEAVY, 15.1f, 1, 0.15f, 128.0, 10, 30, 66.7, 85.3, 4, 88),
    GRAD_OVERWATCH("grad_overwatch", GRAD, AmmoType.HEAVY, 19.2f, 1, 0.15f, 153.6, 8, 30, 100.0, 128.0, 3, 88),
    GRAD_SPARKY("grad_sparky", GRAD, AmmoType.HEAVY, 19.2f, 1, 0.15f, 141.7, 12, 30, 92.2, 118.0, 3, 110),

    // --- Tech variants ---
    // These clone the complete balance profile of their conventional counterpart except for a
    // 50% longer firing interval. Their shots can penetrate the first solid block they encounter.
    TECH_PISTOL("tech_pistol", PISTOL),
    TECH_SMG("tech_smg", SMG),
    TECH_SHOTGUN("tech_shotgun", SHOTGUN),
    TECH_ASSAULT_RIFLE("tech_assault_rifle", ASSAULT_RIFLE),
    TECH_SNIPER("tech_sniper", SNIPER),
    TECH_OVERTURE("tech_overture", OVERTURE),
    TECH_UNITY("tech_unity", UNITY),
    TECH_YUKIMURA("tech_yukimura", YUKIMURA),
    TECH_THREE_FIVE_ONE_SIX("tech_3516", THREE_FIVE_ONE_SIX),
    TECH_SARATOGA("tech_saratoga", SARATOGA),
    TECH_G58_DIAN("tech_g58_dian", G58_DIAN),
    TECH_AJAX("tech_ajax", AJAX),
    TECH_COPPERHEAD("tech_copperhead", COPPERHEAD),
    TECH_M2038("tech_m2038", M2038),
    TECH_CARNAGE("tech_carnage", CARNAGE),
    TECH_GRAD("tech_grad", GRAD),
    TECH_OVERTURE_AMNESTY("tech_overture_amnesty", OVERTURE_AMNESTY),
    TECH_OVERTURE_ARCHANGEL("tech_overture_archangel", OVERTURE_ARCHANGEL),
    TECH_OVERTURE_CRASH("tech_overture_crash", OVERTURE_CRASH),
    TECH_OVERTURE_RELIABLE("tech_overture_reliable", OVERTURE_RELIABLE),
    TECH_OVERTURE_ROSCO("tech_overture_rosco", OVERTURE_ROSCO),
    TECH_UNITY_CHEETAH("tech_unity_cheetah", UNITY_CHEETAH),
    TECH_UNITY_HER_MAJESTY("tech_unity_her_majesty", UNITY_HER_MAJESTY),
    TECH_YUKIMURA_GENJIROH("tech_yukimura_genjiroh", YUKIMURA_GENJIROH),
    TECH_YUKIMURA_SKIPPY("tech_yukimura_skippy", YUKIMURA_SKIPPY),
    TECH_SARATOGA_FENRIR("tech_saratoga_fenrir", SARATOGA_FENRIR),
    TECH_SARATOGA_PROBLEM_SOLVER("tech_saratoga_problem_solver", SARATOGA_PROBLEM_SOLVER),
    TECH_G58_DIAN_YINGLONG("tech_g58_dian_yinglong", G58_DIAN_YINGLONG),
    TECH_AJAX_MORON_LABE("tech_ajax_moron_labe", AJAX_MORON_LABE),
    TECH_AJAX_PIT_BULL("tech_ajax_pit_bull", AJAX_PIT_BULL),
    TECH_COPPERHEAD_PSALM("tech_copperhead_psalm", COPPERHEAD_PSALM),
    TECH_M2038_BLOODY_MARIA("tech_m2038_bloody_maria", M2038_BLOODY_MARIA),
    TECH_M2038_THE_HEADSMAN("tech_m2038_the_headsman", M2038_THE_HEADSMAN),
    TECH_CARNAGE_GUTS("tech_carnage_guts", CARNAGE_GUTS),
    TECH_GRAD_05("tech_grad_05", GRAD_05),
    TECH_GRAD_BORZAYA("tech_grad_borzaya", GRAD_BORZAYA),
    TECH_GRAD_OVERWATCH("tech_grad_overwatch", GRAD_OVERWATCH),
    TECH_GRAD_SPARKY("tech_grad_sparky", GRAD_SPARKY);

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
    private final GunType baseGun;
    /**
     * The weapon whose firing sound and alert radius this one shares. An iconic variant of the
     * Overture is still an Overture to anyone listening to it, so profiles are declared once per
     * frame instead of being restated for every variant.
     */
    private final GunType soundFamily;

    GunType(String id, AmmoType ammo, float damage, int pellets, float spreadDegrees, double range,
            int cooldownTicks, int reloadTicks, double falloffStart, double falloffEnd,
            int magazineSize, int reloadTimeTicks) {
        this(id, null, ammo, damage, pellets, spreadDegrees, range, cooldownTicks, reloadTicks,
                falloffStart, falloffEnd, magazineSize, reloadTimeTicks);
    }

    /** Creates a variant that keeps its own ballistics but sounds like the frame it is built on. */
    GunType(String id, GunType soundFamily, AmmoType ammo, float damage, int pellets,
            float spreadDegrees, double range, int cooldownTicks, int reloadTicks,
            double falloffStart, double falloffEnd, int magazineSize, int reloadTimeTicks) {
        this.soundFamily = soundFamily == null ? this : soundFamily;
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
        this.baseGun = null;
    }

    /** Creates a Tech firearm by cloning a conventional gun and slowing its firing interval. */
    GunType(String id, GunType baseGun) {
        this.id = id;
        this.ammo = baseGun.ammo;
        this.damage = baseGun.damage;
        this.pellets = baseGun.pellets;
        this.spreadDegrees = baseGun.spreadDegrees;
        this.range = baseGun.range;
        // Charged guns wait for cooldown and then perform their wind-up, so include both in the
        // cadence calculation to make every Tech counterpart's true shot interval 50% longer.
        this.cooldownTicks = Math.max(baseGun.cooldownTicks + 1,
                (int) Math.ceil((baseGun.cooldownTicks + baseGun.reloadTicks) * 1.5)
                        - baseGun.reloadTicks);
        this.reloadTicks = baseGun.reloadTicks;
        this.falloffStart = baseGun.falloffStart;
        this.falloffEnd = baseGun.falloffEnd;
        this.magazineSize = baseGun.magazineSize;
        this.reloadTimeTicks = baseGun.reloadTimeTicks;
        this.baseGun = baseGun;
        this.soundFamily = baseGun.soundFamily;
    }

    /** The frame this weapon sounds like; base weapons are their own family. */
    public GunType family() {
        return soundFamily;
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

    /** Whether this firearm uses Tech wall-penetration and cyan shot effects. */
    public boolean isTech() {
        return baseGun != null;
    }

    /** Conventional counterpart used for shared geometry, animations, sounds, and descriptions. */
    public GunType baseGun() {
        return baseGun == null ? this : baseGun;
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
