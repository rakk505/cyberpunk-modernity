package com.example.cyberdeck.cyberware;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * A physical, holdable representation of a {@link Cyberware} augmentation. Right-clicking installs
 * it into the player's matching body slot (replacing whatever mutually-exclusive option was there)
 * and consumes the item. Each item maps one-to-one to a {@link Cyberware} value.
 */
public class CyberwareItem extends Item {
    private final Cyberware cyberware;

    public CyberwareItem(Properties properties, Cyberware cyberware) {
        super(properties.stacksTo(1));
        this.cyberware = cyberware;
    }

    public Cyberware cyberware() {
        return cyberware;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            CyberwareInstaller.install(serverPlayer, cyberware);
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6f, 1.4f);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                TooltipDisplay display, Consumer<Component> adder, TooltipFlag flag) {
        adder.accept(Component.translatable("tooltip.cyberdeck.slot",
                Component.literal(cyberware.slot().displayName())).withStyle(ChatFormatting.DARK_AQUA));
        adder.accept(Component.translatable("tooltip.cyberdeck.cyberware." + cyberware.id())
                .withStyle(ChatFormatting.GRAY));
    }
}
