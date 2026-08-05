package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.CyberwareUnlocks;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.skill.Skill;
import com.example.cyberdeck.skill.DeviceQuickhack;
import com.example.cyberdeck.weapon.GunItem;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Shared queries and per-tick behavior for the data-driven cyberware catalog. */
public final class CyberwareEffects {
    private static final Map<UUID, Boolean> IN_COMBAT = new HashMap<>();
    private static final Map<UUID, Double> RAM_REGEN_BUFFER = new HashMap<>();
    private static final Map<UUID, Skill> LAST_QUICKHACK = new HashMap<>();
    /** Melee swing-rate boost (as a fraction of base) applied while a sandevistan is active. */
    private static final double SANDEVISTAN_ATTACK_SPEED_BONUS = 1.5;

    private CyberwareEffects() {
    }

    public static CyberwareData data(ServerPlayer player) {
        return CyberwareAttachments.get(player);
    }

    public static Cyberware findFlag(ServerPlayer player, String flag) {
        return data(player).findFlag(flag);
    }

    public static boolean hasFlag(ServerPlayer player, String flag) {
        return findFlag(player, flag) != null;
    }

    public static double sumValue(ServerPlayer player, String key) {
        return sumValue(data(player), key);
    }

    public static double sumValue(CyberwareData data, String key) {
        double result = 0.0;
        if (data == null) {
            return result;
        }
        for (Cyberware cyberware : data.allInstalled()) {
            result += cyberware.value(key);
        }
        return result;
    }

    public static int maxRam(ServerPlayer player) {
        return Math.max(1, RamAttachments.BASE_MAX_RAM
                + (int) Math.round(sumValue(player, "max_ram")));
    }

    public static int quickhackRamCost(ServerPlayer player, Skill skill) {
        return quickhackRamCost(data(player), skill);
    }

    public static int quickhackRamCost(CyberwareData data, Skill skill) {
        double extraReduction = skill == Skill.CYBERPSYCHOSIS
                && data != null && data.findFlag("blackwall_gateway") != null ? 0.50 : 0.0;
        return quickhackRamCost(data, skill.ramCost(), extraReduction);
    }

    public static int quickhackRamCost(ServerPlayer player, DeviceQuickhack quickhack) {
        return quickhackRamCost(data(player), quickhack);
    }

    public static int quickhackRamCost(CyberwareData data, DeviceQuickhack quickhack) {
        return quickhackRamCost(data, quickhack.ramCost(), 0.0);
    }

    private static int quickhackRamCost(
            CyberwareData data, int baseCost, double extraReduction) {
        double reduction = sumValue(data, "quickhack_ram_cost_reduction_percent") / 100.0
                + extraReduction;
        return Math.max(0, (int) Math.ceil(baseCost * (1.0 - Math.min(0.75, reduction))));
    }

    public static int quickhackUploadTicks(ServerPlayer player, Skill skill) {
        return quickhackUploadTicks(data(player), skill);
    }

    public static int quickhackUploadTicks(CyberwareData data, Skill skill) {
        int baseTicks = skill.uploadTicks();
        if (baseTicks <= 0) {
            return 0;
        }
        double speed = Math.max(0.0, sumValue(data, "quickhack_upload_speed_percent")) / 100.0;
        return Math.max(1, (int) Math.round(baseTicks / (1.0 + speed)));
    }

    public static int quickhackUploadTicks(ServerPlayer player, DeviceQuickhack quickhack) {
        return quickhackUploadTicks(data(player), quickhack);
    }

    public static int quickhackUploadTicks(CyberwareData data, DeviceQuickhack quickhack) {
        int baseTicks = quickhack.uploadTicks();
        if (baseTicks <= 0) {
            return 0;
        }
        double speed = Math.max(0.0, sumValue(data, "quickhack_upload_speed_percent")) / 100.0;
        return Math.max(1, (int) Math.round(baseTicks / (1.0 + speed)));
    }

