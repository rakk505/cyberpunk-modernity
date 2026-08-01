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
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative, independently-timed quickhack queues grouped by caster and target. */
public final class QuickhackUploads {
    /** Total active and pending quickhacks a player may reserve across all targets. */
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

    private static final class CasterUploads {
        private final LinkedHashMap<UUID, UploadQueue> byTarget = new LinkedHashMap<>();
    }

    private static final Map<UUID, CasterUploads> UPLOADS = new HashMap<>();

    private QuickhackUploads() {
    }

    /**
     * Reserves one quickhack. Different targets upload concurrently; hacks queued on the same
     * target remain FIFO. The global four-entry cap and shared RAM reservation prevent overcommit.
     */
    public static EnqueueResult enqueue(ServerPlayer caster, Skill skill, LivingEntity target,
                                        ServerLevel level) {
        CasterUploads uploads = UPLOADS.get(caster.getUUID());
        boolean pruned = uploads != null && pruneInvalidTargets(caster, uploads, level);
        if (uploads != null && uploads.byTarget.isEmpty()) {
            UPLOADS.remove(caster.getUUID());
            uploads = null;
        }

        int available = availableRam(caster);
        if (!CyberdeckState.isActive(caster)) {
            syncAfterRejectedEnqueue(caster, uploads, pruned);
            return new EnqueueResult(EnqueueStatus.INACTIVE, 0, available);
        }
        if (skill == null || skill == Skill.STANDBY) {
            syncAfterRejectedEnqueue(caster, uploads, pruned);
            return new EnqueueResult(EnqueueStatus.INVALID_SKILL, 0, available);
        }
        if (!isValidNewTarget(caster, target, level)) {
            syncAfterRejectedEnqueue(caster, uploads, pruned);
            return new EnqueueResult(EnqueueStatus.INVALID_TARGET, 0, available);
        }
        if (uploads != null && queuedHackCount(uploads) >= MAX_QUEUE_SIZE) {
            syncAfterRejectedEnqueue(caster, uploads, pruned);
            return new EnqueueResult(EnqueueStatus.QUEUE_FULL, 0, available);
        }

        UploadQueue queue = uploads == null ? null : uploads.byTarget.get(target.getUUID());
        if (queue != null) {
            for (ReservedHack queued : queue.hacks) {
                if (queued.skill() == skill) {
                    syncAfterRejectedEnqueue(caster, uploads, pruned);
                    return new EnqueueResult(EnqueueStatus.DUPLICATE_SKILL, 0, available);
                }
            }
        }

        int cost = com.example.cyberdeck.effect.CyberwareEffects.quickhackRamCost(caster, skill);
        if (available < cost) {
            syncAfterRejectedEnqueue(caster, uploads, pruned);
            return new EnqueueResult(EnqueueStatus.INSUFFICIENT_RAM, 0, available);
        }

        if (uploads == null) {
            uploads = new CasterUploads();
            UPLOADS.put(caster.getUUID(), uploads);
        }
        if (queue == null) {
            queue = new UploadQueue(target);
            uploads.byTarget.put(target.getUUID(), queue);
        }
        queue.hacks.addLast(new ReservedHack(skill, cost));
        if (queue.hacks.size() == 1) {
            beginHead(queue, caster, level.getGameTime());
        }
        sync(caster, uploads);
        return new EnqueueResult(EnqueueStatus.ACCEPTED, queue.hacks.size(), available - cost);
    }

    /** Advances every target head independently and promotes each target's next queued hack. */
    public static void tick(ServerPlayer caster, ServerLevel level) {
        CasterUploads uploads = UPLOADS.get(caster.getUUID());
        if (uploads == null) {
            return;
        }
        if (!caster.isAlive() || !CyberdeckState.hasInstalledCyberdeck(caster)) {
            cancel(caster);
            return;
        }

        boolean changed = false;
        long now = level.getGameTime();
        Iterator<UploadQueue> iterator = uploads.byTarget.values().iterator();
        while (iterator.hasNext()) {
            UploadQueue queue = iterator.next();
            LivingEntity target = resolveTarget(queue, level);
            if (!isValidContinuingTarget(caster, target, level)) {
                iterator.remove();
                changed = true;
                continue;
            }
            if (now < queue.endTick) {
                continue;
            }

            ReservedHack completed = queue.hacks.peekFirst();
            if (completed == null) {
                iterator.remove();
                changed = true;
                continue;
            }
            if (!RamAttachments.spend(caster, completed.ramCost())) {
                cancel(caster);
                return;
            }

            queue.hacks.removeFirst();
            SkillExecutor.execute(completed.skill(), caster, target, level);
            changed = true;

            if (queue.hacks.isEmpty()) {
                iterator.remove();
                continue;
            }

            target = resolveTarget(queue, level);
            if (!isValidContinuingTarget(caster, target, level)) {
                iterator.remove();
                continue;
            }
            beginHead(queue, caster, now);
        }

        if (uploads.byTarget.isEmpty()) {
            UPLOADS.remove(caster.getUUID());
            syncNone(caster);
        } else if (changed) {
            sync(caster, uploads);
        }
    }

