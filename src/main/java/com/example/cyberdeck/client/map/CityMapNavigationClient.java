package com.example.cyberdeck.client.map;

import com.example.cyberdeck.client.screen.CityMapScreen;
import com.example.cyberdeck.client.screen.CityMapTextureCache;
import com.example.cyberdeck.network.OpenCityMapPacket;
import com.example.cyberdeck.network.RequestCityMapPacket;
import dev.modernity.neoncity.CityMapProjection;
import dev.modernity.neoncity.CityRoutePlanner;
import dev.modernity.neoncity.MegacityLayout;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Client-session city plan, waypoint, and route shared by the map screen and HUD. */
public final class CityMapNavigationClient {
    private static final double ROUTE_REFRESH_DISTANCE_SQUARED = 64.0;
    private static final CityRoutePlanner.Route EMPTY_ROUTE =
            new CityRoutePlanner.Route(List.of(), List.of(), 0.0);

    private static OpenCityMapPacket lastPacket;
    private static Snapshot snapshot;
    private static Waypoint waypoint;
    private static CityRoutePlanner.Route route = EMPTY_ROUTE;
    private static boolean requestPending;
    private static boolean openRequested;
    private static double routeOriginX = Double.NaN;
    private static double routeOriginZ = Double.NaN;

    private CityMapNavigationClient() {
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) return;
        if (lastPacket == null && !requestPending) requestSnapshot(false);
        if (snapshot == null || waypoint == null || minecraft.level == null
                || !Level.OVERWORLD.equals(minecraft.level.dimension())) {
            return;
        }
        double dx = minecraft.player.getX() - routeOriginX;
        double dz = minecraft.player.getZ() - routeOriginZ;
        if (route.isEmpty() || !Double.isFinite(routeOriginX)
                || dx * dx + dz * dz >= ROUTE_REFRESH_DISTANCE_SQUARED) {
            rebuildRoute(minecraft);
        }
    }

    public static void requestOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (lastPacket != null && (minecraft.level == null
                || Level.OVERWORLD.equals(minecraft.level.dimension()))) {
            CityMapScreen.open(lastPacket);
            return;
        }
        openRequested = true;
        if (!requestPending) requestSnapshot(true);
    }

    private static void requestSnapshot(boolean forOpen) {
        openRequested |= forOpen;
        requestPending = true;
        ClientPacketDistributor.sendToServer(RequestCityMapPacket.INSTANCE);
    }

    public static void receive(OpenCityMapPacket packet) {
        requestPending = false;
        lastPacket = packet;
        if (packet.available()) {
            boolean samePlan = snapshot != null
                    && snapshot.layoutSeed() == packet.layoutSeed()
                    && snapshot.fingerprint().equals(packet.generatorFingerprint());
            MegacityLayout layout = MegacityLayout.createFromLayoutSeed(packet.layoutSeed());
            snapshot = new Snapshot(
                    packet,
                    layout,
                    CityMapProjection.extent(layout),
                    packet.layoutSeed(),
                    packet.generatorFingerprint());
            if (!samePlan) clearWaypoint();
            CityMapTextureCache.prepare(packet);
            rebuildRoute(Minecraft.getInstance());
        } else {
            snapshot = null;
            clearWaypoint();
            CityMapTextureCache.reset();
        }

        boolean shouldOpen = packet.forceOpen() || openRequested;
        openRequested = false;
        if (shouldOpen) CityMapScreen.open(packet);
    }

    public static void setWaypoint(int worldX, int worldZ) {
        int district = -1;
        if (snapshot != null) {
            MegacityLayout.Location location = snapshot.layout().locateDistrict(worldX, worldZ);
            if (location.insideCity()) district = location.district().ordinal();
        }
        waypoint = new Waypoint(worldX, worldZ, district, "", false);
        route = EMPTY_ROUTE;
        rebuildRoute(Minecraft.getInstance());
    }

    public static void setWaypoint(OpenCityMapPacket.Marker marker) {
        waypoint = new Waypoint(
                marker.x(), marker.z(), marker.districtOrdinal(), marker.labelKey(), true);
        route = EMPTY_ROUTE;
        rebuildRoute(Minecraft.getInstance());
    }

    public static void setMissionWaypoint(
            int worldX, int worldZ, int districtOrdinal, String title) {
        waypoint = new Waypoint(
                worldX, worldZ, districtOrdinal, "literal:" + title, true);
        route = EMPTY_ROUTE;
        rebuildRoute(Minecraft.getInstance());
    }

    public static void clearWaypoint() {
        waypoint = null;
        route = EMPTY_ROUTE;
        routeOriginX = Double.NaN;
        routeOriginZ = Double.NaN;
    }

    private static void rebuildRoute(Minecraft minecraft) {
        if (snapshot == null || waypoint == null || minecraft.player == null
                || minecraft.level == null
                || !Level.OVERWORLD.equals(minecraft.level.dimension())) {
            route = EMPTY_ROUTE;
            return;
        }
        routeOriginX = minecraft.player.getX();
        routeOriginZ = minecraft.player.getZ();
        route = CityRoutePlanner.shortest(
                snapshot.layout(), routeOriginX, routeOriginZ, waypoint.x(), waypoint.z());
    }

    public static void reset() {
        lastPacket = null;
        snapshot = null;
        requestPending = false;
        openRequested = false;
        clearWaypoint();
        CityMapTextureCache.reset();
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static Waypoint waypoint() {
        return waypoint;
    }

    public static CityRoutePlanner.Route route() {
        return route;
    }

    public static double distanceToWaypoint(double worldX, double worldZ) {
        return waypoint == null ? 0.0 : Math.hypot(waypoint.x() - worldX, waypoint.z() - worldZ);
    }

    public record Snapshot(
            OpenCityMapPacket packet,
            MegacityLayout layout,
            int extent,
            long layoutSeed,
            String fingerprint) {
    }

    public record Waypoint(
            int x,
            int z,
            int districtOrdinal,
            String labelKey,
            boolean marker) {
    }
}
