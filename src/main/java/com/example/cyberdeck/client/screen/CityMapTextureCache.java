package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.network.OpenCityMapPacket;
import com.mojang.blaze3d.platform.NativeImage;
import dev.modernity.neoncity.CityMapProjection;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
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
        double blocksPerPixel = worldCoordinates.length < 2
                ? 1.0
                : (worldCoordinates[TEXTURE_SIZE - 1] - worldCoordinates[0])
                        / (double) (TEXTURE_SIZE - 1);
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
                                || nearestDistrict[index] < 0);
                if (needsHullLookup) {
                    exactLocation = layout.locateDistrict(worldX, worldZ);
                }
                if (exactLocation == null
                        && (distance > MegacityLayout.DISTRICT_BLOB_LIMIT
                                || nearestDistrict[index] < 0)) {
                    output.setPixel(pixelX, pixelZ,
                            checker(worldX, worldZ, 0xFF02060B, 0xFF03080E));
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
                int roadColor = osmRoadColor(
                        district, zone, node, worldX, worldZ, blocksPerPixel);
                if (roadColor != 0) {
                    output.setPixel(pixelX, pixelZ, roadColor);
                    continue;
                }
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
        int x = district.ordinal() * ATLAS_DISTRICT_SIZE + atlasSource(worldX, node.x());
        int zoneOffset = zone == MegacityLayout.Zone.NEST ? 0 : ATLAS_ZONE_HEIGHT;
        int z = zoneOffset + atlasSource(worldZ, node.z());
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

    /**
     * Colors a pixel that sits on an actual OSM-baked road, using the same road raster world
     * generation places. Because one map pixel spans several blocks at citywide zoom (thin streets
     * are sub-pixel), the pixel's world footprint is supersampled and the largest road found wins,
     * so streets stay connected instead of aliasing into dots. Returns 0 when no road is present.
     */
    private static int osmRoadColor(
            District district,
            MegacityLayout.Zone zone,
            MegacityLayout.Node node,
            int worldX,
            int worldZ,
            double blocksPerPixel) {
        if (zone != MegacityLayout.Zone.NEST && zone != MegacityLayout.Zone.BACKSTREETS) {
            return 0;
        }
        int step = Math.max(1, (int) Math.round(blocksPerPixel * 0.4));
        int best = 0;
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                int sourceX = atlasSource(worldX + offsetX * step, node.x());
                int sourceZ = atlasSource(worldZ + offsetZ * step, node.z());
                int kind = NeonCityGenerator.mapRoadKind(district, zone, sourceX, sourceZ);
                if (kind > best) best = kind;
            }
        }
        return switch (best) {
            case 5 -> 0xFF16D4E8;
            case 4 -> 0xFF13B7CC;
            case 3 -> 0xFF11A6BA;
            case 2 -> 0xFF0E97A8;
            case 1 -> 0xFF0B8296;
            default -> 0;
        };
    }

    /** Atlas-local block coordinate (0..255) for a world coordinate, matching {@link #atlasCategory}. */
    private static int atlasSource(int world, int nodeCoordinate) {
        AxisMapping tile = mapAxis(
                Math.floorDiv(world, 16) - Math.floorDiv(nodeCoordinate, 16));
        int local = Math.floorMod(world, 16);
        if (tile.flipped()) local = 15 - local;
        return tile.source() * 16 + local;
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
