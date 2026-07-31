package com.example.cyberdeck.economy;

import com.example.cyberdeck.CyberdeckItems;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Shared helpers for the item-based emmies currency. */
public final class Emmies {
    private Emmies() {
    }

    public static Item item() {
        return CyberdeckItems.EMMIES.get();
    }

    /** Gives {@code amount} emmies to the player as item stacks, dropping any overflow. */
    public static void give(ServerPlayer player, int amount) {
        int remaining = Math.max(0, amount);
        while (remaining > 0) {
            ItemStack payment = new ItemStack(item(), Math.min(64, remaining));
            remaining -= payment.getCount();
            if (!player.addItem(payment) && !payment.isEmpty()) {
                player.drop(payment, false);
            }
        }
    }
}
