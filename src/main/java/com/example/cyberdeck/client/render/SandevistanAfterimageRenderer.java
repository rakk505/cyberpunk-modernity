package com.example.cyberdeck.client.render;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Renders a fading trail of translucent, cyan-tinted "ghost" copies of a player's body
 * while their sandevistan is active, selling the speed boost as a Cyberpunk-style
 * time-blur afterimage. Purely client-side and cosmetic.
 *
 * <p>Each ghost is a frozen snapshot: at the moment it was recorded we capture the
 * player's world position, body yaw, and the fields that drive the limb pose. Because the
 * submit pipeline is deferred (it re-runs {@code model.setupAnim(state)} against the passed
 * render state at drain time), each ghost is drawn with its own frozen copy of the render
 * state rather than the shared live one — otherwise every ghost would re-pose to the live
 * player's current animation frame. The trail works for the local player and any tracked
 * remote player because the active flag is a client-synced attachment.</p>
 */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class SandevistanAfterimageRenderer {
    /** How many ghosts to draw behind the player (one per recorded tick). */
    private static final int GHOST_COUNT = 35;
    /** Keep exactly one extra sample so a fresh add can evict the oldest cleanly. */
    private static final int HISTORY_SIZE = GHOST_COUNT;
    /** Skip the freshest sample so ghosts sit visibly behind the live body. */
    private static final int GHOST_START = 1;
    private static final float MAX_ALPHA = 0.55F;
    private static final float MIN_ALPHA = 0.06F;
    /** Trail gradient starts at green (hue 120 deg) ... */
    private static final float GRADIENT_START_HUE = 120.0F / 360.0F;
    /** ... and sweeps a full turn through cyan/blue/purple back around to green. */
    private static final float GRADIENT_HUE_SWEEP = 1.0F;

    private static final Map<UUID, Deque<Snapshot>> HISTORY = new HashMap<>();

    /** Immutable per-tick capture of everything needed to re-draw a frozen ghost pose. */
    private record Snapshot(
            double x, double y, double z,
            float bodyRot, float yRot, float xRot,
            float walkAnimationPos, float walkAnimationSpeed,
            float attackTime, float swimAmount, boolean crouching) {
    }

    private SandevistanAfterimageRenderer() {
    }

    /** Records each player's position and pose once per tick so ghosts freeze mid-stride. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof Player player) || !player.level().isClientSide()) {
            return;
        }
        UUID id = player.getUUID();
        if (!CyberwareAttachments.isSandevistanActive(player)) {
            HISTORY.remove(id);
            return;
        }
        Deque<Snapshot> trail = HISTORY.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        float limbPos = player.walkAnimation.position();
        float limbSpeed = Math.min(1.0F, player.walkAnimation.speed());
        trail.addFirst(new Snapshot(
                player.getX(), player.getY(), player.getZ(),
                player.yBodyRot, player.getYRot(), player.getXRot(),
                limbPos, limbSpeed,
                player.getAttackAnim(1.0F), player.isVisuallySwimming() ? 1.0F : 0.0F,
                player.isCrouching()));
        while (trail.size() > HISTORY_SIZE) {
            trail.removeLast();
        }
    }

    /** Drops stale trails for players that despawned/logged out. */
    @SubscribeEvent
    public static void onClientPlayerTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        if (HISTORY.isEmpty()) {
            return;
        }
        var level = net.minecraft.client.Minecraft.getInstance().level;
        Iterator<UUID> it = HISTORY.keySet().iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (level == null || level.getPlayerByUUID(id) == null) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onRenderPlayerPost(RenderPlayerEvent.Post<? extends Avatar> event) {
        AvatarRenderState state = event.getRenderState();
        if (state.isInvisible) {
            return;
        }
        Deque<Snapshot> trail = HISTORY.get(playerId(state));
        if (trail == null || trail.isEmpty()) {
            return;
        }

        AvatarRenderer<?> renderer = event.getRenderer();
        PlayerModel model = renderer.getModel();
        Identifier texture = state.skin.body().texturePath();
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();

        int drawn = 0;
        int index = 0;
        for (Snapshot snap : trail) {
            if (index++ < GHOST_START) {
                continue;
            }
            if (drawn >= GHOST_COUNT) {
                break;
            }
            float progress = (float) drawn / Math.max(1, GHOST_COUNT - 1);
            float alpha = Mth.lerp(progress, MAX_ALPHA, MIN_ALPHA);
            // Sweep a green -> light green -> cyan -> light blue -> blue -> purple -> green
            // gradient across the trail. In HSV that is a hue starting at green (120 deg),
            // climbing through cyan/blue/purple, then wrapping the wheel back to green.
            float hue = (GRADIENT_START_HUE + progress * GRADIENT_HUE_SWEEP) % 1.0F;
            int rgb = Mth.hsvToRgb(hue, 1.0F, 1.0F) & 0xFFFFFF;
            int tint = ARGB.color(alpha, rgb);

            // The submit pipeline is deferred: it calls model.setupAnim(submit.state()) at
            // drain time. So each ghost needs its OWN frozen render state, otherwise every
            // ghost re-poses to the live player's pose when the queue is drained.
            AvatarRenderState ghostState = frozenState(state, snap);

            poseStack.pushPose();
            // At RenderPlayerEvent.Post the pose stack is back in world-aligned entity space
            // (Y-up, un-flipped), so re-apply the renderer's model-space setup ourselves:
            // move to the historical world position, then body yaw + the (-1,-1,1) flip +
            // the -1.501 vertical offset the vanilla renderer uses. Without the flip the
            // model renders upside down.
            poseStack.translate(snap.x() - state.x, snap.y() - state.y, snap.z() - state.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - snap.bodyRot()));
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.translate(0.0F, -1.501F, 0.0F);
            collector.submitModel(
                    model,
                    ghostState,
                    poseStack,
                    RenderTypes.entityTranslucent(texture),
                    state.lightCoords,
                    OverlayTexture.NO_OVERLAY,
                    tint,
                    null,
                    0,
                    null);
            poseStack.popPose();
            drawn++;
        }
    }

    /**
     * Builds a per-ghost {@link AvatarRenderState} that shares the live player's appearance
     * (skin, visible parts, held-item poses, etc.) but pins the animation-driving fields to
     * the recorded snapshot so the ghost stays frozen when the deferred submit queue drains.
     */
    private static AvatarRenderState frozenState(AvatarRenderState live, Snapshot snap) {
        AvatarRenderState ghost = new AvatarRenderState();
        // Appearance / skin.
        ghost.skin = live.skin;
        ghost.id = live.id;
        ghost.isSpectator = live.isSpectator;
        ghost.showHat = live.showHat;
        ghost.showJacket = live.showJacket;
        ghost.showLeftPants = live.showLeftPants;
        ghost.showRightPants = live.showRightPants;
        ghost.showLeftSleeve = live.showLeftSleeve;
        ghost.showRightSleeve = live.showRightSleeve;
        ghost.showCape = false;
        ghost.lightCoords = live.lightCoords;
        // Humanoid arm poses / held items (kept from the live frame; the ghost is a copy).
        ghost.mainArm = live.mainArm;
        ghost.attackArm = live.attackArm;
        ghost.rightArmPose = live.rightArmPose;
        ghost.leftArmPose = live.leftArmPose;
        ghost.swingAnimationType = live.swingAnimationType;
        ghost.useItemHand = live.useItemHand;
        ghost.isUsingItem = live.isUsingItem;
        ghost.ticksUsingItem = live.ticksUsingItem;
        ghost.speedValue = live.speedValue;
        ghost.isFallFlying = live.isFallFlying;
        ghost.isPassenger = live.isPassenger;
        ghost.isVisuallySwimming = live.isVisuallySwimming;
        // Living scale/body sizing.
        ghost.scale = live.scale;
        ghost.ageScale = live.ageScale;
        ghost.isBaby = live.isBaby;
        ghost.bodyRot = snap.bodyRot();
        ghost.yRot = snap.yRot();
        // Frozen animation-driving fields.
        ghost.walkAnimationPos = snap.walkAnimationPos();
        ghost.walkAnimationSpeed = snap.walkAnimationSpeed();
        ghost.attackTime = snap.attackTime();
        ghost.xRot = snap.xRot();
        ghost.swimAmount = snap.swimAmount();
        ghost.isCrouching = snap.crouching();
        return ghost;
    }

    private static UUID playerId(AvatarRenderState state) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        var entity = level.getEntity(state.id);
        return entity == null ? null : entity.getUUID();
    }
}
