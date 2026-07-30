package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.healing.HealingConsumable;
import com.example.cyberdeck.healing.HealingSystem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client request to use the selected infinite healing consumable. */
public record UseHealingConsumablePacket(int consumableId) implements CustomPacketPayload {
    public static final Type<UseHealingConsumablePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "use_healing_consumable"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UseHealingConsumablePacket>
            STREAM_CODEC = StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    UseHealingConsumablePacket::consumableId,
                    UseHealingConsumablePacket::new);

    public UseHealingConsumablePacket(HealingConsumable consumable) {
        this(consumable.networkId());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UseHealingConsumablePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            HealingConsumable.fromNetworkId(packet.consumableId())
                    .ifPresent(consumable -> HealingSystem.use(player, consumable));
        });
    }
}
