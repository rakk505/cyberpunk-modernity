package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.wanted.WantedState;
import com.example.cyberdeck.wanted.WantedSystem;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stateful triggers shared by nervous-system, integumentary, and takedown cyberware. */
public final class ReactiveCyberware {
    private static final double TIME_DILATION_RADIUS_SQR =
            SandevistanMechanics.EFFECT_RADIUS * SandevistanMechanics.EFFECT_RADIUS;
    private static final long BURST_WINDOW_TICKS = 3 * 20L;
    private static final double BURST_HEALTH_FRACTION = 0.35;
    private static final int DAMAGE_OVER_TIME_PULSES = 5;
    private static final int DAMAGE_OVER_TIME_INTERVAL = 20;
    private static final int NANO_BLOCK_LIMIT = 3;
    private static final int NANO_BLOCK_WINDOW_TICKS = 6 * 20;
    private static final int FACEPLATE_ESCAPE_TICKS = 10 * 20;

    private static final Map<UUID, TimeDilation> TIME_DILATION = new HashMap<>();
    private static final Map<UUID, DamageWindow> DAMAGE_WINDOWS = new HashMap<>();
    private static final Map<UUID, List<DelayedPulse>> DELAYED_DAMAGE = new HashMap<>();
    private static final Map<UUID, NanoBlockState> NANO_BLOCKS = new HashMap<>();
    private static final Map<UUID, Long> FACEPLATE_SAFE_SINCE = new HashMap<>();
    private static final ThreadLocal<Boolean> APPLYING_DELAYED_DAMAGE =
            ThreadLocal.withInitial(() -> false);

    private ReactiveCyberware() {
    }

    public static void tick(ServerPlayer player) {
        long now = player.level().getGameTime();
        tickTimeDilation(player, now);
        tickTriggeredTimeDilation(player);
        tickSelfIce(player);
        tickDelayedDamage(player, now);
        tickFaceplate(player, now);
    }

    public static double detectionFraction(ServerPlayer player) {
        AABB area = player.getBoundingBox().inflate(32.0);
        double strongest = 0.0;
        for (FactionEnemy enemy : player.level().getEntitiesOfClass(
                FactionEnemy.class, area, FactionEnemy::isAlive)) {
            strongest = Math.max(strongest,
                    enemy.getDetection() / (double) FactionEnemy.detectionThreshold());
        }
        return Math.min(1.0, strongest);
    }

    public static double detectionMovementBonus(ServerPlayer player, boolean inCombat) {
        if (inCombat) {
            return 0.0;
        }
        Cyberware sensors = CyberwareAttachments.get(player).findFlag("detection_speed");
        if (sensors == null) {
            return 0.0;
        }
        return sensors.value("detection_speed_percent") / 100.0 * detectionFraction(player);
    }

    public static void triggerKerenzikov(ServerPlayer player) {
        Cyberware kerenzikov = CyberwareAttachments.get(player).findFlag("kerenzikov");
        if (kerenzikov == null || ActiveAbilities.onCooldown(player, kerenzikov.id())) {
            return;
        }
        activateTimeDilation(player, kerenzikov, "kerenzikov");
    }

    public static double slowFractionAffecting(Entity target) {
        if (!(target.level() instanceof ServerLevel level)) {
            return 0.0;
        }
        if (target instanceof ServerPlayer player && isTimeDilationOwner(player)) {
            return 0.0;
        }
        if (target instanceof Projectile projectile
                && projectile.getOwner() instanceof ServerPlayer owner
                && isTimeDilationOwner(owner)) {
            return 0.0;
        }

        long now = level.getGameTime();
        double strongest = 0.0;
        for (ServerPlayer owner : level.players()) {
            TimeDilation state = TIME_DILATION.get(owner.getUUID());
            if (state == null || now >= state.endTick || owner == target
                    || owner.distanceToSqr(target) > TIME_DILATION_RADIUS_SQR) {
                continue;
            }
            strongest = Math.max(strongest, state.slowFraction);
        }
        return strongest;
    }

