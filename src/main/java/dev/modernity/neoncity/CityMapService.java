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
        MegacityLayout layout = NeonCityGenerator.fixedLayout();
        return new OpenCityMapPacket(
                true,
                forceOpen,
                layout.seed(),
                NeonCityGenerator.GENERATOR_FINGERPRINT,
                markers(
                        layout,
                        MissionService.activeMarker(player),
                        VendorAnchorData.get(level).anchors(),
                        AmbientGigService.availableOffers(player)));
    }

    static List<OpenCityMapPacket.Marker> markers(MegacityLayout layout) {
        return markers(layout, java.util.Optional.empty());
    }

    static List<OpenCityMapPacket.Marker> markers(
            MegacityLayout layout,
            java.util.Optional<OpenCityMapPacket.Marker> activeMission) {
        return markers(layout, activeMission, List.of());
    }

    static List<OpenCityMapPacket.Marker> markers(
            MegacityLayout layout,
            java.util.Optional<OpenCityMapPacket.Marker> activeMission,
            List<VendorAnchorData.Anchor> vendors) {
        return markers(layout, activeMission, vendors, List.of());
    }

    static List<OpenCityMapPacket.Marker> markers(
            MegacityLayout layout,
            java.util.Optional<OpenCityMapPacket.Marker> activeMission,
            List<VendorAnchorData.Anchor> vendors,
            List<AmbientGigService.DiscoveredGig> gigs) {
        List<OpenCityMapPacket.Marker> markers = new ArrayList<>();
        activeMission.ifPresent(markers::add);
        for (AmbientGigService.DiscoveredGig gig : gigs) {
            MissionService.MissionOffer offer = gig.offer();
            markers.add(new OpenCityMapPacket.Marker(
                    OpenCityMapPacket.MarkerKind.AVAILABLE_GIG,
                    offer.targetX(),
                    offer.targetZ(),
                    offer.targetDistrictOrdinal(),
                    "literal:" + offer.title(),
                    gig.offerId().toString()));
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
        for (VendorAnchorData.Anchor vendor : vendors) {
            markers.add(new OpenCityMapPacket.Marker(
                    vendor.fixer()
                            ? OpenCityMapPacket.MarkerKind.FIXER
                            : OpenCityMapPacket.MarkerKind.MERCHANT,
                    vendor.merchantPos().getX(),
                    vendor.merchantPos().getZ(),
                    vendor.district().ordinal(),
                    "literal:" + vendor.role().displayName()
                            + " // District " + vendor.district().code()));
        }
        return List.copyOf(markers);
    }
}
