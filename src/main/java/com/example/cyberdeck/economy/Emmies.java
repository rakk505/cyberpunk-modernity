package com.example.cyberdeck.economy;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared helpers for the emerald-backed emmies currency. */
public final class Emmies {
    private Emmies() {
    }

    public static Item item() {
        return Items.EMERALD;
    }

    /** Returns the player's spendable emmie balance. Legacy stacks are intentionally excluded. */
    public static int count(Player player) {
        long total = 0L;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.is(Items.EMERALD)) {
                total += stack.getCount();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /** Gives {@code amount} emmies as emerald stacks, dropping any overflow. */
    public static void give(ServerPlayer player, int amount) {
        int remaining = Math.max(0, amount);
        while (remaining > 0) {
            ItemStack payment = new ItemStack(
                    Items.EMERALD, Math.min(Items.EMERALD.getDefaultMaxStackSize(), remaining));
            remaining -= payment.getCount();
            if (!player.addItem(payment) && !payment.isEmpty()) {
                ItemEntity dropped = player.drop(payment, false);
                if (dropped != null) {
                    dropped.setTarget(player.getUUID());
                }
            }
        }
    }

    /** Removes and then converts a legacy stack so both forms can never be spent. */
    static void migrateLegacyStack(ServerPlayer player, ItemStack legacyStack) {
        if (legacyStack.isEmpty()) {
            return;
        }
        int amount = legacyStack.getCount();
        legacyStack.setCount(0);
        give(player, amount);
    }
}