    public static double mitigationChance(ServerPlayer player) {
        double chance = 0.0;
        for (Cyberware cyberware : CyberwareAttachments.get(player).allInstalled()) {
            if (cyberware.hasFlag("high_stamina_mitigation")
                    && player.getFoodData().getFoodLevel() < 17) {
                continue;
            }
            if (cyberware.hasFlag("burst_damage_mitigation")
                    && !ActiveAbilities.isActive(player, "burst_mitigation")) {
                continue;
            }
            if (cyberware.hasFlag("post_kerenzikov_mitigation")
                    && !ActiveAbilities.isActive(player, "post_kerenzikov_mitigation")) {
                continue;
            }
            chance += cyberware.value("mitigation_chance_percent") / 100.0;
        }
        return Math.min(1.0, chance);
    }

    public static double mitigationStrength(ServerPlayer player) {
        if (mitigationChance(player) <= 0.0) {
            return 0.0;
        }
        // Cyberpunk's base Mitigation Strength is 50%; implants such as Spring Joints add to it.
        return Math.min(0.9,
                0.5 + CyberwareEffects.sumValue(player, "mitigation_strength_percent") / 100.0);
    }

    /** Converts Painducer damage and records Countershell's rolling three-second damage window. */
    public static float afterMitigation(ServerPlayer player, float amount) {
        if (amount <= 0.0f || isApplyingDelayedDamage()) {
            return amount;
        }
        recordBurstDamage(player, amount);
        Cyberware painducer = CyberwareAttachments.get(player).findFlag("damage_to_dot");
        if (painducer == null) {
            return amount;
        }
        double fraction = Math.min(0.9, painducer.value("damage_to_dot_percent") / 100.0);
        float delayed = (float) (amount * fraction);
        if (delayed > 0.0f) {
            long firstPulse = player.level().getGameTime() + DAMAGE_OVER_TIME_INTERVAL;
            DELAYED_DAMAGE.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>())
                    .add(new DelayedPulse(
                            firstPulse, DAMAGE_OVER_TIME_PULSES, delayed / DAMAGE_OVER_TIME_PULSES));
        }
        return amount - delayed;
    }

    public static boolean isApplyingDelayedDamage() {
        return APPLYING_DELAYED_DAMAGE.get();
    }

    public static void onDodge(ServerPlayer player) {
        Cyberware plating = CyberwareAttachments.get(player).findFlag("projectile_block");
        if (plating == null) {
            return;
        }
        long duration = Math.max(28L, 28L + plating.tier().rank());
        nanoState(player, player.level().getGameTime()).bonusUntilTick =
                player.level().getGameTime() + duration;
    }

    public static boolean tryBlockProjectile(ServerPlayer player, Cyberware plating) {
        long now = player.level().getGameTime();
        NanoBlockState state = nanoState(player, now);
        if (state.blocks >= NANO_BLOCK_LIMIT) {
            return false;
        }
        double chance = plating.value("projectile_block_chance_percent") / 100.0;
        if (now < state.bonusUntilTick) {
            chance *= 2.0;
        }
        if (player.getRandom().nextDouble() >= Math.min(1.0, chance)) {
            return false;
        }
        state.blocks++;
        state.bonusUntilTick = 0L;
        return true;
    }

    public static void onTakedown(ServerPlayer player) {
        CyberwareData data = CyberwareAttachments.get(player);
        Cyberware ram = data.findFlag("takedown_ram");
        if (ram != null) {
            RamAttachments.set(player, RamAttachments.get(player)
                    + Math.max(0, (int) Math.round(ram.value("takedown_ram"))));
        }
        Cyberware boost = data.findFlag("takedown_boost");
        if (boost != null) {
            ActiveAbilities.activate(player, "takedown_boost", 15 * 20);
        }
    }

    public static double takedownMovementBonus(ServerPlayer player) {
        if (!ActiveAbilities.isActive(player, "takedown_boost")) {
            return 0.0;
        }
        Cyberware boost = CyberwareAttachments.get(player).findFlag("takedown_boost");
        return boost == null ? 0.0 : boost.value("kill_movement_percent") / 100.0;
    }

    public static double takedownHeadshotBonus(ServerPlayer player) {
        if (!ActiveAbilities.isActive(player, "takedown_boost")) {
            return 0.0;
        }
        Cyberware boost = CyberwareAttachments.get(player).findFlag("takedown_boost");
        return boost == null ? 0.0 : boost.value("kill_headshot_percent") / 100.0;
    }

    public static void forget(UUID playerId) {
        TIME_DILATION.remove(playerId);
        DAMAGE_WINDOWS.remove(playerId);
        DELAYED_DAMAGE.remove(playerId);
        NANO_BLOCKS.remove(playerId);
        FACEPLATE_SAFE_SINCE.remove(playerId);
    }

    public static void clearAll() {
        TIME_DILATION.clear();
        DAMAGE_WINDOWS.clear();
        DELAYED_DAMAGE.clear();
        NANO_BLOCKS.clear();
        FACEPLATE_SAFE_SINCE.clear();
    }

    private static void tickTriggeredTimeDilation(ServerPlayer player) {
        CyberwareData data = CyberwareAttachments.get(player);
        Cyberware lowHealth = data.findFlag("low_health_time_slow");
        if (lowHealth != null && player.getHealth() <= player.getMaxHealth() * 0.25f
                && !ActiveAbilities.onCooldown(player, lowHealth.id())) {
            activateTimeDilation(player, lowHealth, "low_health");
        }

        Cyberware detection = data.findFlag("detection_time_slow");
        if (detection != null && detectionFraction(player) >= 0.5
                && !ActiveAbilities.onCooldown(player, detection.id())) {
            activateTimeDilation(player, detection, "detection");
        }
    }

    private static void activateTimeDilation(
            ServerPlayer player, Cyberware cyberware, String source) {
        long now = player.level().getGameTime();
        double slow = cyberware.value("time_slow_percent") / 100.0;
        if (cyberware.hasFlag("kerenzikov")) {
            Cyberware booster = CyberwareAttachments.get(player).findFlag("kerenzikov_boost");
            if (booster != null) {
                slow += booster.value("time_slow_bonus_percent") / 100.0;
            }
        }
        int duration = Math.max(1, (int) Math.round(cyberware.value("duration_seconds") * 20.0));
        TIME_DILATION.put(player.getUUID(),
                new TimeDilation(Math.min(0.9, slow), now + duration, source));
        ActiveAbilities.setCooldown(player, cyberware.id(),
                CyberwareEffects.cooldownTicks(player, cyberware, "cooldown_seconds"));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.35f, 1.8f);
    }

    private static void tickTimeDilation(ServerPlayer player, long now) {
        TimeDilation state = TIME_DILATION.get(player.getUUID());
        if (state == null || now < state.endTick) {
            return;
        }
        TIME_DILATION.remove(player.getUUID());
        if (!"kerenzikov".equals(state.source)) {
            return;
        }
        Cyberware defenzikov = CyberwareAttachments.get(player)
                .findFlag("post_kerenzikov_mitigation");
        if (defenzikov != null) {
            int duration = Math.max(1,
                    (int) Math.round(Math.max(3.0, defenzikov.value("duration_seconds")) * 20.0));
            ActiveAbilities.activate(player, "post_kerenzikov_mitigation", duration);
        }
    }

    private static boolean isTimeDilationOwner(ServerPlayer player) {
        TimeDilation state = TIME_DILATION.get(player.getUUID());
        return state != null && player.level().getGameTime() < state.endTick;
    }

    private static void tickSelfIce(ServerPlayer player) {
        if (player.tickCount % 10 != 0) {
            return;
        }
        Cyberware selfIce = CyberwareAttachments.get(player).findFlag("self_ice");
        if (selfIce == null || ActiveAbilities.onCooldown(player, selfIce.id())) {
            return;
        }
        List<Holder<MobEffect>> harmful = player.getActiveEffects().stream()
                .filter(instance -> instance.getEffect().value().getCategory()
                        == MobEffectCategory.HARMFUL)
                .map(MobEffectInstance::getEffect)
                .toList();
        if (harmful.isEmpty()) {
            return;
        }
        player.removeEffect(harmful.getFirst());
        ActiveAbilities.setCooldown(player, selfIce.id(), Math.max(1,
                (int) Math.round(selfIce.value("trigger_cooldown_seconds") * 20.0)));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.5f, 1.8f);
    }

    private static void recordBurstDamage(ServerPlayer player, float amount) {
        Cyberware countershell = CyberwareAttachments.get(player)
                .findFlag("burst_damage_mitigation");
        if (countershell == null) {
            return;
        }
        long now = player.level().getGameTime();
        DamageWindow window = DAMAGE_WINDOWS.computeIfAbsent(
                player.getUUID(), ignored -> new DamageWindow(now));
        if (now - window.startedTick > BURST_WINDOW_TICKS) {
            window.startedTick = now;
            window.damage = 0.0f;
        }
        window.damage += amount;
        if (window.damage < player.getMaxHealth() * BURST_HEALTH_FRACTION
                || ActiveAbilities.onCooldown(player, countershell.id())) {
            return;
        }
        int duration = Math.max(1,
                (int) Math.round(countershell.value("duration_seconds") * 20.0));
        ActiveAbilities.activate(player, "burst_mitigation", duration);
        ActiveAbilities.setCooldown(player, countershell.id(),
                CyberwareEffects.cooldownTicks(player, countershell, "cooldown_seconds"));
        window.damage = 0.0f;
        window.startedTick = now;
    }

    private static void tickDelayedDamage(ServerPlayer player, long now) {
        List<DelayedPulse> pulses = DELAYED_DAMAGE.get(player.getUUID());
        if (pulses == null || pulses.isEmpty() || !player.isAlive()) {
            return;
        }
        Iterator<DelayedPulse> iterator = pulses.iterator();
        List<DelayedPulse> replacements = new ArrayList<>();
        while (iterator.hasNext()) {
            DelayedPulse pulse = iterator.next();
            if (now < pulse.nextTick) {
                continue;
            }
            iterator.remove();
            if (player.level() instanceof ServerLevel level) {
                APPLYING_DELAYED_DAMAGE.set(true);
                try {
                    player.hurtServer(level, level.damageSources().magic(), pulse.damage);
                } finally {
                    APPLYING_DELAYED_DAMAGE.remove();
                }
            }
            if (pulse.remainingPulses > 1) {
                replacements.add(new DelayedPulse(
                        now + DAMAGE_OVER_TIME_INTERVAL,
                        pulse.remainingPulses - 1,
                        pulse.damage));
            }
        }
        pulses.addAll(replacements);
        if (pulses.isEmpty()) {
            DELAYED_DAMAGE.remove(player.getUUID());
        }
    }

    private static NanoBlockState nanoState(ServerPlayer player, long now) {
        NanoBlockState state = NANO_BLOCKS.computeIfAbsent(
                player.getUUID(), ignored -> new NanoBlockState(now));
        if (now - state.windowStartedTick >= NANO_BLOCK_WINDOW_TICKS) {
            state.windowStartedTick = now;
            state.blocks = 0;
        }
        return state;
    }

    private static void tickFaceplate(ServerPlayer player, long now) {
        if (CyberwareAttachments.get(player).findFlag("behavioral_identity") == null
                || !WantedState.get(player).active()) {
            FACEPLATE_SAFE_SINCE.remove(player.getUUID());
            return;
        }
        if (isInCombat(player)) {
            FACEPLATE_SAFE_SINCE.put(player.getUUID(), now);
            return;
        }
        long safeSince = FACEPLATE_SAFE_SINCE.computeIfAbsent(
                player.getUUID(), ignored -> now);
        if (now - safeSince >= FACEPLATE_ESCAPE_TICKS) {
            WantedSystem.clearWithIdentityMask(player);
            FACEPLATE_SAFE_SINCE.remove(player.getUUID());
        }
    }

    private static boolean isInCombat(ServerPlayer player) {
        AABB area = player.getBoundingBox().inflate(24.0);
        return !player.level().getEntitiesOfClass(Mob.class, area,
                mob -> mob.isAlive() && mob.getTarget() == player).isEmpty();
    }

    private record TimeDilation(double slowFraction, long endTick, String source) {
    }

    private record DelayedPulse(long nextTick, int remainingPulses, float damage) {
    }

    private static final class DamageWindow {
        private long startedTick;
        private float damage;

        private DamageWindow(long startedTick) {
            this.startedTick = startedTick;
        }
    }

    private static final class NanoBlockState {
        private long windowStartedTick;
        private int blocks;
        private long bonusUntilTick;

        private NanoBlockState(long windowStartedTick) {
            this.windowStartedTick = windowStartedTick;
        }
    }
}
