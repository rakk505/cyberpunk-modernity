package com.example.cyberdeck.effect;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffects;

/** Optical Camo: toggle up to 3 minutes of invisibility + hostile aggro immunity. */
public final class OpticalCamo {
    public static final int MAX_TICKS = 3 * 60 * 20; // 3 minutes

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
        ActiveAbilities.opticalCamo.put(player.getUUID(), MAX_TICKS);
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
