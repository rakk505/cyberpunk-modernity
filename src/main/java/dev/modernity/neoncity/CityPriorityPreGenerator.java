package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Resumable background generation for the city's travel and mission-critical chunks. */
final class CityPriorityPreGenerator {
    static final int ROAD_MARGIN_CHUNKS = 1;
    static final int ROAD_SAMPLE_SPACING_BLOCKS = 8;
    static final int STATION_MARGIN_CHUNKS = 1;
    static final int MISSION_MARGIN_CHUNKS = 4;
    static final long MAX_AVERAGE_TICK_NANOS = 35_000_000L;

    private static final TicketType PREGEN_TICKET = new TicketType(
            12_345L, TicketType.FLAG_LOADING);
    private static final int PROGRESS_LOG_INTERVAL = 512;
    private static final int RETRY_DELAY_TICKS = 200;
    private static final int IDLE_GENERATION_INTERVAL_TICKS = 1;

    private static List<ChunkPos> plan = List.of();
    private static int cursor;
    private static int generatedThisRun;
    private static boolean paused;
    private static boolean waitingForPlayers;
    private static boolean completionLogged;
    private static ChunkPos loadingChunk;
    private static CompletableFuture<?> loadingFuture;
    private static long loadingStartedNanos;
    private static long nextAttemptTick;

    private CityPriorityPreGenerator() {
    }

    static void initialize(ServerLevel level) {
        stop(level);
        plan = buildPlan(NeonCityGenerator.layout(), MainlineQuestData.fixedSites().values());
        cursor = 0;
        generatedThisRun = 0;
        paused = false;
        waitingForPlayers = false;
        completionLogged = false;
        nextAttemptTick = level.getGameTime();
        advancePastGenerated();
        Status status = status();
        Cyberdeck.LOGGER.info(
                "[ProjectMoonCity] priority pre-generation scheduled: total={}, complete={}, "
                        + "remaining={}, road_margin={} chunk",
                status.total(), status.complete(), status.remaining(), ROAD_MARGIN_CHUNKS);
    }

    static void tick(ServerLevel level, boolean foregroundGeneratedChunk) {
        if (paused || plan.isEmpty()) {
            waitingForPlayers = false;
            return;
        }
        // This is speculative background work. Foreground generation still services chunks that
        // players approach, but priority pre-generation must never stamp a full chunk during play.
        waitingForPlayers = !level.players().isEmpty();
        if (waitingForPlayers) {
            if (loadingFuture != null) releaseTicket(level);
            return;
        }
        if (foregroundGeneratedChunk) {
            CityGenerationTrace.pregenSkipped(CityGenerationTrace.PregenSkip.FOREGROUND);
            return;
        }
        if (level.getServer().getAverageTickTimeNanos() > MAX_AVERAGE_TICK_NANOS) {
            CityGenerationTrace.pregenSkipped(CityGenerationTrace.PregenSkip.TICK_BUDGET);
            return;
        }

        if (loadingFuture != null) {
            if (!loadingFuture.isDone()) return;
            if (!finishLoadedChunk(level)) return;
        }

        advancePastGenerated();
        if (cursor >= plan.size()) {
            if (!completionLogged) {
                completionLogged = true;
                Cyberdeck.LOGGER.info(
                        "[ProjectMoonCity] priority pre-generation complete: {} chunks generated "
                                + "this run, {} total priority chunks",
                        generatedThisRun, plan.size());
            }
            return;
        }
        if (level.getGameTime() < nextAttemptTick) return;

        loadingChunk = plan.get(cursor);
        CityChunkPlanner.request(loadingChunk, CityChunkPlanner.Priority.PREGEN);
        loadingStartedNanos = CityGenerationTrace.loadStarted();
        loadingFuture = level.getChunkSource().addTicketAndLoadWithRadius(
                PREGEN_TICKET, loadingChunk, 0);
    }

    private static boolean finishLoadedChunk(ServerLevel level) {
        ChunkPos chunk = loadingChunk;
        try {
            loadingFuture.join();
        } catch (CompletionException exception) {
            Cyberdeck.LOGGER.warn(
                    "[ProjectMoonCity] priority chunk {} failed to load; retrying", chunk,
                    exception.getCause());
            releaseTicket(level);
            nextAttemptTick = level.getGameTime() + RETRY_DELAY_TICKS;
            return false;
        }

        if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
            Cyberdeck.LOGGER.warn(
                    "[ProjectMoonCity] priority chunk {} completed without becoming available; "
                            + "retrying",
                    chunk);
            releaseTicket(level);
            nextAttemptTick = level.getGameTime() + RETRY_DELAY_TICKS;
            return false;
        }
        CityGenerationTrace.loadFinished(chunk, loadingStartedNanos);

        boolean generatedByPregen = false;
        if (!NeonCityGenerator.isGenerated(chunk)) {
            NeonCityGenerator.generateNow(
                    level, chunk.x(), chunk.z(), 0, CityGenerationTrace.Source.PREGEN);
            generatedByPregen = NeonCityGenerator.isGenerated(chunk);
        } else {
            CityGenerationTrace.loadDiscarded(chunk);
        }
        if (!NeonCityGenerator.isGenerated(chunk)) {
            Cyberdeck.LOGGER.warn(
                    "[ProjectMoonCity] priority chunk {} failed city stamping; retrying", chunk);
            releaseTicket(level);
            nextAttemptTick = level.getGameTime() + RETRY_DELAY_TICKS;
            return false;
        }

