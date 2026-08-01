package com.example.cyberdeck.lifepath;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.function.Supplier;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

/** Persisted, owner-synced one-time lifepath choice and its server-rolled leg implant. */
public record LifepathState(String lifepathId, String startingLegId) {
    public static final LifepathState UNSELECTED = new LifepathState("", "");

    public static final MapCodec<LifepathState> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.STRING.optionalFieldOf("lifepath", "")
                            .forGetter(LifepathState::lifepathId),
                    Codec.STRING.optionalFieldOf("starting_leg", "")
                            .forGetter(LifepathState::startingLegId))
                    .apply(instance, LifepathState::new));

    public static final StreamCodec<ByteBuf, LifepathState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LifepathState::lifepathId,
            ByteBufCodecs.STRING_UTF8, LifepathState::startingLegId,
            LifepathState::new);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<LifepathState>> LIFEPATH =
            ATTACHMENT_TYPES.register("lifepath", () -> AttachmentType
                    .builder(() -> UNSELECTED)
                    .serialize(MAP_CODEC)
                    .sync(STREAM_CODEC)
                    .copyOnDeath()
                    .build());

    public LifepathState {
        lifepathId = lifepathId == null ? "" : lifepathId;
        startingLegId = startingLegId == null ? "" : startingLegId;
    }

    public boolean selected() {
        return lifepath() != null;
    }

    public @Nullable Lifepath lifepath() {
        return Lifepath.byId(lifepathId);
    }

    public static LifepathState get(Player player) {
        return player.getData(LIFEPATH.get());
    }

    public static void set(Player player, LifepathState state) {
        player.setData(LIFEPATH.get(), state);
    }
}
