package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

/** Opt-in, bounded performance tracing for megacity chunk generation. */
final class CityGenerationTrace {
    static final int MAX_RECORDS = 4_096;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static volatile Session active;
    private static Session last;

    private CityGenerationTrace() {
    }

    enum Source {
        URGENT,
        NEAR,
        NORMAL,
        PREGEN,
        MANUAL,
        PREWARM
    }

    enum Phase {
        ASYNC_SAMPLE,
        ASYNC_PLANNING,
        SAMPLE,
        PLANNING,
        COLUMNS,
        INFRASTRUCTURE,
        ARNIS,
        WORLD_FEATURES,
        STATIONS,
        LOGO_BANNERS,
        CITY_LOOT,
        URBAN_CRATES,
        CLIENT_REFRESH
    }

    enum PregenSkip {
        FOREGROUND,
        TICK_BUDGET
    }

    static boolean start(ServerLevel level, int durationSeconds) {
        if (active != null) return false;
        long now = System.nanoTime();
        Session session = new Session(
                Instant.now(), now, now + durationSeconds * 1_000_000_000L,
                durationSeconds, level.getServer().getAverageTickTimeNanos());
        last = null;
        active = session;
        Cyberdeck.LOGGER.info(
                "[ProjectMoonCity] generation trace started for {} seconds", durationSeconds);
        return true;
    }

    static void tick(ServerLevel level) {
        Session session = active;
        if (session == null) return;
        session.observeTick(level.getServer().getAverageTickTimeNanos());
        session.maximumOutstandingPlans = Math.max(
                session.maximumOutstandingPlans, CityChunkPlanner.outstandingPlans());
        if (System.nanoTime() >= session.deadlineNanos) {
            finish("duration_complete");
        }
    }

    static boolean stop() {
        return finish("operator_stop") != null;
    }

    static void reset() {
        active = null;
        last = null;
    }

    private static Session finish(String reason) {
        Session session = active;
        if (session == null) return null;
        session.stoppedNanos = System.nanoTime();
        session.stopReason = reason;
        active = null;
        last = session;
        Status status = status();
        Cyberdeck.LOGGER.info(
                "[ProjectMoonCity] generation trace stopped: chunks={}, rate={} chunks/s, "
                        + "stamp_avg={} ms, stamp_p95={} ms",
                status.chunks(), format(status.chunksPerSecond()),
                format(status.averageStampMillis()), format(status.p95StampMillis()));
        return session;
    }

    static boolean isActive() {
        return active != null;
    }

    static void queued(ChunkPos chunk, Source source) {
        Session session = active;
        if (session == null) return;
        session.queued.put(chunk.pack(), new QueueEntry(System.nanoTime(), source));
    }

    static void promoted(ChunkPos chunk, Source source) {
        Session session = active;
        if (session == null) return;
        session.queued.put(chunk.pack(), new QueueEntry(System.nanoTime(), source));
    }

    static void dequeued(long chunkKey) {
        Session session = active;
        if (session != null) session.queued.remove(chunkKey);
    }

    static void queueCandidateUnavailable(Source source) {
        Session session = active;
        if (session != null) session.unavailableCandidates.merge(source, 1L, Long::sum);
    }

    static long loadStarted() {
        return active == null ? 0L : System.nanoTime();
    }

    static void loadFinished(ChunkPos chunk, long startedNanos) {
        Session session = active;
        if (session == null || startedNanos == 0L) return;
        long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        session.loadSamples++;
        session.loadNanosTotal += elapsed;
        session.loadNanos.put(chunk.pack(), elapsed);
    }

    static void loadDiscarded(ChunkPos chunk) {
        Session session = active;
        if (session != null) session.loadNanos.remove(chunk.pack());
    }

    static void pregenSkipped(PregenSkip reason) {
        Session session = active;
        if (session != null) session.pregenSkips.merge(reason, 1L, Long::sum);
    }