        if (generatedByPregen) generatedThisRun++;
        cursor++;
        releaseTicket(level);
        nextAttemptTick = level.getGameTime() + IDLE_GENERATION_INTERVAL_TICKS;
        if (generatedByPregen && generatedThisRun % PROGRESS_LOG_INTERVAL == 0) {
            Status status = status();
            Cyberdeck.LOGGER.info(
                    "[ProjectMoonCity] priority pre-generation progress: complete={}/{}, "
                            + "remaining={}, generated_this_run={}",
                    status.complete(), status.total(), status.remaining(), generatedThisRun);
        }
        return true;
    }

    private static void releaseTicket(ServerLevel level) {
        if (loadingChunk != null) {
            level.getChunkSource().removeTicketWithRadius(PREGEN_TICKET, loadingChunk, 0);
            CityChunkPlanner.cancel(loadingChunk.pack());
        }
        loadingChunk = null;
        loadingFuture = null;
        loadingStartedNanos = 0L;
    }

    static void stop(ServerLevel level) {
        releaseTicket(level);
        plan = List.of();
        cursor = 0;
        generatedThisRun = 0;
        waitingForPlayers = false;
        completionLogged = false;
    }

    static void pause() {
        paused = true;
    }

    static void resume() {
        paused = false;
    }

    static Status status() {
        int complete = 0;
        for (ChunkPos chunk : plan) {
            if (NeonCityGenerator.isGenerated(chunk)) complete++;
        }
        return new Status(
                plan.size(), complete, plan.size() - complete, paused,
                waitingForPlayers, loadingChunk != null);
    }

    private static void advancePastGenerated() {
        while (cursor < plan.size() && NeonCityGenerator.isGenerated(plan.get(cursor))) {
            cursor++;
        }
    }

    static List<ChunkPos> buildPlan(
            MegacityLayout layout,
            Iterable<MissionBuildingPlanner.Site> rawMissionSites) {
        LinkedHashSet<Long> chunks = new LinkedHashSet<>();
        addSquare(chunks, ChunkPos.ZERO.x(), ChunkPos.ZERO.z(), STATION_MARGIN_CHUNKS);

        LinkedHashSet<Long> roadChunks = new LinkedHashSet<>();
        for (MegacityLayout.Edge edge : layout.edges()) {
            int steps = Math.max(1, (int) Math.ceil(
                    approximateLength(edge) / ROAD_SAMPLE_SPACING_BLOCKS));
            for (int step = 0; step <= steps; step++) {
                MegacityLayout.CurvePoint point = MegacityLayout.curvePoint(
                        edge, step / (double) steps);
                addSquare(
                        roadChunks,
                        Math.floorDiv((int) Math.round(point.x()), 16),
                        Math.floorDiv((int) Math.round(point.z()), 16),
                        ROAD_MARGIN_CHUNKS);
            }
        }
        roadChunks.stream()
                .map(ChunkPos::unpack)
                .filter(chunk -> chunkTouchesCity(layout, chunk))
                .sorted(Comparator.comparingLong(CityPriorityPreGenerator::distanceFromSpawn))
                .map(ChunkPos::pack)
                .forEach(chunks::add);

        for (MegacityLayout.Node node : layout.nodes()) {
            addSquare(
                    chunks,
                    Math.floorDiv(node.x(), 16),
                    Math.floorDiv(node.z(), 16),
                    STATION_MARGIN_CHUNKS);
        }

        ArrayList<MissionBuildingPlanner.Site> missionSites = new ArrayList<>();
        rawMissionSites.forEach(missionSites::add);
        missionSites.sort(Comparator
                .comparingInt((MissionBuildingPlanner.Site site) -> site.district().ordinal())
                .thenComparingInt(site -> site.bounds().minX())
                .thenComparingInt(site -> site.bounds().minZ()));
        for (MissionBuildingPlanner.Site site : missionSites) {
            addBounds(chunks, site.bounds(), MISSION_MARGIN_CHUNKS);
        }

        return chunks.stream()
                .map(ChunkPos::unpack)
                .filter(chunk -> chunkTouchesCity(layout, chunk))
                .toList();
    }

    private static long distanceFromSpawn(ChunkPos chunk) {
        return (long) chunk.x() * chunk.x() + (long) chunk.z() * chunk.z();
    }

    private static double approximateLength(MegacityLayout.Edge edge) {
        double length = 0.0;
        MegacityLayout.CurvePoint previous = MegacityLayout.curvePoint(edge, 0.0);
        for (int sample = 1; sample <= 128; sample++) {
            MegacityLayout.CurvePoint current = MegacityLayout.curvePoint(
                    edge, sample / 128.0);
            length += Math.hypot(
                    current.x() - previous.x(), current.z() - previous.z());
            previous = current;
        }
        return length;
    }

    private static void addBounds(Set<Long> chunks, BoundingBox bounds, int margin) {
        int minChunkX = Math.floorDiv(bounds.minX(), 16) - margin;
        int maxChunkX = Math.floorDiv(bounds.maxX(), 16) + margin;
        int minChunkZ = Math.floorDiv(bounds.minZ(), 16) - margin;
        int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16) + margin;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                chunks.add(ChunkPos.pack(chunkX, chunkZ));
            }
        }
    }

    private static void addSquare(Set<Long> chunks, int centerX, int centerZ, int radius) {
        for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
            for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                chunks.add(ChunkPos.pack(centerX + deltaX, centerZ + deltaZ));
            }
        }
    }

    private static boolean chunkTouchesCity(MegacityLayout layout, ChunkPos chunk) {
        int[] offsets = {0, 8, 15};
        for (int offsetZ : offsets) {
            for (int offsetX : offsets) {
                if (layout.containsCity(
                        chunk.getMinBlockX() + offsetX,
                        chunk.getMinBlockZ() + offsetZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    record Status(
            int total,
            int complete,
            int remaining,
            boolean paused,
            boolean waitingForPlayers,
            boolean loading) {
    }
}