    public static double quickhackDamageMultiplier(ServerPlayer player, Skill skill) {
        double bonus = sumValue(player, "quickhack_damage_percent") / 100.0;
        if (skill == Skill.OVERHEAT || skill == Skill.CONTAGION) {
            bonus += sumValue(player, "quickhack_dot_damage_percent") / 100.0;
        }
        Skill previous = LAST_QUICKHACK.put(player.getUUID(), skill);
        Cyberware combo = data(player).findFlag("quickhack_combo");
        if (combo != null && isCombatQuickhack(skill) && previous != null
                && !isCombatQuickhack(previous)) {
            bonus += combo.value("quickhack_combo_damage_percent") / 100.0;
        }
        double multiplier = 1.0 + bonus;
        double criticalChance = com.example.cyberdeck.cyberware.CyberwareStats
                .from(data(player)).quickhackCriticalChance();
        if (criticalChance > 0.0 && isDamagingQuickhack(skill)
                && player.getRandom().nextDouble() < criticalChance) {
            multiplier *= 1.5;
        }
        return multiplier;
    }

    public static double quickhackDurationMultiplier(ServerPlayer player) {
        return 1.0 + Math.max(0.0,
                sumValue(player, "quickhack_duration_percent") / 100.0);
    }

    public static double quickhackSpreadRadius(ServerPlayer player, double baseRadius) {
        double bonus = sumValue(player, "quickhack_spread_distance_percent") / 100.0;
        return baseRadius * (1.0 + Math.max(0.0, bonus));
    }

    public static double quickhackSpreadChance(ServerPlayer player) {
        return hasFlag(player, "quickhack_spread") ? 1.0 : 0.5;
    }

    public static boolean canQuickhack(ServerPlayer player) {
        return canQuickhack(data(player));
    }

    /** Capability query shared by server authorization and the owner-synced client UI. */
    public static boolean canQuickhack(CyberwareData data) {
        return data != null && data.findFlag("cyberdeck") != null;
    }

    public static boolean canScan(ServerPlayer player) {
        return canScan(data(player));
    }

    /**
     * Highest optical-zoom factor granted by the player's installed Face optics
     * (e.g. 4/6/8/10), or {@code 1.0} when no zoom-capable optics are equipped.
     * Client-safe: reads only from the synced {@link CyberwareData}.
     */
    public static double opticalZoom(CyberwareData data) {
        if (data == null) {
            return 1.0;
        }
        double zoom = 1.0;
        for (Cyberware cyberware : data.sockets(BodySlot.FACE)) {
            if (cyberware != null) {
                zoom = Math.max(zoom, cyberware.value("optical_zoom"));
            }
        }
        return zoom;
    }

    /**
     * Every Face-slot family except the identity faceplate is an ocular implant. Scanner-highlight
     * flags describe optional through-wall bonuses, not the baseline scanner capability.
     */
    public static boolean canScan(CyberwareData data) {
        if (data == null) {
            return false;
        }
        for (Cyberware cyberware : data.sockets(BodySlot.FACE)) {
            if (cyberware != null
                    && !"behavioral_imprint_synced_faceplate".equals(cyberware.familyId())) {
                return true;
            }
        }
        return false;
    }

    public static double scannerRange(ServerPlayer player) {
        CyberwareData data = data(player);
        if (canQuickhack(data)) {
            return com.example.cyberdeck.skill.QuickhackUploads.MAX_TARGET_RANGE;
        }
        double range = 32.0;
        for (Cyberware cyberware : data.sockets(BodySlot.FACE)) {
            if (cyberware == null) {
                continue;
            }
            range = Math.max(range, cyberware.value("scanner_enemy_range"));
            range = Math.max(range, cyberware.value("scanner_device_range"));
            range = Math.max(range, cyberware.value("scanner_explosive_range"));
            range = Math.max(range, cyberware.value("scanner_wall_range"));
        }
        return range;
    }

