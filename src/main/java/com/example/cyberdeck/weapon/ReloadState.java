package com.example.cyberdeck.weapon;

import com.example.cyberdeck.Cyberdeck;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Transient, client-synced reload progress for the player's currently reloading gun. {@code total}
 * is 0 when no reload is in progress; otherwise the reload finishes at {@code endTick} and the HUD
 * fills a bar from {@code startTick} to {@code endTick}.
 *
 * @param startTick server game time the reload began
 * @param endTick   server game time the reload completes
 */
public record ReloadState(long startTick, long endTick) {
    public static final ReloadState NONE = new ReloadState(0L, 0L);

    public static final StreamCodec<ByteBuf, ReloadState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ReloadState::startTick,
            ByteBufCodecs.VAR_LONG, ReloadState::endTick,
            ReloadState::new);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<ReloadState>> RELOAD =
            ATTACHMENT_TYPES.register("reload", () -> AttachmentType
                    .builder(() -> NONE)
                    .sync(STREAM_CODEC)
                    .build());

    public boolean active() {
        return endTick > startTick;
    }

    public static ReloadState get(Player player) {
        return player.getData(RELOAD.get());
    }

    public static void set(Player player, ReloadState state) {
        player.setData(RELOAD.get(), state);
    }

    public static void clear(Player player) {
        player.setData(RELOAD.get(), NONE);
    }
}
