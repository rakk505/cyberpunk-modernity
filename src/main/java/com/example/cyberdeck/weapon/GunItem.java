package com.example.cyberdeck.weapon;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * A firearm. Right-click to fire (hitscan; see {@link GunFiring}). Each gun holds a magazine of
 * {@link GunType#magazineSize()} rounds tracked per-{@link ItemStack} via
 * {@link WeaponComponents#MAGAZINE}. Firing draws from the magazine; when it runs dry the gun
 * automatically begins a {@link GunType#reloadTimeTicks()} reload that pulls fresh rounds from the
 * matching {@link AmmoType} item in the player's inventory. The reload is tracked on the player via
 * {@link ReloadState} (synced to the client for the HUD bar) and finalized by the server tick
 * handler in {@link com.example.cyberdeck.ServerEvents}.
 *
 * <p>The sniper additionally uses a per-shot wind-up ({@link GunType#reloadTicks()}) via the use
 * animation, so it can't be fired rapidly.
 */
public final class GunItem extends Item {
    private final GunType gun;

    public GunItem(Properties properties, GunType gun) {
        super(properties.stacksTo(1));
        this.gun = gun;
    }

    public GunType gun() {
        return gun;
    }

    /** Current rounds loaded in this gun stack (defaults to a full magazine when unset). */
    public int magazine(ItemStack stack) {
        Integer value = stack.get(WeaponComponents.MAGAZINE.get());
        return value == null ? gun.magazineSize() : Math.max(0, Math.min(gun.magazineSize(), value));
    }

    /**
     * Updates the rounds stored in this gun stack. Kept on the item so players and gun-wielding
     * faction enemies share the same persistent, network-synchronized magazine component.
     */
    public void setMagazine(ItemStack stack, int value) {
        stack.set(WeaponComponents.MAGAZINE.get(), Math.max(0, Math.min(gun.magazineSize(), value)));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);

        if (player instanceof ServerPlayer serverPlayer
                && com.example.cyberdeck.effect.CyberwareWeaponEffects
                        .rangedWeaponsBlocked(serverPlayer)) {
            return InteractionResult.FAIL;
        }

        // A reload in progress blocks all firing.
        if (ReloadState.get(player).active()) {
            return InteractionResult.FAIL;
        }

        // Empty magazine: try to reload instead of firing.
        if (magazine(held) <= 0) {
            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
                tryStartReload(serverLevel, serverPlayer, held);
            }
            return InteractionResult.CONSUME;
        }

        // Check this before starting a charged shot. Otherwise sniper-style guns can begin their
        // wind-up during cooldown and bypass the Tech variant's slower firing interval.
        if (player.getCooldowns().isOnCooldown(held)) {
            return InteractionResult.FAIL;
        }

        // Snipers wind up via the use/charge animation and fire in finishUsingItem.
        if (gun.reloadTicks() > 0) {
            player.startUsingItem(hand);
            return InteractionResult.CONSUME;
        }

        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            fireOnce(serverLevel, serverPlayer, held);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (gun.reloadTicks() > 0 && entity instanceof Player player
                && level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer
                && !ReloadState.get(player).active() && magazine(stack) > 0
                && !player.getCooldowns().isOnCooldown(stack)
                && !com.example.cyberdeck.effect.CyberwareWeaponEffects
                        .rangedWeaponsBlocked(serverPlayer)) {
            fireOnce(serverLevel, serverPlayer, stack);
        }
        return stack;
    }

    /** Fires a single shot, spends a round, and auto-reloads when the magazine empties. */
    private void fireOnce(ServerLevel level, ServerPlayer player, ItemStack stack) {
        GunFiring.fire(level, player, gun);
        player.getCooldowns().addCooldown(stack, gun.cooldownTicks());
        int remaining = magazine(stack) - 1;
        setMagazine(stack, remaining);
        if (remaining <= 0) {
            tryStartReload(level, player, stack);
        }
    }

    /** Begins a reload if there is reserve ammo (or creative). Sets client-synced reload state. */
    public void tryStartReload(ServerLevel level, ServerPlayer player, ItemStack stack) {
        if (ReloadState.get(player).active() || magazine(stack) >= gun.magazineSize()) {
            return;
        }
        if (!hasReserveAmmo(player)) {
            notifyNoAmmo(player);
            return;
        }
        long now = level.getGameTime();
        ReloadState.set(player, new ReloadState(now, now + gun.reloadTimeTicks()));
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PISTON_CONTRACT, SoundSource.PLAYERS, 0.7f, 1.2f);
    }

    /** Called by the server tick handler when the reload timer completes: tops up the magazine. */
    public void completeReload(ServerPlayer player, ItemStack stack) {
        int needed = gun.magazineSize() - magazine(stack);
        int loaded = 0;
        if (needed > 0) {
            loaded = player.getAbilities().instabuild
                    ? needed
                    : AmmoItems.consume(player, gun.ammo(), needed);
            setMagazine(stack, magazine(stack) + loaded);
        }
        com.example.cyberdeck.effect.CyberwareWeaponEffects.armMicrogenerator(player, loaded);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.7f, 1.4f);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return gun.reloadTicks() > 0 ? gun.reloadTicks() : 0;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return gun.reloadTicks() > 0 ? ItemUseAnimation.BOW : ItemUseAnimation.NONE;
    }

    private boolean hasReserveAmmo(Player player) {
        if (player.getAbilities().instabuild) {
            return true;
        }
        return AmmoItems.count(player, gun.ammo()) > 0;
    }

    private void notifyNoAmmo(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("message.cyberdeck.no_ammo").withStyle(ChatFormatting.RED), true);
            serverPlayer.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.0f);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                TooltipDisplay display, Consumer<Component> adder, TooltipFlag flag) {
        adder.accept(Component.literal(magazine(stack) + " / " + gun.magazineSize())
                .withStyle(ChatFormatting.GOLD));
        adder.accept(Component.translatable("tooltip.cyberdeck.gun.ammo",
                Component.translatable("item.cyberdeck." + gun.ammo().itemId()))
                .withStyle(ChatFormatting.DARK_AQUA));
        adder.accept(Component.translatable("tooltip.cyberdeck.gun." + gun.baseGun().id())
                .withStyle(ChatFormatting.GRAY));
        if (gun.isTech()) {
            adder.accept(Component.translatable("tooltip.cyberdeck.gun.tech")
                    .withStyle(ChatFormatting.AQUA));
        }
    }
}
