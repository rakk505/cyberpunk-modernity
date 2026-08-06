package com.example.cyberdeck.client.render;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.TacticalManeuver;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Gives a hostile sandevistan the same time-blur afterimage the player's does.
 *
 * <p>A soldier's blink already reuses the dash animation and a particle burst, but next to the
 * player's trail of frozen ghost copies it read as a different ability. This draws the same effect
 * for the enemy: a short history of frozen poses, redrawn translucent behind the live body along
 * the same green-through-purple gradient, for as long as the synced maneuver is a sandevistan
 * dash. Purely cosmetic and entirely client-side.</p>
 */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class EnemySandevistanAfterimage {
    /**
     * The dash itself lasts a handful of ticks, so the trail is much shorter than the player's
     * minutes-long sandevistan window - long enough to smear the blink, short enough that it has
     * faded before the soldier next moves normally.
     */
    private static final int GHOST_COUNT = 8;
    private static final int GHOST_START = 1;
    private static final float MAX_ALPHA = 0.5F;
    private static final float MIN_ALPHA = 0.06F;
    private static final float GRADIENT_START_HUE = 120.0F / 360.0F;
    private static final float GRADIENT_HUE_SWEEP = 1.0F;

    private static final Map<UUID, Deque<Snapshot>> HISTORY = new HashMap<>();

    private record Snapshot(
            double x, double y, double z,
            float bodyRot, float yRot, float xRot,
            float walkAnimationPos, float walkAnimationSpeed, float attackTime) {
    }

    private EnemySandevistanAfterimage() {
    }

    @SubscribeEvent
    public static void onEnemyTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof FactionEnemy enemy)
                || !enemy.level().isClientSide()) {
            return;
        }
        UUID id = enemy.getUUID();
        if (enemy.getTacticalManeuver() != TacticalManeuver.SANDEVISTAN_DASH) {
            // Let an existing trail age out rather than snapping off the instant the dash ends.
            Deque<Snapshot> fading = HISTORY.get(id);
            if (fading == null) return;
            fading.pollLast();
            if (fading.isEmpty()) HISTORY.remove(id);
            return;
        }
        Deque<Snapshot> trail = HISTORY.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        trail.addFirst(new Snapshot(
                enemy.getX(), enemy.getY(), enemy.getZ(),
                enemy.yBodyRot, enemy.getYRot(), enemy.getXRot(),
                enemy.walkAnimation.position(),
                Math.min(1.0F, enemy.walkAnimation.speed()),
                enemy.getAttackAnim(1.0F)));
        while (trail.size() > GHOST_COUNT) {
            trail.removeLast();
        }
    }

    /** Drops trails for soldiers that died or left tracking range. */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (HISTORY.isEmpty()) return;
        var level = Minecraft.getInstance().level;
        Iterator<UUID> ids = HISTORY.keySet().iterator();
        while (ids.hasNext()) {
            UUID id = ids.next();
            if (level == null) {
                ids.remove();
                continue;
            }
            boolean present = false;
            for (var entity : level.entitiesForRendering()) {
                if (entity.getUUID().equals(id)) {
                    present = true;
                    break;
                }
            }
            if (!present) ids.remove();
        }
    }

    @SubscribeEvent
    public static void onRenderPost(RenderLivingEvent.Post<?, ?, ?> event) {
        if (!(event.getRenderState() instanceof FactionEnemyRenderState state)
                || state.isInvisible) {
            return;
        }
        Deque<Snapshot> trail = HISTORY.get(entityId(state));
        if (trail == null || trail.isEmpty()) return;
        var renderer = event.getRenderer();
        if (!(renderer instanceof FactionEnemyRenderer enemyRenderer)) return;
        Identifier texture = enemyRenderer.getTextureLocation(state);
        if (texture == null) return;
        var model = enemyRenderer.getModel();
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        int drawn = 0;
        int index = 0;
        for (Snapshot snap : trail) {
            if (index++ < GHOST_START) continue;
            if (drawn >= GHOST_COUNT) break;
            float progress = (float) drawn / Math.max(1, GHOST_COUNT - 1);
            float alpha = Mth.lerp(progress, MAX_ALPHA, MIN_ALPHA);
            float hue = (GRADIENT_START_HUE + progress * GRADIENT_HUE_SWEEP) % 1.0F;
            int tint = ARGB.color(alpha, Mth.hsvToRgb(hue, 1.0F, 1.0F) & 0xFFFFFF);

            // The submit queue re-poses the model from the state it is handed at drain time, so
            // each ghost needs its own frozen copy or they all snap to the live pose.
            FactionEnemyRenderState ghost = frozen(state, snap);
            poseStack.pushPose();
            poseStack.translate(snap.x() - state.x, snap.y() - state.y, snap.z() - state.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - snap.bodyRot()));
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.translate(0.0F, -1.501F, 0.0F);
            collector.submitModel(
                    model, ghost, poseStack, RenderTypes.entityTranslucent(texture),
                    state.lightCoords, OverlayTexture.NO_OVERLAY, tint, null, 0, null);
            poseStack.popPose();
            drawn++;
        }
    }

    /**
     * A copy of the live render state with the pose-driving fields pinned to the snapshot. Held
     * items and the tactical pose are deliberately cleared: a ghost is a smear of the body, and
     * re-submitting item models per ghost would both cost and look wrong.
     */
    private static FactionEnemyRenderState frozen(
            FactionEnemyRenderState live, Snapshot snap) {
        FactionEnemyRenderState ghost = new FactionEnemyRenderState();
        ghost.cyberpsycho = live.cyberpsycho;
        ghost.traumaTeam = live.traumaTeam;
        ghost.excision = live.excision;
        ghost.rCorp = live.rCorp;
        ghost.skinVariant = live.skinVariant;
        ghost.quickhackTraceStart = null;
        ghost.quickhackTraceEnd = null;
        ghost.mainArm = live.mainArm;
        ghost.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
        ghost.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
        ghost.scale = live.scale;
        ghost.ageScale = live.ageScale;
        ghost.isBaby = live.isBaby;
        ghost.lightCoords = live.lightCoords;
        ghost.bodyRot = snap.bodyRot();
        ghost.yRot = snap.yRot();
        ghost.xRot = snap.xRot();
        ghost.walkAnimationPos = snap.walkAnimationPos();
        ghost.walkAnimationSpeed = snap.walkAnimationSpeed();
        ghost.attackTime = snap.attackTime();
        ghost.ageInTicks = live.ageInTicks;
        ghost.x = snap.x();
        ghost.y = snap.y();
        ghost.z = snap.z();
        return ghost;
    }

    private static UUID entityId(FactionEnemyRenderState state) {
        var level = Minecraft.getInstance().level;
        if (level == null || state.entityId < 0) return null;
        var entity = level.getEntity(state.entityId);
        return entity == null ? null : entity.getUUID();
    }
}
