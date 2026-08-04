package com.example.cyberdeck.weapon;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * A throwable grenade. Right-click lobs a {@link ThrownGrenade} of this item's {@link GrenadeType}
 * along the player's look vector, consuming one from the stack.
 */
public final class GrenadeItem extends Item {
    private final GrenadeType type;

    public GrenadeItem(Properties properties, GrenadeType type) {
        super(properties.stacksTo(16));
        this.type = type;
    }

    public GrenadeType type() {
        return type;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer
                && com.example.cyberdeck.effect.CyberwareWeaponEffects
                        .rangedWeaponsBlocked(serverPlayer)) {
            return InteractionResult.FAIL;
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6f, 0.9f);

        if (level instanceof ServerLevel serverLevel) {
            ThrownGrenade grenade = new ThrownGrenade(serverLevel, player, new ItemStack(this));
            // Lob along the look vector with a light arc.
            grenade.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f, 1.4f, 1.0f);
            serverLevel.addFreshEntity(grenade);
        }

        int cooldown = 10;
        if (player instanceof ServerPlayer serverPlayer) {
            double reduction = com.example.cyberdeck.cyberware.CyberwareStats
                    .from(com.example.cyberdeck.cyberware.CyberwareAttachments.get(serverPlayer))
                    .grenadeCooldownReduction();
            cooldown = Math.max(1, (int) Math.round(cooldown * (1.0 - reduction)));
        }
        player.getCooldowns().addCooldown(held, cooldown);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                TooltipDisplay display, Consumer<Component> adder, TooltipFlag flag) {
        adder.accept(Component.translatable("tooltip.cyberdeck.grenade." + type.id())
                .withStyle(ChatFormatting.GRAY));
    }
}
