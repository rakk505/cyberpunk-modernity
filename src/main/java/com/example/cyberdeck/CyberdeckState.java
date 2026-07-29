package com.example.cyberdeck;

import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.skill.Skill;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative helper for the cyberdeck interface toggle state.
 *
 * <p>When the interface is toggled on, the player's real hotbar (slots 0-8) is stashed in a
 * transient in-memory map and replaced with the skill blocks. Toggling off restores the
 * original hotbar. The active flag is stored in the player's persistent data so other systems
 * can query it cheaply.
 */
public final class CyberdeckState {
    private static final String ACTIVE_KEY = "cyberdeck_active";
    private static final int HOTBAR_SIZE = 9;

    // Player UUID -> their saved hotbar while the interface is active.
    private static final Map<UUID, ItemStack[]> SAVED_HOTBARS = new HashMap<>();

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
        // Save the current hotbar so we can restore it later.
        ItemStack[] saved = new ItemStack[HOTBAR_SIZE];
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            saved[i] = player.getInventory().getItem(i).copy();
        }
        SAVED_HOTBARS.put(player.getUUID(), saved);
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
        // Release queue reservations immediately. Waiting for the next player tick would allow a
        // head that completes on the toggle tick to execute after quickhacking was switched off.
        com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
        if (!player.getPersistentData().getBoolean(ACTIVE_KEY).orElse(false)) {
            return;
        }
        player.getPersistentData().putBoolean(ACTIVE_KEY, false);
        QuickhackAttachments.set(player, false);

        ItemStack[] saved = SAVED_HOTBARS.remove(player.getUUID());
        if (saved != null) {
            for (int i = 0; i < HOTBAR_SIZE && i < saved.length; i++) {
                player.getInventory().setItem(i, saved[i] == null ? ItemStack.EMPTY : saved[i]);
            }
        } else {
            // No saved state (e.g. after a relog): clear the skill blocks so they are not kept.
            for (int i = 0; i < HOTBAR_SIZE; i++) {
                Skill skill = Skill.fromSlot(i);
                if (skill != null && player.getInventory().getItem(i).is(skill.item())) {
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        }
        syncInventory(player);
    }

    private static void syncInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }
}
