package dev.modernity.neoncity;

import com.example.cyberdeck.network.OpenCityMapPacket;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server authority for city-map availability, active missions, and transit markers. */
public final class CityMapService {
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
        PacketDistributor.sendToPlayer(player, snapshot(overworld, player, forceOpen));
    }

    static OpenCityMapPacket snapshot(
            ServerLevel level, ServerPlayer player, boolean forceOpen) {
        MegacityLayout layout = MegacityLayout.create(level.getSeed());
        return new OpenCityMapPacket(
                true,
                forceOpen,
                layout.seed(),
                NeonCityGenerator.GENERATOR_FINGERPRINT,
                markers(layout, MissionService.activeMarker(player)));
    }

    static List<OpenCityMapPacket.Marker> markers(MegacityLayout layout) {
        return markers(layout, java.util.Optional.empty());
    }

    static List<OpenCityMapPacket.Marker> markers(
            MegacityLayout layout,
            java.util.Optional<OpenCityMapPacket.Marker> activeMission) {
        List<OpenCityMapPacket.Marker> markers = new ArrayList<>();
        activeMission.ifPresent(markers::add);
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
}