    static void travelLookahead(
            double speedBlocksPerSecond,
            double readyAheadBlocks,
            double plannedAheadBlocks,
            int promotedChunks,
            boolean positionFallback) {
        Session session = active;
        if (session == null) return;
        double headroomSeconds = speedBlocksPerSecond <= 0.0
                ? 0.0 : readyAheadBlocks / speedBlocksPerSecond;
        session.lookaheadSamples++;
        session.latestSpeedBlocksPerSecond = speedBlocksPerSecond;
        session.latestReadyAheadBlocks = readyAheadBlocks;
        session.latestPlannedAheadBlocks = plannedAheadBlocks;
        session.latestHeadroomSeconds = headroomSeconds;
        session.minimumHeadroomSeconds = Math.min(
                session.minimumHeadroomSeconds, headroomSeconds);
        session.promotedTravelChunks += promotedChunks;
        if (positionFallback) session.positionFallbackSamples++;
    }

    static void foregroundBatch(int chunks, long elapsedNanos, boolean stoppedByBudget) {
        Session session = active;
        if (session == null) return;
        session.foregroundBatches++;
        session.foregroundBatchChunks += chunks;
        session.foregroundExtraChunks += Math.max(0, chunks - 1);
        session.maximumForegroundBatch = Math.max(session.maximumForegroundBatch, chunks);
        session.foregroundBatchNanos += elapsedNanos;
        if (stoppedByBudget) session.foregroundBudgetStops++;
    }

    static void asyncPlanUsed() {
        Session session = active;
        if (session != null) session.asyncPlanHits++;
    }

    static void synchronousPlanFallback() {
        Session session = active;
        if (session != null) session.synchronousPlanFallbacks++;
    }

    static ChunkSpan begin(ChunkPos chunk, Source source) {
        Session session = active;
        if (session == null) return null;
        long now = System.nanoTime();
        QueueEntry queued = session.queued.remove(chunk.pack());
        long queueNanos = queued == null ? -1L : Math.max(0L, now - queued.startedNanos());
        Source effectiveSource = queued == null ? source : queued.source();
        Long loadNanos = session.loadNanos.remove(chunk.pack());
        ChunkSpan span = new ChunkSpan(
                session, chunk, effectiveSource, now, queueNanos,
                loadNanos == null ? -1L : loadNanos);
        session.currentSpan = span;
        return span;
    }

    static void blockChanged() {
        Session session = active;
        if (session != null && session.currentSpan != null) {
            session.currentSpan.directBlockChanges++;
        }
    }

    static Status status() {
        Session session = active != null ? active : last;
        if (session == null) return Status.EMPTY;
        return session.status(active == session);
    }

