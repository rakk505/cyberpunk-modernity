package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Destructive atlas-stamping job and player-local OSM mapping overlay. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class ArnisOsmDebugService {
    public static final int DEFAULT_OVERLAY_RADIUS = 384;
    public static final int MIN_OVERLAY_RADIUS = 16;
    public static final int MAX_OVERLAY_RADIUS = 384;

    private static final int EMIT_INTERVAL_TICKS = 8;
    private static final int MAX_PARTICLES_PER_PASS = 4_000;
    private static final double VALIDATION_PROBE_DISTANCE = 1.5;
    private static final double MAX_VEHICLE_STEP_HEIGHT = 1.0;
    private static final double VEHICLE_HEADROOM = 2.0;
    private static final int PLACE_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    private static final Map<UUID, OverlaySession> OVERLAYS = new HashMap<>();
    private static final Map<AtlasKey, OsmRoadSample.Sample> LOADED_SAMPLES = new HashMap<>();
    private static LoadJob activeJob;

    private ArnisOsmDebugService() {}

    public static boolean start(ServerPlayer player, OsmRoadSample.Sample sample) {
        if (activeJob != null) return false;
        List<ArnisPatchLibrary.Patch> tiles = ArnisPatchLibrary.atlasTiles(
                sample.district(), sample.zone());
        if (tiles.size() != OsmRoadSample.ATLAS_CHUNKS * OsmRoadSample.ATLAS_CHUNKS) {
            return false;
        }
        ChunkPos current = player.chunkPosition();
        int originChunkX = Math.floorDiv(current.x(), OsmRoadSample.ATLAS_CHUNKS)
                * OsmRoadSample.ATLAS_CHUNKS;
        int originChunkZ = Math.floorDiv(current.z(), OsmRoadSample.ATLAS_CHUNKS)
                * OsmRoadSample.ATLAS_CHUNKS;
        activeJob = new LoadJob(
                player.level().dimension().identifier().toString(),
                player.getUUID(), originChunkX, originChunkZ, sample, tiles, 0);
        return true;
    }

    public static boolean cancel() {
        if (activeJob == null) return false;
        activeJob = null;
        return true;
    }

    public static Status status() {
        LoadJob job = activeJob;
        return job == null
                ? new Status(false, "", "", 0, 0, 0,
                        OsmRoadSample.ATLAS_CHUNKS * OsmRoadSample.ATLAS_CHUNKS)
                : new Status(true, job.sample().id(), job.sample().name(),
                        job.originChunkX(), job.originChunkZ(), job.nextTile(), job.tiles().size());
    }

    public static void enableOverlay(ServerPlayer player, int radius) {
        ChunkPos current = player.chunkPosition();
        int originChunkX = Math.floorDiv(current.x(), OsmRoadSample.ATLAS_CHUNKS)
                * OsmRoadSample.ATLAS_CHUNKS;
        int originChunkZ = Math.floorDiv(current.z(), OsmRoadSample.ATLAS_CHUNKS)
                * OsmRoadSample.ATLAS_CHUNKS;
        String dimension = player.level().dimension().identifier().toString();
        OsmRoadSample.Sample sample = LOADED_SAMPLES.getOrDefault(
                new AtlasKey(dimension, originChunkX, originChunkZ),
                OsmRoadSample.defaultSample());
        OVERLAYS.put(player.getUUID(), new OverlaySession(
                dimension, originChunkX, originChunkZ, sample,
                Math.clamp(radius, MIN_OVERLAY_RADIUS, MAX_OVERLAY_RADIUS)));
    }

    public static boolean disableOverlay(ServerPlayer player) {
        return OVERLAYS.remove(player.getUUID()) != null;
    }

    public static OverlayStatus overlayStatus(ServerPlayer player) {
        OverlaySession session = OVERLAYS.get(player.getUUID());
        return session == null
                ? new OverlayStatus(false, "", "", 0, 0, 0)
                : new OverlayStatus(true, session.sample().id(), session.sample().name(),
                        session.originChunkX(), session.originChunkZ(), session.radius());
    }

    public record Status(boolean active, String sampleId, String sampleName,
                         int originChunkX, int originChunkZ,
                         int completed, int total) {}

    public record OverlayStatus(boolean enabled, String sampleId, String sampleName,
                                int originChunkX, int originChunkZ, int radius) {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        processLoadJob(event);
        if (event.getServer().getTickCount() % EMIT_INTERVAL_TICKS != 0L) return;
        for (Map.Entry<UUID, OverlaySession> entry : OVERLAYS.entrySet()) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !(player.level() instanceof ServerLevel level)) continue;
            OverlaySession session = entry.getValue();
            if (!level.dimension().identifier().toString().equals(session.dimension())) continue;
            int pass = (int) (event.getServer().getTickCount() / EMIT_INTERVAL_TICKS);
            emitOverlay(level, player, session, pass);
        }
    }

    private static void processLoadJob(ServerTickEvent.Post event) {
        LoadJob job = activeJob;
        if (job == null) return;
        ServerLevel level = null;
        for (ServerLevel candidate : event.getServer().getAllLevels()) {
            if (candidate.dimension().identifier().toString().equals(job.dimension())) {
                level = candidate;
                break;
            }
        }
        if (level == null) {
            activeJob = null;
            return;
        }
        int index = job.nextTile();
        int tileX = index % OsmRoadSample.ATLAS_CHUNKS;
        int tileZ = index / OsmRoadSample.ATLAS_CHUNKS;
        ChunkPos destination = new ChunkPos(job.originChunkX() + tileX, job.originChunkZ() + tileZ);
        try {
            if (!stampTile(level, destination, job.tiles().get(index))) {
                notifyOwner(event, job, "OSM atlas load failed at tile " + tileX + "," + tileZ + ".");
                activeJob = null;
                return;
            }
        } catch (RuntimeException exception) {
            Cyberdeck.LOGGER.error("OSM atlas debug load failed at {}", destination, exception);
            notifyOwner(event, job, "OSM atlas load failed; see server log.");
            activeJob = null;
            return;
        }
        int next = index + 1;
        if (next >= job.tiles().size()) {
            LOADED_SAMPLES.put(
                    new AtlasKey(job.dimension(), job.originChunkX(), job.originChunkZ()),
                    job.sample());
            activeJob = null;
            notifyOwner(event, job, "OSM sample " + job.sample().id()
                    + " loaded. Use /neoncity osm_sample overlay on.");
        } else {
            activeJob = new LoadJob(job.dimension(), job.owner(), job.originChunkX(),
                    job.originChunkZ(), job.sample(), job.tiles(), next);
            if (next % 32 == 0) {
                notifyOwner(event, job, "OSM atlas load: " + next + "/" + job.tiles().size());
            }
        }
    }

    private static void notifyOwner(ServerTickEvent.Post event, LoadJob job, String message) {
        ServerPlayer owner = event.getServer().getPlayerList().getPlayer(job.owner());
        if (owner != null) owner.sendSystemMessage(Component.literal(message));
    }

    private static boolean stampTile(
            ServerLevel level, ChunkPos destination, ArnisPatchLibrary.Patch patch) {
        StructureTemplate template = level.getStructureManager().get(patch.templateId()).orElse(null);
        if (template == null) return false;
        level.getChunk(destination.x(), destination.z());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = destination.getMinBlockX();
        int minZ = destination.getMinBlockZ();
        for (int z = minZ; z <= destination.getMaxBlockZ(); z++) {
            for (int x = minX; x <= destination.getMaxBlockX(); x++) {
                for (int y = NeonCityGenerator.CITY_GROUND_Y + 1;
                        y <= NeonCityGenerator.MAX_BUILD_Y; y++) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).isAir()) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                    }
                }
                for (int y = NeonCityGenerator.CITY_GROUND_Y - 5;
                        y < NeonCityGenerator.CITY_GROUND_Y; y++) {
                    cursor.set(x, y, z);
                    level.setBlock(cursor, Blocks.DEEPSLATE.defaultBlockState(), PLACE_FLAGS);
                }
                cursor.set(x, NeonCityGenerator.CITY_GROUND_Y, z);
                level.setBlock(cursor, Blocks.SMOOTH_STONE.defaultBlockState(), PLACE_FLAGS);
            }
        }

        int minY = NeonCityGenerator.CITY_GROUND_Y - patch.surfaceOffset();
        BlockPos anchor = new BlockPos(minX, minY, minZ);
        BoundingBox bounds = new BoundingBox(
                minX, minY, minZ,
                destination.getMaxBlockX(), minY + patch.sizeY() - 1,
                destination.getMaxBlockZ());
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setMirror(Mirror.NONE)
                .setRotation(Rotation.NONE)
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                .setBoundingBox(bounds)
                .addProcessor(BlockIgnoreProcessor.AIR);
        boolean placed = template.placeInWorld(
                level, anchor, anchor, settings,
                net.minecraft.util.RandomSource.create(destination.pack()), PLACE_FLAGS);
        NeonCityGenerator.scheduleTrackingClientRefresh(level, destination);
        return placed;
    }

    private static void emitOverlay(
            ServerLevel level, ServerPlayer player, OverlaySession session, int pass) {
        int originX = session.originChunkX() << 4;
        int originZ = session.originChunkZ() << 4;
        double radiusSquared = (double) session.radius() * session.radius();
        LinkedHashMap<Long, OverlayPoint> points = new LinkedHashMap<>();
        for (OsmRoadSample.Road road : session.sample().roads()) {
            for (OsmRoadSample.Segment segment : road.segments()) {
                double dx = segment.second().x() - segment.first().x();
                double dz = segment.second().z() - segment.first().z();
                double length = Math.hypot(dx, dz);
                if (length < 0.01) continue;
                double normalX = -dz / length;
                double normalZ = dx / length;
                int steps = Math.max(1, (int) Math.ceil(length / 0.55));
                int lateralSteps = Math.max(1, (int) Math.ceil(road.width() / 0.75));
                for (int step = 0; step <= steps; step++) {
                    double progress = step / (double) steps;
                    double centerX = originX + segment.first().x() + dx * progress;
                    double centerZ = originZ + segment.first().z() + dz * progress;
                    for (int lateral = 0; lateral <= lateralSteps; lateral++) {
                        double offset = -road.width() * 0.5
                                + road.width() * lateral / lateralSteps;
                        int x = (int) Math.floor(centerX + normalX * offset);
                        int z = (int) Math.floor(centerZ + normalZ * offset);
                        double playerDx = x + 0.5 - player.getX();
                        double playerDz = z + 0.5 - player.getZ();
                        if (playerDx * playerDx + playerDz * playerDz > radiusSquared) continue;
                        long key = BlockPos.asLong(x, 0, z);
                        points.putIfAbsent(key, new OverlayPoint(
                                x, z, road.kind(), dx / length, dz / length));
                    }
                }
            }
        }
        if (points.isEmpty()) return;
        List<OverlayPoint> visible = List.copyOf(points.values());
        int passes = Math.max(1, (int) Math.ceil(
                visible.size() / (double) MAX_PARTICLES_PER_PASS));
        int selectedPass = Math.floorMod(pass, passes);
        int emitted = 0;
        for (int index = selectedPass; index < visible.size(); index += passes) {
            OverlayPoint point = visible.get(index);
            BlockPos probe = new BlockPos(point.x(), player.getBlockY(), point.z());
            if (!level.hasChunkAt(probe)) continue;
            Surface surface = surfaceAt(level, point.x(), point.z());
            boolean valid = surface.present()
                    && traversableForVehicle(level, point, surface.height());
            level.sendParticles(
                    player, particle(point.kind(), valid), true, true,
                    point.x() + 0.5, surface.height() + 1.05, point.z() + 0.5,
                    2, 0.08, 0.03, 0.08, 0.0);
            if (++emitted >= MAX_PARTICLES_PER_PASS) break;
        }
    }

    private static DustParticleOptions particle(String kind, boolean valid) {
        if (!valid) return new DustParticleOptions(0xFF3030, 1.8F);
        int color = switch (kind) {
            case "motorway", "motorway_link", "trunk", "trunk_link" -> 0xD946EF;
            case "primary", "primary_link" -> 0x00E5FF;
            case "secondary", "secondary_link", "tertiary", "tertiary_link" -> 0xFFD21A;
            case "service" -> 0xFFFFFF;
            default -> 0x35E06F;
        };
        return new DustParticleOptions(color, 1.6F);
    }

    private static boolean traversableForVehicle(
            ServerLevel level, OverlayPoint point, double currentHeight) {
        int aheadX = (int) Math.floor(point.x() + 0.5
                + point.forwardX() * VALIDATION_PROBE_DISTANCE);
        int aheadZ = (int) Math.floor(point.z() + 0.5
                + point.forwardZ() * VALIDATION_PROBE_DISTANCE);
        int behindX = (int) Math.floor(point.x() + 0.5
                - point.forwardX() * VALIDATION_PROBE_DISTANCE);
        int behindZ = (int) Math.floor(point.z() + 0.5
                - point.forwardZ() * VALIDATION_PROBE_DISTANCE);
        Surface ahead = surfaceAt(level, aheadX, aheadZ);
        Surface behind = surfaceAt(level, behindX, behindZ);
        return ahead.present()
                && behind.present()
                && Math.abs(ahead.height() - currentHeight) <= MAX_VEHICLE_STEP_HEIGHT
                && Math.abs(behind.height() - currentHeight) <= MAX_VEHICLE_STEP_HEIGHT
                && hasVehicleHeadroom(level, point.x(), point.z(), currentHeight);
    }

    private static Surface surfaceAt(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos support = new BlockPos(x, top - 1, z);
        var shape = level.getBlockState(support).getCollisionShape(level, support);
        if (shape.isEmpty()) return new Surface(false, top);
        return new Surface(true, support.getY() + shape.max(net.minecraft.core.Direction.Axis.Y));
    }

    private static boolean hasVehicleHeadroom(
            ServerLevel level, int x, int z, double surfaceHeight) {
        AABB vehicleColumn = new AABB(
                x + 0.15, surfaceHeight + 0.01, z + 0.15,
                x + 0.85, surfaceHeight + VEHICLE_HEADROOM, z + 0.85);
        return level.noCollision(vehicleColumn);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OVERLAYS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        activeJob = null;
        OVERLAYS.clear();
        LOADED_SAMPLES.clear();
    }

    private record LoadJob(
            String dimension,
            UUID owner,
            int originChunkX,
            int originChunkZ,
            OsmRoadSample.Sample sample,
            List<ArnisPatchLibrary.Patch> tiles,
            int nextTile) {}

    private record OverlaySession(
            String dimension, int originChunkX, int originChunkZ,
            OsmRoadSample.Sample sample, int radius) {}

    private record AtlasKey(String dimension, int originChunkX, int originChunkZ) {}

    private record OverlayPoint(int x, int z, String kind, double forwardX, double forwardZ) {}

    private record Surface(boolean present, double height) {}
}
