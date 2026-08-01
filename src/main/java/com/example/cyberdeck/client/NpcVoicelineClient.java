package com.example.cyberdeck.client;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.network.NpcVoicelinePacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Ephemeral client state for the latest server-issued NPC subtitle. */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class NpcVoicelineClient {
    private static Snapshot current;

    private NpcVoicelineClient() {
    }

    public static void receive(NpcVoicelinePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || packet.line().isBlank()) {
            return;
        }
        current = new Snapshot(
                packet.speaker(), packet.line(),
                minecraft.level.getGameTime() + packet.durationTicks());
    }

    public static Snapshot active() {
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null || minecraft.level == null
                || minecraft.level.getGameTime() >= current.expiresAtTick()) {
            current = null;
        }
        return current;
    }

    public static void reset() {
        current = null;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    public record Snapshot(String speaker, String line, long expiresAtTick) {
    }
}