    public static boolean shouldScannerHighlight(
            ServerPlayer player, LivingEntity target, double distance) {
        CyberwareData data = data(player);
        if (canQuickhack(data)) {
            return distance <= com.example.cyberdeck.skill.QuickhackUploads.MAX_TARGET_RANGE;
        }
        for (Cyberware optics : data.sockets(BodySlot.FACE)) {
            if (optics == null
                    || "behavioral_imprint_synced_faceplate".equals(optics.familyId())) {
                continue;
            }
            boolean ordinarySight = distance <= Math.max(32.0,
                    optics.value("scanner_enemy_range")) && player.hasLineOfSight(target);
            if (ordinarySight) {
                return true;
            }
            if ((optics.familyId().equals("clairvoyant")
                    || optics.familyId().equals("the_oracle"))
                    && target instanceof Enemy
                    && distance <= optics.value("scanner_enemy_range")) {
                return true;
            }
            if ((optics.familyId().equals("sentry")
                    || optics.familyId().equals("the_oracle"))
                    && target instanceof KangTaoTurret
                    && distance <= optics.value("scanner_device_range")) {
                return true;
            }
            if ((optics.familyId().equals("doomsayer")
                    || optics.familyId().equals("the_oracle"))
                    && target instanceof Creeper
                    && distance <= optics.value("scanner_explosive_range")) {
                return true;
            }
            if (optics.hasFlag("tech_targeting")
                    && player.getMainHandItem().getItem() instanceof GunItem gun
                    && gun.gun().isTech()
                    && target instanceof Enemy
                    && distance <= optics.value("scanner_wall_range")) {
                return true;
            }
        }
        return false;
    }

    public static int cooldownTicks(ServerPlayer player, Cyberware cyberware, String key) {
        double seconds = cyberware.value(key);
        double reduction = Math.min(0.9, sumValue(player, "cooldown_reduction_percent") / 100.0);
        return Math.max(1, (int) Math.round(seconds * 20.0 * (1.0 - reduction)));
    }

    public static void tickPlayer(ServerPlayer player) {
        CyberwareUnlocks.syncProgress(player);
        double movement = LegSpeed.tick(player);

        CyberwareData data = data(player);
        Cyberware lynx = data.findFamily("lynx_paws");
        if (lynx != null && player.isShiftKeyDown()) {
            movement += lynx.value("crouch_speed_percent") / 100.0;
        }
        Cyberware scarab = data.findFamily("scarab");
        if (scarab != null && player.isShiftKeyDown()) {
            movement -= scarab.value("crouch_speed_penalty_percent") / 100.0;
        }

        Cyberware threatEvac = data.findFlag("low_health_speed");
        double healthFraction = player.getHealth() / Math.max(1.0, player.getMaxHealth());
        if (threatEvac != null && healthFraction <= 0.25) {
            double base = threatEvac.value("low_health_speed_percent") / 100.0;
            double maximum = threatEvac.value("critical_health_speed_percent") / 100.0;
            movement += base + (maximum - base) * (1.0 - healthFraction / 0.25);
        }

        boolean inCombat = isInCombat(player);
        boolean wasInCombat = IN_COMBAT.getOrDefault(player.getUUID(), false);
        IN_COMBAT.put(player.getUUID(), inCombat);
        Cyberware combatSpeed = data.findFlag("combat_entry_speed");
        if (inCombat && !wasInCombat && combatSpeed != null) {
            ActiveAbilities.activate(player, "combat_speed",
                    Math.max(1, (int) Math.round(combatSpeed.value("combat_speed_seconds") * 20)));
        }
        if (combatSpeed != null && ActiveAbilities.isActive(player, "combat_speed")) {
            movement += combatSpeed.value("combat_speed_percent") / 100.0;
        }
        movement += ReactiveCyberware.detectionMovementBonus(player, inCombat);
        movement += ReactiveCyberware.takedownMovementBonus(player);

        Cyberware berserk = data.findFlag("berserk");
        double attackSpeed = 0.0;
        if (berserk != null && ActiveAbilities.isActive(player, "berserk")) {
            movement += berserk.value("active_movement_speed_percent") / 100.0;
            attackSpeed += berserk.value("active_attack_speed_percent") / 100.0;
        }
        // Attacks land faster while the sandevistan is running so melee keeps pace with the
        // world-slow, selling the reflex boost.
        if (SandevistanMechanics.isActive(player)) {
            attackSpeed += SANDEVISTAN_ATTACK_SPEED_BONUS;
        }
        CyberwarePassives.setDynamicMovement(player, movement);
        CyberwarePassives.setDynamicAttackSpeed(player, attackSpeed);
        CyberwarePassives.setDynamicArmor(player, dynamicArmor(player));
        ReactiveCyberware.tick(player);

        if (player.tickCount % 20 == 0) {
            tickRam(player);
            tickRegeneration(player);
            tickBiomonitor(player);
        }
    }

