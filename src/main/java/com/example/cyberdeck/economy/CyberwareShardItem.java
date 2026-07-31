package com.example.cyberdeck.economy;

import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareCapacity;

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
 * Consumable shard that permanently raises the holder's maximum cyberware capacity by
 * {@link #CAPACITY_PER_SHARD} (server-authoritative, per-player), up to the absolute capacity cap.
 */
public final class CyberwareShardItem extends Item {
    public static final int CAPACITY_PER_SHARD = 10;

    public CyberwareShardItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            int baseAtCap = CyberwareCapacity.absoluteCap() - CyberwareCapacity.baseMaximum(player);
            int headroom = baseAtCap - CyberwareAttachments.getBonusCapacity(player);
            if (headroom <= 0) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        "message.cyberdeck.cyberware_shard.capped").withStyle(ChatFormatting.RED));
                return InteractionResult.SUCCESS;
            }
            int gain = Math.min(CAPACITY_PER_SHARD, headroom);
            CyberwareAttachments.addBonusCapacity(serverPlayer, gain);
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            serverPlayer.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.cyberware_shard.gained",
                    gain, CyberwareAttachments.getBonusCapacity(serverPlayer))
                    .withStyle(ChatFormatting.AQUA));
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.6f, 1.6f);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                TooltipDisplay display, Consumer<Component> adder, TooltipFlag flag) {
        adder.accept(Component.translatable("tooltip.cyberdeck.cyberware_shard", CAPACITY_PER_SHARD)
                .withStyle(ChatFormatting.GRAY));
    }
}
