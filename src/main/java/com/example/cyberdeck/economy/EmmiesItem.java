package com.example.cyberdeck.economy;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * "Eddies" (emmies) — Night City's physical currency. A plain stackable item that merchants accept
 * as payment (via {@code ItemCost}) and that missions pay out. Holding emmies in the inventory is
 * the player's spendable balance.
 */
public final class EmmiesItem extends Item {
    public EmmiesItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
