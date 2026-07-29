package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class CyberdeckNetwork {
    private CyberdeckNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Cyberdeck.MODID).versioned("2");
        registrar.playToServer(
                ToggleInterfacePacket.TYPE,
                ToggleInterfacePacket.STREAM_CODEC,
                ToggleInterfacePacket::handle);
        registrar.playToServer(
                ActivateSkillPacket.TYPE,
                ActivateSkillPacket.STREAM_CODEC,
                ActivateSkillPacket::handle);
        registrar.playToServer(
                CyberwareActionPacket.TYPE,
                CyberwareActionPacket.STREAM_CODEC,
                CyberwareActionPacket::handle);
        registrar.playToServer(
                ReloadPacket.TYPE,
                ReloadPacket.STREAM_CODEC,
                ReloadPacket::handle);
        registrar.playToServer(
                EquipCyberwarePacket.TYPE,
                EquipCyberwarePacket.STREAM_CODEC,
                EquipCyberwarePacket::handle);
        registrar.playToServer(
                RemoveCyberwarePacket.TYPE,
                RemoveCyberwarePacket.STREAM_CODEC,
                RemoveCyberwarePacket::handle);
        registrar.playToClient(
                QuickhackUploadPacket.TYPE,
                QuickhackUploadPacket.STREAM_CODEC,
                QuickhackUploadPacket::handle);
    }
}
