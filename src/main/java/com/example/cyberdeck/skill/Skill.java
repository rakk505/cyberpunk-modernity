package com.example.cyberdeck.skill;

import com.example.cyberdeck.QuickhackItems;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The eight cyberdeck skills. The ordinal of each value maps to its hotbar slot (0-7)
 * while the cyberdeck interface is toggled on.
 */
public enum Skill {
    OVERHEAT(DyeColor.ORANGE, "Overheat", 5, 40, "quickhack_overheat"),
    CRIPPLE(DyeColor.GRAY, "Cripple Movement", 4, 30, "quickhack_cripple_cyberware"),
    SHORT_CIRCUIT(DyeColor.BLUE, "Short Circuit", 4, 30, "quickhack_short_circuit"),
    CONTAGION(DyeColor.GREEN, "Contagion", 6, 50, "quickhack_contagion"),
    WEAPON_GLITCH(DyeColor.PURPLE, "Weapon Glitch", 5, 40, "quickhack_weapon_glitch"),
    CYBERPSYCHOSIS(DyeColor.RED, "Cyberpsychosis", 8, 70, "quickhack_cyberpsychosis"),
    DETONATE(DyeColor.YELLOW, "Detonate", 7, 60, "quickhack_detonate"),
    // Eighth slot left as a spacer so the whole hotbar is themed; a neutral dye that can't be placed.
    STANDBY(DyeColor.BLACK, "Standby", 0, 0, null);

    public static final Skill[] VALUES = values();

    private final DyeColor color;
    private final String displayName;
    private final int ramCost;
    private final int uploadTicks;
    private final String itemId;

    Skill(DyeColor color, String displayName, int ramCost, int uploadTicks, String itemId) {
        this.color = color;
        this.displayName = displayName;
        this.ramCost = ramCost;
        this.uploadTicks = uploadTicks;
        this.itemId = itemId;
    }

    /** RAM consumed to activate this quickhack. */
    public int ramCost() {
        return ramCost;
    }

    /** Ticks the quickhack takes to upload onto a target before it takes effect. */
    public int uploadTicks() {
        return uploadTicks;
    }

    /** Registry path of the custom quickhack item, or {@code null} for the STANDBY spacer. */
    public String itemId() {
        return itemId;
    }

    public Item item() {
        // Non-placeable custom quickhack items (STANDBY falls back to a black dye spacer).
        Item custom = QuickhackItems.item(this);
        return custom != null ? custom : net.minecraft.world.item.Items.DYE.pick(color).asItem();
    }

    public String displayName() {
        return displayName;
    }

    public ItemStack stack() {
        ItemStack stack = new ItemStack(item());
        // Rename the block to its ability name (non-italic, so it reads like a real label).
        Component name = this == STANDBY
                ? Component.literal(displayName).withStyle(style -> style.withItalic(false)
                        .withColor(ChatFormatting.DARK_GRAY))
                : Component.literal(displayName).withStyle(style -> style.withItalic(false)
                        .withColor(ChatFormatting.AQUA))
                        .append(Component.literal("  [" + ramCost + " RAM]").withStyle(style ->
                                style.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)));
        stack.set(DataComponents.CUSTOM_NAME, name);
        return stack;
    }

    public static Skill fromSlot(int slot) {
        if (slot < 0 || slot >= VALUES.length) {
            return null;
        }
        return VALUES[slot];
    }
}
