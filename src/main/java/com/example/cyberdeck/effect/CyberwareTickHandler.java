package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/**
 * Runs the per-tick, server-authoritative logic for cyberware:
 * <ul>
 *   <li>Hyena Legs sprint speed toggling (only while sprinting),</li>
 *   <li>Sandevistan world-slow enforcement (slow all nearby entities except the owner),</li>
 *   <li>Optical Camo invisibility + hostile aggro clearing,</li>
 *   <li>ability timer/cooldown decrements.</li>
 * </ul>
 * Also re-applies passive attribute modifiers on login and clears transient state on logout.
 */
public final class CyberwareTickHandler {
    private static final double SANDEVISTAN_RADIUS = 24.0;
    private static final double OPTICAL_CAMO_AGGRO_RADIUS = 48.0;

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        tickRamRegen(player);
        tickHyenaSprint(player);
        tickSandevistan(player);
        tickOpticalCamo(player);
    }

    /** Regenerate 1 RAM per second (every 20 ticks) up to the cap. */
    private void tickRamRegen(ServerPlayer player) {
        if (player.tickCount % 20 != 0) {
            return;
        }
        int ram = com.example.cyberdeck.ram.RamAttachments.get(player);
        if (ram < com.example.cyberdeck.ram.RamAttachments.MAX_RAM) {
            com.example.cyberdeck.ram.RamAttachments.set(player, ram + 1);
        }
    }

    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // Global cooldown decrement (independent of player count).
        ActiveAbilities.tickCooldowns();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Re-assert passive modifiers so a reloaded loadout is correctly reflected.
            CyberwarePassives.reapply(player);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ActiveAbilities.forget(player.getUUID());
            LegSpeed.forget(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // copyOnDeath keeps the data; make sure passives are re-applied to the new entity.
            CyberwarePassives.reapply(player);
        }
    }

    private void tickHyenaSprint(ServerPlayer player) {
        if (!LegSpeed.hasHyena(player)) {
            return;
        }
        LegSpeed.setBoost(player, player.isSprinting());
    }

    private void tickSandevistan(ServerPlayer player) {
        Integer remaining = ActiveAbilities.sandevistan.get(player.getUUID());
        if (remaining == null || remaining <= 0) {
            return;
        }
        ActiveAbilities.sandevistan.put(player.getUUID(), remaining - 1);

        // Keep the player noticeably faster than normal so they visibly outpace the frozen world.
        player.addEffect(new MobEffectInstance(MobEffects.SPEED, 4, 4, true, false, false));
        // Boost the player's attack/dig speed too so they act fast while the world is frozen.
        player.addEffect(new MobEffectInstance(MobEffects.HASTE, 4, 2, true, false, false));

        // "Time dilation": everything that isn't the player is slowed to a near standstill. We both
        // apply the strongest movement debuffs AND directly dampen per-tick velocity so mobs,
        // projectiles, thrown items and falling blocks visibly crawl instead of merely walking slow.
        AABB area = player.getBoundingBox().inflate(SANDEVISTAN_RADIUS);
        List<net.minecraft.world.entity.Entity> others =
                player.level().getEntitiesOfClass(net.minecraft.world.entity.Entity.class, area,
                        e -> e != player && !(e instanceof ServerPlayer) && e.isAlive());
        for (net.minecraft.world.entity.Entity other : others) {
            if (other instanceof LivingEntity living) {
                // Near-total movement lockdown + crippled attack speed and reactions.
                living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 4, 9, true, false, false));
                living.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 4, 9, true, false, false));
                living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 4, 1, true, false, false));
            }
            // Scale down whatever motion the entity currently has so it appears frozen in time.
            net.minecraft.world.phys.Vec3 m = other.getDeltaMovement();
            other.setDeltaMovement(m.x * 0.15, m.y * 0.15, m.z * 0.15);
            other.hurtMarked = true;
        }
    }

    private void tickOpticalCamo(ServerPlayer player) {
        Integer remaining = ActiveAbilities.opticalCamo.get(player.getUUID());
        if (remaining == null || remaining <= 0) {
            return;
        }
        ActiveAbilities.opticalCamo.put(player.getUUID(), remaining - 1);

        // Keep the player invisible while camo is active.
        player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 6, 0, true, false, true));

        // Force any nearby hostile mob to forget the player as a target, regardless of range.
        AABB area = player.getBoundingBox().inflate(OPTICAL_CAMO_AGGRO_RADIUS);
        List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, area,
                m -> m instanceof Enemy && m.isAlive());
        for (Mob mob : mobs) {
            if (mob.getTarget() == player) {
                mob.setTarget(null);
            }
        }
    }
}
