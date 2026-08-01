package com.example.cyberdeck.client.mission;

import com.example.cyberdeck.network.AbandonContractPacket;
import com.example.cyberdeck.network.GigJournalPacket;
import com.example.cyberdeck.network.OpenCityMapPacket;
import com.example.cyberdeck.network.RequestGigJournalPacket;
import java.util.List;
import java.util.UUID;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Client-session cache for the accepted journal and current district's unaccepted offers. */
public final class GigJournalClient {
    private static List<GigJournalPacket.Contract> contracts = List.of();
    private static List<GigJournalPacket.AvailableGig> availableGigs = List.of();

    private GigJournalClient() {
    }

    public static void receive(GigJournalPacket packet) {
        contracts = packet.contracts();
        availableGigs = packet.availableGigs();
    }

    public static void requestRefresh() {
        ClientPacketDistributor.sendToServer(RequestGigJournalPacket.INSTANCE);
    }

    public static void requestAbandon(UUID instanceId) {
        ClientPacketDistributor.sendToServer(new AbandonContractPacket(instanceId));
        ClientPacketDistributor.sendToServer(RequestGigJournalPacket.INSTANCE);
    }

    public static List<GigJournalPacket.Contract> contracts() {
        return contracts;
    }

    public static List<GigJournalPacket.AvailableGig> availableGigs() {
        return availableGigs;
    }

    public static GigJournalPacket.AvailableGig availableAt(OpenCityMapPacket.Marker marker) {
        if (marker == null || marker.kind() != OpenCityMapPacket.MarkerKind.AVAILABLE_GIG) return null;
        return availableGigs.stream()
                .filter(gig -> gig.offerId().toString().equals(marker.referenceId())
                        || marker.referenceId().isBlank()
                                && gig.targetX() == marker.x()
                                && gig.targetZ() == marker.z()
                                && gig.districtOrdinal() == marker.districtOrdinal())
                .findFirst().orElse(null);
    }

    public static void reset() {
        contracts = List.of();
        availableGigs = List.of();
    }
}