    static Path export(ServerLevel level) throws IOException {
        Session session = active != null ? active : last;
        if (session == null) return null;
        JsonObject report = session.toJson(active == session);
        Path directory = level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("neoncity").resolve("traces");
        Files.createDirectories(directory);
        String baseName = "generation-" + FILE_TIME.format(session.startedAt)
                + "-" + session.startedAt.toEpochMilli();
        Path target = directory.resolve(baseName + ".json");
        Path temporary = directory.resolve(baseName + ".json.tmp");
        Files.writeString(temporary, GSON.toJson(report), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    static long percentileNanos(long[] values, double percentile) {
        if (values.length == 0) return 0L;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static final class ChunkSpan {
        private final Session session;
        private final ChunkPos chunk;
        private final Source source;
        private final long startedNanos;
        private final long queueNanos;
        private final long loadNanos;
        private final long[] phaseNanos = new long[Phase.values().length];
        private Phase phase;
        private long phaseStartedNanos;
        private int directBlockChanges;
        private boolean finished;

        private ChunkSpan(
                Session session,
                ChunkPos chunk,
                Source source,
                long startedNanos,
                long queueNanos,
                long loadNanos) {
            this.session = session;
            this.chunk = chunk;
            this.source = source;
            this.startedNanos = startedNanos;
            this.queueNanos = queueNanos;
            this.loadNanos = loadNanos;
        }

        void phase(Phase next) {
            long now = System.nanoTime();
            closePhase(now);
            phase = next;
            phaseStartedNanos = now;
        }

        void completedPhase(Phase completed, long elapsedNanos) {
            phaseNanos[completed.ordinal()] += Math.max(0L, elapsedNanos);
        }

        void finish(boolean success) {
            if (finished) return;
            finished = true;
            long now = System.nanoTime();
            closePhase(now);
            if (session.currentSpan == this) session.currentSpan = null;
            session.record(new ChunkRecord(
                    chunk.x(), chunk.z(), source, success,
                    Math.max(0L, now - startedNanos), queueNanos, loadNanos,
                    directBlockChanges, phaseNanos.clone()));
        }

        private void closePhase(long now) {
            if (phase != null) {
                phaseNanos[phase.ordinal()] += Math.max(0L, now - phaseStartedNanos);
            }
        }
    }

    private record QueueEntry(long startedNanos, Source source) {
    }

    private record ChunkRecord(
            int chunkX,
            int chunkZ,
            Source source,
            boolean success,
            long stampNanos,
            long queueNanos,
            long loadNanos,
            int directBlockChanges,
            long[] phaseNanos) {
    }

    record Status(
            boolean available,
            boolean active,
            int targetSeconds,
            double elapsedSeconds,
            long chunks,
            long failures,
            double chunksPerSecond,
            double averageStampMillis,
            double p50StampMillis,
            double p95StampMillis,
            double p99StampMillis,
            double averageQueueMillis,
            double averageLoadMillis,
            long directBlockChanges,
            long foregroundSkips,
            long tickBudgetSkips,
            long foregroundBatches,
            long foregroundExtraChunks,
            long foregroundBudgetStops,
            long lookaheadSamples,
            long positionFallbackSamples,
            double latestReadyAheadBlocks,
            double latestHeadroomSeconds,
            double minimumHeadroomSeconds,
            int sampledRecords,
            String stopReason) {
        private static final Status EMPTY = new Status(
                false, false, 0, 0.0, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0, 0.0, 0.0,
                0, "none");
    }

    private static final class Session {
        private final Instant startedAt;
        private final long startedNanos;
        private final long deadlineNanos;
        private final int targetSeconds;
        private final long startingAverageTickNanos;
        private final ArrayDeque<ChunkRecord> records = new ArrayDeque<>();
        private final Map<Long, QueueEntry> queued = new HashMap<>();
        private final Map<Long, Long> loadNanos = new HashMap<>();
        private final EnumMap<Source, Long> sourceCounts = new EnumMap<>(Source.class);
        private final EnumMap<Source, Long> unavailableCandidates = new EnumMap<>(Source.class);
        private final EnumMap<PregenSkip, Long> pregenSkips = new EnumMap<>(PregenSkip.class);
        private final long[] phaseTotals = new long[Phase.values().length];
        private long stoppedNanos;
        private String stopReason = "running";
        private long chunks;
        private long failures;
        private long stampNanos;
        private long queueNanosTotal;
        private long queueSamples;
        private long loadNanosTotal;
        private long loadSamples;
        private long directBlockChanges;
        private long observedTicks;
        private long observedTickNanos;
        private long maximumAverageTickNanos;
        private long lookaheadSamples;
        private long positionFallbackSamples;
        private long promotedTravelChunks;
        private double latestSpeedBlocksPerSecond;
        private double latestReadyAheadBlocks;
        private double latestPlannedAheadBlocks;
        private double latestHeadroomSeconds;
        private double minimumHeadroomSeconds = Double.POSITIVE_INFINITY;
        private long foregroundBatches;
        private long foregroundBatchChunks;
        private long foregroundExtraChunks;
        private long foregroundBudgetStops;
        private int maximumForegroundBatch;
        private long foregroundBatchNanos;
        private long asyncPlanHits;
        private long synchronousPlanFallbacks;
        private int maximumOutstandingPlans;
        private ChunkRecord slowest;
        private ChunkSpan currentSpan;

        private Session(
                Instant startedAt,
                long startedNanos,
                long deadlineNanos,
                int targetSeconds,
                long startingAverageTickNanos) {
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
            this.deadlineNanos = deadlineNanos;
            this.targetSeconds = targetSeconds;
            this.startingAverageTickNanos = startingAverageTickNanos;
        }

        private void observeTick(long averageTickNanos) {
            observedTicks++;
            observedTickNanos += averageTickNanos;
            maximumAverageTickNanos = Math.max(maximumAverageTickNanos, averageTickNanos);
        }

        private void record(ChunkRecord record) {
            chunks++;
            if (!record.success()) failures++;
            stampNanos += record.stampNanos();
            directBlockChanges += record.directBlockChanges();
            sourceCounts.merge(record.source(), 1L, Long::sum);
            if (record.queueNanos() >= 0L) {
                queueSamples++;
                queueNanosTotal += record.queueNanos();
            }
            for (int index = 0; index < phaseTotals.length; index++) {
                phaseTotals[index] += record.phaseNanos()[index];
            }
            if (slowest == null || record.stampNanos() > slowest.stampNanos()) {
                slowest = record;
            }
            if (records.size() == MAX_RECORDS) records.removeFirst();
            records.addLast(record);
        }

        private Status status(boolean running) {
            long now = running ? System.nanoTime() : stoppedNanos;
            double elapsedSeconds = Math.max(0.001, (now - startedNanos) / 1_000_000_000.0);
            long[] samples = records.stream().mapToLong(ChunkRecord::stampNanos).toArray();
            return new Status(
                    true,
                    running,
                    targetSeconds,
                    elapsedSeconds,
                    chunks,
                    failures,
                    chunks / elapsedSeconds,
                    millis(stampNanos, chunks),
                    percentileNanos(samples, 0.50) / 1_000_000.0,
                    percentileNanos(samples, 0.95) / 1_000_000.0,
                    percentileNanos(samples, 0.99) / 1_000_000.0,
                    millis(queueNanosTotal, queueSamples),
                    millis(loadNanosTotal, loadSamples),
                    directBlockChanges,
                    pregenSkips.getOrDefault(PregenSkip.FOREGROUND, 0L),
                    pregenSkips.getOrDefault(PregenSkip.TICK_BUDGET, 0L),
                    foregroundBatches,
                    foregroundExtraChunks,
                    foregroundBudgetStops,
                    lookaheadSamples,
                    positionFallbackSamples,
                    latestReadyAheadBlocks,
                    latestHeadroomSeconds,
                    lookaheadSamples == 0L ? 0.0 : minimumHeadroomSeconds,
                    records.size(),
                    stopReason);
        }

        private JsonObject toJson(boolean running) {
            Status status = status(running);
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", 1);
            root.addProperty("started_at", startedAt.toString());
            root.addProperty("active", running);
            root.addProperty("stop_reason", stopReason);
            root.addProperty("target_seconds", targetSeconds);
            root.addProperty("elapsed_seconds", status.elapsedSeconds());

            JsonObject summary = new JsonObject();
            summary.addProperty("chunks", chunks);
            summary.addProperty("failures", failures);
            summary.addProperty("chunks_per_second", status.chunksPerSecond());
            summary.addProperty("direct_block_changes", directBlockChanges);
            summary.addProperty("direct_block_changes_per_second",
                    directBlockChanges / Math.max(0.001, status.elapsedSeconds()));
            summary.addProperty("stamp_average_ms", status.averageStampMillis());
            summary.addProperty("stamp_p50_ms", status.p50StampMillis());
            summary.addProperty("stamp_p95_ms", status.p95StampMillis());
            summary.addProperty("stamp_p99_ms", status.p99StampMillis());
            summary.addProperty("queue_average_ms", status.averageQueueMillis());
            summary.addProperty("queue_samples", queueSamples);
            summary.addProperty("load_average_ms", status.averageLoadMillis());
            summary.addProperty("load_samples", loadSamples);
            summary.addProperty("sampled_records", records.size());
            if (slowest != null) {
                summary.addProperty("slowest_stamp_ms", slowest.stampNanos() / 1_000_000.0);
            }
            root.add("summary", summary);

            JsonObject phases = new JsonObject();
            for (Phase phase : Phase.values()) {
                JsonObject value = new JsonObject();
                value.addProperty("total_ms", phaseTotals[phase.ordinal()] / 1_000_000.0);
                value.addProperty("average_ms", millis(phaseTotals[phase.ordinal()], chunks));
                phases.add(phase.name().toLowerCase(Locale.ROOT), value);
            }
            root.add("phases", phases);

            JsonObject sources = new JsonObject();
            for (Source source : Source.values()) {
                sources.addProperty(
                        source.name().toLowerCase(Locale.ROOT),
                        sourceCounts.getOrDefault(source, 0L));
            }
            root.add("sources", sources);

            JsonObject scheduling = new JsonObject();
            scheduling.addProperty("pregen_skipped_foreground",
                    pregenSkips.getOrDefault(PregenSkip.FOREGROUND, 0L));
            scheduling.addProperty("pregen_skipped_tick_budget",
                    pregenSkips.getOrDefault(PregenSkip.TICK_BUDGET, 0L));
            scheduling.addProperty("foreground_batches", foregroundBatches);
            scheduling.addProperty("foreground_batch_chunks", foregroundBatchChunks);
            scheduling.addProperty("foreground_extra_chunks", foregroundExtraChunks);
            scheduling.addProperty("foreground_budget_stops", foregroundBudgetStops);
            scheduling.addProperty("foreground_maximum_batch", maximumForegroundBatch);
            scheduling.addProperty("foreground_average_batch",
                    foregroundBatches == 0L
                            ? 0.0 : foregroundBatchChunks / (double) foregroundBatches);
            scheduling.addProperty("foreground_average_batch_ms",
                    millis(foregroundBatchNanos, foregroundBatches));
            scheduling.addProperty("async_plan_hits", asyncPlanHits);
            scheduling.addProperty("synchronous_plan_fallbacks", synchronousPlanFallbacks);
            scheduling.addProperty("async_plan_hit_rate",
                    asyncPlanHits + synchronousPlanFallbacks == 0L
                            ? 0.0
                            : asyncPlanHits / (double) (asyncPlanHits + synchronousPlanFallbacks));
            scheduling.addProperty("planner_workers", CityChunkPlanner.WORKER_COUNT);
            scheduling.addProperty(
                    "planner_capacity", CityChunkPlanner.MAX_OUTSTANDING_PLANS);
            scheduling.addProperty("planner_maximum_outstanding", maximumOutstandingPlans);
            for (Source source : Source.values()) {
                scheduling.addProperty(
                        "unavailable_candidate_checks_"
                                + source.name().toLowerCase(Locale.ROOT),
                        unavailableCandidates.getOrDefault(source, 0L));
            }
            root.add("scheduling", scheduling);

            JsonObject driving = new JsonObject();
            driving.addProperty("lookahead_samples", lookaheadSamples);
            driving.addProperty("position_fallback_samples", positionFallbackSamples);
            driving.addProperty("promoted_chunks", promotedTravelChunks);
            driving.addProperty("latest_speed_blocks_per_second",
                    latestSpeedBlocksPerSecond);
            driving.addProperty("latest_ready_ahead_blocks", latestReadyAheadBlocks);
            driving.addProperty("latest_planned_ahead_blocks", latestPlannedAheadBlocks);
            driving.addProperty("latest_headroom_seconds", latestHeadroomSeconds);
            driving.addProperty("minimum_headroom_seconds",
                    lookaheadSamples == 0L ? 0.0 : minimumHeadroomSeconds);
            root.add("driving", driving);

            JsonObject ticks = new JsonObject();
            ticks.addProperty("starting_average_ms", startingAverageTickNanos / 1_000_000.0);
            ticks.addProperty("observed_average_ms", millis(observedTickNanos, observedTicks));
            ticks.addProperty("maximum_observed_average_ms",
                    maximumAverageTickNanos / 1_000_000.0);
            ticks.addProperty("observations", observedTicks);
            root.add("server_ticks", ticks);

            JsonArray sampleArray = new JsonArray();
            for (ChunkRecord record : records) sampleArray.add(recordJson(record));
            root.add("chunks", sampleArray);
            if (slowest != null) root.add("slowest_chunk", recordJson(slowest));
            return root;
        }

        private static JsonObject recordJson(ChunkRecord record) {
            JsonObject value = new JsonObject();
            value.addProperty("chunk_x", record.chunkX());
            value.addProperty("chunk_z", record.chunkZ());
            value.addProperty("source", record.source().name().toLowerCase(Locale.ROOT));
            value.addProperty("success", record.success());
            value.addProperty("stamp_ms", record.stampNanos() / 1_000_000.0);
            if (record.queueNanos() >= 0L) {
                value.addProperty("queue_ms", record.queueNanos() / 1_000_000.0);
            }
            if (record.loadNanos() >= 0L) {
                value.addProperty("load_ms", record.loadNanos() / 1_000_000.0);
            }
            value.addProperty("direct_block_changes", record.directBlockChanges());
            JsonObject phases = new JsonObject();
            for (Phase phase : Phase.values()) {
                phases.addProperty(
                        phase.name().toLowerCase(Locale.ROOT) + "_ms",
                        record.phaseNanos()[phase.ordinal()] / 1_000_000.0);
            }
            value.add("phases", phases);
            return value;
        }
    }

    private static double millis(long nanos, long count) {
        return count == 0L ? 0.0 : nanos / 1_000_000.0 / count;
    }
}
