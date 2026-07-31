package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.movement.TacticalMovementPacket;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class CyberdeckNetwork {
    private CyberdeckNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Cyberdeck.MODID).versioned("9");
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
                UseHealingConsumablePacket.TYPE,
                UseHealingConsumablePacket.STREAM_CODEC,
                UseHealingConsumablePacket::handle);
        registrar.playToServer(
                TacticalMovementPacket.TYPE,
                TacticalMovementPacket.STREAM_CODEC,
                TacticalMovementPacket::handle);
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
        registrar.playToClient(
                OpenQuicktimeStationPacket.TYPE,
                OpenQuicktimeStationPacket.STREAM_CODEC,
                OpenQuicktimeStationPacket::handle);
        registrar.playToClient(
                DistrictAtmospherePacket.TYPE,
                DistrictAtmospherePacket.STREAM_CODEC,
                DistrictAtmospherePacket::handle);
        registrar.playToServer(
                RequestCityMapPacket.TYPE,
                RequestCityMapPacket.STREAM_CODEC,
                RequestCityMapPacket::handle);
        registrar.playToClient(
                OpenCityMapPacket.TYPE,
                OpenCityMapPacket.STREAM_CODEC,
                OpenCityMapPacket::handle);
        registrar.playToClient(
                SetCityWaypointPacket.TYPE,
                SetCityWaypointPacket.STREAM_CODEC,
                SetCityWaypointPacket::handle);
        registrar.playToClient(
                ClearCityWaypointPacket.TYPE,
                ClearCityWaypointPacket.STREAM_CODEC,
                ClearCityWaypointPacket::handle);
        registrar.playToClient(
                MissionSyncPacket.TYPE,
                MissionSyncPacket.STREAM_CODEC,
                MissionSyncPacket::handle);
        registrar.playToClient(
                OpenMerchantQuestPacket.TYPE,
                OpenMerchantQuestPacket.STREAM_CODEC,
                OpenMerchantQuestPacket::handle);
        registrar.playToServer(
                AcceptMerchantQuestPacket.TYPE,
                AcceptMerchantQuestPacket.STREAM_CODEC,
                AcceptMerchantQuestPacket::handle);
        registrar.playToServer(
                TravelQuicktimePacket.TYPE,
                TravelQuicktimePacket.STREAM_CODEC,
                TravelQuicktimePacket::handle);
    }
}