    private static double dynamicArmor(ServerPlayer player) {
        CyberwareData data = data(player);
        double armor = 0.0;
        Cyberware scarab = data.findFamily("scarab");
        if (scarab != null && player.isShiftKeyDown()) {
            armor += scarab.value("crouch_armor") / 10.0;
        }
        Cyberware rangeGuard = data.findFlag("range_armor");
        if (rangeGuard != null && !hasEnemyWithin(player, 6.0)) {
            armor += rangeGuard.value("range_armor") / 10.0;
        }
        Cyberware lowHealth = data.findFlag("low_health_armor");
        if (lowHealth != null && player.getHealth() <= player.getMaxHealth() * 0.5f) {
            armor += lowHealth.value("armor_points")
                    * lowHealth.value("conditional_armor_percent") / 100.0;
        }
        Cyberware lowRam = data.findFlag("low_ram_armor");
        if (lowRam != null && RamAttachments.get(player) <= 2) {
            armor += lowRam.value("armor_points") * lowRam.value("low_ram_armor_percent") / 100.0;
        }
        Cyberware healthBoost = data.findFlag("health_item_boost");
        if (healthBoost != null && ActiveAbilities.isActive(player, "health_item_boost")) {
            armor += sumValue(player, "armor_points")
                    * healthBoost.value("health_item_armor_percent") / 100.0;
        }
        return armor;
    }

    private static void tickRam(ServerPlayer player) {
        int maximum = RamAttachments.max(player);
        int current = RamAttachments.get(player);
        double regeneration = 1.0 + sumValue(player, "ram_regen_per_second");
        Cyberware feenX = data(player).findFlag("low_ram_regen");
        if (feenX != null && current < feenX.value("low_ram_threshold")) {
            regeneration *= 1.0 + feenX.value("low_ram_regen_percent") / 100.0;
        }
        double buffered = RAM_REGEN_BUFFER.getOrDefault(player.getUUID(), 0.0) + regeneration;
        int whole = (int) Math.floor(buffered);
        RAM_REGEN_BUFFER.put(player.getUUID(), buffered - whole);
        if (whole > 0 && current < maximum) {
            RamAttachments.set(player, Math.min(maximum, current + whole));
        }

        Cyberware restore = data(player).findFlag("low_ram_restore");
        if (restore != null && maximum > 0
                && RamAttachments.get(player) <= maximum * restore.value("low_ram_threshold_percent") / 100.0
                && !ActiveAbilities.onCooldown(player, restore.id())) {
            int amount = Math.max(1, (int) Math.round(maximum
                    * restore.value("low_ram_restore_percent") / 100.0));
            RamAttachments.set(player, RamAttachments.get(player) + amount);
            ActiveAbilities.setCooldown(player, restore.id(),
                    Math.max(1, (int) Math.round(restore.value("trigger_cooldown_seconds") * 20)));
        }
    }

