package com.example.cyberdeck;

import com.mojang.serialization.Codec;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Client-synced flag mirroring {@link CyberdeckState}'s "interface active" (quickhacking mode) state
 * so client rendering (e.g. the RAM HUD) can be gated to only show while quickhacking.
 */
public final class QuickhackAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<Boolean>> QUICKHACKING =
            ATTACHMENT_TYPES.register("quickhacking", () -> AttachmentType
                    .builder(() -> Boolean.FALSE)
                    .serialize(Codec.BOOL.fieldOf("quickhacking"))
                    .sync(ByteBufCodecs.BOOL)
                    .build());

    private QuickhackAttachments() {
    }

    public static boolean isQuickhacking(Player player) {
        return player.getData(QUICKHACKING.get());
    }

    public static void set(Player player, boolean value) {
        player.setData(QUICKHACKING.get(), value);
    }
}
