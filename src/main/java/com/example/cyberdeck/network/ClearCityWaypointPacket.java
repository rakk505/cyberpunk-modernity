package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.map.CityMapNavigationClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Clears a server-owned mission waypoint after its delivery completes. */
public record ClearCityWaypointPacket() implements CustomPacketPayload {
    public static final ClearCityWaypointPacket INSTANCE = new ClearCityWaypointPacket();
    public static final Type<ClearCityWaypointPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "clear_city_waypoint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClearCityWaypointPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClearCityWaypointPacket packet, IPayloadContext context) {
        context.enqueueWork(CityMapNavigationClient::clearWaypoint);
    }
}
