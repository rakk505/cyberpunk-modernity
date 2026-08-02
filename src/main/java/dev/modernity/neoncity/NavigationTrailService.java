package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-authoritative, player-local particle navigation for the active contract. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class NavigationTrailService {
    static final double SAMPLE_SPACING = 3.0;
    static final double MAX_VISIBLE_DISTANCE = 72.0;
    private static final int TRAIL_DURATION_TICKS = 20 * 4;
    private static final int EMIT_INTERVAL_TICKS = 10;
    private static final int REQUEST_COOLDOWN_TICKS = 20;
    private static final int VERTICAL_SEARCH = 6;
    private static final DustParticleOptions TRAIL_PARTICLE =
            new DustParticleOptions(0x26E6FF, 0.9F);
    private static final Map<UUID, TrailSession> SESSIONS = new HashMap<>();
    private static final Map<UUID, Long> REQUEST_COOLDOWNS = new HashMap<>();

    private NavigationTrailService() {
    }

    /** Starts or refreshes a trail without accepting any client-supplied coordinates. */
    public static void request(ServerPlayer player) {
        if (player == null || !player.isAlive() || player.isSpectator()
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        long nextAllowed = REQUEST_COOLDOWNS.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (now < nextAllowed) return;
        REQUEST_COOLDOWNS.put(player.getUUID(), now + REQUEST_COOLDOWN_TICKS);

        if (!Level.OVERWORLD.equals(level.dimension())) {
            SESSIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.navigation.unavailable").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        if (targetFor(level, player).isEmpty()) {
            SESSIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.navigation.no_active").withStyle(ChatFormatting.YELLOW), true);
            return;
        }

        TrailSession session = new TrailSession(now + TRAIL_DURATION_TICKS, now);
        SESSIONS.put(player.getUUID(), session);
        if (!emit(level, player)) {
            SESSIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.navigation.unavailable").withStyle(ChatFormatting.YELLOW), true);
        } else {
            SESSIONS.put(player.getUUID(), new TrailSession(
                    session.expiresAt(), now + EMIT_INTERVAL_TICKS));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, TrailSession>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrailSession> entry = iterator.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !(player.level() instanceof ServerLevel level)
                    || !Level.OVERWORLD.equals(level.dimension())) {
                iterator.remove();
                continue;
            }
            long now = level.getGameTime();
            TrailSession session = entry.getValue();
            if (now >= session.expiresAt() || targetFor(level, player).isEmpty()) {
                iterator.remove();
                continue;
            }
            if (now >= session.nextEmitAt()) {
                emit(level, player);
                entry.setValue(new TrailSession(
                        session.expiresAt(), now + EMIT_INTERVAL_TICKS));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        SESSIONS.remove(playerId);
        REQUEST_COOLDOWNS.remove(playerId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.clear();
        REQUEST_COOLDOWNS.clear();
    }

    private static boolean emit(ServerLevel level, ServerPlayer player) {
        NavigationTarget target = targetFor(level, player).orElse(null);
        if (target == null) return false;
        CityRoutePlanner.Route route = route(level, player, target);
        List<CityRoutePlanner.Point> samples = sampleRoute(
                route, MAX_VISIBLE_DISTANCE, SAMPLE_SPACING);
        if (samples.isEmpty() && route.length() <= SAMPLE_SPACING) {
            samples = List.of(new CityRoutePlanner.Point(player.getX(), player.getZ()));
        }
        int emitted = 0;
        int preferredY = player.getBlockY();
        for (int index = 0; index < samples.size(); index++) {
            CityRoutePlanner.Point sample = samples.get(index);
            Vec3 position = groundPoint(level, sample, preferredY);
            if (position == null && index == 0) {
                position = new Vec3(sample.x(), player.getY() + 0.12, sample.z());
            }
            if (position == null) continue;
            preferredY = (int) Math.floor(position.y());
            if (level.sendParticles(
                    player,
                    TRAIL_PARTICLE,
                    true,
                    true,
                    position.x(),
                    position.y(),
                    position.z(),
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0)) {
                emitted++;
            }
        }
        return emitted > 0;
    }

    private static CityRoutePlanner.Route route(
            ServerLevel level, ServerPlayer player, NavigationTarget target) {
        if (NeonCityGenerator.isMegacityWorld(level)) {
            return CityRoutePlanner.shortest(
                    NeonCityGenerator.layout(),
                    player.getX(),
                    player.getZ(),
                    target.x(),
                    target.z());
        }
        List<CityRoutePlanner.Point> points = List.of(
                new CityRoutePlanner.Point(player.getX(), player.getZ()),
                new CityRoutePlanner.Point(target.x(), target.z()));
        return new CityRoutePlanner.Route(
                points, List.of(), Math.hypot(target.x() - player.getX(), target.z() - player.getZ()));
    }

    private static Optional<NavigationTarget> targetFor(
            ServerLevel level, ServerPlayer player) {
        MissionService.ActiveMission active = MissionService.activeMission(player).orElse(null);
        if (active == null) return Optional.empty();
        MissionService.ContractContext context = MissionService.contractContext(player).orElse(null);
        if (context != null) {
            MissionService.JournalEntry journal = MissionJournalData.get(level)
                    .entries(player.getUUID()).stream()
                    .filter(entry -> entry.instanceId().equals(context.instanceId()))
                    .filter(entry -> entry.status() == MissionService.JournalStatus.ACTIVE)
                    .findFirst().orElse(null);
            if (journal != null) {
                return Optional.of(new NavigationTarget(
                        journal.navigationX(), journal.navigationZ()));
            }
        }
        return Optional.of(MissionService.site(player)
                .map(MissionBuildingPlanner::navigationTarget)
                .map(position -> new NavigationTarget(position.getX(), position.getZ()))
                .orElseGet(() -> new NavigationTarget(
                        active.target().getX(), active.target().getZ())));
    }

    /** Resamples the authoritative route to a bounded, evenly spaced local trail. */
    static List<CityRoutePlanner.Point> sampleRoute(
            CityRoutePlanner.Route route, double maximumDistance, double spacing) {
        if (route == null || route.points().size() < 2
                || !Double.isFinite(maximumDistance) || maximumDistance <= 0.0
                || !Double.isFinite(spacing) || spacing <= 0.0) {
            return List.of();
        }
        ArrayList<CityRoutePlanner.Point> result = new ArrayList<>();
        double traversed = 0.0;
        double nextSample = spacing;
        List<CityRoutePlanner.Point> points = route.points();
        for (int index = 1; index < points.size() && nextSample <= maximumDistance; index++) {
            CityRoutePlanner.Point from = points.get(index - 1);
            CityRoutePlanner.Point to = points.get(index);
            double segmentLength = Math.hypot(to.x() - from.x(), to.z() - from.z());
            if (segmentLength <= 1.0E-6) continue;
            while (nextSample <= traversed + segmentLength
                    && nextSample <= maximumDistance) {
                double progress = (nextSample - traversed) / segmentLength;
                result.add(new CityRoutePlanner.Point(
                        from.x() + (to.x() - from.x()) * progress,
                        from.z() + (to.z() - from.z()) * progress));
                nextSample += spacing;
            }
            traversed += segmentLength;
        }
        if (result.isEmpty() && route.length() > 0.01) {
            CityRoutePlanner.Point from = points.getFirst();
            CityRoutePlanner.Point to = points.get(1);
            double segmentLength = Math.hypot(to.x() - from.x(), to.z() - from.z());
            if (segmentLength > 1.0E-6) {
                double progress = Math.min(1.0, maximumDistance / segmentLength);
                result.add(new CityRoutePlanner.Point(
                        from.x() + (to.x() - from.x()) * progress,
                        from.z() + (to.z() - from.z()) * progress));
            }
        }
        return List.copyOf(result);
    }

    private static Vec3 groundPoint(
            ServerLevel level, CityRoutePlanner.Point point, int preferredY) {
        int x = (int) Math.round(point.x());
        int z = (int) Math.round(point.z());
        BlockPos probe = new BlockPos(x, preferredY, z);
        if (!level.isLoaded(probe)) return null;
        for (int distance = 0; distance <= VERTICAL_SEARCH; distance++) {
            int below = preferredY - distance;
            if (isWalkable(level, x, below, z)) {
                return new Vec3(point.x(), below + 0.12, point.z());
            }
            if (distance > 0) {
                int above = preferredY + distance;
                if (isWalkable(level, x, above, z)) {
                    return new Vec3(point.x(), above + 0.12, point.z());
                }
            }
        }
        return null;
    }

    private static boolean isWalkable(ServerLevel level, int x, int feetY, int z) {
        BlockPos feet = new BlockPos(x, feetY, z);
        return level.getBlockState(feet.below()).blocksMotion()
                && !level.getBlockState(feet).blocksMotion()
                && !level.getBlockState(feet.above()).blocksMotion();
    }

    private record NavigationTarget(int x, int z) {
    }

    private record TrailSession(long expiresAt, long nextEmitAt) {
    }
}
