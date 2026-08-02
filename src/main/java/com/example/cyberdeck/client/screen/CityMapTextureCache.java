package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.network.OpenCityMapPacket;
import com.mojang.blaze3d.platform.NativeImage;
import dev.modernity.neoncity.CityMapProjection;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.PerimeterOutskirts;
import dev.modernity.neoncity.UCorpPortGeneration;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/** Generates and retains one seed-bound top-down city texture off the render thread. */
public final class CityMapTextureCache {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "dynamic/project_moon_city_map");
    public static final int TEXTURE_SIZE = 1024;

    private static final String ATLAS_RESOURCE =
            "/assets/cyberdeck/textures/gui/project_moon_map_atlas.png";
    private static final int ATLAS_AXIS_CHUNKS = 16;
    private static final int ATLAS_DISTRICT_SIZE = ATLAS_AXIS_CHUNKS * 16;
    private static final int ATLAS_ZONE_HEIGHT = ATLAS_AXIS_CHUNKS * 16;
    private static final double DISTRICT_ENVELOPE = 1.32;

    private static volatile Status status = Status.EMPTY;
    private static volatile double progress;
    private static volatile String failure = "";
    private static CacheKey activeKey;
    private static volatile long generation;

    private CityMapTextureCache() {
    }

    public static synchronized void prepare(OpenCityMapPacket packet) {
        if (!packet.available()) {
            reset();
            return;
        }
        CacheKey requested = new CacheKey(packet.layoutSeed(), packet.generatorFingerprint());
        if (requested.equals(activeKey) && status != Status.FAILED && status != Status.EMPTY) {
            return;
        }

        releaseTexture();
        activeKey = requested;
        status = Status.LOADING;
        progress = 0.0;
        failure = "";
        long requestGeneration = ++generation;
        CompletableFuture
                .supplyAsync(() -> rasterize(requested.layoutSeed(), requestGeneration),
                        Util.backgroundExecutor())
                .whenComplete((image, error) -> Minecraft.getInstance().execute(() -> {
                    synchronized (CityMapTextureCache.class) {
                        if (requestGeneration != generation) {
                            if (image != null) image.close();
                            return;
                        }
                        if (error != null) {
                            status = Status.FAILED;
                            failure = rootMessage(error);
                            Cyberdeck.LOGGER.error("Could not build Project Moon city map", error);
                            return;
                        }
                        try {
                            Minecraft.getInstance().getTextureManager().register(
                                    TEXTURE,
                                    new DynamicTexture(() -> "Project Moon city map", image));
                            progress = 1.0;
                            status = Status.READY;
                        } catch (RuntimeException registrationError) {
                            image.close();
                            status = Status.FAILED;
                            failure = rootMessage(registrationError);
                            Cyberdeck.LOGGER.error(
                                    "Could not upload Project Moon city map", registrationError);
                        }
                    }
                }));
    }

    public static synchronized void reset() {
        generation++;
        activeKey = null;
        status = Status.EMPTY;
        progress = 0.0;
        failure = "";
        releaseTexture();
    }

    public static Status status() {
        return status;
    }

    public static double progress() {
        return progress;
    }

    public static String failure() {
        return failure;
    }

    public static synchronized boolean readyFor(long layoutSeed, String fingerprint) {
        return status == Status.READY
                && new CacheKey(layoutSeed, fingerprint).equals(activeKey);
    }

    private static NativeImage rasterize(long layoutSeed, long requestGeneration) {
        MegacityLayout layout = MegacityLayout.createFromLayoutSeed(layoutSeed);
        int extent = CityMapProjection.extent(layout);
        NativeImage output = null;
        try (InputStream stream = CityMapTextureCache.class.getResourceAsStream(ATLAS_RESOURCE);
             NativeImage atlas = readAtlas(stream)) {
            ensureCurrent(requestGeneration);
            if (atlas.getWidth() != District.values().length * ATLAS_DISTRICT_SIZE
                    || atlas.getHeight() != ATLAS_ZONE_HEIGHT * 2) {
                throw new IOException("unexpected Project Moon atlas dimensions "
                        + atlas.getWidth() + "x" + atlas.getHeight());
            }
            output = new NativeImage(TEXTURE_SIZE, TEXTURE_SIZE, false);
            int[] worldCoordinates = worldCoordinates(extent);
            int pixelCount = TEXTURE_SIZE * TEXTURE_SIZE;
            double[] nearestDistance = new double[pixelCount];
            double[] secondDistance = new double[pixelCount];
            byte[] nearestDistrict = new byte[pixelCount];
            byte[] secondDistrict = new byte[pixelCount];
            Arrays.fill(nearestDistance, Double.POSITIVE_INFINITY);
            Arrays.fill(secondDistance, Double.POSITIVE_INFINITY);
            Arrays.fill(nearestDistrict, (byte) -1);
            Arrays.fill(secondDistrict, (byte) -1);

            rasterizeDistrictDistances(
                    layout, extent, worldCoordinates, nearestDistance, secondDistance,
                    nearestDistrict, secondDistrict, requestGeneration);
            colorDistricts(
                    output, atlas, layout, worldCoordinates, nearestDistance, secondDistance,
                    nearestDistrict, secondDistrict, requestGeneration);
            ensureCurrent(requestGeneration);
            drawConnections(output, layout, extent);
            updateProgress(requestGeneration, 0.99);
            return output;
        } catch (RuntimeException | IOException error) {
            if (output != null) output.close();
            throw new IllegalStateException("city map rasterization failed", error);
        }
    }

    private static NativeImage readAtlas(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IOException("missing " + ATLAS_RESOURCE);
        }
        return NativeImage.read(stream);
    }

    private static int[] worldCoordinates(int extent) {
        int[] coordinates = new int[TEXTURE_SIZE];
        for (int pixel = 0; pixel < TEXTURE_SIZE; pixel++) {
            coordinates[pixel] = (int) Math.round(CityMapProjection.unitToWorld(
                    (pixel + 0.5) / TEXTURE_SIZE, extent));
        }
        return coordinates;
    }

    private static void rasterizeDistrictDistances(
            MegacityLayout layout,
            int extent,
            int[] worldCoordinates,
            double[] nearestDistance,
            double[] secondDistance,
            byte[] nearestDistrict,
            byte[] secondDistrict,
            long requestGeneration) {
        int completed = 0;
        for (MegacityLayout.Node node : layout.nodes()) {
            ensureCurrent(requestGeneration);
            double cosine = Math.cos(node.rotation());
            double sine = Math.sin(node.rotation());
            double halfWidth = DISTRICT_ENVELOPE * Math.hypot(
                    node.radiusX() * cosine, node.radiusZ() * sine);
            double halfHeight = DISTRICT_ENVELOPE * Math.hypot(
                    node.radiusX() * sine, node.radiusZ() * cosine);
            int minX = pixelFloor(node.x() - halfWidth, extent);
            int maxX = pixelCeil(node.x() + halfWidth, extent);
            int minZ = pixelFloor(node.z() - halfHeight, extent);
            int maxZ = pixelCeil(node.z() + halfHeight, extent);

            for (int pixelZ = minZ; pixelZ <= maxZ; pixelZ++) {
                int worldZ = worldCoordinates[pixelZ];
                int row = pixelZ * TEXTURE_SIZE;
                for (int pixelX = minX; pixelX <= maxX; pixelX++) {
                    int index = row + pixelX;
                    double distance = layout.normalizedDistanceTo(
                            node, worldCoordinates[pixelX], worldZ);
                    if (distance < nearestDistance[index]) {
                        secondDistance[index] = nearestDistance[index];
                        secondDistrict[index] = nearestDistrict[index];
                        nearestDistance[index] = distance;
                        nearestDistrict[index] = (byte) node.district().ordinal();
                    } else if (distance < secondDistance[index]) {
                        secondDistance[index] = distance;
                        secondDistrict[index] = (byte) node.district().ordinal();
                    }
                }
            }
            completed++;
            updateProgress(requestGeneration,
                    0.05 + 0.53 * completed / layout.nodes().size());
        }
    }

    private static void colorDistricts(
            NativeImage output,
            NativeImage atlas,
            MegacityLayout layout,
            int[] worldCoordinates,
            double[] nearestDistance,
            double[] secondDistance,
            byte[] nearestDistrict,
            byte[] secondDistrict,
            long requestGeneration) {
        District[] districts = District.values();
        UCorpPortGeneration.Plan port = UCorpPortGeneration.plan(layout);
        PerimeterOutskirts.Plan outskirts = PerimeterOutskirts.plan(layout);
        for (int pixelZ = 0; pixelZ < TEXTURE_SIZE; pixelZ++) {
            if ((pixelZ & 15) == 0) ensureCurrent(requestGeneration);
            int worldZ = worldCoordinates[pixelZ];
            int row = pixelZ * TEXTURE_SIZE;
            for (int pixelX = 0; pixelX < TEXTURE_SIZE; pixelX++) {
                int worldX = worldCoordinates[pixelX];
                int index = row + pixelX;
                UCorpPortGeneration.Feature marineFeature = port.featureAt(worldX, worldZ);
                if (marineFeature != UCorpPortGeneration.Feature.NONE) {
                    output.setPixel(pixelX, pixelZ,
                            marineColor(marineFeature, worldX, worldZ));
                    continue;
                }
                double distance = nearestDistance[index];
                MegacityLayout.Location exactLocation = null;
                boolean needsHullLookup = layout.insideUrbanHull(worldX, worldZ)
                        && (distance > MegacityLayout.DISTRICT_BLOB_LIMIT
                                || nearestDistrict[index] < 0
                                || layout.insidePerimeterDistrictBand(worldX, worldZ));
                if (needsHullLookup) {
                    exactLocation = layout.locateDistrict(worldX, worldZ);
                }
                if (exactLocation == null
                        && (distance > MegacityLayout.DISTRICT_BLOB_LIMIT
                                || nearestDistrict[index] < 0)) {
                    PerimeterOutskirts.Feature feature = outskirts.featureAt(worldX, worldZ);
                    output.setPixel(pixelX, pixelZ, feature == PerimeterOutskirts.Feature.NONE
                            ? checker(worldX, worldZ, 0xFF02060B, 0xFF03080E)
                            : outskirtsColor(feature, worldX, worldZ));
                    continue;
                }

                District district;
                MegacityLayout.Zone zone;
                if (exactLocation != null) {
                    distance = exactLocation.normalizedDistance();
                    district = exactLocation.district();
                    zone = exactLocation.zone();
                } else {
                    district = districts[Byte.toUnsignedInt(nearestDistrict[index])];
                    zone = distance <= 0.45
                            ? MegacityLayout.Zone.NEST : MegacityLayout.Zone.BACKSTREETS;
                    if (secondDistrict[index] >= 0
                            && MegacityLayout.isDistrictBorder(
                                    distance, secondDistance[index])) {
                        District other = districts[Byte.toUnsignedInt(secondDistrict[index])];
                        zone = layout.boundaryZone(district, other);
                    }
                }

                MegacityLayout.Node node = layout.node(district);
                int category = atlasCategory(
                        atlas, layout, district, zone, worldX, worldZ);
                output.setPixel(pixelX, pixelZ, color(
                        district, zone, node, distance, category, worldX, worldZ));
            }
            updateProgress(requestGeneration,
                    0.58 + 0.36 * (pixelZ + 1.0) / TEXTURE_SIZE);
        }
    }

    private static int marineColor(
            UCorpPortGeneration.Feature feature, int worldX, int worldZ) {
        return switch (feature) {
            case CONTAINER_PORT -> switch (Math.floorMod(
                    Math.floorDiv(worldX, 12) * 31 + Math.floorDiv(worldZ, 12) * 17, 6)) {
                case 0 -> 0xFF9D3C32;
                case 1 -> 0xFFD0782B;
                case 2 -> 0xFF2C7884;
                case 3 -> 0xFF35634A;
                case 4 -> 0xFFB59A35;
                default -> 0xFF4D5961;
            };
            case HARBOR_WATER -> checker(worldX, worldZ, 0xFF08647A, 0xFF0A7182);
            case OCEAN -> checker(worldX, worldZ, 0xFF06384D, 0xFF07475C);
            case PORTSHIP -> checker(worldX, worldZ, 0xFF87542D, 0xFFB46D32);
            case NONE -> checker(worldX, worldZ, 0xFF02060B, 0xFF03080E);
        };
    }

    private static int outskirtsColor(
            PerimeterOutskirts.Feature feature, int worldX, int worldZ) {
        return switch (feature) {
            case NORTH_TUNDRA -> checker(worldX, worldZ, 0xFFD7E5E7, 0xFFAFC7CD);
            case WEST_LAND -> checker(worldX, worldZ, 0xFF31513A, 0xFF3E5D43);
            case EAST_LAND -> checker(worldX, worldZ, 0xFF35483A, 0xFF465044);
            case EAST_EXTRACTION -> checker(worldX, worldZ, 0xFF66513A, 0xFF3E3B38);
            case NONE -> checker(worldX, worldZ, 0xFF02060B, 0xFF03080E);
        };
    }

    private static void ensureCurrent(long requestGeneration) {
        if (requestGeneration != generation) {
            throw new CancellationException("superseded city map render");
        }
    }

    private static void updateProgress(long requestGeneration, double value) {
        if (requestGeneration == generation) progress = value;
    }

    private static int pixelFloor(double world, int extent) {
        return clampPixel((int) Math.floor(
                CityMapProjection.worldToUnit(world, extent) * TEXTURE_SIZE - 0.5));
    }

    private static int pixelCeil(double world, int extent) {
        return clampPixel((int) Math.ceil(
                CityMapProjection.worldToUnit(world, extent) * TEXTURE_SIZE - 0.5));
    }

    private static int clampPixel(int pixel) {
        return Math.max(0, Math.min(TEXTURE_SIZE - 1, pixel));
    }

    private static int atlasCategory(
            NativeImage atlas,
            MegacityLayout layout,
            District district,
            MegacityLayout.Zone zone,
            int worldX,
            int worldZ) {
        if (zone != MegacityLayout.Zone.NEST
                && zone != MegacityLayout.Zone.BACKSTREETS) {
            return 0;
        }
        MegacityLayout.Node node = layout.node(district);
        AxisMapping tileX = mapAxis(
                Math.floorDiv(worldX, 16) - Math.floorDiv(node.x(), 16));
        AxisMapping tileZ = mapAxis(
                Math.floorDiv(worldZ, 16) - Math.floorDiv(node.z(), 16));
        int localX = Math.floorMod(worldX, 16);
        int localZ = Math.floorMod(worldZ, 16);
        if (tileX.flipped()) localX = 15 - localX;
        if (tileZ.flipped()) localZ = 15 - localZ;
        int x = district.ordinal() * ATLAS_DISTRICT_SIZE
                + tileX.source() * 16 + localX;
        int zoneOffset = zone == MegacityLayout.Zone.NEST ? 0 : ATLAS_ZONE_HEIGHT;
        int z = zoneOffset + tileZ.source() * 16 + localZ;
        return atlas.getPixel(x, z) & 0xFF;
    }

    private static AxisMapping mapAxis(int destinationRelative) {
        int centered = destinationRelative + ATLAS_AXIS_CHUNKS / 2;
        int copy = Math.floorDiv(centered, ATLAS_AXIS_CHUNKS);
        int local = Math.floorMod(centered, ATLAS_AXIS_CHUNKS);
        boolean flipped = Math.floorMod(copy, 2) == 1;
        return new AxisMapping(flipped ? ATLAS_AXIS_CHUNKS - 1 - local : local, flipped);
    }

    private static int color(
            District district,
            MegacityLayout.Zone zone,
            MegacityLayout.Node node,
            double normalizedDistance,
            int atlasCategory,
            int worldX,
            int worldZ) {
        if (zone == MegacityLayout.Zone.BORDER_WALLED) return 0xFF67443C;
        if (zone == MegacityLayout.Zone.BORDER_FOREST) return 0xFF245C38;
        if (zone == MegacityLayout.Zone.BORDER_CLIFF) return 0xFF4A4D50;
        int routeColor = districtRouteColor(node, normalizedDistance, worldX, worldZ);
        if (routeColor != 0) return routeColor;
        if (atlasCategory != 0) {
            return switch (atlasCategory) {
                case 1 -> checker(worldX, worldZ, 0xFF0A3540, 0xFF0C3E49);
                case 2 -> buildingColor(district, worldX, worldZ);
                case 3 -> 0xFF31594C;
                case 4 -> 0xFF073D55;
                default -> districtGround(district, zone);
            };
        }
        return districtGround(district, zone);
    }

    private static int districtGround(District district, MegacityLayout.Zone zone) {
        int variation = (district.ordinal() * 7) & 0x0F;
        return switch (zone) {
            case NEST -> 0xFF2D101B + (variation << 16);
            case BACKSTREETS -> 0xFF1B0B14 + (variation << 8);
            case OUTSKIRTS -> 0xFF10131A;
            case BORDER_WALLED -> 0xFF49302D;
            case BORDER_FOREST -> 0xFF1D472D;
            case BORDER_CLIFF -> 0xFF34373A;
            case WILDERNESS -> 0xFF02060B;
        };
    }

    private static int buildingColor(District district, int x, int z) {
        int noise = Math.floorMod(x * 31 + z * 17 + district.ordinal() * 13, 24);
        int red = Math.min(218, 104 + noise + district.maxHeight() / 5);
        int green = 24 + noise / 3;
        int blue = 42 + noise / 2;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int districtRouteColor(
            MegacityLayout.Node node,
            double normalizedDistance,
            int worldX,
            int worldZ) {
        double dx = worldX - node.x();
        double dz = worldZ - node.z();
        double radius = Math.hypot(dx, dz);
        if (radius < 38.0) return 0xFF36F2DF;
        if (Math.abs(normalizedDistance - 0.34) < 0.012
                || Math.abs(normalizedDistance - 0.69) < 0.011) {
            return 0xFF13B7CC;
        }
        int spokes = 4 + Math.floorMod((int) node.identity(), 4);
        double angle = Math.atan2(dz, dx);
        double curvedAngle = normalizeAngle(
                angle + 0.16 * Math.sin(radius / 113.0 + node.identity() * 0.00001));
        double spokeAngle = Math.PI * 2.0 / spokes;
        double nearestSpoke = Math.rint(curvedAngle / spokeAngle) * spokeAngle;
        double angularDistance = Math.abs(normalizeAngle(curvedAngle - nearestSpoke));
        return angularDistance * Math.max(48.0, radius) < 10.0 ? 0xFF0B8FA1 : 0;
    }

    private static double normalizeAngle(double angle) {
        double wrapped = angle % (Math.PI * 2.0);
        if (wrapped <= -Math.PI) wrapped += Math.PI * 2.0;
        if (wrapped > Math.PI) wrapped -= Math.PI * 2.0;
        return wrapped;
    }

    private static void drawConnections(NativeImage image, MegacityLayout layout, int extent) {
        for (MegacityLayout.Edge edge : layout.edges()) {
            int color = switch (edge.kind()) {
                case ELEVATED_RAIL, GRAND_BOULEVARD -> 0xFF16D4E8;
                case SCENIC_ROAD -> 0xFF39A995;
            };
            drawConnection(image, edge, extent, 2, color);
            if (edge.hasElevatedLayer()) {
                drawConnection(image, edge, extent, 1, 0xFF8AF7E8);
            }
        }
    }

    private static void drawConnection(
            NativeImage image,
            MegacityLayout.Edge edge,
            int extent,
            int radius,
            int color) {
            for (int step = 0; step <= 192; step++) {
                double t = step / 192.0;
                MegacityLayout.CurvePoint point = MegacityLayout.curvePoint(edge, t);
                int pixelX = (int) Math.round(CityMapProjection.worldToUnit(point.x(), extent)
                        * (TEXTURE_SIZE - 1));
                int pixelZ = (int) Math.round(CityMapProjection.worldToUnit(point.z(), extent)
                        * (TEXTURE_SIZE - 1));
                plotDisc(image, pixelX, pixelZ, radius, color);
            }
    }

    private static void plotDisc(NativeImage image, int centerX, int centerY, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                if (x * x + y * y > radius * radius) continue;
                int pixelX = centerX + x;
                int pixelY = centerY + y;
                if (pixelX >= 0 && pixelX < image.getWidth()
                        && pixelY >= 0 && pixelY < image.getHeight()) {
                    image.setPixel(pixelX, pixelY, color);
                }
            }
        }
    }

    private static int checker(int x, int z, int first, int second) {
        return ((Math.floorDiv(x, 128) + Math.floorDiv(z, 128)) & 1) == 0 ? first : second;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return Objects.toString(current.getMessage(), current.getClass().getSimpleName());
    }

    private static void releaseTexture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.getTextureManager().release(TEXTURE);
        }
    }

    public enum Status {
        EMPTY,
        LOADING,
        READY,
        FAILED
    }

    private record CacheKey(long layoutSeed, String fingerprint) {
    }

    private record AxisMapping(int source, boolean flipped) {
    }
}
