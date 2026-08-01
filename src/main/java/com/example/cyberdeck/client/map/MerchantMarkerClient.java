package com.example.cyberdeck.client.map;

import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import java.util.ArrayList;
import java.util.List;

/** Client-side source of deterministic district merchant markers for the live minimap. */
public final class MerchantMarkerClient {
    private MerchantMarkerClient() {
    }

    public static List<Marker> markers(MegacityLayout layout) {
        if (layout == null) {
            return List.of();
        }
        List<Marker> markers = new ArrayList<>(layout.nodes().size());
        for (MegacityLayout.Node node : layout.nodes()) {
            District district = node.district();
            markers.add(new Marker(
                    node.x(),
                    node.z(),
                    district.code(),
                    district.label() + " Merchants"));
        }
        return List.copyOf(markers);
    }

    public record Marker(int x, int z, String districtCode, String label) {
    }
}
