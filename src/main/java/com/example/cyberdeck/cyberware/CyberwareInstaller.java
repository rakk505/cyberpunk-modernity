package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.effect.CyberwarePassives;
import com.example.cyberdeck.effect.SandevistanMechanics;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative installation, removal, socket and capacity validation. */
public final class CyberwareInstaller {
    private CyberwareInstaller() {
    }

    /** Right-click installation picks an empty unlocked socket; single-socket systems replace. */
    public static boolean install(ServerPlayer player, Cyberware cyberware) {
        if (cyberware == null) {
            return false;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        int socket = data.firstEmptySocket(cyberware.slot(), data.unlockedSockets(cyberware.slot()));
        if (socket < 0 && cyberware.slot().maximumSockets() == 1) {
            socket = 0;
        }
        if (socket < 0) {
            player.sendSystemMessage(Component.translatable("message.cyberdeck.no_free_socket",
                    Component.literal(cyberware.slot().displayName())), true);
            return false;
        }
        return install(player, cyberware, socket);
    }

    public static boolean install(ServerPlayer player, Cyberware cyberware, int socket) {
        if (cyberware == null) {
            return false;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        int unlocked = data.unlockedSockets(cyberware.slot());
        if (socket < 0 || socket >= unlocked) {
            player.sendSystemMessage(Component.translatable("message.cyberdeck.socket_locked"), true);
            return false;
        }
        Cyberware previous = data.get(cyberware.slot(), socket);
        if (previous == cyberware) {
            player.sendSystemMessage(Component.translatable("message.cyberdeck.already_installed",
                    Component.literal(cyberware.fullDisplayName())), true);
            return false;
        }
        if (!CyberwareCapacity.canInstall(player, data, cyberware, socket)) {
            CyberwareData prospective = data.copy();
            prospective.install(cyberware, socket);
            player.sendSystemMessage(Component.translatable("message.cyberdeck.capacity_exceeded",
                    prospective.capacityUsed(), CyberwareCapacity.maximum(player, prospective)), true);
            return false;
        }

        CyberwareData updated = data.copy();
        updated.install(cyberware, socket);
        player.setData(CyberwareAttachments.CYBERWARE.get(), updated);
        if (cyberware.slot() == BodySlot.OPERATING_SYSTEM) {
            SandevistanMechanics.onOperatingSystemChanged(player, cyberware);
        }
        CyberwarePassives.reapply(player);
        if (previous != null) {
            returnItem(player, previous);
        }
        player.sendSystemMessage(Component.translatable("message.cyberdeck.installed",
                Component.literal(cyberware.fullDisplayName())), true);
        return true;
    }

    public static void remove(ServerPlayer player, BodySlot slot) {
        if (slot == null) {
            return;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        for (int socket = 0; socket < slot.maximumSockets(); socket++) {
            if (data.get(slot, socket) != null) {
                remove(player, slot, socket);
                return;
            }
        }
    }

    public static boolean remove(ServerPlayer player, BodySlot slot, int socket) {
        if (slot == null || socket < 0 || socket >= slot.maximumSockets()) {
            return false;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        Cyberware removed = data.get(slot, socket);
        if (removed == null) {
            return false;
        }
        CyberwareData updated = data.copy();
        updated.remove(slot, socket);
        // Chrome Compressor cannot be removed if doing so would strand an over-capacity loadout.
        if (!CyberwareCapacity.isValid(player, updated)) {
            player.sendSystemMessage(Component.translatable("message.cyberdeck.capacity_remove_blocked",
                    updated.capacityUsed(), CyberwareCapacity.maximum(player, updated)), true);
            return false;
        }
        player.setData(CyberwareAttachments.CYBERWARE.get(), updated);
        if (slot == BodySlot.OPERATING_SYSTEM) {
            SandevistanMechanics.onOperatingSystemChanged(player, null);
        }
        CyberwarePassives.reapply(player);
        returnItem(player, removed);
        player.sendSystemMessage(Component.translatable("message.cyberdeck.removed",
                Component.literal(removed.fullDisplayName())), true);
        return true;
    }

    public static boolean unlock(ServerPlayer player, SlotUnlock unlock) {
        if (unlock == null) {
            return false;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        CyberwareData updated = data.copy();
        if (!updated.unlock(unlock)) {
            return false;
        }
        player.setData(CyberwareAttachments.CYBERWARE.get(), updated);
        player.sendSystemMessage(Component.translatable("message.cyberdeck.socket_unlocked",
                Component.literal(unlock.slot().displayName()),
                Component.literal(unlock.displayName())), true);
        return true;
    }

    private static void returnItem(ServerPlayer player, Cyberware cyberware) {
        ItemStack stack = CyberwareItems.item(cyberware).get().getDefaultInstance();
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}
