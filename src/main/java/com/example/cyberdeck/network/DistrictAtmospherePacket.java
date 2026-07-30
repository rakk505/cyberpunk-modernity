package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.client.ProjectMoonAtmosphereClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-authoritative district identity used by local client atmosphere rendering. */
public record DistrictAtmospherePacket(int districtOrdinal) implements CustomPacketPayload {
    public static final Type<DistrictAtmospherePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "district_atmosphere"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DistrictAtmospherePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    DistrictAtmospherePacket::districtOrdinal,
                    DistrictAtmospherePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DistrictAtmospherePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ProjectMoonAtmosphereClient.setDistrict(
                packet.districtOrdinal()));
    }
}
