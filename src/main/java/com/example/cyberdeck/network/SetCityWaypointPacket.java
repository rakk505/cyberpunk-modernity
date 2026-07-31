package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.map.CityMapNavigationClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-issued coordinate waypoint used by the map command fallback. */
public record SetCityWaypointPacket(int x, int z) implements CustomPacketPayload {
    public static final Type<SetCityWaypointPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "set_city_waypoint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetCityWaypointPacket> STREAM_CODEC =
            StreamCodec.ofMember(SetCityWaypointPacket::encode, SetCityWaypointPacket::decode);

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(z);
    }

    private static SetCityWaypointPacket decode(RegistryFriendlyByteBuf buffer) {
        return new SetCityWaypointPacket(buffer.readInt(), buffer.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetCityWaypointPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CityMapNavigationClient.setWaypoint(packet.x(), packet.z()));
    }
}
