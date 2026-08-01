package com.example.cyberdeck.skill;

import com.example.cyberdeck.CyberdeckState;
import com.example.cyberdeck.network.QuickhackUploadPacket;
import com.example.cyberdeck.ram.RamAttachments;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative, per-caster FIFO of quickhacks reserved against one target. */
public final class QuickhackUploads {
    public static final int MAX_QUEUE_SIZE = 4;
    /** Ten chunks: substantially beyond combat range while retaining client entity tracking. */
    public static final double MAX_TARGET_RANGE = 160.0;
    private static final double MAX_TARGET_RANGE_SQR = MAX_TARGET_RANGE * MAX_TARGET_RANGE;

    public enum EnqueueStatus {
        ACCEPTED,
        INACTIVE,
        INVALID_SKILL,
        INVALID_TARGET,
        QUEUE_FULL,
        DUPLICATE_SKILL,
        TARGET_MISMATCH,
        INSUFFICIENT_RAM
    }

    /** Result returned after the server atomically validates and reserves a queue entry. */
    public record EnqueueResult(EnqueueStatus status, int position, int availableRam) {
        public boolean accepted() {
            return status == EnqueueStatus.ACCEPTED;
        }
    }

    private record ReservedHack(Skill skill, int ramCost) {
    }

    private static final class UploadQueue {
        private final UUID targetUuid;
        private final int targetId;
        private final ArrayDeque<ReservedHack> hacks = new ArrayDeque<>();
        private long startTick;
        private long endTick;

        private UploadQueue(LivingEntity target) {
            this.targetUuid = target.getUUID();
            this.targetId = target.getId();
        }
    }

    private static final Map<UUID, UploadQueue> QUEUES = new HashMap<>();

    private QuickhackUploads() {
    }

    /**
     * Validates and queues one quickhack. RAM remains in its attachment while queued; the sum of
     * queued costs is reserved and therefore unavailable to later enqueue attempts. The head cost
     * is deducted only when its upload successfully completes.
     */
    public static EnqueueResult enqueue(ServerPlayer caster, Skill skill, LivingEntity target,
                                        ServerLevel level) {
        UploadQueue queue = QUEUES.get(caster.getUUID());
        if (queue != null
                && !isValidContinuingTarget(caster, resolveTarget(queue, level), level)) {
            QUEUES.remove(caster.getUUID());
            syncNone(caster);
            queue = null;
        }
        int available = availableRam(caster);
        if (!CyberdeckState.isActive(caster)) {
            return new EnqueueResult(EnqueueStatus.INACTIVE, 0, available);
        }
        if (skill == null || skill == Skill.STANDBY) {
            return new EnqueueResult(EnqueueStatus.INVALID_SKILL, 0, available);
        }
        if (!isValidNewTarget(caster, target, level)) {
            return new EnqueueResult(EnqueueStatus.INVALID_TARGET, 0, available);
        }

        if (queue != null && (!queue.targetUuid.equals(target.getUUID())
                || queue.targetId != target.getId())) {
            return new EnqueueResult(EnqueueStatus.TARGET_MISMATCH, 0, available);
        }
        if (queue != null && queue.hacks.size() >= MAX_QUEUE_SIZE) {
            return new EnqueueResult(EnqueueStatus.QUEUE_FULL, 0, available);
        }
        if (queue != null) {
            for (ReservedHack queued : queue.hacks) {
                if (queued.skill() == skill) {
                    return new EnqueueResult(EnqueueStatus.DUPLICATE_SKILL, 0, available);
                }
            }
        }

        int cost = com.example.cyberdeck.effect.CyberwareEffects.quickhackRamCost(caster, skill);
        if (available < cost) {
            return new EnqueueResult(EnqueueStatus.INSUFFICIENT_RAM, 0, available);
        }

        if (queue == null) {
            queue = new UploadQueue(target);
            QUEUES.put(caster.getUUID(), queue);
        }
        queue.hacks.addLast(new ReservedHack(skill, cost));
        if (queue.hacks.size() == 1) {
            beginHead(queue, caster, level.getGameTime());
        }
        sync(caster, queue);
        return new EnqueueResult(EnqueueStatus.ACCEPTED, queue.hacks.size(), available - cost);
    }

