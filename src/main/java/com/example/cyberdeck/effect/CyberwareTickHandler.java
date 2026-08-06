package com.example.cyberdeck.effect;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
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
    private static final double OPTICAL_CAMO_AGGRO_RADIUS = 48.0;
    // Speed III (amplifier 2), silent icon-less buff refreshed each tick while sandevistan runs.
    private static final int SANDEVISTAN_SPEED_AMPLIFIER = 2;
    private static final int SANDEVISTAN_SPEED_DURATION = 40;
    private static final int SANDEVISTAN_SPEED_MIN_REMAINING = 10;

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        CyberwareEffects.tickPlayer(player);
        ChargedJump.tick(player);
        DoubleJumpGuard.tick(player);
        SandevistanMechanics.tick(player);
        tickSandevistanSpeed(player);
        tickSandevistanPlayerSlow(player);
        tickOpticalCamo(player);
    }

    /**
     * Gives the sandevistan owner a Speed III burst while the reflex booster is active,
     * so the world-slow reads as the player blitzing forward. Refreshed every tick and
     * cleared the instant the sandevistan drops so it never lingers.
     */
    private static void tickSandevistanSpeed(ServerPlayer player) {
        if (SandevistanMechanics.isActive(player)) {
            MobEffectInstance current = player.getEffect(MobEffects.SPEED);
            if (current == null || current.getAmplifier() < SANDEVISTAN_SPEED_AMPLIFIER
                    || current.getDuration() < SANDEVISTAN_SPEED_MIN_REMAINING) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.SPEED,
                        SANDEVISTAN_SPEED_DURATION,
                        SANDEVISTAN_SPEED_AMPLIFIER,
                        true,
                        false,
                        false));
            }
        } else {
            MobEffectInstance current = player.getEffect(MobEffects.SPEED);
            if (current != null && current.getAmplifier() == SANDEVISTAN_SPEED_AMPLIFIER
                    && !current.isVisible()) {
                player.removeEffect(MobEffects.SPEED);
            }
        }
    }

    /** Fractionally cancels non-player entity ticks for an exact 20-tick time-dilation ratio. */
    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide() || entity instanceof net.minecraft.world.entity.player.Player) {
            return;
        }
        // This fires for every entity on every tick, so it must cost nothing when nobody is
        // dilating time. Both lookups are gated on their owner sets being non-empty and then
        // answer from a per-tick index, rather than scanning the player list for every entity.
        double slowFraction = Math.max(
                SandevistanMechanics.slowFractionAffecting(entity),
                ReactiveCyberware.slowFractionAffecting(entity));
        if (slowFraction <= 0.0) {
            return;
        }
        int allowedTicks = Math.max(1, Math.min(20,
                (int) Math.round((1.0 - slowFraction) * 20.0)));
        int phase = Math.floorMod(entity.level().getGameTime() + entity.getId(), 20);
        if (phase >= allowedTicks) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        // Global cooldown decrement (independent of player count).
        ActiveAbilities.tickCooldowns();
        // The radio is deliberately not driven from the city module: that runs
        // inside its own tick-count window, and music must play outside the city too.
        com.example.cyberdeck.radio.RadioService.tick(event.getServer());
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Recover a scanner session left active by a server/client crash before touching any
            // other equipment-derived state.
            com.example.cyberdeck.CyberdeckState.recover(player);
            // Re-assert passive modifiers so a reloaded loadout is correctly reflected.
            SandevistanMechanics.deactivateForSessionBoundary(player);
            CyberwarePassives.reapply(player);
        }
    }

    /** Put real items back before vanilla evaluates keep-inventory and death drops. */
    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.example.cyberdeck.CyberdeckState.recover(player);
            com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
            com.example.cyberdeck.faction.HostileQuickhackState.clearPlayer(player);
            com.example.cyberdeck.WeaponGlitchData.clear(player);
            com.example.cyberdeck.control.RemoteEntityControl.end(player);
            DoubleJumpGuard.forget(player.getUUID());
            ChargedJump.forget(player.getUUID());
            ReactiveCyberware.forget(player.getUUID());
            CyberwareWeaponEffects.forget(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Restore the real hotbar before player data is saved and release queued reservations.
            com.example.cyberdeck.CyberdeckState.deactivate(player);
            com.example.cyberdeck.skill.QuickhackUploads.forget(player.getUUID());
            com.example.cyberdeck.faction.HostileQuickhackState.clearPlayer(player);
            com.example.cyberdeck.WeaponGlitchData.clear(player);
            com.example.cyberdeck.control.RemoteEntityControl.end(player);
            com.example.cyberdeck.control.RemoteEntityControl.forget(player.getUUID());
            SandevistanMechanics.deactivateForSessionBoundary(player);
            ActiveAbilities.forget(player.getUUID());
            DoubleJumpGuard.forget(player.getUUID());
            ChargedJump.forget(player.getUUID());
            LegSpeed.forget(player.getUUID());
            CyberwareEffects.forget(player.getUUID());
            ReactiveCyberware.forget(player.getUUID());
            CyberwareWeaponEffects.forget(player.getUUID());
            com.example.cyberdeck.radio.RadioService.forget(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
            com.example.cyberdeck.faction.HostileQuickhackState.clearPlayer(player);
            com.example.cyberdeck.WeaponGlitchData.clear(player);
            com.example.cyberdeck.control.RemoteEntityControl.end(player);
            com.example.cyberdeck.CyberdeckState.recover(player);
            DoubleJumpGuard.forget(player.getUUID());
            ChargedJump.forget(player.getUUID());
            ReactiveCyberware.forget(player.getUUID());
            CyberwareWeaponEffects.forget(player.getUUID());
            // copyOnDeath keeps the data; make sure passives are re-applied to the new entity.
            SandevistanMechanics.deactivateForSessionBoundary(player);
            CyberwarePassives.reapply(player);
        }
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.example.cyberdeck.faction.HostileQuickhackState.clearPlayer(player);
            com.example.cyberdeck.WeaponGlitchData.clear(player);
        }
    }

    @SubscribeEvent
    public void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        com.example.cyberdeck.skill.QuickhackUploads.clearAll();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            com.example.cyberdeck.faction.HostileQuickhackState.clearPlayer(player);
            com.example.cyberdeck.WeaponGlitchData.clear(player);
        }
        com.example.cyberdeck.faction.HostileQuickhackState.clearAll();
        com.example.cyberdeck.control.RemoteEntityControl.clearAll();
        DoubleJumpGuard.clearAll();
        ChargedJump.clearAll();
        ReactiveCyberware.clearAll();
        SandevistanMechanics.clearAll();
        com.example.cyberdeck.radio.RadioService.clearAll();
        CyberwareWeaponEffects.clearAll();
    }

    private void tickSandevistanPlayerSlow(ServerPlayer player) {
        double slowFraction = Math.max(
                SandevistanMechanics.slowFractionAffecting(player),
                ReactiveCyberware.slowFractionAffecting(player));
        int amplifier = SandevistanMechanics.slownessAmplifier(slowFraction);
        if (amplifier >= 0) {
            // Player ticks cannot be canceled on the logical server, so use the nearest vanilla
            // Slowness tier. Non-player entities use exact fractional tick cancellation above.
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 4, amplifier,
                    true, false, false));
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
