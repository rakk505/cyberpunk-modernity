package com.example.cyberdeck.skill;

import com.example.cyberdeck.network.QuickhackUploadPacket;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative registry of in-progress quickhack uploads. A quickhack does not take effect
 * the instant it is triggered; instead it "uploads" onto the target over {@link Skill#uploadTicks()}
 * ticks. During the upload the caster's client shows a marker with the skill name and progress, and
 * only once the timer completes does {@link SkillExecutor} apply the effect.
 */
public final class QuickhackUploads {
    /** A single upload in flight. */
    private record Upload(Skill skill, int targetId, long startTick, long endTick) {
    }

    private static final Map<UUID, Upload> ACTIVE = new HashMap<>();

    private QuickhackUploads() {
    }

    /**
     * Begins an upload of {@code skill} onto {@code target} for {@code caster}. RAM has already been
     * spent by the caller. Instant quickhacks (0 upload ticks) apply immediately.
     */
    public static void start(ServerPlayer caster, Skill skill, LivingEntity target, ServerLevel level) {
        if (skill.uploadTicks() <= 0) {
            SkillExecutor.execute(skill, caster, target, level);
            return;
        }
        long now = level.getGameTime();
        Upload upload = new Upload(skill, target.getId(), now, now + skill.uploadTicks());
        ACTIVE.put(caster.getUUID(), upload);
        sync(caster, upload);
    }

    /** Advances all uploads for {@code caster}; completes or cancels as appropriate. */
    public static void tick(ServerPlayer caster, ServerLevel level) {
        Upload upload = ACTIVE.get(caster.getUUID());
        if (upload == null) {
            return;
        }
        Entity target = level.getEntity(upload.targetId());
        // Cancel if the target is gone/dead or out of range.
        if (!(target instanceof LivingEntity living) || !living.isAlive()
                || caster.distanceToSqr(living) > 64 * 64) {
            cancel(caster);
            return;
        }
        if (level.getGameTime() >= upload.endTick()) {
            ACTIVE.remove(caster.getUUID());
            SkillExecutor.execute(upload.skill(), caster, living, level);
            syncNone(caster);
        }
    }

    public static boolean isUploading(ServerPlayer caster) {
        return ACTIVE.containsKey(caster.getUUID());
    }

    public static void cancel(ServerPlayer caster) {
        if (ACTIVE.remove(caster.getUUID()) != null) {
            syncNone(caster);
        }
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    private static void sync(ServerPlayer caster, Upload upload) {
        PacketDistributor.sendToPlayer(caster, new QuickhackUploadPacket(
                upload.skill().ordinal(), upload.targetId(), upload.startTick(), upload.endTick()));
    }

    private static void syncNone(ServerPlayer caster) {
        PacketDistributor.sendToPlayer(caster, QuickhackUploadPacket.NONE);
    }
}