    /** Advances the active head, commits its reserved RAM, and promotes the next queued hack. */
    public static void tick(ServerPlayer caster, ServerLevel level) {
        UploadQueue queue = QUEUES.get(caster.getUUID());
        if (queue == null) {
            return;
        }
        // Scanner mode is only required to enqueue. Once reserved, an upload keeps running while
        // the player returns to normal movement and combat, provided the deck remains installed.
        if (!caster.isAlive() || !CyberdeckState.hasInstalledCyberdeck(caster)) {
            cancel(caster);
            return;
        }

        LivingEntity target = resolveTarget(queue, level);
        if (!isValidContinuingTarget(caster, target, level)) {
            cancel(caster);
            return;
        }
        if (level.getGameTime() < queue.endTick) {
            return;
        }

        ReservedHack completed = queue.hacks.peekFirst();
        if (completed == null) {
            QUEUES.remove(caster.getUUID());
            syncNone(caster);
            return;
        }

        // A reservation should make this spend infallible. If another system reduced RAM anyway,
        // fail closed: do not execute the quickhack and release the whole queue.
        if (!RamAttachments.spend(caster, completed.ramCost())) {
            cancel(caster);
            return;
        }
        queue.hacks.removeFirst();
        SkillExecutor.execute(completed.skill(), caster, target, level);

        if (queue.hacks.isEmpty()) {
            QUEUES.remove(caster.getUUID());
            syncNone(caster);
            return;
        }

        // A completed effect may kill or remove the shared target. In that case the remaining
        // reservations are released instead of being charged for effects that cannot execute.
        target = resolveTarget(queue, level);
        if (!isValidContinuingTarget(caster, target, level)) {
            cancel(caster);
            return;
        }
        beginHead(queue, caster, level.getGameTime());
        sync(caster, queue);
    }

    public static boolean hasQueue(ServerPlayer caster) {
        return QUEUES.containsKey(caster.getUUID());
    }

    /** Compatibility alias for callers that only need to know whether any upload is queued. */
    public static boolean isUploading(ServerPlayer caster) {
        return hasQueue(caster);
    }

    /** Total RAM promised to active and pending entries but not yet spent. */
    public static int reservedRam(ServerPlayer caster) {
        UploadQueue queue = QUEUES.get(caster.getUUID());
        if (queue == null) {
            return 0;
        }
        int reserved = 0;
        for (ReservedHack hack : queue.hacks) {
            reserved += hack.ramCost();
        }
        return reserved;
    }

    /** RAM that can still be reserved by another queue entry. */
    public static int availableRam(ServerPlayer caster) {
        return Math.max(0, RamAttachments.get(caster) - reservedRam(caster));
    }

    /** Releases every uncommitted reservation and clears the owner's client snapshot. */
    public static void cancel(ServerPlayer caster) {
        if (QUEUES.remove(caster.getUUID()) != null) {
            syncNone(caster);
        }
    }

    /** Removes transient state when no client connection remains to receive a clear packet. */
    public static void forget(UUID casterId) {
        QUEUES.remove(casterId);
    }

    public static void clearAll() {
        QUEUES.clear();
    }

    private static boolean isValidNewTarget(ServerPlayer caster, LivingEntity target,
                                            ServerLevel level) {
        return caster.isAlive()
                && target != caster
                && target instanceof Enemy
                && target.isPickable()
                && target.level() == level
                && target.isAlive()
                && caster.distanceToSqr(target) <= MAX_TARGET_RANGE_SQR
                && caster.hasLineOfSight(target);
    }

    private static boolean isValidContinuingTarget(ServerPlayer caster, LivingEntity target,
                                                   ServerLevel level) {
        return target != null
                && target.level() == level
                && target.isAlive()
                && caster.distanceToSqr(target) <= MAX_TARGET_RANGE_SQR;
    }

    /** Resolves by ID and verifies UUID too, preventing a recycled network ID from changing target. */
    private static LivingEntity resolveTarget(UploadQueue queue, ServerLevel level) {
        Entity entity = level.getEntity(queue.targetId);
        if (!(entity instanceof LivingEntity living)
                || !queue.targetUuid.equals(living.getUUID())) {
            return null;
        }
        return living;
    }

    private static void beginHead(UploadQueue queue, ServerPlayer caster, long now) {
        ReservedHack head = queue.hacks.peekFirst();
        queue.startTick = now;
        queue.endTick = now + (head == null ? 0
                : com.example.cyberdeck.effect.CyberwareEffects
                        .quickhackUploadTicks(caster, head.skill()));
    }

    private static void sync(ServerPlayer caster, UploadQueue queue) {
        ReservedHack head = queue.hacks.peekFirst();
        if (head == null) {
            syncNone(caster);
            return;
        }
        List<Integer> skills = new ArrayList<>(queue.hacks.size());
        for (ReservedHack hack : queue.hacks) {
            skills.add(hack.skill().ordinal());
        }
        PacketDistributor.sendToPlayer(caster, new QuickhackUploadPacket(
                head.skill().ordinal(), queue.targetId, queue.startTick, queue.endTick,
                reservedRam(caster), List.copyOf(skills)));
    }

    private static void syncNone(ServerPlayer caster) {
        PacketDistributor.sendToPlayer(caster, QuickhackUploadPacket.NONE);
    }
}
