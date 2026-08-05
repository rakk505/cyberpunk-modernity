package dev.modernity.neoncity;

import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.Cyberdeck;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Player-local visualization of the procedural road classification. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class RoadDebugOverlayService {
    public static final int DEFAULT_RADIUS = 32;
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 64;

    private static final int EMIT_INTERVAL_TICKS = 5;
    private static final int MAX_PARTICLES_PER_PASS = 1_600;
    private static final Map<UUID, Integer> SESSIONS = new HashMap<>();

    private RoadDebugOverlayService() {}

    public static void enable(ServerPlayer player, int radius) {
        SESSIONS.put(player.getUUID(), Math.clamp(radius, MIN_RADIUS, MAX_RADIUS));
    }

    public static boolean disable(ServerPlayer player) {
        return SESSIONS.remove(player.getUUID()) != null;
    }

    public static int radius(ServerPlayer player) {
        return SESSIONS.getOrDefault(player.getUUID(), 0);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long tick = event.getServer().getTickCount();
        if (tick % EMIT_INTERVAL_TICKS != 0L || SESSIONS.isEmpty()) return;

        int phase = (int) ((tick / EMIT_INTERVAL_TICKS) & 3L);
        for (Map.Entry<UUID, Integer> entry : SESSIONS.entrySet()) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !(player.level() instanceof ServerLevel level)) continue;
            if (!NeonCityGenerator.isMegacityWorld(level)) continue;
            emit(level, player, entry.getValue(), phase);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.clear();
    }

    private static void emit(ServerLevel level, ServerPlayer player, int radius, int phase) {
        BlockPos center = player.blockPosition();
        int radiusSquared = radius * radius;
        int xPhase = phase & 1;
        int zPhase = phase >> 1;
        int emitted = 0;

        for (int z = center.getZ() - radius + zPhase;
                z <= center.getZ() + radius;
                z += 2) {
            int dz = z - center.getZ();
            for (int x = center.getX() - radius + xPhase;
                    x <= center.getX() + radius;
                    x += 2) {
                int dx = x - center.getX();
                if (dx * dx + dz * dz > radiusSquared) continue;

                BlockPos loadedProbe = new BlockPos(x, center.getY(), z);
                if (!CityWorlds.hasFullyLoadedChunk(level, loadedProbe)) continue;
                NeonCityGenerator.RoadClass proceduralRoad = NeonCityGenerator.roadAt(x, z);
                DustParticleOptions particle = particleAt(x, z, proceduralRoad);
                if (particle == null) continue;

                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                level.sendParticles(
                        player,
                        particle,
                        true,
                        true,
                        x + 0.5,
                        surfaceY + 0.15,
                        z + 0.5,
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0);
                if (++emitted >= MAX_PARTICLES_PER_PASS) return;
            }
        }
    }

    private static DustParticleOptions particleAt(
            int worldX, int worldZ, NeonCityGenerator.RoadClass proceduralRoad) {
        if (NeonCityGenerator.overridesArnis(proceduralRoad)) {
            return particleFor(proceduralRoad);
        }
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        Optional<ArnisPatchLibrary.Placement> selected =
                NeonCityGenerator.arnisPlacementAt(chunkX, chunkZ);
        if (selected.isPresent()) {
            ArnisPatchLibrary.Placement placement = selected.get();
            MegacityLayout.Location location = NeonCityGenerator.layout().locate(worldX, worldZ);
            MegacityLayout.Zone zone = location.zone();
            if (location.district() == placement.patch().district()
                    && placement.patch().placementZones().contains(zone)) {
                int localX = Math.floorMod(worldX, 16);
                int localZ = Math.floorMod(worldZ, 16);
                int sourceX = sourceCoordinate(
                        placement.sourceTileX(), localX, placement.flipX());
                int sourceZ = sourceCoordinate(
                        placement.sourceTileZ(), localZ, placement.flipZ());
                OsmRoadSample.RoadKind osmRoad = OsmRoadSample.forAtlas(
                                placement.patch().district(), zone)
                        .map(sample -> sample.roadAt(sourceX, sourceZ))
                        .orElse(OsmRoadSample.RoadKind.NONE);
                DustParticleOptions osmParticle = particleFor(osmRoad);
                if (osmParticle != null) return osmParticle;
            }
        }
        return particleFor(proceduralRoad);
    }

    static int sourceCoordinate(int sourceTile, int destinationLocal, boolean flipped) {
        return sourceTile * 16 + (flipped ? 15 - destinationLocal : destinationLocal);
    }

    static DustParticleOptions particleFor(OsmRoadSample.RoadKind roadKind) {
        return switch (roadKind) {
            case MOTORWAY -> new DustParticleOptions(0xD946EF, 0.85F);
            case PRIMARY -> new DustParticleOptions(0x00E5FF, 0.8F);
            case SECONDARY -> new DustParticleOptions(0xFFD21A, 0.75F);
            case SERVICE -> new DustParticleOptions(0xFFFFFF, 0.65F);
            case LOCAL -> new DustParticleOptions(0x35E06F, 0.7F);
            default -> null;
        };
    }

    static DustParticleOptions particleFor(NeonCityGenerator.RoadClass roadClass) {
        return switch (roadClass) {
            case CENTRAL_PLAZA -> new DustParticleOptions(0xFFFFFF, 0.8F);
            case DISTRICT_BOULEVARD -> new DustParticleOptions(0x00E5FF, 0.75F);
            case LOCAL_STREET -> new DustParticleOptions(0x35E06F, 0.7F);
            case SERVICE_ALLEY -> new DustParticleOptions(0x9AA0A6, 0.6F);
            case INTERDISTRICT_ROAD -> new DustParticleOptions(0xFFD21A, 0.85F);
            case BRIDGE -> new DustParticleOptions(0xFF8A00, 0.85F);
            case ELEVATED_RAIL -> new DustParticleOptions(0xD946EF, 0.8F);
            case HIGHWAY_BUFFER -> new DustParticleOptions(0xFF3030, 0.65F);
            default -> null;
        };
    }
}
