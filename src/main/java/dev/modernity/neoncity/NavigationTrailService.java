package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-authoritative, player-local particle navigation for the active contract. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class NavigationTrailService {
    static final double SAMPLE_SPACING = 2.5;
    static final double MAX_VISIBLE_DISTANCE = 72.0;
    private static final double MAX_LOOKAHEAD_DISTANCE = 64.0;
    private static final int MAX_PATH_DISTANCE = 96;
    private static final int CHECKPOINT_SEARCH_RADIUS = 6;
    private static final int MAX_CHECKPOINT_CANDIDATES = 8;
    private static final double REPLAN_DISTANCE_SQUARED = 9.0;
    private static final int TRAIL_DURATION_TICKS = 20 * 4;
    private static final int EMIT_INTERVAL_TICKS = 10;
    private static final int REQUEST_COOLDOWN_TICKS = 20;
    private static final int VERTICAL_SEARCH = 6;
    private static final DustParticleOptions TRAIL_PARTICLE =
            new DustParticleOptions(0xFFD21A, 1.25F);
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
        NavigationTarget target = targetFor(level, player).orElse(null);
        if (target == null) {
            SESSIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.navigation.no_active").withStyle(ChatFormatting.YELLOW), true);
            return;
        }

        List<Vec3> path = planOpenPath(level, player, target);
        if (path.size() < 2) {
            SESSIONS.remove(player.getUUID());
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.navigation.no_open_route")
                    .withStyle(ChatFormatting.YELLOW), true);
            Cyberdeck.LOGGER.info(
                    "[Navigation] no open route for {} from {} to {},{}",
                    player.getScoreboardName(), player.blockPosition(), target.x(), target.z());
            return;
        }
        TrailSession session = new TrailSession(
                now + TRAIL_DURATION_TICKS,
                now + EMIT_INTERVAL_TICKS,
                target,
                player.position(),
                path);
        if (!emit(level, player, path)) {
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.navigation.unavailable").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        SESSIONS.put(player.getUUID(), session);
        Cyberdeck.LOGGER.debug(
                "[Navigation] planned {} nodes for {} toward {},{}",
                path.size(), player.getScoreboardName(), target.x(), target.z());
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
            NavigationTarget target = targetFor(level, player).orElse(null);
            if (now >= session.expiresAt() || target == null) {
                iterator.remove();
                continue;
            }
            double movedX = player.getX() - session.origin().x();
            double movedZ = player.getZ() - session.origin().z();
            if (!target.equals(session.target())
                    || movedX * movedX + movedZ * movedZ >= REPLAN_DISTANCE_SQUARED) {
                List<Vec3> replanned = planOpenPath(level, player, target);
                if (replanned.size() < 2) {
                    iterator.remove();
                    player.sendSystemMessage(Component.translatable(
                            "message.cyberdeck.navigation.no_open_route")
                            .withStyle(ChatFormatting.YELLOW), true);
                    Cyberdeck.LOGGER.info(
                            "[Navigation] route became blocked for {} from {} to {},{}",
                            player.getScoreboardName(), player.blockPosition(),
                            target.x(), target.z());
                    continue;
                }
                session = new TrailSession(
                        session.expiresAt(), session.nextEmitAt(), target,
                        player.position(), replanned);
                entry.setValue(session);
            }
            if (now >= session.nextEmitAt()) {
                if (!emit(level, player, session.path())) {
                    iterator.remove();
                    continue;
                }
                entry.setValue(new TrailSession(
                        session.expiresAt(), now + EMIT_INTERVAL_TICKS,
                        session.target(), session.origin(), session.path()));
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

    private static boolean emit(
            ServerLevel level, ServerPlayer player, List<Vec3> path) {
        List<Vec3> samples = samplePath(path, MAX_VISIBLE_DISTANCE, SAMPLE_SPACING);
        int emitted = 0;
        for (Vec3 position : samples) {
            if (level.sendParticles(
                    player,
                    TRAIL_PARTICLE,
                    true,
                    true,
                    position.x(),
                    position.y(),
                    position.z(),
                    2,
                    0.035,
                    0.02,
                    0.035,
                    0.0)) {
                emitted++;
            }
        }
        return emitted > 0;
    }

    private static CityRoutePlanner.Route guidanceRoute(
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

    private static List<Vec3> planOpenPath(
            ServerLevel level, ServerPlayer player, NavigationTarget target) {
        CityRoutePlanner.Route guidance = guidanceRoute(level, player, target);
        if (guidance.points().size() < 2) return List.of();
        double furthest = Math.min(MAX_LOOKAHEAD_DISTANCE, guidance.length());
        double[] factors = {1.0, 0.75, 0.5, 0.3125, 0.1875};
        double previousDistance = Double.NaN;
        Villager probe = createPathProbe(level, player);
        if (probe == null) return List.of();
        try {
            for (double factor : factors) {
                double distance = furthest * factor;
                if (distance < 1.0 || Math.abs(distance - previousDistance) < 0.5) continue;
                previousDistance = distance;
                CityRoutePlanner.Point desired = pointAtDistance(guidance, distance);
                for (BlockPos checkpoint : loadedWalkableCheckpoints(
                        level, desired, player.getBlockY())) {
                    List<Vec3> path = findOpenPath(level, player, probe, checkpoint);
                    if (path.size() >= 2) return path;
                }
            }
        } finally {
            probe.discard();
        }
        return List.of();
    }

    private static CityRoutePlanner.Point pointAtDistance(
            CityRoutePlanner.Route route, double requestedDistance) {
        double remaining = Math.max(0.0, requestedDistance);
        List<CityRoutePlanner.Point> points = route.points();
        for (int index = 1; index < points.size(); index++) {
            CityRoutePlanner.Point from = points.get(index - 1);
            CityRoutePlanner.Point to = points.get(index);
            double length = Math.hypot(to.x() - from.x(), to.z() - from.z());
            if (length <= 1.0E-6) continue;
            if (remaining <= length) {
                double progress = remaining / length;
                return new CityRoutePlanner.Point(
                        from.x() + (to.x() - from.x()) * progress,
                        from.z() + (to.z() - from.z()) * progress);
            }
            remaining -= length;
        }
        return points.getLast();
    }

    private static List<BlockPos> loadedWalkableCheckpoints(
            ServerLevel level, CityRoutePlanner.Point desired, int preferredY) {
        int centerX = (int) Math.round(desired.x());
        int centerZ = (int) Math.round(desired.z());
        ArrayList<BlockPos> candidates = new ArrayList<>();
        for (int radius = 0; radius <= CHECKPOINT_SEARCH_RADIUS; radius++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) continue;
                    Vec3 ground = groundPoint(
                            level,
                            new CityRoutePlanner.Point(centerX + offsetX, centerZ + offsetZ),
                            preferredY);
                    if (ground == null) continue;
                    BlockPos candidate = BlockPos.containing(ground);
                    if (!candidates.contains(candidate)) candidates.add(candidate);
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((BlockPos position) ->
                        Math.abs(position.getY() - preferredY) * 4
                                + Math.abs(position.getX() - centerX)
                                + Math.abs(position.getZ() - centerZ))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        if (candidates.size() > MAX_CHECKPOINT_CANDIDATES) {
            return List.copyOf(candidates.subList(0, MAX_CHECKPOINT_CANDIDATES));
        }
        return List.copyOf(candidates);
    }

    /** Vanilla collision-aware path used by runtime and the focused obstacle GameTest. */
    static List<Vec3> findOpenPath(
            ServerLevel level, ServerPlayer player, BlockPos checkpoint) {
        if (level == null || player == null || checkpoint == null
                || !level.isLoaded(player.blockPosition()) || !level.isLoaded(checkpoint)) {
            return List.of();
        }
        Villager probe = createPathProbe(level, player);
        if (probe == null) return List.of();
        try {
            return findOpenPath(level, player, probe, checkpoint);
        } finally {
            probe.discard();
        }
    }

    private static Villager createPathProbe(ServerLevel level, ServerPlayer player) {
        Villager probe = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
        if (probe == null) return null;
        probe.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
        probe.setOnGround(true);
        PathNavigation navigation = probe.getNavigation();
        navigation.setRequiredPathLength(MAX_PATH_DISTANCE);
        navigation.setMaxVisitedNodesMultiplier(2.0F);
        return probe;
    }

    private static List<Vec3> findOpenPath(
            ServerLevel level,
            ServerPlayer player,
            Villager probe,
            BlockPos checkpoint) {
        if (!level.isLoaded(checkpoint)) return List.of();
        Path path = probe.getNavigation().createPath(checkpoint, 1, MAX_PATH_DISTANCE);
        if (path == null || !path.canReach() || path.getNodeCount() == 0) {
            return List.of();
        }
        ArrayList<Vec3> points = new ArrayList<>();
        points.add(new Vec3(player.getX(), player.getY() + 0.12, player.getZ()));
        for (int index = 0; index < path.getNodeCount(); index++) {
            Vec3 node = path.getEntityPosAtNode(probe, index).add(0.0, 0.12, 0.0);
            if (points.getLast().distanceToSqr(node) > 1.0E-4) points.add(node);
        }
        return List.copyOf(points);
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

    static List<Vec3> samplePath(
            List<Vec3> path, double maximumDistance, double spacing) {
        if (path == null || path.size() < 2
                || !Double.isFinite(maximumDistance) || maximumDistance <= 0.0
                || !Double.isFinite(spacing) || spacing <= 0.0) {
            return List.of();
        }
        ArrayList<Vec3> result = new ArrayList<>();
        double traversed = 0.0;
        double nextSample = spacing;
        for (int index = 1; index < path.size() && nextSample <= maximumDistance; index++) {
            Vec3 from = path.get(index - 1);
            Vec3 to = path.get(index);
            double segmentLength = from.distanceTo(to);
            if (segmentLength <= 1.0E-6) continue;
            while (nextSample <= traversed + segmentLength
                    && nextSample <= maximumDistance) {
                double progress = (nextSample - traversed) / segmentLength;
                result.add(from.lerp(to, progress));
                nextSample += spacing;
            }
            traversed += segmentLength;
        }
        if (result.isEmpty()) {
            Vec3 from = path.getFirst();
            Vec3 to = path.get(1);
            double segmentLength = from.distanceTo(to);
            if (segmentLength > 1.0E-6) {
                result.add(from.lerp(
                        to, Math.min(1.0, maximumDistance / segmentLength)));
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

    private record TrailSession(
            long expiresAt,
            long nextEmitAt,
            NavigationTarget target,
            Vec3 origin,
            List<Vec3> path) {
        private TrailSession {
            path = List.copyOf(path);
        }
    }
}
