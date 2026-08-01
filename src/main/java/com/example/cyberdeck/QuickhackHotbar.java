package com.example.cyberdeck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Durable snapshot of the nine real hotbar slots hidden while the scanner is active. */
public record QuickhackHotbar(boolean present, List<ItemStack> items) {
    public static final int SIZE = 9;
    public static final QuickhackHotbar NONE = new QuickhackHotbar(false, List.of());

    public static final MapCodec<QuickhackHotbar> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.BOOL.optionalFieldOf("present", false)
                            .forGetter(QuickhackHotbar::present),
                    ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("items", List.of())
                            .forGetter(QuickhackHotbar::items))
                    .apply(instance, QuickhackHotbar::new));

    public QuickhackHotbar {
        if (!present) {
            items = List.of();
        } else {
            List<ItemStack> normalized = new ArrayList<>(SIZE);
            for (int slot = 0; slot < SIZE; slot++) {
                ItemStack stack = slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
                normalized.add(stack == null ? ItemStack.EMPTY : stack.copy());
            }
            items = List.copyOf(normalized);
        }
    }

    public static QuickhackHotbar capture(List<ItemStack> hotbar) {
        return new QuickhackHotbar(true, hotbar);
    }
}
