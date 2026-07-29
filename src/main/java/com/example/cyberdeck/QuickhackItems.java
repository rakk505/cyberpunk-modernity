package com.example.cyberdeck;

import com.example.cyberdeck.skill.Skill;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers the custom, non-placeable quickhack items shown in the cyberdeck hotbar. Each active
 * {@link Skill} (all except {@link Skill#STANDBY}) gets its own icon item; being plain {@link Item}s
 * they can never be placed as blocks on the ground.
 */
public final class QuickhackItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cyberdeck.MODID);

    private static final Map<Skill, DeferredItem<Item>> BY_SKILL = new EnumMap<>(Skill.class);

    /** Purely decorative icon used as the quickhacking creative-tab icon. */
    public static final DeferredItem<Item> QUICKHACK_HEAD =
            ITEMS.registerItem("quickhack_head", props -> new Item(props.stacksTo(1)));

    static {
        for (Skill skill : Skill.VALUES) {
            if (skill.itemId() != null) {
                BY_SKILL.put(skill, ITEMS.registerItem(skill.itemId(),
                        props -> new Item(props.stacksTo(1))));
            }
        }
    }

    private QuickhackItems() {
    }

    /** {@return the registered quickhack item for {@code skill}, or {@code null} if it has none}. */
    public static Item item(Skill skill) {
        DeferredItem<Item> holder = BY_SKILL.get(skill);
        return holder == null ? null : holder.get();
    }

    public static boolean isQuickhackItem(Item item) {
        for (DeferredItem<Item> holder : BY_SKILL.values()) {
            if (holder.get() == item) {
                return true;
            }
        }
        return false;
    }
}
