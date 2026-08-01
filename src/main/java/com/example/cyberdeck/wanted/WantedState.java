package com.example.cyberdeck.wanted;

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

/** Persistent, owner-synced crime and pursuit state for one player. */
public record WantedState(int npcKills, int stars, int excisionKills, int districtOrdinal) {
    public static final int NPC_KILLS_TO_TRIGGER = 3;
    public static final int EXCISION_KILLS_TO_ESCALATE = 2;
    public static final WantedState NONE = new WantedState(0, 0, 0, -1);

    public WantedState {
        npcKills = Math.max(0, npcKills);
        stars = stars >= 3 ? 3 : stars > 0 ? 1 : 0;
        excisionKills = Math.max(0, excisionKills);
        districtOrdinal = Math.max(-1, districtOrdinal);
        if (stars == 0) {
            excisionKills = 0;
            districtOrdinal = -1;
        }
    }

    public static final MapCodec<WantedState> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.INT.fieldOf("npc_kills").forGetter(WantedState::npcKills),
                    Codec.INT.fieldOf("stars").forGetter(WantedState::stars),
                    Codec.INT.fieldOf("excision_kills").forGetter(WantedState::excisionKills),
                    Codec.INT.fieldOf("district").forGetter(WantedState::districtOrdinal))
                    .apply(instance, WantedState::new));

    public static final StreamCodec<ByteBuf, WantedState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WantedState::npcKills,
            ByteBufCodecs.VAR_INT, WantedState::stars,
            ByteBufCodecs.VAR_INT, WantedState::excisionKills,
            ByteBufCodecs.VAR_INT, WantedState::districtOrdinal,
            WantedState::new);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<WantedState>> WANTED =
            ATTACHMENT_TYPES.register("wanted", () -> AttachmentType
                    .builder(() -> NONE)
                    .serialize(MAP_CODEC)
                    .sync(STREAM_CODEC)
                    .build());

    public WantedState recordNpcKill(int currentDistrictOrdinal) {
        int updatedKills = npcKills + 1;
        if (stars == 0
                && updatedKills >= NPC_KILLS_TO_TRIGGER
                && currentDistrictOrdinal >= 0) {
            return new WantedState(updatedKills, 1, 0, currentDistrictOrdinal);
        }
        return new WantedState(updatedKills, stars, excisionKills, districtOrdinal);
    }

    public WantedState recordExcisionKill() {
        if (stars == 0) {
            return this;
        }
        int updatedKills = excisionKills + 1;
        int updatedStars = updatedKills >= EXCISION_KILLS_TO_ESCALATE ? 3 : stars;
        return new WantedState(npcKills, updatedStars, updatedKills, districtOrdinal);
    }

    public boolean active() {
        return stars > 0 && districtOrdinal >= 0;
    }

    public static WantedState get(Player player) {
        return player.getData(WANTED.get());
    }

    public static void set(Player player, WantedState state) {
        player.setData(WANTED.get(), state);
    }
}
