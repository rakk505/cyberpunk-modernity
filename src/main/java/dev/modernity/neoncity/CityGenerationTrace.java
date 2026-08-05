package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
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
        BANNER_SCAN,
        BANNER_QUEUE,
        CITY_LOOT,
        URBAN_CRATES,
        ADS,
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
        session.observeQueueDepth(NeonCityGenerator.pendingQueueDepth());
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

    static void deferredBannerPlacement(
            long elapsedNanos, boolean success, int remainingBanners) {
        Session session = active;
        if (session == null) return;
        session.deferredBannerAttempts++;
        if (success) session.deferredBannerSuccesses++;
        session.deferredBannerNanos += elapsedNanos;
        session.maximumDeferredBannerNanos = Math.max(
                session.maximumDeferredBannerNanos, elapsedNanos);
        session.maximumPendingBanners = Math.max(
                session.maximumPendingBanners, remainingBanners);
    }

    /** Records one Arnis-tile branded ad placement attempt (facade rectangles). */
    static void adArnisTile(
            boolean placed, boolean worldBlocked, boolean retryable, long elapsedNanos) {
        Session session = active;
        if (session == null) return;
        session.adArnisAttempts++;
        if (placed) {
            session.adArnisPlaced++;
        } else if (worldBlocked) {
            session.adArnisWorldBlocked++;
        } else if (retryable) {
            session.adArnisRetryable++;
        } else {
            session.adArnisNotApplicable++;
        }
        session.adArnisNanos += Math.max(0L, elapsedNanos);
        session.adArnisMaxNanos = Math.max(session.adArnisMaxNanos, elapsedNanos);
    }

    /** Records one district freestanding ad decoration pass (medium + small candidates). */
    static void adDistrict(
            boolean applicable, int presentStructures, int placedStructures, long elapsedNanos) {
        Session session = active;
        if (session == null) return;
        session.adDistrictCalls++;
        if (applicable) session.adDistrictApplicable++;
        session.adDistrictPresent += presentStructures;
        session.adDistrictPlaced += placedStructures;
        session.adDistrictNanos += Math.max(0L, elapsedNanos);
        session.adDistrictMaxNanos = Math.max(session.adDistrictMaxNanos, elapsedNanos);
    }

    /** Records one mainline-reservation guard check gating Arnis-tile ad placement. */
    static void adReservationGuard(long elapsedNanos) {
        Session session = active;
        if (session == null) return;
        session.adReservationChecks++;
        session.adReservationNanos += Math.max(0L, elapsedNanos);
        session.adReservationMaxNanos = Math.max(session.adReservationMaxNanos, elapsedNanos);
    }

    /** Records one deferred Arnis-tile ad backfill attempt (runs after the chunk generated). */
    static void adBackfill(boolean placed, boolean retryable, long elapsedNanos) {
        Session session = active;
        if (session == null) return;
        session.adBackfillAttempts++;
        if (placed) {
            session.adBackfillPlaced++;
        } else if (retryable) {
            session.adBackfillRetryable++;
        } else {
            session.adBackfillOther++;
        }
        session.adBackfillNanos += Math.max(0L, elapsedNanos);
        session.adBackfillMaxNanos = Math.max(session.adBackfillMaxNanos, elapsedNanos);
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
        private final long startAllocatedBytes;
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
            this.startAllocatedBytes = currentThreadAllocatedBytes();
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
            if (startAllocatedBytes >= 0L) {
                session.recordForegroundAllocation(
                        currentThreadAllocatedBytes() - startAllocatedBytes);
            }
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
        private long deferredBannerAttempts;
        private long deferredBannerSuccesses;
        private long deferredBannerNanos;
        private long maximumDeferredBannerNanos;
        private int maximumPendingBanners;
        private long adArnisAttempts;
        private long adArnisPlaced;
        private long adArnisWorldBlocked;
        private long adArnisRetryable;
        private long adArnisNotApplicable;
        private long adArnisNanos;
        private long adArnisMaxNanos;
        private long adDistrictCalls;
        private long adDistrictApplicable;
        private long adDistrictPresent;
        private long adDistrictPlaced;
        private long adDistrictNanos;
        private long adDistrictMaxNanos;
        private long adReservationChecks;
        private long adReservationNanos;
        private long adReservationMaxNanos;
        private long adBackfillAttempts;
        private long adBackfillPlaced;
        private long adBackfillRetryable;
        private long adBackfillOther;
        private long adBackfillNanos;
        private long adBackfillMaxNanos;
        private final long startingGcCount;
        private final long startingGcMillis;
        private final long startingAllocatedBytes;
        private long foregroundAllocatedBytes;
        private long maximumForegroundAllocatedBytes;
        private long foregroundAllocationSamples;
        private int maximumQueueDepth;
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
            this.startingGcCount = totalGcCount();
            this.startingGcMillis = totalGcMillis();
            this.startingAllocatedBytes = totalAllocatedBytes();
        }

        private void recordForegroundAllocation(long bytes) {
            if (bytes < 0L) return;
            foregroundAllocationSamples++;
            foregroundAllocatedBytes += bytes;
            maximumForegroundAllocatedBytes = Math.max(maximumForegroundAllocatedBytes, bytes);
        }

        private void observeTick(long averageTickNanos) {
            observedTicks++;
            observedTickNanos += averageTickNanos;
            maximumAverageTickNanos = Math.max(maximumAverageTickNanos, averageTickNanos);
        }

        private void observeQueueDepth(int depth) {
            maximumQueueDepth = Math.max(maximumQueueDepth, depth);
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
            root.addProperty("schema_version", 4);
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
            long[] queueSamplesNanos = records.stream()
                    .mapToLong(ChunkRecord::queueNanos)
                    .filter(value -> value >= 0L)
                    .toArray();
            summary.addProperty(
                    "queue_p95_ms", percentileNanos(queueSamplesNanos, 0.95) / 1_000_000.0);
            summary.addProperty(
                    "queue_p99_ms", percentileNanos(queueSamplesNanos, 0.99) / 1_000_000.0);
            summary.addProperty(
                    "queue_maximum_ms", percentileNanos(queueSamplesNanos, 1.0) / 1_000_000.0);
            summary.addProperty("queue_samples", queueSamples);
            summary.addProperty("maximum_queue_depth", maximumQueueDepth);
            summary.addProperty("load_average_ms", status.averageLoadMillis());
            summary.addProperty("load_samples", loadSamples);
            summary.addProperty("sampled_records", records.size());
            if (slowest != null) {
                summary.addProperty("slowest_stamp_ms", slowest.stampNanos() / 1_000_000.0);
            }
            root.add("summary", summary);

            JsonObject phases = new JsonObject();
            for (Phase phase : Phase.values()) {
                int ordinal = phase.ordinal();
                long[] phaseSamples = records.stream()
                        .mapToLong(record -> record.phaseNanos()[ordinal])
                        .toArray();
                JsonObject value = new JsonObject();
                value.addProperty("total_ms", phaseTotals[ordinal] / 1_000_000.0);
                value.addProperty("average_ms", millis(phaseTotals[ordinal], chunks));
                value.addProperty("p50_ms", percentileNanos(phaseSamples, 0.50) / 1_000_000.0);
                value.addProperty("p95_ms", percentileNanos(phaseSamples, 0.95) / 1_000_000.0);
                value.addProperty("p99_ms", percentileNanos(phaseSamples, 0.99) / 1_000_000.0);
                value.addProperty("maximum_ms", percentileNanos(phaseSamples, 1.0) / 1_000_000.0);
                value.addProperty(
                        "share_of_stamp",
                        stampNanos == 0L ? 0.0 : phaseTotals[ordinal] / (double) stampNanos);
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

            JsonObject deferredBanners = new JsonObject();
            deferredBanners.addProperty("attempts", deferredBannerAttempts);
            deferredBanners.addProperty("successes", deferredBannerSuccesses);
            deferredBanners.addProperty(
                    "failures", deferredBannerAttempts - deferredBannerSuccesses);
            deferredBanners.addProperty(
                    "average_ms", millis(deferredBannerNanos, deferredBannerAttempts));
            deferredBanners.addProperty(
                    "maximum_ms", maximumDeferredBannerNanos / 1_000_000.0);
            deferredBanners.addProperty("pending_at_export", DistrictLogoBanners.pendingCount());
            deferredBanners.addProperty("maximum_pending_after_attempt", maximumPendingBanners);
            root.add("deferred_banners", deferredBanners);

            JsonObject ads = new JsonObject();
            JsonObject arnisTileAds = new JsonObject();
            arnisTileAds.addProperty("attempts", adArnisAttempts);
            arnisTileAds.addProperty("placed", adArnisPlaced);
            arnisTileAds.addProperty("world_blocked", adArnisWorldBlocked);
            arnisTileAds.addProperty("retryable_failures", adArnisRetryable);
            arnisTileAds.addProperty("not_applicable", adArnisNotApplicable);
            arnisTileAds.addProperty(
                    "placement_rate",
                    adArnisAttempts == 0L ? 0.0 : adArnisPlaced / (double) adArnisAttempts);
            arnisTileAds.addProperty("total_ms", adArnisNanos / 1_000_000.0);
            arnisTileAds.addProperty("average_ms", millis(adArnisNanos, adArnisAttempts));
            arnisTileAds.addProperty("maximum_ms", adArnisMaxNanos / 1_000_000.0);
            ads.add("arnis_tile", arnisTileAds);

            JsonObject districtAds = new JsonObject();
            districtAds.addProperty("chunks_scanned", adDistrictCalls);
            districtAds.addProperty("applicable_chunks", adDistrictApplicable);
            districtAds.addProperty("present_structures", adDistrictPresent);
            districtAds.addProperty("placed_structures", adDistrictPlaced);
            districtAds.addProperty(
                    "placement_rate",
                    adDistrictPresent == 0L ? 0.0 : adDistrictPlaced / (double) adDistrictPresent);
            districtAds.addProperty("total_ms", adDistrictNanos / 1_000_000.0);
            districtAds.addProperty("average_ms", millis(adDistrictNanos, adDistrictCalls));
            districtAds.addProperty("maximum_ms", adDistrictMaxNanos / 1_000_000.0);
            ads.add("district_freestanding", districtAds);

            JsonObject reservationGuard = new JsonObject();
            reservationGuard.addProperty("checks", adReservationChecks);
            reservationGuard.addProperty("total_ms", adReservationNanos / 1_000_000.0);
            reservationGuard.addProperty("average_ms", millis(adReservationNanos, adReservationChecks));
            reservationGuard.addProperty("maximum_ms", adReservationMaxNanos / 1_000_000.0);
            ads.add("reservation_guard", reservationGuard);

            JsonObject backfill = new JsonObject();
            backfill.addProperty("attempts", adBackfillAttempts);
            backfill.addProperty("placed", adBackfillPlaced);
            backfill.addProperty("retryable_failures", adBackfillRetryable);
            backfill.addProperty("other", adBackfillOther);
            backfill.addProperty("total_ms", adBackfillNanos / 1_000_000.0);
            backfill.addProperty("average_ms", millis(adBackfillNanos, adBackfillAttempts));
            backfill.addProperty("maximum_ms", adBackfillMaxNanos / 1_000_000.0);
            backfill.addProperty("retry_pending_at_export", NeonCityGenerator.adRetryPendingCount());
            ads.add("backfill", backfill);

            ads.addProperty("total_ms",
                    (adArnisNanos + adDistrictNanos + adReservationNanos + adBackfillNanos)
                            / 1_000_000.0);
            ads.addProperty(
                    "total_structures_placed",
                    adArnisPlaced + adDistrictPlaced + adBackfillPlaced);
            root.add("ad_placement", ads);

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

            JsonObject gc = new JsonObject();
            long gcCollections = Math.max(0L, totalGcCount() - startingGcCount);
            long gcPauseMillis = Math.max(0L, totalGcMillis() - startingGcMillis);
            gc.addProperty("collections", gcCollections);
            gc.addProperty("total_pause_ms", gcPauseMillis);
            gc.addProperty(
                    "pause_fraction_of_elapsed",
                    status.elapsedSeconds() <= 0.0
                            ? 0.0
                            : (gcPauseMillis / 1_000.0) / status.elapsedSeconds());
            gc.addProperty(
                    "average_pause_ms",
                    gcCollections == 0L ? 0.0 : gcPauseMillis / (double) gcCollections);
            root.add("server_gc", gc);

            JsonObject allocation = new JsonObject();
            long endingAllocatedBytes = totalAllocatedBytes();
            boolean allocationSupported =
                    startingAllocatedBytes >= 0L && endingAllocatedBytes >= 0L;
            allocation.addProperty("supported", allocationSupported);
            if (allocationSupported) {
                long jvmBytes = Math.max(0L, endingAllocatedBytes - startingAllocatedBytes);
                double jvmMib = jvmBytes / 1_048_576.0;
                allocation.addProperty("jvm_allocated_mib", jvmMib);
                allocation.addProperty(
                        "jvm_allocated_mib_per_second",
                        status.elapsedSeconds() <= 0.0 ? 0.0 : jvmMib / status.elapsedSeconds());
                allocation.addProperty(
                        "jvm_allocated_kib_per_chunk",
                        chunks == 0L ? 0.0 : jvmBytes / 1024.0 / chunks);
            }
            allocation.addProperty(
                    "foreground_allocated_mib", foregroundAllocatedBytes / 1_048_576.0);
            allocation.addProperty(
                    "foreground_kib_per_chunk_average",
                    foregroundAllocationSamples == 0L
                            ? 0.0
                            : foregroundAllocatedBytes / 1024.0 / foregroundAllocationSamples);
            allocation.addProperty(
                    "foreground_kib_per_chunk_maximum",
                    maximumForegroundAllocatedBytes / 1024.0);
            allocation.addProperty("foreground_samples", foregroundAllocationSamples);
            root.add("allocation", allocation);

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

    private static final com.sun.management.ThreadMXBean ALLOCATION_BEAN = resolveAllocationBean();

    private static com.sun.management.ThreadMXBean resolveAllocationBean() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sunBean
                && sunBean.isThreadAllocatedMemorySupported()) {
            if (!sunBean.isThreadAllocatedMemoryEnabled()) {
                sunBean.setThreadAllocatedMemoryEnabled(true);
            }
            return sunBean;
        }
        return null;
    }

    /** Bytes allocated by ALL threads since JVM start, or -1 when unsupported. */
    private static long totalAllocatedBytes() {
        return ALLOCATION_BEAN == null ? -1L : ALLOCATION_BEAN.getTotalThreadAllocatedBytes();
    }

    /** Bytes allocated by the calling (generation) thread since JVM start, or -1 when unsupported. */
    private static long currentThreadAllocatedBytes() {
        return ALLOCATION_BEAN == null
                ? -1L
                : ALLOCATION_BEAN.getCurrentThreadAllocatedBytes();
    }

    private static long totalGcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) total += count;
        }
        return total;
    }

    private static long totalGcMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0L) total += time;
        }
        return total;
    }
}
