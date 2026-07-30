package com.example.cyberdeck;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Dubious but filling street food that always carries an enchanted sheen. */
public final class SlopItem extends Item {
    public SlopItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
