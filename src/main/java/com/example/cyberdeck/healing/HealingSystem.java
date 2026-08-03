package com.example.cyberdeck.healing;

import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareStats;
import com.example.cyberdeck.effect.CyberwareEffects;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server-authoritative healing activation and Bounce Back regeneration. */
public final class HealingSystem {
    private HealingSystem() {
    }

    public static boolean use(ServerPlayer player, HealingConsumable consumable) {
        if (consumable == null
                || !player.isAlive()
                || player.isSpectator()
                || com.example.cyberdeck.effect.CyberwareWeaponEffects
                        .rangedWeaponsBlocked(player)) {
            return false;
        }

        long gameTick = player.level().getGameTime();
        HealingState state = HealingState.get(player);
        if (!state.ready(consumable, gameTick)) {
            return false;
        }

        CyberwareStats stats = CyberwareStats.from(CyberwareAttachments.get(player));
        float multiplier = (float) (1.0 + stats.healthItemEffectiveness());
        player.heal(consumable.instantHealing() * multiplier);
        int cooldown = Math.max(1, (int) Math.round(
                consumable.cooldownTicks() * (1.0 - stats.healthItemCooldownReduction())));
        HealingState.set(player, state.afterUse(consumable, gameTick, cooldown));
        CyberwareEffects.onHealthItemUsed(player);
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
            CyberwareStats stats = CyberwareStats.from(CyberwareAttachments.get(player));
            player.heal(HealingConsumable.BOUNCE_BACK.regenerationPerPulse()
                    * (float) (1.0 + stats.healthItemEffectiveness()));
        }
        HealingState.set(player, state.afterRegenerationPulse());
    }
}
