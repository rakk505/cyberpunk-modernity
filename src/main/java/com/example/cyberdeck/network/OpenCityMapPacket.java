package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Immutable server snapshot used to open the full-screen Project Moon city map. */
public record OpenCityMapPacket(
        boolean available,
        boolean forceOpen,
        long layoutSeed,
        String generatorFingerprint,
        List<Marker> markers) implements CustomPacketPayload {
    public static final int MAX_MARKERS = 64;
    private static final int MAX_LABEL_LENGTH = 128;

    public static final Type<OpenCityMapPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "open_city_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCityMapPacket> STREAM_CODEC =
            StreamCodec.ofMember(OpenCityMapPacket::encode, OpenCityMapPacket::decode);

    public OpenCityMapPacket {
        generatorFingerprint = generatorFingerprint == null ? "" : generatorFingerprint;
        markers = markers == null ? List.of() : List.copyOf(
                markers.subList(0, Math.min(markers.size(), MAX_MARKERS)));
    }

    public static OpenCityMapPacket unavailable(boolean forceOpen) {
        return new OpenCityMapPacket(false, forceOpen, 0L, "", List.of());
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(available);
        buffer.writeBoolean(forceOpen);
        buffer.writeLong(layoutSeed);
        buffer.writeUtf(generatorFingerprint, MAX_LABEL_LENGTH);
        buffer.writeVarInt(markers.size());
        for (Marker marker : markers) {
            buffer.writeVarInt(marker.kind().ordinal());
            buffer.writeInt(marker.x());
            buffer.writeInt(marker.z());
            buffer.writeVarInt(marker.districtOrdinal());
            buffer.writeUtf(marker.labelKey(), MAX_LABEL_LENGTH);
        }
    }

    private static OpenCityMapPacket decode(RegistryFriendlyByteBuf buffer) {
        boolean available = buffer.readBoolean();
        boolean forceOpen = buffer.readBoolean();
        long layoutSeed = buffer.readLong();
        String fingerprint = buffer.readUtf(MAX_LABEL_LENGTH);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_MARKERS) {
            throw new DecoderException("Invalid city map marker count: " + count);
        }
        MarkerKind[] kinds = MarkerKind.values();
        List<Marker> markers = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int kindOrdinal = buffer.readVarInt();
            if (kindOrdinal < 0 || kindOrdinal >= kinds.length) {
                throw new DecoderException("Invalid city map marker kind: " + kindOrdinal);
            }
            markers.add(new Marker(
                    kinds[kindOrdinal],
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readVarInt(),
                    buffer.readUtf(MAX_LABEL_LENGTH)));
        }
        return new OpenCityMapPacket(available, forceOpen, layoutSeed, fingerprint, markers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenCityMapPacket packet, IPayloadContext context) {
        context.enqueueWork(() ->
                com.example.cyberdeck.client.map.CityMapNavigationClient.receive(packet));
    }

    public enum MarkerKind {
        ACTIVE_MISSION,
        TRANSIT
    }

    public record Marker(
            MarkerKind kind,
            int x,
            int z,
            int districtOrdinal,
            String labelKey) {
        public Marker {
            labelKey = labelKey == null ? "" : labelKey;
        }
    }
}
