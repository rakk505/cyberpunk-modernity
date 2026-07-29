package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.SandevistanProfile;
import com.example.cyberdeck.ram.RamAttachments;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;

import java.util.List;

/** Combat triggers shared by all tiered cyberware families. */
public final class CyberwareCombatHandler {
    private static final double GORILLA_FLING_HORIZONTAL = 2.4;
    private static final double GORILLA_FLING_VERTICAL = 0.55;

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        float amount = event.getAmount();

        if (source.getEntity() instanceof ServerPlayer attacker) {
            CyberwareData attackerData = CyberwareAttachments.get(attacker);
            Cyberware arms = attackerData.get(BodySlot.ARMS);
            if (arms != null && arms.hasFlag("gorilla_arms")) {
                amount += 4.0f + arms.tier().rank() * 0.5f;
            }
            if (!SandevistanMechanics.gunDamageAlreadyModified()) {
                amount *= (float) (1.0 + SandevistanMechanics.outgoingDamageBonus(attacker));
            }
            Cyberware mechatronic = attackerData.findFamily("mechatronic_core");
            if (mechatronic != null && event.getEntity() instanceof IronGolem) {
                amount *= (float) (1.0 + mechatronic.value("mechanical_damage_percent") / 100.0);
            }
            Cyberware blackMamba = attackerData.findFlag("poison_synergy");
            if (blackMamba != null && event.getEntity().hasEffect(MobEffects.POISON)) {
                amount *= (float) (1.0 + blackMamba.value("poison_other_damage_percent") / 100.0);
            }
            if (CyberwareEffects.isBerserkActive(attacker)
                    && source.getDirectEntity() == attacker) {
                Cyberware berserk = attackerData.findFlag("berserk");
                if (berserk != null && berserk.familyId().equals("militech_berserk")) {
                    double missing = 1.0 - attacker.getHealth() / Math.max(1.0, attacker.getMaxHealth());
                    amount *= (float) (1.0 + 0.50 * missing);
                }
            }
        }

