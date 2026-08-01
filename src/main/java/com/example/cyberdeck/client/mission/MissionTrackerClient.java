package com.example.cyberdeck.client.mission;

import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.network.MissionSyncPacket;
import dev.modernity.neoncity.MissionCatalog;
import dev.modernity.neoncity.MissionService;

/** Client-session active mission used by HUD and map presentation. */
public final class MissionTrackerClient {
    private static Snapshot active;

    private MissionTrackerClient() {
    }

    public static void receive(MissionSyncPacket packet) {
        if (!packet.active()) {
            active = null;
            CityMapNavigationClient.clearWaypoint();
            return;
        }
        MissionCatalog.MissionType type = MissionCatalog.MissionType.values()[packet.typeOrdinal()];
        MissionService.ContractKind kind = MissionService.ContractKind.values()[packet.kindOrdinal()];
        active = new Snapshot(
                kind, type, packet.title(), packet.objective(), packet.districtOrdinal(),
                packet.targetX(), packet.targetZ(), packet.reward(), packet.streetCred(),
                packet.deployed());
        CityMapNavigationClient.setMissionWaypoint(
                packet.targetX(), packet.targetZ(), packet.districtOrdinal(), packet.title());
    }

    public static Snapshot active() {
        return active;
    }

    public static void reset() {
        active = null;
    }

    public record Snapshot(
            MissionService.ContractKind kind,
            MissionCatalog.MissionType type,
            String title,
            String objective,
            int districtOrdinal,
            int targetX,
            int targetZ,
            int reward,
            int streetCred,
            boolean deployed) {
    }
}
