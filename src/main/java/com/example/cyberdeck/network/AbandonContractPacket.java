package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.AmbientGigService;
import dev.modernity.neoncity.MissionService;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Player-confirmed request to abandon the exact contract displayed in their Journal. */
public record AbandonContractPacket(UUID instanceId) implements CustomPacketPayload {
    public static final Type<AbandonContractPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "abandon_contract"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AbandonContractPacket> STREAM_CODEC =
            StreamCodec.ofMember(AbandonContractPacket::encode, AbandonContractPacket::decode);

    public AbandonContractPacket {
        if (instanceId == null) throw new IllegalArgumentException("Contract id is required");
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(instanceId);
    }

    private static AbandonContractPacket decode(RegistryFriendlyByteBuf buffer) {
        return new AbandonContractPacket(buffer.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AbandonContractPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            boolean matchesActive = MissionService.contractContext(player)
                    .map(active -> active.instanceId().equals(packet.instanceId()))
                    .orElse(false);
            if (matchesActive) {
                MissionService.abandon(player);
            } else {
                // Repair only the bounded Journal; a stale request must not serialize the map.
                AmbientGigService.syncJournal(player);
            }
        });
    }
}
