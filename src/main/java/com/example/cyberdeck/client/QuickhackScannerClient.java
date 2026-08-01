package com.example.cyberdeck.client;

import com.example.cyberdeck.QuickhackAttachments;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.skill.Skill;
import com.example.cyberdeck.skill.QuickhackUploads;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Client-owned selection and crosshair target state shared by both scanner modes. */
public final class QuickhackScannerClient {
    /** Long-range scanner reach, capped at the practical ten-chunk entity tracking radius. */
    public static final double TARGET_RANGE = QuickhackUploads.MAX_TARGET_RANGE;
    private static final int FIRST_SKILL = 0;
    private static final int SKILL_COUNT = Skill.STANDBY.ordinal();
    private static final int TARGET_CONFIRM_TICKS = 4;
    private static final int TARGET_RELEASE_TICKS = 3;

    private static boolean active;
    private static boolean quickhacking;
    private static int selectedSkill;
    private static int targetId = -1;
    private static int directTargetId = -1;
    private static int candidateTargetId = -1;
    private static int candidateTicks;
    private static int missedTargetTicks;
    private static @Nullable ClientLevel lastLevel;

    private QuickhackScannerClient() {
    }

    /** Refreshes mode and targeting once per client tick. */
    public static void tick(Minecraft minecraft) {
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        boolean nextQuickhacking = player != null && QuickhackAttachments.isQuickhacking(player);
        boolean nextActive = player != null
                && level != null
                && minecraft.gui.screen() == null
                && QuickhackAttachments.isScannerActive(player);

        if (level != lastLevel) {
            reset();
            lastLevel = level;
        }
        if (!nextActive) {
            if (active) {
                reset();
                lastLevel = level;
            }
            return;
        }

        active = true;
        quickhacking = nextQuickhacking;
        updateTargetLock(findTarget(player));
    }

    public static boolean isActive() {
        return active;
    }

    /** True only when the open scanner is backed by a quickhack-capable cyberdeck. */
    public static boolean isQuickhacking() {
        return active && quickhacking;
    }

    public static int selectedSkillOrdinal() {
        return selectedSkill;
    }

    public static Skill selectedSkill() {
        return Skill.fromSlot(selectedSkill);
    }

    public static int targetId() {
        return targetId;
    }

    public static int directTargetId() {
        return directTargetId;
    }

    public static @Nullable LivingEntity target(@Nullable ClientLevel level) {
        if (level == null || targetId < 0) {
            return null;
        }
        return level.getEntity(targetId) instanceof LivingEntity living && living.isAlive()
                ? living
                : null;
    }

    /** The entity directly under the reticle right now; stale display locks cannot queue hacks. */
    public static @Nullable LivingEntity actionTarget(@Nullable ClientLevel level) {
        if (!isQuickhacking() || level == null || directTargetId < 0
                || directTargetId != targetId) {
            return null;
        }
        return level.getEntity(directTargetId) instanceof LivingEntity living
                && living instanceof Enemy
                && living.isAlive()
                ? living
                : null;
    }

    /** Wraps over the seven executable presets and repairs vanilla's number-key hotbar selection. */
    public static void cycle(Player player, int direction) {
        if (!isQuickhacking() || targetId < 0 || direction == 0) {
            return;
        }
        selectedSkill = Math.floorMod(selectedSkill + Integer.signum(direction), SKILL_COUNT);
        player.getInventory().setSelectedSlot(selectedSkill);
    }

    public static void reset() {
        active = false;
        quickhacking = false;
        selectedSkill = FIRST_SKILL;
        targetId = -1;
        directTargetId = -1;
        candidateTargetId = -1;
        candidateTicks = 0;
        missedTargetTicks = 0;
    }

    /**
     * Requires a brief, deliberate hold before exposing target actions and tolerates tiny aim
     * jitter.
     */
    private static void updateTargetLock(@Nullable LivingEntity directTarget) {
        directTargetId = directTarget == null ? -1 : directTarget.getId();

        if (directTargetId == targetId && targetId >= 0) {
            candidateTargetId = -1;
            candidateTicks = 0;
            missedTargetTicks = 0;
            return;
        }

        if (directTargetId < 0) {
            candidateTargetId = -1;
            candidateTicks = 0;
            if (targetId >= 0 && ++missedTargetTicks >= TARGET_RELEASE_TICKS) {
                targetId = -1;
                missedTargetTicks = 0;
            }
            return;
        }

        // Moving directly onto a different enemy drops the old details instead of showing stale
        // intelligence while the new target earns a lock.
        if (targetId >= 0) {
            targetId = -1;
        }
        missedTargetTicks = 0;
        if (candidateTargetId != directTargetId) {
            candidateTargetId = directTargetId;
            candidateTicks = 1;
            return;
        }
        if (++candidateTicks >= TARGET_CONFIRM_TICKS) {
            targetId = candidateTargetId;
            candidateTargetId = -1;
            candidateTicks = 0;
        }
    }

    /** Block-clipped crosshair ray; the server independently validates the resulting target. */
    private static @Nullable LivingEntity findTarget(Player player) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 reachEnd = eye.add(look.scale(TARGET_RANGE));

        BlockHitResult blockHit = player.level().clip(new ClipContext(
                eye, reachEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 lineEnd = blockHit.getType() == HitResult.Type.MISS
                ? reachEnd
                : blockHit.getLocation();
        double reach = eye.distanceTo(lineEnd);
        AABB search = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player,
                eye,
                lineEnd,
                search,
                entity -> entity instanceof LivingEntity living
                        && living != player
                        && (living instanceof Enemy || living instanceof CityNpc)
                        && living.isAlive()
                        && !living.isSpectator()
                        && living.isPickable(),
                reach * reach);
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }
}
