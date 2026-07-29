package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.effect.CyberwarePassives;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side entry point for installing / removing cyberware. Centralizes the mutual-exclusion
 * rule (one cyberware per body slot) and keeps derived passive effects (attribute modifiers,
 * bonus armor, etc.) in sync whenever the loadout changes.
 */
public final class CyberwareInstaller {
    private CyberwareInstaller() {
    }

    public static void install(ServerPlayer player, Cyberware cyberware) {
        if (cyberware == null) {
            return;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        if (data.get(cyberware.slot()) == cyberware) {
            player.sendSystemMessage(
                    Component.translatable("message.cyberdeck.already_installed",
                            Component.literal(cyberware.displayName())), true);
            return;
        }
        // Installing replaces any mutually-exclusive option already in the slot.
        data.install(cyberware);
        player.setData(CyberwareAttachments.CYBERWARE.get(), data);
        CyberwarePassives.reapply(player);
        player.sendSystemMessage(
                Component.translatable("message.cyberdeck.installed",
                        Component.literal(cyberware.displayName())), true);
    }

    public static void remove(ServerPlayer player, BodySlot slot) {
        if (slot == null) {
            return;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        Cyberware removed = data.get(slot);
        if (removed == null) {
            return;
        }
        data.remove(slot);
        player.setData(CyberwareAttachments.CYBERWARE.get(), data);
        CyberwarePassives.reapply(player);
        player.sendSystemMessage(
                Component.translatable("message.cyberdeck.removed",
                        Component.literal(removed.displayName())), true);
    }
}
