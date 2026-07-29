package com.example.cyberdeck;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Server-side event handling for cyberdeck mode, uploads, reloading, and Weapon Glitch enforcement.
 */
public final class ServerEvents {
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

        // Deactivate if the helmet was removed while active.
        if (player.getPersistentData().getBoolean("cyberdeck_active").orElse(false)
                && !CyberdeckState.isWearingCyberdeck(player)) {
            CyberdeckState.deactivate(player);
            return;
        }

        if (!CyberdeckState.isActive(player)) {
            com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
            return;
        }

        // Uploads are advanced only after mode and helmet validation, preventing a completion on
        // the same tick that quickhacking is deactivated.
        com.example.cyberdeck.skill.QuickhackUploads.tick(player, level);
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
}
