package com.example.cyberdeck;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/**
 * Server-side event handling for the cyberdeck: entity outlining while the interface is active,
 * auto-deactivation when the helmet is removed, and Weapon Glitch enforcement.
 */
public final class ServerEvents {
    // How far the scan reaches and how wide the "field of view" cone is (dot-product threshold).
    private static final double SCAN_RANGE = 48.0;
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

        // Smart Link target acquisition is independent of the cyberdeck helmet/interface.
        com.example.cyberdeck.weapon.SmartTargeting.tick(player, level);

        // Advance any in-progress quickhack upload.
        com.example.cyberdeck.skill.QuickhackUploads.tick(player, level);

        // Deactivate if the helmet was removed while active.
        if (player.getPersistentData().getBoolean("cyberdeck_active").orElse(false)
                && !CyberdeckState.isWearingCyberdeck(player)) {
            com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
            CyberdeckState.deactivate(player);
            return;
        }

        if (!CyberdeckState.isActive(player)) {
            com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
            return;
        }

        // Outline valid entities within the player's field of view.
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
        if (!entity.isAlive()) {
            return false;
        }
        return entity instanceof Mob || entity instanceof Villager || entity instanceof IronGolem;
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
}
