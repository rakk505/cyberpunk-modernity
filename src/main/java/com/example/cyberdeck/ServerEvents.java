package com.example.cyberdeck;

import com.example.cyberdeck.npc.CityNpc;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/**
 * Server-side event handling for cyberdeck mode, uploads, reloading, entity outlining, and Weapon
 * Glitch enforcement.
 */
public final class ServerEvents {
    // How far the scan reaches and how wide the "field of view" cone is (dot-product threshold).
    private static final double SCAN_RANGE =
            com.example.cyberdeck.skill.QuickhackUploads.MAX_TARGET_RANGE;
    private static final double FOV_DOT = 0.5; // ~120 degree cone

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        // Complete a gun reload once its timer elapses (and cancel it if the gun is put away).
        tickReload(player, level);

        // Smart Link target acquisition is independent of the cyberdeck operating system/interface.
        com.example.cyberdeck.weapon.SmartTargeting.tick(player, level);

        // Capability removal closes either interface immediately; a removed deck also releases RAM.
        if (CyberdeckState.hasQuickhackSession(player)
                && !CyberdeckState.hasInstalledCyberdeck(player)) {
            CyberdeckState.deactivate(player);
            com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
            return;
        }
        if (CyberdeckState.hasScanOnlySession(player)
                && !CyberdeckState.hasInstalledEyeImplant(player)) {
            CyberdeckState.deactivate(player);
            return;
        }

        // Uploads are committed server-side and continue after scanner mode is closed. The queue
        // itself still cancels for death, deck removal, target loss, or excessive distance.
        com.example.cyberdeck.skill.QuickhackUploads.tick(player, level);

        if (!CyberdeckState.isScannerActive(player)) {
            return;
        }

        // Outline valid entities within the player's field of view while either scanner is active.
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        AABB scanBox = player.getBoundingBox().inflate(SCAN_RANGE);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, scanBox,
                ServerEvents::isTargetable);
        for (LivingEntity entity : candidates) {
            if (entity == player) {
                continue;
            }
            Vec3 toEntity = entity.position().add(0, entity.getBbHeight() * 0.5, 0).subtract(eye);
            if (toEntity.lengthSqr() < 1.0e-4) {
                continue;
            }
            if (look.dot(toEntity.normalize()) < FOV_DOT) {
                continue;
            }
            // Re-apply a short glowing effect each tick so it stays lit while in view.
            entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, 12, 0, false, false));
        }
    }

    /**
     * Finalizes an in-progress gun reload. While the player still holds the reloading gun and the
     * timer has elapsed, the magazine is topped up from reserve ammo; if the gun is switched away or
     * dropped, the reload is cancelled.
     */
    private static void tickReload(ServerPlayer player, ServerLevel level) {
        com.example.cyberdeck.weapon.ReloadState state =
                com.example.cyberdeck.weapon.ReloadState.get(player);
        if (!state.active()) {
            return;
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof com.example.cyberdeck.weapon.GunItem gun)) {
            // Switched away from the gun mid-reload: cancel it.
            com.example.cyberdeck.weapon.ReloadState.clear(player);
            return;
        }
        if (level.getGameTime() >= state.endTick()) {
            gun.completeReload(player, held);
            com.example.cyberdeck.weapon.ReloadState.clear(player);
        }
    }

    private static boolean isTargetable(LivingEntity entity) {
        return entity.isAlive() && (entity instanceof Enemy || entity instanceof CityNpc);
    }

    /** No hostile AI, vanilla or modded, may select a city civilian as an attack target. */
    @SubscribeEvent
    public void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof Enemy
                && event.getNewAboutToBeSetTarget() instanceof CityNpc) {
            // Do not cancel: cancellation retains the previous target. Replacing with null makes
            // both Mob#setTarget and Brain StartAttacking integrations safely drop the civilian.
            event.setNewAboutToBeSetTarget(null);
        }
    }

    // Timed Weapon Glitch fallback: prevent non-faction ranged mobs from firing projectiles while
    // they recover. FactionEnemy guns are hitscan and enforce their own synchronized state machine.
    @SubscribeEvent
    public void onProjectileSpawn(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Projectile projectile)) {
            return;
        }
        if (projectile.getOwner() instanceof LivingEntity owner && WeaponGlitchData.isGlitched(owner)) {
            event.setCanceled(true);
        }
    }

    // Prevent quickhack items from being dropped (e.g. pressing Q): keep them in the inventory.
    @SubscribeEvent
    public void onItemToss(net.neoforged.neoforge.event.entity.item.ItemTossEvent event) {
        if (QuickhackItems.isQuickhackItem(event.getEntity().getItem().getItem())) {
            event.setCanceled(true);
            event.getPlayer().getInventory().add(event.getEntity().getItem());
        }
    }

    /** Reserve the normal swap-hands key for queue input while quickhacking mode is active. */
    @SubscribeEvent
    public void onSwapHands(LivingSwapItemsEvent.Hands event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CyberdeckState.isActive(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * A player's splash potion of water baits nearby faction enemies toward its ground-impact
     * point so they leave cover to investigate the splash.
     */
    @SubscribeEvent
    public void onProjectileImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        com.example.cyberdeck.combat.WaterBait.onProjectileImpact(event);
    }
}
