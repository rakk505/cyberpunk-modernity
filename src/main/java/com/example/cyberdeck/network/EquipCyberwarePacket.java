package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareInstaller;
import com.example.cyberdeck.cyberware.CyberwareItem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server request from the cyberware screen to install a specific cyberware. The server
 * verifies the player is holding (or has in inventory) a matching cyberware item and consumes it.
 *
 * @param cyberwareId the stable {@link Cyberware} id to install
 */
public record EquipCyberwarePacket(String cyberwareId) implements CustomPacketPayload {
    public static final Type<EquipCyberwarePacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "equip_cyberware"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EquipCyberwarePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, EquipCyberwarePacket::cyberwareId,
                    EquipCyberwarePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EquipCyberwarePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Cyberware cyberware = Cyberware.byId(packet.cyberwareId());
            if (cyberware == null) {
                return;
            }
            // Require and consume a matching item from the player's inventory (anti-cheat / balance).
            int found = findMatchingItemSlot(player, cyberware);
            if (found < 0) {
                return;
            }
            ItemStack stack = player.getInventory().getItem(found);
            CyberwareInstaller.install(player, cyberware);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        });
    }

    private static int findMatchingItemSlot(ServerPlayer player, Cyberware cyberware) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() instanceof CyberwareItem item && item.cyberware() == cyberware) {
                return i;
            }
        }
        return -1;
    }
}
