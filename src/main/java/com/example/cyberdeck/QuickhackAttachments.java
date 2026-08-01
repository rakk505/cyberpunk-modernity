package com.example.cyberdeck;

import com.mojang.serialization.Codec;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Client-synced state for the scanner interface and its quickhack-capable variant. */
public final class QuickhackAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<Boolean>> QUICKHACKING =
            ATTACHMENT_TYPES.register("quickhacking", () -> AttachmentType
                    .builder(() -> Boolean.FALSE)
                    .serialize(Codec.BOOL.fieldOf("quickhacking"))
                    .sync(ByteBufCodecs.BOOL)
                    .build());

    /** True only for an optics-powered scanner session that has no quickhack controls. */
    public static final Supplier<AttachmentType<Boolean>> SCANNING =
            ATTACHMENT_TYPES.register("scanning", () -> AttachmentType
                    .builder(() -> Boolean.FALSE)
                    .serialize(Codec.BOOL.fieldOf("scanning"))
                    .sync(ByteBufCodecs.BOOL)
                    .build());

    /** Server-only durable stash; copied across death so scanner mode cannot destroy real items. */
    public static final Supplier<AttachmentType<QuickhackHotbar>> STASHED_HOTBAR =
            ATTACHMENT_TYPES.register("stashed_hotbar", () -> AttachmentType
                    .builder(() -> QuickhackHotbar.NONE)
                    .serialize(QuickhackHotbar.MAP_CODEC)
                    .copyOnDeath()
                    .build());

    private QuickhackAttachments() {
    }

    public static boolean isQuickhacking(Player player) {
        return player.getData(QUICKHACKING.get());
    }

    public static boolean isScanning(Player player) {
        return player.getData(SCANNING.get());
    }

    public static boolean isScannerActive(Player player) {
        return isQuickhacking(player) || isScanning(player);
    }

    public static void set(Player player, boolean value) {
        player.setData(QUICKHACKING.get(), value);
    }

    public static void setScanning(Player player, boolean value) {
        player.setData(SCANNING.get(), value);
    }
}
