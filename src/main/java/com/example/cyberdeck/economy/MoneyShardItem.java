package com.example.cyberdeck.economy;

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
 * Consumable shard that cracks open into a stack of {@link #EMMIES_PER_SHARD} emmies, increasing the
 * player's spendable currency balance (server-authoritative).
 */
public final class MoneyShardItem extends Item {
    /** Legacy/default shard value retained for existing stacks that predate per-stack credit data. */
    public static final int EMMIES_PER_SHARD = 64;

    public MoneyShardItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(int credits) {
        ItemStack stack = new ItemStack(com.example.cyberdeck.CyberdeckItems.MONEY_SHARD.get());
        stack.set(MoneyShardComponents.CREDITS.get(), clampCredits(credits));
        return stack;
    }

    public static int credits(ItemStack stack) {
        Integer value = stack.get(MoneyShardComponents.CREDITS.get());
        return value == null ? EMMIES_PER_SHARD : clampCredits(value);
    }

    private static int clampCredits(int credits) {
        return Math.max(1, Math.min(10_000, credits));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            int credits = credits(held);
            Emmies.give(serverPlayer, credits);
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            serverPlayer.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.money_shard.gained", credits)
                    .withStyle(ChatFormatting.GREEN));
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.5f);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                TooltipDisplay display, Consumer<Component> adder, TooltipFlag flag) {
        adder.accept(Component.translatable("tooltip.cyberdeck.money_shard", credits(stack))
                .withStyle(ChatFormatting.GRAY));
    }
}