    private static void tickRegeneration(ServerPlayer player) {
        if (hasFlag(player, "health_regen") && player.getHealth() < player.getMaxHealth()) {
            player.heal(0.5f);
        }
        Cyberware bloodPump = data(player).findFlag("blood_pump");
        if (bloodPump != null && ActiveAbilities.isActive(player, "blood_pump_regen")) {
            player.heal((float) bloodPump.value("blood_pump_regen_per_second"));
        }
    }

    private static void tickBiomonitor(ServerPlayer player) {
        Cyberware biomonitor = data(player).findFlag("biomonitor");
        if (biomonitor == null || player.getHealth() >= player.getMaxHealth() * 0.5f
                || ActiveAbilities.onCooldown(player, biomonitor.id())) {
            return;
        }
        double effectiveness = com.example.cyberdeck.cyberware.CyberwareStats
                .from(data(player)).healthItemEffectiveness();
        player.heal(player.getMaxHealth() * 0.25f * (float) (1.0 + effectiveness));
        ActiveAbilities.setCooldown(player, biomonitor.id(), 30 * 20);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.6f, 1.3f);
    }

    public static void toggleBerserk(ServerPlayer player, Cyberware berserk) {
        if (ActiveAbilities.isActive(player, "berserk")) {
            ActiveAbilities.deactivate(player, "berserk");
            return;
        }
        if (ActiveAbilities.onCooldown(player, "berserk")) {
            player.sendSystemMessage(Component.translatable("message.cyberdeck.berserk_recharging"), true);
            return;
        }
        ActiveAbilities.activate(player, "berserk",
                Math.max(1, (int) Math.round(berserk.value("duration_seconds") * 20)));
        ActiveAbilities.setCooldown(player, "berserk",
                cooldownTicks(player, berserk, "cooldown_seconds"));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 0.8f, 0.8f);
        player.sendSystemMessage(Component.translatable("message.cyberdeck.berserk"), true);
    }

    public static boolean isBerserkActive(ServerPlayer player) {
        return ActiveAbilities.isActive(player, "berserk") && hasFlag(player, "berserk");
    }

    public static void onHealthItemUsed(ServerPlayer player) {
        Cyberware booster = data(player).findFlag("health_item_boost");
        if (booster == null) {
            return;
        }
        int duration = Math.max(1,
                (int) Math.round(booster.value("duration_seconds") * 20.0));
        ActiveAbilities.activate(player, "health_item_boost", duration);
    }

    public static double activeStaminaReduction(ServerPlayer player) {
        Cyberware booster = data(player).findFlag("health_item_boost");
        if (booster == null || !ActiveAbilities.isActive(player, "health_item_boost")) {
            return 0.0;
        }
        return Math.min(1.0,
                booster.value("health_item_stamina_reduction_percent") / 100.0);
    }

    private static boolean isInCombat(ServerPlayer player) {
        AABB area = player.getBoundingBox().inflate(18.0);
        return !player.level().getEntitiesOfClass(Mob.class, area,
                mob -> mob instanceof Enemy && mob.isAlive() && mob.getTarget() == player).isEmpty();
    }

    private static boolean hasEnemyWithin(ServerPlayer player, double radius) {
        AABB area = player.getBoundingBox().inflate(radius);
        return !player.level().getEntitiesOfClass(Mob.class, area,
                mob -> mob instanceof Enemy && mob.isAlive()).isEmpty();
    }

    public static void forget(UUID id) {
        IN_COMBAT.remove(id);
        RAM_REGEN_BUFFER.remove(id);
        LAST_QUICKHACK.remove(id);
    }

    private static boolean isCombatQuickhack(Skill skill) {
        return skill == Skill.OVERHEAT
                || skill == Skill.SHORT_CIRCUIT
                || skill == Skill.CONTAGION
                || skill == Skill.DETONATE;
    }

    private static boolean isDamagingQuickhack(Skill skill) {
        return skill == Skill.OVERHEAT
                || skill == Skill.CONTAGION
                || skill == Skill.DETONATE;
    }
}
