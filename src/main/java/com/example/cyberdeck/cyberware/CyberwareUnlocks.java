package com.example.cyberdeck.cyberware;

import net.minecraft.server.level.ServerPlayer;

/** Bridge used by quest/perk integrations to grant the three optional sockets. */
public final class CyberwareUnlocks {
    private CyberwareUnlocks() {
    }

    /**
     * Imports canonical persistent progress booleans into the synced cyberware attachment.
     * Quest/perk code may either call {@link CyberwareInstaller#unlock} directly or set the key
     * exposed by {@link SlotUnlock#progressTag()} to true on the player's persistent data.
     */
    public static void syncProgress(ServerPlayer player) {
        CyberwareData data = CyberwareAttachments.get(player);
        CyberwareData updated = null;
        for (SlotUnlock unlock : SlotUnlock.VALUES) {
            if (player.getPersistentData().getBoolean(unlock.progressTag()).orElse(false)
                    && !data.isUnlocked(unlock)) {
                if (updated == null) {
                    updated = data.copy();
                }
                updated.unlock(unlock);
            }
        }
        if (updated != null) {
            player.setData(CyberwareAttachments.CYBERWARE.get(), updated);
        }
    }
}