        if (event.getEntity() instanceof ServerPlayer victim) {
            amount = modifyIncoming(victim, source, amount);
        }
        event.setAmount(Math.max(0.0f, amount));
    }

    private float modifyIncoming(ServerPlayer victim, DamageSource source, float amount) {
        CyberwareData data = CyberwareAttachments.get(victim);
        SandevistanProfile profile = SandevistanMechanics.activeProfile(victim);
        if (profile != null && profile.isFamily("qiant_warp_dancer")) {
            if (isWarpElement(source)) {
                amount *= (float) (1.0 - profile.elementalResistance());
            }
            if (victim.getRandom().nextDouble() < profile.mitigationChance()) {
                amount *= (float) (1.0 - profile.mitigationStrength());
            }
        }

        double passiveReduction = 0.0;
        double mitigationChance = 0.0;
        double mitigationStrength = 0.0;
        for (Cyberware cyberware : data.allInstalled()) {
            passiveReduction += cyberware.value("incoming_damage_reduction_percent") / 100.0;
            mitigationChance += cyberware.value("mitigation_chance_percent") / 100.0;
            mitigationStrength += cyberware.value("mitigation_strength_percent") / 100.0;
        }
        amount *= (float) (1.0 - Math.min(0.9, passiveReduction));

        Entity attacker = source.getEntity();
        Cyberware proximity = data.findFlag("proximity_damage_reduction");
        if (proximity != null && attacker != null) {
            double distance = victim.distanceTo(attacker);
            double factor = distance <= 3.0 ? 1.0 : distance >= 6.0 ? 0.0 : (6.0 - distance) / 3.0;
            amount *= (float) (1.0
                    - proximity.value("close_damage_reduction_percent") / 100.0 * factor);
        }

        Cyberware rearArmor = data.findFlag("rear_armor");
        if (rearArmor != null && attacker != null) {
            Vec3 towardAttacker = attacker.position().subtract(victim.position()).normalize();
            if (victim.getLookAngle().dot(towardAttacker) < 0.70) {
                amount *= (float) (1.0 - Math.min(0.75,
                        rearArmor.value("rear_armor_percent") / 100.0));
            }
        }

        if (mitigationChance > 0.0 && victim.getRandom().nextDouble() < Math.min(1.0, mitigationChance)) {
            amount *= (float) (1.0 - Math.min(0.9, mitigationStrength));
        }

        Cyberware blocker = data.findFlag("projectile_block");
        if (blocker != null && source.getDirectEntity() instanceof Projectile
                && victim.getRandom().nextDouble()
                < blocker.value("projectile_block_chance_percent") / 100.0) {
            victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.8f, 1.4f);
            return 0.0f;
        }

        Cyberware berserk = data.findFlag("berserk");
        if (berserk != null && CyberwareEffects.isBerserkActive(victim)) {
            if (berserk.hasFlag("invulnerable")) {
                return 0.0f;
            }
            amount *= (float) (1.0
                    - Math.min(1.0, berserk.value("active_damage_reduction_percent") / 100.0));
            float healthFloor = victim.getMaxHealth() * 0.25f;
            amount = Math.min(amount, Math.max(0.0f, victim.getHealth() - healthFloor));
        }

        Cyberware secondHeart = data.findFlag("second_heart");
        if (secondHeart != null && amount >= victim.getHealth()
                && !ActiveAbilities.onCooldown(victim, "second_heart")) {
            victim.setHealth(victim.getMaxHealth());
            ActiveAbilities.setCooldown(victim, "second_heart",
                    CyberwareEffects.cooldownTicks(victim, secondHeart, "cooldown_seconds"));
            victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(),
                    SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
            return 0.0f;
        }

        Cyberware ramRecoup = data.findFlag("ram_on_damage");
        if (ramRecoup != null && amount > 0.0f) {
            int restored = (int) Math.floor(amount * ramRecoup.value("ram_damage_percent") / 100.0);
            if (restored > 0) {
                RamAttachments.set(victim, RamAttachments.get(victim) + restored);
            }
        }

        Cyberware shock = data.findFlag("damage_reactive_shock");
        if (shock != null && victim.level() instanceof ServerLevel level
                && victim.getRandom().nextDouble() < shock.value("shock_chance_percent") / 100.0) {
            float shockDamage = (float) Math.max(1.0, shock.value("shock_damage") / 20.0);
            List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class,
                    victim.getBoundingBox().inflate(4.0),
                    entity -> entity != victim && entity.isAlive());
            for (LivingEntity entity : nearby) {
                entity.hurtServer(level, level.damageSources().magic(), shockDamage);
            }
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    victim.getX(), victim.getY(0.5), victim.getZ(), 24, 2.0, 1.0, 2.0, 0.1);
        }
        return amount;
    }

    @SubscribeEvent
    public void onCriticalHit(CriticalHitEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        double chance = 0.0;
        double bonusDamage = 0.0;
        SandevistanProfile profile = SandevistanMechanics.activeProfile(player);
        if (profile != null) {
            chance += profile.critChance();
            bonusDamage += profile.critDamageBonus();
        }
        CyberwareData data = CyberwareAttachments.get(player);
        chance += data.allInstalled().stream()
                .mapToDouble(cyberware -> cyberware.value("crit_chance_percent") / 100.0).sum();
        Cyberware distanceCrit = data.findFlag("distance_crit");
        if (distanceCrit != null) {
            double distance = player.distanceTo(event.getTarget());
            chance += distanceCrit.value("distance_crit_percent") / 100.0
                    * Math.min(1.0, distance / Math.max(1.0, distanceCrit.value("distance_crit_range")));
        }
        Cyberware berserk = data.findFlag("berserk");
        if (berserk != null && CyberwareEffects.isBerserkActive(player)) {
            chance += berserk.value("active_crit_chance_percent") / 100.0;
            bonusDamage += berserk.value("active_crit_damage_percent") / 100.0;
        }
        if (event.isCriticalHit()) {
            event.setDamageMultiplier(event.getDamageMultiplier() + (float) bonusDamage);
        } else if (player.getRandom().nextDouble() < Math.min(1.0, chance)) {
            event.setCriticalHit(true);
            event.setDamageMultiplier((float) (1.5 + bonusDamage));
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Enemy)
                || !(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        SandevistanProfile profile = SandevistanMechanics.activeProfile(player);
        if (profile != null) {
            SandevistanState state = CyberwareAttachments.getSandevistanState(player);
            if (profile.killDurationFraction() > 0.0) {
                state.addCharge(profile, profile.durationTicks() * profile.killDurationFraction());
            }
            if (profile.killHealthFraction() > 0.0) {
                player.heal((float) (player.getMaxHealth() * profile.killHealthFraction()));
            }
            restoreStamina(player, profile.killHungerFraction());
        }
        Cyberware heal = data.findFlag("health_on_kill");
        if (heal != null) {
            player.heal((float) (player.getMaxHealth()
                    * heal.value("health_on_kill_percent") / 100.0));
        }
        Cyberware memory = data.findFamily("memory_boost");
        if (memory != null) {
            RamAttachments.set(player, RamAttachments.get(player)
                    + Math.max(1, (int) Math.round(memory.value("ram_on_kill"))));
        }
        double cooldownReduction = data.allInstalled().stream()
                .mapToDouble(cyberware -> cyberware.value("cooldown_on_kill_percent") / 100.0)
                .sum();
        ActiveAbilities.reduceCooldowns(player, cooldownReduction);
        if (data.findFlag("berserk") != null && CyberwareEffects.isBerserkActive(player)) {
            player.heal(player.getMaxHealth() * 0.25f);
        }
        Cyberware adrenaline = data.findFamily("adrenaline_booster");
        if (adrenaline != null && event.getSource().getDirectEntity() == player) {
            restoreStamina(player, adrenaline.value("stamina_on_melee_kill_percent") / 100.0);
        }
    }

    @SubscribeEvent
    public void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        double reduction = 0.0;
        SandevistanProfile profile = SandevistanMechanics.activeProfile(player);
        if (profile != null && profile.isFamily("zetatech_sandevistan")) {
            reduction = Math.max(reduction,
                    profile.cyberware().value("fall_damage_reduction_percent") / 100.0);
        }
        Cyberware lynx = CyberwareAttachments.get(player).findFamily("lynx_paws");
        if (lynx != null) {
            reduction = Math.max(reduction, lynx.value("fall_damage_reduction_percent") / 100.0);
        }
        Cyberware berserk = CyberwareAttachments.get(player).findFlag("berserk");
        if (berserk != null && CyberwareEffects.isBerserkActive(player)) {
            reduction = Math.max(reduction,
                    berserk.value("fall_damage_reduction_percent") / 100.0);
        }
        event.setDamageMultiplier(event.getDamageMultiplier() * (float) (1.0 - reduction));
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        Player playerEntity = event.getEntity();
        if (!(playerEntity instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (ActiveAbilities.isOpticalCamoActive(player)) {
            OpticalCamo.deactivate(player);
        }
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }

        Cyberware arms = CyberwareAttachments.get(player).get(BodySlot.ARMS);
        if (arms == null) {
            return;
        }
        if (arms.hasFlag("gorilla_arms")) {
            flingBack(player, target);
            level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.5), target.getZ(),
                    16, 0.4, 0.4, 0.4, 0.2);
        } else if (arms.hasFlag("mantis_blades")) {
            level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                    target.getX(), target.getY(0.6), target.getZ(), 3, 0.2, 0.1, 0.2, 0.0);
        } else if (arms.hasFlag("monowire")) {
            for (LivingEntity nearby : level.getEntitiesOfClass(LivingEntity.class,
                    target.getBoundingBox().inflate(2.5),
                    entity -> entity != player && entity != target && entity.isAlive())) {
                nearby.hurtServer(level, player.damageSources().playerAttack(player), 3.0f);
            }
        }
        applyArmStatus(player, target, arms);
    }

    private static void applyArmStatus(ServerPlayer player, LivingEntity target, Cyberware arms) {
        if (player.getRandom().nextDouble() >= arms.value("status_chance_percent") / 100.0) {
            return;
        }
        if (arms.hasFlag("status_burn")) {
            target.igniteForSeconds(4.0f);
        } else if (arms.hasFlag("status_poison")) {
            target.addEffect(new MobEffectInstance(MobEffects.POISON, 5 * 20, 0));
        } else if (arms.hasFlag("status_shock")) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 5 * 20, 1));
        } else if (arms.hasFlag("status_bleeding")) {
            target.addEffect(new MobEffectInstance(MobEffects.WITHER, 5 * 20, 0));
        }
    }

    private static void restoreStamina(ServerPlayer player, double fraction) {
        if (fraction <= 0.0) {
            return;
        }
        int restored = Math.max(1, (int) Math.round(20.0 * fraction));
        var food = player.getFoodData();
        food.setFoodLevel(Math.min(20, food.getFoodLevel() + restored));
        food.setSaturation(Math.min(food.getFoodLevel(),
                food.getSaturationLevel() + (float) restored));
    }

    private static void flingBack(ServerPlayer player, LivingEntity target) {
        Vec3 direction = target.position().subtract(player.position());
        direction = new Vec3(direction.x, 0, direction.z);
        if (direction.lengthSqr() < 1.0e-4) {
            Vec3 look = player.getLookAngle();
            direction = new Vec3(look.x, 0, look.z);
        }
        direction = direction.normalize();
        target.push(direction.x * GORILLA_FLING_HORIZONTAL,
                GORILLA_FLING_VERTICAL, direction.z * GORILLA_FLING_HORIZONTAL);
        target.hurtMarked = true;
        player.level().playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0f, 0.7f);
    }

    private static boolean isWarpElement(DamageSource source) {
        return source.is(DamageTypeTags.IS_FIRE)
                || source.is(DamageTypeTags.IS_LIGHTNING)
                || source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.DRAGON_BREATH)
                || source.is(DamageTypes.WITHER);
    }
}
