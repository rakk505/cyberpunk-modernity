package com.example.cyberdeck.faction;

import com.example.cyberdeck.WeaponGlitchData;
import com.example.cyberdeck.weapon.ReloadState;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * Server-only ownership lock for hostile quickhacks. A player's slot stays occupied from the first
 * upload tick through effect expiry, preventing two netrunners from racing different effects onto
 * the same player.
 */
public final class HostileQuickhackState {
    private static final Map<UUID, Slot> SLOTS = new HashMap<>();

    private HostileQuickhackState() {
    }

    public static boolean tryReserve(
            ServerPlayer player, FactionEnemy source, EnemyQuickhack quickhack, long uploadEndTick) {
        if (quickhack == EnemyQuickhack.NONE || !player.isAlive()) {
            return false;
        }
        clearExpired(player, player.level().getGameTime());
        Slot occupied = SLOTS.get(player.getUUID());
        if (occupied != null) {
            return occupied.uploading()
                    && occupied.sourceId().equals(source.getUUID())
                    && occupied.quickhack() == quickhack;
        }
        SLOTS.put(player.getUUID(), new Slot(
                source.getUUID(), quickhack, uploadEndTick, true, false));
        return true;
    }

    public static boolean isReservedBy(ServerPlayer player, UUID sourceId, EnemyQuickhack quickhack) {
        clearExpired(player, player.level().getGameTime());
        Slot slot = SLOTS.get(player.getUUID());
        return slot != null && slot.uploading()
                && slot.sourceId().equals(sourceId) && slot.quickhack() == quickhack;
    }

    public static boolean complete(
            ServerPlayer player, FactionEnemy source, EnemyQuickhack quickhack, long now) {
        Slot slot = SLOTS.get(player.getUUID());
        if (slot == null || !slot.uploading()
                || !slot.sourceId().equals(source.getUUID()) || slot.quickhack() != quickhack) {
            return false;
        }
        if (now < slot.endTick()) {
            return false;
        }

        boolean effectOwned = false;
        switch (quickhack) {
            case CRIPPLE_MOVEMENT -> {
                if (!player.hasEffect(MobEffects.SLOWNESS)) {
                    effectOwned = player.addEffect(new MobEffectInstance(
                            MobEffects.SLOWNESS, EnemyQuickhack.EFFECT_TICKS,
                            3, false, true, true));
                }
            }
            case WEAPON_GLITCH -> {
                WeaponGlitchData.glitchFor(player, EnemyQuickhack.EFFECT_TICKS);
                ReloadState.clear(player);
                player.stopUsingItem();
                effectOwned = true;
            }
            case BLIND -> {
                if (!player.hasEffect(MobEffects.BLINDNESS)) {
                    effectOwned = player.addEffect(new MobEffectInstance(
                            MobEffects.BLINDNESS, EnemyQuickhack.EFFECT_TICKS,
                            0, false, true, true));
                }
            }
            case NONE -> {
                return false;
            }
        }
        SLOTS.put(player.getUUID(), new Slot(
                source.getUUID(), quickhack, now + EnemyQuickhack.EFFECT_TICKS,
                false, effectOwned));
        player.sendSystemMessage(Component.translatable(
                "message.cyberdeck.hostile_quickhack." + quickhack.id()), true);
        return true;
    }

    public static void tick(ServerPlayer player) {
        Slot slot = SLOTS.get(player.getUUID());
        if (slot == null) {
            return;
        }
        long now = player.level().getGameTime();
        if (isExpired(slot, now)) {
            SLOTS.remove(player.getUUID());
            return;
        }
        if (slot.uploading()) {
            if (!(player.level() instanceof ServerLevel level)) {
                SLOTS.remove(player.getUUID());
                return;
            }
            var source = level.getEntity(slot.sourceId());
            if (!(source instanceof FactionEnemy netrunner)
                    || !netrunner.isAlive()
                    || netrunner.getEnemyQuickhackTargetId() != player.getId()) {
                SLOTS.remove(player.getUUID());
            }
        }
    }

    public static void cancelReservation(ServerPlayer player, UUID sourceId) {
        Slot slot = SLOTS.get(player.getUUID());
        if (slot != null && slot.uploading() && slot.sourceId().equals(sourceId)) {
            SLOTS.remove(player.getUUID());
        }
    }

    public static void cancelForSource(UUID sourceId) {
        Iterator<Slot> iterator = SLOTS.values().iterator();
        while (iterator.hasNext()) {
            Slot slot = iterator.next();
            if (slot.uploading() && slot.sourceId().equals(sourceId)) {
                iterator.remove();
            }
        }
    }

    public static void clearPlayer(UUID playerId) {
        SLOTS.remove(playerId);
    }

    public static void clearPlayer(ServerPlayer player) {
        Slot slot = SLOTS.remove(player.getUUID());
        if (slot == null || slot.uploading() || !slot.effectOwned()) {
            return;
        }
        switch (slot.quickhack()) {
            case CRIPPLE_MOVEMENT -> removeOwnedEffect(player, MobEffects.SLOWNESS, 3);
            case WEAPON_GLITCH -> WeaponGlitchData.clear(player);
            case BLIND -> removeOwnedEffect(player, MobEffects.BLINDNESS, 0);
            case NONE -> {
            }
        }
    }

    public static void clearAll() {
        SLOTS.clear();
    }

    public static boolean isOccupied(ServerPlayer player) {
        clearExpired(player, player.level().getGameTime());
        return SLOTS.containsKey(player.getUUID());
    }

    public static EnemyQuickhack activeQuickhack(ServerPlayer player) {
        clearExpired(player, player.level().getGameTime());
        Slot slot = SLOTS.get(player.getUUID());
        return slot == null ? EnemyQuickhack.NONE : slot.quickhack();
    }

    private static void clearExpired(ServerPlayer player, long now) {
        Slot slot = SLOTS.get(player.getUUID());
        if (slot != null && (isExpired(slot, now) || !player.isAlive())) {
            SLOTS.remove(player.getUUID());
        }
    }

    private static boolean isExpired(Slot slot, long now) {
        // The entity commits on its upload-end tick, which may run after PlayerTick for that tick.
        return slot.uploading() ? now > slot.endTick() + 20L : now >= slot.endTick();
    }

    private static void removeOwnedEffect(
            ServerPlayer player,
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect,
            int amplifier) {
        MobEffectInstance active = player.getEffect(effect);
        if (active != null && active.getAmplifier() == amplifier
                && active.getDuration() <= EnemyQuickhack.EFFECT_TICKS) {
            player.removeEffect(effect);
        }
    }

    private record Slot(UUID sourceId, EnemyQuickhack quickhack, long endTick,
                        boolean uploading, boolean effectOwned) {
    }
}
