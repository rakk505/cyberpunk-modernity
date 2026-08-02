package com.example.cyberdeck.economy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Compatibility carrier for currency created before emmies became vanilla emeralds. It is not
 * accepted as payment and converts itself one-for-one on the server when held by a player.
 */
public final class EmmiesItem extends Item {
    public EmmiesItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(
            ItemStack itemStack,
            ServerLevel level,
            Entity owner,
            @Nullable EquipmentSlot slot) {
        if (owner instanceof ServerPlayer player) {
            Emmies.migrateLegacyStack(player, itemStack);
        }
    }
}
