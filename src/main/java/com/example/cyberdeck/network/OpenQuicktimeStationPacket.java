package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.screen.QuicktimeStationScreen;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server-owned snapshot used to open a Quicktime station destination picker. */
public record OpenQuicktimeStationPacket(
        long sourcePos,
        int currentDistrictOrdinal,
        List<Destination> destinations) implements CustomPacketPayload {
    public static final int MAX_DESTINATIONS = 25;

    public static final Type<OpenQuicktimeStationPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "open_quicktime_station"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenQuicktimeStationPacket> STREAM_CODEC =
            StreamCodec.ofMember(OpenQuicktimeStationPacket::encode, OpenQuicktimeStationPacket::decode);

    public OpenQuicktimeStationPacket {
        if (destinations == null || destinations.isEmpty()) {
            destinations = List.of();
        } else {
            destinations = List.copyOf(destinations.subList(
                    0, Math.min(destinations.size(), MAX_DESTINATIONS)));
        }
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(sourcePos);
        buffer.writeVarInt(currentDistrictOrdinal);
        buffer.writeVarInt(destinations.size());
        for (Destination destination : destinations) {
            buffer.writeVarInt(destination.districtOrdinal());
            buffer.writeVarInt(destination.distanceBlocks());
        }
    }

    private static OpenQuicktimeStationPacket decode(RegistryFriendlyByteBuf buffer) {
        long sourcePos = buffer.readLong();
        int currentDistrictOrdinal = buffer.readVarInt();
        int destinationCount = buffer.readVarInt();
        if (destinationCount < 0 || destinationCount > MAX_DESTINATIONS) {
            throw new DecoderException("Invalid Quicktime destination count: " + destinationCount);
        }
        List<Destination> destinations = new ArrayList<>(destinationCount);
        for (int index = 0; index < destinationCount; index++) {
            destinations.add(new Destination(buffer.readVarInt(), buffer.readVarInt()));
        }
        return new OpenQuicktimeStationPacket(sourcePos, currentDistrictOrdinal, destinations);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenQuicktimeStationPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> QuicktimeStationScreen.open(packet));
    }

    /** A display-only route. The server re-resolves and validates it when travel is requested. */
    public record Destination(int districtOrdinal, int distanceBlocks) {
        public Destination {
            distanceBlocks = Math.max(0, distanceBlocks);
        }
    }
}
