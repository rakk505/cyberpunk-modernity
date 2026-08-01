package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.CyberdeckState;
import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.effect.CyberwarePassives;
import com.example.cyberdeck.effect.SandevistanMechanics;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

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
        reconcileScannerAccess(player, updated);
        CyberwarePassives.reapply(player);
        if (previous != null) {
            returnItem(player, previous);
        }
        player.sendSystemMessage(Component.translatable("message.cyberdeck.installed",
                Component.literal(cyberware.fullDisplayName())), true);
        return true;
    }

    /**
     * Atomically installs a server-granted loadout without consuming cyberware items. Every socket
     * is preflighted on a copy before player data changes; existing exact variants are accepted,
     * while conflicting occupied sockets reject the whole grant. Starter grants permanently cover
     * only the capacity shortfall required to keep the committed loadout valid.
     */
    public static boolean installGrantedLoadout(ServerPlayer player, List<Cyberware> cyberware) {
        if (cyberware == null || cyberware.isEmpty()) {
            return false;
        }
        CyberwareData original = CyberwareAttachments.get(player);
        CyberwareData updated = original.copy();
        CyberwareData starterOnly = new CyberwareData();

        for (Cyberware implant : cyberware) {
            if (implant == null) {
                return false;
            }
            if (updated.hasExact(implant)) {
                installStarterPreview(starterOnly, implant);
                continue;
            }
            int socket = updated.firstEmptySocket(
                    implant.slot(), updated.unlockedSockets(implant.slot()));
            if (socket < 0) {
                player.sendSystemMessage(Component.translatable(
                        "message.cyberdeck.lifepath.socket_conflict",
                        Component.literal(implant.slot().displayName())), true);
                return false;
            }
            updated.install(implant, socket);
            installStarterPreview(starterOnly, implant);
        }

        int currentMaximum = CyberwareCapacity.maximum(player, updated);
        int starterMaximum = CyberwareCapacity.maximum(player, starterOnly);
        int allowedStarterBonus = Math.max(0, starterOnly.capacityUsed() - starterMaximum);
        int shortfall = Math.max(0, updated.capacityUsed() - currentMaximum);
        if (updated.capacityUsed() > CyberwareCapacity.absoluteCap()) {
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.capacity_exceeded",
                    updated.capacityUsed(), CyberwareCapacity.absoluteCap()), true);
            return false;
        }
        if (shortfall > allowedStarterBonus) {
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.lifepath.capacity_conflict",
                    updated.capacityUsed(), currentMaximum + allowedStarterBonus), true);
            return false;
        }

        Cyberware oldOperatingSystem = original.get(BodySlot.OPERATING_SYSTEM);
        Cyberware newOperatingSystem = updated.get(BodySlot.OPERATING_SYSTEM);
        if (shortfall > 0) {
            CyberwareAttachments.addBonusCapacity(player, shortfall);
        }
        player.setData(CyberwareAttachments.CYBERWARE.get(), updated);
        if (oldOperatingSystem != newOperatingSystem) {
            SandevistanMechanics.onOperatingSystemChanged(player, newOperatingSystem);
        }
        reconcileScannerAccess(player, updated);
        CyberwarePassives.reapply(player);
        return true;
    }

    private static void installStarterPreview(CyberwareData preview, Cyberware implant) {
        if (preview.hasExact(implant)) {
            return;
        }
        int socket = preview.firstEmptySocket(
                implant.slot(), preview.unlockedSockets(implant.slot()));
        if (socket < 0) {
            throw new IllegalArgumentException(
                    "Granted loadout contains conflicting " + implant.slot() + " cyberware");
        }
        preview.install(implant, socket);
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
        reconcileScannerAccess(player, updated);
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

    /** Replacing/removing the active deck must restore the hotbar and cancel queued uploads now. */
    private static void reconcileScannerAccess(ServerPlayer player, CyberwareData data) {
        if (CyberdeckState.hasQuickhackSession(player) && !CyberwareEffects.canQuickhack(data)) {
            CyberdeckState.deactivate(player);
            com.example.cyberdeck.skill.QuickhackUploads.cancel(player);
        } else if (CyberdeckState.hasScanOnlySession(player) && !CyberwareEffects.canScan(data)) {
            CyberdeckState.deactivate(player);
        }
    }
}
