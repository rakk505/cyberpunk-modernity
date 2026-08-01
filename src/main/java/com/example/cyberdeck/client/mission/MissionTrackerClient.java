package com.example.cyberdeck.client.mission;

import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.network.MissionSyncPacket;
import com.example.cyberdeck.network.OpenCityMapPacket;
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
            CityMapNavigationClient.Waypoint waypoint = CityMapNavigationClient.waypoint();
            if (waypoint != null
                    && waypoint.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION) {
                CityMapNavigationClient.clearWaypoint();
            }
            return;
        }
        MissionCatalog.MissionType type = MissionCatalog.MissionType.values()[packet.typeOrdinal()];
        MissionService.ContractKind kind = MissionService.ContractKind.values()[packet.kindOrdinal()];
        active = new Snapshot(
                kind, type, packet.title(), packet.objective(), packet.districtOrdinal(),
                packet.targetX(), packet.targetZ(), packet.navigationX(), packet.navigationZ(),
                packet.reward(), packet.streetCred(), packet.deployed());
        CityMapNavigationClient.Waypoint waypoint = CityMapNavigationClient.waypoint();
        if (waypoint == null
                || waypoint.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION
                || waypoint.kind() == OpenCityMapPacket.MarkerKind.AVAILABLE_GIG) {
            CityMapNavigationClient.setMissionWaypoint(
                    packet.navigationX(), packet.navigationZ(),
                    packet.districtOrdinal(), packet.title());
        }
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
            int navigationX,
            int navigationZ,
            int reward,
            int streetCred,
            boolean deployed) {
    }
}
