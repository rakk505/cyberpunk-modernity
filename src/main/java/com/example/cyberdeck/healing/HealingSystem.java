package com.example.cyberdeck.healing;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server-authoritative healing activation and Bounce Back regeneration. */
public final class HealingSystem {
    private HealingSystem() {
    }

    public static boolean use(ServerPlayer player, HealingConsumable consumable) {
        if (consumable == null
                || !player.isAlive()
                || player.isSpectator()) {
            return false;
        }

        long gameTick = player.level().getGameTime();
        HealingState state = HealingState.get(player);
        if (!state.ready(consumable, gameTick)) {
            return false;
        }

        player.heal(consumable.instantHealing());
        HealingState.set(player, state.afterUse(consumable, gameTick));
        return true;
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        long gameTick = player.level().getGameTime();
        HealingState state = HealingState.get(player);
        if (!state.regenerationPulseDue(gameTick)) {
            return;
        }

        if (player.isAlive() && !player.isSpectator()) {
            player.heal(HealingConsumable.BOUNCE_BACK.regenerationPerPulse());
        }
        HealingState.set(player, state.afterRegenerationPulse());
    }
}
