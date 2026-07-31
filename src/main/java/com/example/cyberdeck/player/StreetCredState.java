package com.example.cyberdeck.player;

import com.example.cyberdeck.Cyberdeck;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Persistent per-player "Street Cred" progression state. Survives death and relog: the attachment
 * is serialized to disk and copied on death so a player never loses their standing in the city.
 *
 * <p>{@code streetCred} is the player's reputation score. {@code experience} and
 * {@code cyberwareCapacity} are reserved for the future mission/progression system to build on;
 * they are persisted now so existing saves already carry the fields. All values clamp to
 * {@code >= 0}.
 *
 * @param streetCred        player reputation score
 * @param experience        reserved: accumulated progression experience
 * @param cyberwareCapacity reserved: mission-granted cyberware capacity bonus
 */
public record StreetCredState(int streetCred, int experience, int cyberwareCapacity) {
    public static final StreetCredState NONE = new StreetCredState(0, 0, 0);

    /** Clamp every field to {@code >= 0}. */
    public StreetCredState {
        streetCred = Math.max(0, streetCred);
        experience = Math.max(0, experience);
        cyberwareCapacity = Math.max(0, cyberwareCapacity);
    }

    public static final MapCodec<StreetCredState> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance
            .group(
                    Codec.INT.fieldOf("street_cred").forGetter(StreetCredState::streetCred),
                    Codec.INT.fieldOf("experience").forGetter(StreetCredState::experience),
                    Codec.INT.fieldOf("cyberware_capacity")
                            .forGetter(StreetCredState::cyberwareCapacity))
            .apply(instance, StreetCredState::new));

    public static final StreamCodec<ByteBuf, StreetCredState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StreetCredState::streetCred,
            ByteBufCodecs.VAR_INT, StreetCredState::experience,
            ByteBufCodecs.VAR_INT, StreetCredState::cyberwareCapacity,
            StreetCredState::new);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<StreetCredState>> STREET_CRED =
            ATTACHMENT_TYPES.register("street_cred", () -> AttachmentType
                    .builder(() -> NONE)
                    .serialize(MAP_CODEC)
                    .sync(STREAM_CODEC)
                    .copyOnDeath()
                    .build());

    public static StreetCredState get(Player player) {
        return player.getData(STREET_CRED.get());
    }

    public static void set(Player player, StreetCredState state) {
        player.setData(STREET_CRED.get(), state);
    }

    public static int getStreetCred(Player player) {
        return get(player).streetCred();
    }

    public static void setStreetCred(Player player, int value) {
        StreetCredState current = get(player);
        set(player, new StreetCredState(value, current.experience(), current.cyberwareCapacity()));
    }

    public static void addStreetCred(Player player, int delta) {
        setStreetCred(player, getStreetCred(player) + delta);
    }

    public static int getExperience(Player player) {
        return get(player).experience();
    }

    public static void setExperience(Player player, int value) {
        StreetCredState current = get(player);
        set(player, new StreetCredState(current.streetCred(), value, current.cyberwareCapacity()));
    }

    public static void addExperience(Player player, int delta) {
        setExperience(player, getExperience(player) + delta);
    }

    public static int getCyberwareCapacity(Player player) {
        return get(player).cyberwareCapacity();
    }

    public static void setCyberwareCapacity(Player player, int value) {
        StreetCredState current = get(player);
        set(player, new StreetCredState(current.streetCred(), current.experience(), value));
    }

    public static void addCyberwareCapacity(Player player, int delta) {
        setCyberwareCapacity(player, getCyberwareCapacity(player) + delta);
    }
}