    public static boolean hasQueue(ServerPlayer caster) {
        return UPLOADS.containsKey(caster.getUUID());
    }

    public static boolean isUploading(ServerPlayer caster) {
        return hasQueue(caster);
    }

    /** Total RAM promised to active and pending entries on every target. */
    public static int reservedRam(ServerPlayer caster) {
        CasterUploads uploads = UPLOADS.get(caster.getUUID());
        if (uploads == null) {
            return 0;
        }
        int reserved = 0;
        for (UploadQueue queue : uploads.byTarget.values()) {
            for (ReservedHack hack : queue.hacks) {
                reserved += hack.ramCost();
            }
        }
        return reserved;
    }

    public static int availableRam(ServerPlayer caster) {
        return Math.max(0, RamAttachments.get(caster) - reservedRam(caster));
    }

    /** Number of enemies whose queue head is currently uploading. */
    public static int activeTargetCount(ServerPlayer caster) {
        CasterUploads uploads = UPLOADS.get(caster.getUUID());
        return uploads == null ? 0 : uploads.byTarget.size();
    }

    /** End tick for a target's current upload, or -1 when that target has no queue. */
    public static long uploadEndTick(ServerPlayer caster, int targetId) {
        CasterUploads uploads = UPLOADS.get(caster.getUUID());
        if (uploads != null) {
            for (UploadQueue queue : uploads.byTarget.values()) {
                if (queue.targetId == targetId) {
                    return queue.endTick;
                }
            }
        }
        return -1L;
    }

    /** Releases every uncommitted reservation and clears the owner's client snapshot. */
    public static void cancel(ServerPlayer caster) {
        if (UPLOADS.remove(caster.getUUID()) != null) {
            syncNone(caster);
        }
    }

    /** Removes transient state when no client connection remains to receive a clear packet. */
    public static void forget(UUID casterId) {
        UPLOADS.remove(casterId);
    }

    public static void clearAll() {
        UPLOADS.clear();
    }

    private static boolean pruneInvalidTargets(ServerPlayer caster, CasterUploads uploads,
                                               ServerLevel level) {
        return uploads.byTarget.values().removeIf(queue ->
                !isValidContinuingTarget(caster, resolveTarget(queue, level), level));
    }

    private static int queuedHackCount(CasterUploads uploads) {
        int count = 0;
        for (UploadQueue queue : uploads.byTarget.values()) {
            count += queue.hacks.size();
        }
        return count;
    }

    private static void syncAfterRejectedEnqueue(ServerPlayer caster,
                                                  CasterUploads uploads,
                                                  boolean changed) {
        if (!changed) {
            return;
        }
        if (uploads == null || uploads.byTarget.isEmpty()) {
            syncNone(caster);
        } else {
            sync(caster, uploads);
        }
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

    private static void sync(ServerPlayer caster, CasterUploads uploads) {
        List<QuickhackUploadPacket.TargetUpload> snapshots =
                new ArrayList<>(uploads.byTarget.size());
        for (UploadQueue queue : uploads.byTarget.values()) {
            ReservedHack head = queue.hacks.peekFirst();
            if (head == null) {
                continue;
            }
            List<Integer> skills = new ArrayList<>(queue.hacks.size());
            for (ReservedHack hack : queue.hacks) {
                skills.add(hack.skill().ordinal());
            }
            snapshots.add(new QuickhackUploadPacket.TargetUpload(
                    head.skill().ordinal(), queue.targetId, queue.startTick, queue.endTick,
                    skills));
        }
        sendSnapshot(caster, new QuickhackUploadPacket(reservedRam(caster), snapshots));
    }

    private static void syncNone(ServerPlayer caster) {
        sendSnapshot(caster, QuickhackUploadPacket.NONE);
    }

    private static void sendSnapshot(ServerPlayer caster, QuickhackUploadPacket packet) {
        if (caster.connection != null
                && NetworkRegistry.hasChannel(caster.connection, QuickhackUploadPacket.TYPE.id())) {
            PacketDistributor.sendToPlayer(caster, packet);
        }
    }
}
