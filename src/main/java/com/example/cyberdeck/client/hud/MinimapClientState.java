package com.example.cyberdeck.client.hud;

/**
 * Client-only, session-persistent toggle state for the city minimap and its overlays.
 *
 * <p>The flags survive world reloads within a single client run because they live on static
 * fields that are never reset by dimension changes.</p>
 */
public final class MinimapClientState {
    private static boolean minimapVisible = true;
    private static boolean merchantMarkersVisible = true;

    private MinimapClientState() {
    }

    public static boolean minimapVisible() {
        return minimapVisible;
    }

    public static boolean toggleMinimap() {
        minimapVisible = !minimapVisible;
        return minimapVisible;
    }

    public static boolean merchantMarkersVisible() {
        return merchantMarkersVisible;
    }

    public static boolean toggleMerchantMarkers() {
        merchantMarkersVisible = !merchantMarkersVisible;
        return merchantMarkersVisible;
    }
}
