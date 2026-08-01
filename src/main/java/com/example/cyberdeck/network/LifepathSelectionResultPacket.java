package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.screen.LifepathScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server acknowledgement that closes the picker only after a successful, persisted grant. */
public record LifepathSelectionResultPacket(boolean accepted) implements CustomPacketPayload {
    public static final Type<LifepathSelectionResultPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "lifepath_selection_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LifepathSelectionResultPacket>
            STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.BOOL, LifepathSelectionResultPacket::accepted,
                    LifepathSelectionResultPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LifepathSelectionResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> LifepathScreen.handleResult(packet.accepted()));
    }
}
