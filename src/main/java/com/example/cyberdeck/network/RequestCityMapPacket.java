package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.CityMapService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client request for the current Project Moon city-plan snapshot. */
public record RequestCityMapPacket() implements CustomPacketPayload {
    public static final RequestCityMapPacket INSTANCE = new RequestCityMapPacket();
    public static final Type<RequestCityMapPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "request_city_map"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestCityMapPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestCityMapPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                CityMapService.open(player, false);
            }
        });
    }
}
