package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;

/** Bounded priority executor for deterministic, world-independent chunk planning. */
final class CityChunkPlanner {
    static final int MAX_OUTSTANDING_PLANS = 48;
    static final int WORKER_COUNT = Math.max(
            2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<Long, PlanTask> TASKS = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            WORKER_COUNT,
            WORKER_COUNT,
            0L,
            TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(),
            new PlannerThreadFactory());

    private CityChunkPlanner() {
    }

    enum Priority {
        URGENT(0),
        NEAR(1),
        NORMAL(2),
        PREGEN(3);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }
    }

    static boolean request(ChunkPos chunk, Priority priority) {
        long key = chunk.pack();
        PlanTask existing = TASKS.get(key);
        if (existing != null) {
            existing.promote(priority);
            return true;
        }
        if (TASKS.size() >= MAX_OUTSTANDING_PLANS && !makeRoom(priority)) return false;

        PlanTask created = new PlanTask(chunk, priority, SEQUENCE.getAndIncrement());
        existing = TASKS.putIfAbsent(key, created);
        if (existing != null) {
            existing.promote(priority);
            return true;
        }
        EXECUTOR.execute(created);
        return true;
    }

    private static boolean makeRoom(Priority priority) {
        PlanTask victim = null;
        for (PlanTask candidate : TASKS.values()) {
            if (candidate.priority.rank <= priority.rank) continue;
            if (!candidate.isDone() && !EXECUTOR.getQueue().contains(candidate)) continue;
            if (victim == null
                    || candidate.priority.rank > victim.priority.rank
                    || (candidate.priority == victim.priority
                            && candidate.sequence > victim.sequence)) {
                victim = candidate;
            }
        }
        if (victim == null) return false;
        cancel(victim.chunk.pack());
        return true;
    }

    static NeonCityGenerator.ChunkBuildPlan takeReady(ChunkPos chunk) {
        long key = chunk.pack();
        PlanTask task = TASKS.get(key);
        if (task == null || !task.isDone()) return null;
        if (!TASKS.remove(key, task)) return null;
        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException exception) {
            Cyberdeck.LOGGER.error(
                    "[ProjectMoonCity] asynchronous planning failed for {}",
                    chunk, exception.getCause());
            return null;
        }
    }

    static boolean isPending(ChunkPos chunk) {
        PlanTask task = TASKS.get(chunk.pack());
        return task != null && !task.isDone();
    }

    static boolean isReady(ChunkPos chunk) {
        PlanTask task = TASKS.get(chunk.pack());
        return task != null && task.isDone() && !task.isCancelled();
    }

    static int outstandingPlans() {
        return TASKS.size();
    }

    static void cancel(long chunkKey) {
        PlanTask task = TASKS.remove(chunkKey);
        if (task == null) return;
        EXECUTOR.getQueue().remove(task);
        task.cancel(false);
    }

    static void reset() {
        for (PlanTask task : TASKS.values()) task.cancel(false);
        EXECUTOR.getQueue().clear();
        TASKS.clear();
    }

    private static final class PlanTask
            extends FutureTask<NeonCityGenerator.ChunkBuildPlan>
            implements Comparable<PlanTask> {
        private final ChunkPos chunk;
        private final long sequence;
        private volatile Priority priority;

        private PlanTask(ChunkPos chunk, Priority priority, long sequence) {
            super(() -> NeonCityGenerator.planChunk(chunk));
            this.chunk = chunk;
            this.priority = priority;
            this.sequence = sequence;
        }

        private void promote(Priority next) {
            if (next.rank >= priority.rank || isDone()) return;
            boolean queued = EXECUTOR.getQueue().remove(this);
            priority = next;
            if (queued) EXECUTOR.execute(this);
        }

        @Override
        public int compareTo(PlanTask other) {
            int byPriority = Integer.compare(priority.rank, other.priority.rank);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }

        @Override
        public String toString() {
            return "PlanTask[chunk=" + chunk + ", priority=" + priority + "]";
        }
    }

    private static final class PlannerThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(
                    task, "neoncity-planner-" + nextId.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
