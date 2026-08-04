package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.NavigationTrailService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Coordinate-free client request for the server-owned active-contract trail. */
public record RequestNavigationTrailPacket() implements CustomPacketPayload {
    public static final RequestNavigationTrailPacket INSTANCE =
            new RequestNavigationTrailPacket();
    public static final Type<RequestNavigationTrailPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "request_navigation_trail"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestNavigationTrailPacket>
            STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestNavigationTrailPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                NavigationTrailService.request(player);
            }
        });
    }
}
