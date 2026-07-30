package dev.modernity.neoncity;

import com.example.cyberdeck.network.OpenCityMapPacket;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server authority for city-map availability and future mission/transit markers. */
public final class CityMapService {
    private static final List<MissionLead> MISSION_LEADS = List.of(
            new MissionLead(District.A_CORP, 132, -96,
                    "screen.cyberdeck.city_map.mission.corporate"),
            new MissionLead(District.S_CORP, -184, 116,
                    "screen.cyberdeck.city_map.mission.agriculture"),
            new MissionLead(District.U_CORP, 208, 64,
                    "screen.cyberdeck.city_map.mission.harbor"),
            new MissionLead(District.V_CORP, -148, -132,
                    "screen.cyberdeck.city_map.mission.canal"),
            new MissionLead(District.X_CORP, 176, -152,
                    "screen.cyberdeck.city_map.mission.extraction"),
            new MissionLead(District.Z_CORP, -120, 176,
                    "screen.cyberdeck.city_map.mission.crossroads"));

    private CityMapService() {
    }

    public static void open(ServerPlayer player, boolean forceOpen) {
        ServerLevel overworld = player.level().getServer().overworld();
        // The immutable city plan remains safe to inspect when a saved generator ledger is
        // paused for migration; map navigation never loads or modifies chunks.
        if (player.level() != overworld || !NeonCityGenerator.isMegacityWorld(overworld)) {
            PacketDistributor.sendToPlayer(player, OpenCityMapPacket.unavailable(forceOpen));
            return;
        }
        PacketDistributor.sendToPlayer(player, snapshot(overworld, forceOpen));
    }

    static OpenCityMapPacket snapshot(ServerLevel level, boolean forceOpen) {
        MegacityLayout layout = MegacityLayout.create(level.getSeed());
        return new OpenCityMapPacket(
                true,
                forceOpen,
                layout.seed(),
                NeonCityGenerator.GENERATOR_FINGERPRINT,
                markers(layout));
    }

    static List<OpenCityMapPacket.Marker> markers(MegacityLayout layout) {
        List<OpenCityMapPacket.Marker> markers = new ArrayList<>();
        for (MissionLead mission : MISSION_LEADS) {
            MegacityLayout.Node node = layout.node(mission.district());
            markers.add(new OpenCityMapPacket.Marker(
                    OpenCityMapPacket.MarkerKind.MISSION_LEAD,
                    node.x() + mission.offsetX(),
                    node.z() + mission.offsetZ(),
                    mission.district().ordinal(),
                    mission.labelKey()));
        }
        for (District district : District.values()) {
            MegacityLayout.Node node = layout.node(district);
            markers.add(new OpenCityMapPacket.Marker(
                    OpenCityMapPacket.MarkerKind.TRANSIT,
                    node.x(),
                    node.z(),
                    district.ordinal(),
                    "screen.cyberdeck.city_map.transit"));
        }
        return List.copyOf(markers);
    }

    private record MissionLead(
            District district,
            int offsetX,
            int offsetZ,
            String labelKey) {
    }
}
