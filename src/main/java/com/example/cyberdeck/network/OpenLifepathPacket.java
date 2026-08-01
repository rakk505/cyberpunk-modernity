package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.screen.LifepathScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server request to show the one-time lifepath picker. */
public record OpenLifepathPacket() implements CustomPacketPayload {
    public static final OpenLifepathPacket INSTANCE = new OpenLifepathPacket();
    public static final Type<OpenLifepathPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "open_lifepath"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenLifepathPacket> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenLifepathPacket packet, IPayloadContext context) {
        context.enqueueWork(LifepathScreen::open);
    }
}
