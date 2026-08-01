package com.example.cyberdeck;

import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.skill.Skill;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative helper for the cyberdeck interface toggle state.
 *
 * <p>When the interface is toggled on, the player's real hotbar (slots 0-8) is stashed in a
 * durable player attachment and replaced with the skill blocks. Toggling off restores the
 * original hotbar. The snapshot survives saves and death so a crash cannot strand real items.
 */
public final class CyberdeckState {
    private static final String ACTIVE_KEY = "cyberdeck_active";
    private static final int HOTBAR_SIZE = 9;

    private CyberdeckState() {
    }

    /** True when an installed Operating System asset grants quickhack access. */
    public static boolean hasInstalledCyberdeck(ServerPlayer player) {
        return CyberwareEffects.canQuickhack(player);
    }

    public static boolean isActive(ServerPlayer player) {
        return player.getPersistentData().getBoolean(ACTIVE_KEY).orElse(false)
                && hasInstalledCyberdeck(player);
    }

    public static void toggle(ServerPlayer player) {
        if (isActive(player)) {
            deactivate(player);
        } else if (hasInstalledCyberdeck(player)) {
            activate(player);
        }
    }

    /** Sets scanner mode explicitly, used by the key toggle and the accessible command fallback. */
    public static void setActive(ServerPlayer player, boolean active) {
        if (active) {
            if (!isActive(player) && hasInstalledCyberdeck(player)) {
                activate(player);
            }
        } else {
            deactivate(player);
        }
    }

    private static void activate(ServerPlayer player) {
        // Persist the real hotbar before replacing any slot. Saving the player then atomically
        // stores both the scanner items and this recovery snapshot.
        List<ItemStack> saved = new ArrayList<>(HOTBAR_SIZE);
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            saved.add(player.getInventory().getItem(i).copy());
        }
        player.setData(QuickhackAttachments.STASHED_HOTBAR.get(),
                QuickhackHotbar.capture(saved));
        player.getPersistentData().putBoolean(ACTIVE_KEY, true);
        QuickhackAttachments.set(player, true);

        // Fill the hotbar with the skill blocks.
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            Skill skill = Skill.fromSlot(i);
            player.getInventory().setItem(i, skill == null ? ItemStack.EMPTY : skill.stack());
        }
        syncInventory(player);
    }

    public static void deactivate(ServerPlayer player) {
        QuickhackHotbar saved = player.getData(QuickhackAttachments.STASHED_HOTBAR.get());
        boolean markedActive = player.getPersistentData().getBoolean(ACTIVE_KEY).orElse(false);
        if (!markedActive && !saved.present()) {
            return;
        }
        player.getPersistentData().putBoolean(ACTIVE_KEY, false);
        QuickhackAttachments.set(player, false);

        if (saved.present()) {
            for (int i = 0; i < HOTBAR_SIZE; i++) {
                player.getInventory().setItem(i, saved.items().get(i).copy());
            }
        } else {
            // Legacy saves may have an active flag but no durable stash. Remove only synthetic
            // skill items and leave every unrelated slot untouched.
            for (int i = 0; i < HOTBAR_SIZE; i++) {
                Skill skill = Skill.fromSlot(i);
                if (skill != null && player.getInventory().getItem(i).is(skill.item())) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }
        player.setData(QuickhackAttachments.STASHED_HOTBAR.get(), QuickhackHotbar.NONE);
        syncInventory(player);
    }

    /** Restores an interrupted scanner session during login or respawn. */
    public static void recover(ServerPlayer player) {
        deactivate(player);
    }

    private static void syncInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }
}
