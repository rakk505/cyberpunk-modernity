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
 * Transient, client-synced Smart Link acquisition state. The server alone chooses the target;
 * clients receive this state only so the HUD can display acquisition progress and lock status.
 *
 * @param targetId entity id of the mob being acquired, or -1 when idle
 * @param startTick server game time acquisition started
 * @param endTick server game time at which the target becomes locked
 */
public record SmartLockState(int targetId, long startTick, long endTick) {
    public static final SmartLockState NONE = new SmartLockState(-1, 0L, 0L);

    public static final StreamCodec<ByteBuf, SmartLockState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SmartLockState::targetId,
            ByteBufCodecs.VAR_LONG, SmartLockState::startTick,
            ByteBufCodecs.VAR_LONG, SmartLockState::endTick,
            SmartLockState::new);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<SmartLockState>> SMART_LOCK =
            ATTACHMENT_TYPES.register("smart_lock", () -> AttachmentType
                    .builder(() -> NONE)
                    .sync(STREAM_CODEC)
                    .build());

    public boolean acquiring() {
        return targetId >= 0 && endTick > startTick;
    }

    public boolean locked(long gameTime) {
        return acquiring() && gameTime >= endTick;
    }

    public float progress(long gameTime) {
        if (!acquiring()) {
            return 0.0F;
        }
        long duration = endTick - startTick;
        return net.minecraft.util.Mth.clamp(
                (gameTime - startTick) / (float) duration, 0.0F, 1.0F);
    }

    public static SmartLockState get(Player player) {
        return player.getData(SMART_LOCK.get());
    }

    public static void set(Player player, SmartLockState state) {
        player.setData(SMART_LOCK.get(), state);
    }

    public static void clear(Player player) {
        if (get(player).acquiring()) {
            set(player, NONE);
        }
    }
}
