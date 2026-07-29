package com.example.cyberdeck.client;

import com.example.cyberdeck.QuickhackAttachments;
import com.example.cyberdeck.skill.Skill;

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

/** Client-owned selection and crosshair target state for the quickhack scanner HUD. */
public final class QuickhackScannerClient {
    public static final double TARGET_RANGE = 48.0;
    private static final int FIRST_SKILL = 0;
    private static final int SKILL_COUNT = Skill.STANDBY.ordinal();

    private static boolean active;
    private static int selectedSkill;
    private static int targetId = -1;
    private static @Nullable ClientLevel lastLevel;

    private QuickhackScannerClient() {
    }

    /** Refreshes mode and targeting once per client tick. */
    public static void tick(Minecraft minecraft) {
        Player player = minecraft.player;
        ClientLevel level = minecraft.level;
        boolean nextActive = player != null
                && level != null
                && minecraft.gui.screen() == null
                && QuickhackAttachments.isQuickhacking(player);

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
        LivingEntity target = findTarget(player);
        targetId = target == null ? -1 : target.getId();
    }

    public static boolean isActive() {
        return active;
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

    public static @Nullable LivingEntity target(@Nullable ClientLevel level) {
        if (level == null || targetId < 0) {
            return null;
        }
        return level.getEntity(targetId) instanceof LivingEntity living && living.isAlive()
                ? living
                : null;
    }

    /** Wraps over the seven executable presets and repairs vanilla's number-key hotbar selection. */
    public static void cycle(Player player, int direction) {
        if (!active || direction == 0) {
            return;
        }
        selectedSkill = Math.floorMod(selectedSkill + Integer.signum(direction), SKILL_COUNT);
        player.getInventory().setSelectedSlot(selectedSkill);
    }

    public static void reset() {
        active = false;
        selectedSkill = FIRST_SKILL;
        targetId = -1;
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
                        && living instanceof Enemy
                        && living.isAlive()
                        && !living.isSpectator()
                        && living.isPickable(),
                reach * reach);
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }
}
