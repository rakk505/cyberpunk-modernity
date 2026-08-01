package com.example.cyberdeck.lifepath;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Re-opens an unclaimed lifepath choice on each login until the player commits one. */
public final class LifepathEvents {
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && !LifepathState.get(player).selected()) {
            LifepathService.openSelection(player);
        }
    }
}
