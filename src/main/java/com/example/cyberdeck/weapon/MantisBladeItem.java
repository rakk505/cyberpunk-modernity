package com.example.cyberdeck.weapon;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.Weapon;

/**
 * Arm-mounted mantis blades.
 *
 * <p>These used to be registered as a {@link GunItem}: right-clicking sprayed seven hitscan
 * "pellets" over fourteen blocks and consumed heavy rifle ammunition, which is not what a pair of
 * blades bolted to someone's forearms does. They are a melee weapon now - swing to attack, no
 * ammunition, no magazine - with the extra reach and heavy per-hit damage that justifies giving up
 * a firearm slot, and a long recovery between swings so they stay a commitment rather than a
 * strictly better sword.</p>
 */
public final class MantisBladeItem extends Item {
    /** Same id vanilla uses for a weapon's reach bonus, so tooltips group it with attack stats. */
    private static final Identifier REACH_MODIFIER =
            Identifier.fromNamespaceAndPath("cyberdeck", "mantis_blade_reach");
    /** Vanilla base attack speed is 4.0; -2.8 lands at 1.2 swings per second. */
    private static final double ATTACK_SPEED_MODIFIER = -2.8;
    private static final double REACH_BONUS = 1.0;

    private final String rig;

    public MantisBladeItem(Properties properties) {
        this(properties, "mantis_blade", 9.0);
    }

    public MantisBladeItem(Properties properties, String rig, double attackDamage) {
        super(properties
                .stacksTo(1)
                .attributes(attributes(attackDamage))
                // No durability: these are chrome, not a tool. Weapon(0) keeps the vanilla
                // sweep/disable-shield handling without ever consuming the item.
                .component(DataComponents.WEAPON, new Weapon(0)));
        this.rig = rig;
    }

    /** Bedrock geometry, animation and UV atlas id used for the first-person render. */
    public String rig() {
        return rig;
    }

    private static ItemAttributeModifiers attributes(double attackDamage) {
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(
                                BASE_ATTACK_DAMAGE_ID, attackDamage,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(
                                BASE_ATTACK_SPEED_ID, ATTACK_SPEED_MODIFIER,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ENTITY_INTERACTION_RANGE,
                        new AttributeModifier(
                                REACH_MODIFIER, REACH_BONUS,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }

    @Override
    public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        super.hurtEnemy(stack, target, attacker);
        attacker.level().playSound(
                attacker instanceof Player player ? player : null,
                target.getX(), target.getY(), target.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP,
                attacker instanceof Player ? SoundSource.PLAYERS : SoundSource.HOSTILE,
                0.9F, 1.15F);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> lines,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        for (Component line : List.of(
                Component.translatable("tooltip.cyberdeck.gun.mantis_blade"))) {
            lines.accept(line.copy().withStyle(ChatFormatting.DARK_AQUA));
        }
    }
}
