package com.example.cyberdeck.effect;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;

/** Tier-aware Optical Camo visibility reduction represented by invisibility + aggro immunity. */
public final class OpticalCamo {
    private OpticalCamo() {
    }

    public static void toggle(ServerPlayer player) {
        if (ActiveAbilities.isOpticalCamoActive(player)) {
            deactivate(player);
        } else {
            activate(player);
        }
    }

    public static void activate(ServerPlayer player) {
        com.example.cyberdeck.cyberware.Cyberware camo =
                CyberwareEffects.findFlag(player, "optical_camo");
        if (camo == null || ActiveAbilities.onCooldown(player, "optical_camo")) {
            player.sendSystemMessage(Component.translatable("message.cyberdeck.camo_recharging"), true);
            return;
        }
        int duration = Math.max(1, (int) Math.round(camo.value("duration_seconds") * 20));
        ActiveAbilities.opticalCamo.put(player.getUUID(), duration);
        ActiveAbilities.setCooldown(player, "optical_camo",
                CyberwareEffects.cooldownTicks(player, camo, "cooldown_seconds"));
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7f, 1.6f);
        player.sendSystemMessage(Component.translatable("message.cyberdeck.camo_on"), true);
    }

    public static void deactivate(ServerPlayer player) {
        if (ActiveAbilities.opticalCamo.remove(player.getUUID()) != null) {
            player.removeEffect(MobEffects.INVISIBILITY);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 0.6f, 0.8f);
            player.sendSystemMessage(Component.translatable("message.cyberdeck.camo_off"), true);
        }
    }
}
