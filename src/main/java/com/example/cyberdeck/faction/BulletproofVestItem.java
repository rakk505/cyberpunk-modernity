package com.example.cyberdeck.faction;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** Black tactical vest whose ballistic reduction is applied server-side without enchantment glint. */
public final class BulletproofVestItem extends Item {
    public BulletproofVestItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> adder,
            TooltipFlag flag) {
        adder.accept(Component.translatable("tooltip.cyberdeck.bulletproof_vest")
                .withStyle(ChatFormatting.BLUE));
    }
}
