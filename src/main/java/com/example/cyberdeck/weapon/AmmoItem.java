package com.example.cyberdeck.weapon;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Ammunition stack whose NeoForge stack-sensitive limit exceeds vanilla's component cap. */
public final class AmmoItem extends Item {
    public static final int MAX_STACK_SIZE = 500;

    public AmmoItem(Properties properties) {
        super(properties);
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return MAX_STACK_SIZE;
    }
}
