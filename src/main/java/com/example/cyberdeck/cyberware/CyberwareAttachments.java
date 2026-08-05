package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.effect.SandevistanState;

import com.mojang.serialization.Codec;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Registers the {@link CyberwareData} attachment used to store a player's installed cyberware.
 * The attachment is persisted to disk, copied across death/dimension change, and synced to the
 * owning client so the HUD/screen and client-side effects (visuals, input) stay in sync.
 */
public final class CyberwareAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<CyberwareData>> CYBERWARE =
            ATTACHMENT_TYPES.register("cyberware", () -> AttachmentType
                    .builder(CyberwareData::new)
                    .serialize(CyberwareData.MAP_CODEC)
                    .sync(CyberwareData.STREAM_CODEC)
                    .copyOnDeath()
                    .build());

    /** Persisted charge prevents relogging or dying from bypassing Sandevistan cooldowns. */
    public static final Supplier<AttachmentType<SandevistanState>> SANDEVISTAN_STATE =
            ATTACHMENT_TYPES.register("sandevistan_state", () -> AttachmentType
                    .serializable(SandevistanState::new)
                    .copyOnDeath()
                    .build());

    /**
     * Client-synced flag that mirrors whether a player's sandevistan is currently active.
     * Not persisted (transient combat state) but synced to every tracking client so the
     * afterimage trail renders for the owner and nearby players alike.
     */
    public static final Supplier<AttachmentType<Boolean>> SANDEVISTAN_ACTIVE =
            ATTACHMENT_TYPES.register("sandevistan_active", () -> AttachmentType
                    .<Boolean>builder(() -> Boolean.FALSE)
                    .sync(ByteBufCodecs.BOOL)
                    .build());

    /**
     * Permanent per-player bonus to maximum cyberware capacity granted by consuming
     * {@code cyberware_shard} items. Persisted to disk and copied across death so the
     * bonus is never lost, and folded into {@link CyberwareCapacity#maximum}.
     */
    public static final Supplier<AttachmentType<Integer>> BONUS_CYBERWARE_CAPACITY =
            ATTACHMENT_TYPES.register("bonus_cyberware_capacity", () -> AttachmentType
                    .builder(() -> 0)
                    .serialize(Codec.INT.fieldOf("bonus_cyberware_capacity"))
                    .sync(ByteBufCodecs.VAR_INT)
                    .copyOnDeath()
                    .build());

    private CyberwareAttachments() {
    }

    /** Convenience accessor for a player's cyberware data (creating a default if absent). */
    public static CyberwareData get(Player player) {
        return player.getData(CYBERWARE.get());
    }

    public static SandevistanState getSandevistanState(Player player) {
        return player.getData(SANDEVISTAN_STATE.get());
    }

    /** Whether this player's sandevistan is active (client-synced; safe on either side). */
    public static boolean isSandevistanActive(Player player) {
        return player.getData(SANDEVISTAN_ACTIVE.get());
    }

    /** Server-only: updates the synced sandevistan-active flag, syncing to tracking clients. */
    public static void setSandevistanActive(Player player, boolean active) {
        if (isSandevistanActive(player) != active) {
            player.setData(SANDEVISTAN_ACTIVE.get(), active);
        }
    }

    public static int getBonusCapacity(Player player) {
        return player.getData(BONUS_CYBERWARE_CAPACITY.get());
    }

    public static void addBonusCapacity(Player player, int delta) {
        player.setData(BONUS_CYBERWARE_CAPACITY.get(), getBonusCapacity(player) + delta);
    }
}
