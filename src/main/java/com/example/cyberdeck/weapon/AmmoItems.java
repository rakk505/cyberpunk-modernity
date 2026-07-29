package com.example.cyberdeck.weapon;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers the three ammunition items and provides inventory helpers for counting and consuming
 * them. Ammo is plain stackable {@link Item}s; guns look them up by {@link AmmoType}.
 */
public final class AmmoItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cyberdeck.MODID);

    private static final Map<AmmoType, DeferredItem<Item>> BY_TYPE = new EnumMap<>(AmmoType.class);

    static {
        for (AmmoType type : AmmoType.values()) {
            BY_TYPE.put(type, ITEMS.registerItem(type.itemId(), Item::new));
        }
    }

    private AmmoItems() {
    }

    public static DeferredItem<Item> item(AmmoType type) {
        return BY_TYPE.get(type);
    }

    /** {@return total number of matching ammo rounds in the player's inventory}. */
    public static int count(Player player, AmmoType type) {
        Item ammo = BY_TYPE.get(type).get();
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(ammo)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Removes up to {@code amount} rounds of the given type. {@return the number actually removed}. */
    public static int consume(Player player, AmmoType type, int amount) {
        Item ammo = BY_TYPE.get(type).get();
        Inventory inv = player.getInventory();
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(ammo)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        return amount - remaining;
    }
}
