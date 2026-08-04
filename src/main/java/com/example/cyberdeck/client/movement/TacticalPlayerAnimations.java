package com.example.cyberdeck.client.movement;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.movement.TacticalAction;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.movement.TacticalMovementState;
import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.weapon.ReloadState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

/** Bridges synchronized movement state into deferred player rendering and restrained camera motion. */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class TacticalPlayerAnimations {
    public static final ContextKey<TacticalPoseData> POSE_DATA = new ContextKey<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "tactical_pose"));

    private TacticalPlayerAnimations() {
    }

    public static void registerRenderStateModifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(
                    T avatar, AvatarRenderState renderState) {
                if (!(avatar instanceof Player player) || !canAnimate(player)) {
                    renderState.setRenderData(POSE_DATA, null);
                    return;
                }
                TacticalPoseData pose = sample(player, renderState.partialTick);
                renderState.setRenderData(POSE_DATA, pose);
                if (pose.action() == TacticalAction.SLIDE) {
                    // Retain the low server collision pose but render a deliberate feet-first slide
                    // rather than vanilla's horizontal swimming animation.
                    renderState.pose = Pose.CROUCHING;
                    renderState.isCrouching = true;
                    renderState.isVisuallySwimming = false;
                    renderState.swimAmount = 0.0F;
                    renderState.walkAnimationSpeed = 0.0F;
                }
            }
        });
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre<?> event) {
        TacticalPoseData pose = event.getRenderState().getRenderData(POSE_DATA);
        if (pose == null) {
            return;
        }
        PoseStack stack = event.getPoseStack();
        if (pose.action() == TacticalAction.DASH) {
            float blend = TacticalPoseAnimator.pulse(pose.actionProgress(), 0.10F, 0.82F);
            stack.mulPose(Axis.ZP.rotationDegrees(-7.5F * pose.lateralAmount() * blend));
            stack.mulPose(Axis.XP.rotationDegrees(4.0F * blend));
        } else if (pose.action() == TacticalAction.SLIDE) {
            float blend = TacticalPoseAnimator.pulse(pose.actionProgress(), 0.16F, 0.84F);
            stack.translate(0.0, -0.34 * blend, 0.10 * blend);
            stack.mulPose(Axis.XP.rotationDegrees(7.0F * blend));
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !canAnimate(player)) {
            return;
        }
        TacticalPoseData pose = sample(player, (float) event.getPartialTick());
        if (pose.action() == TacticalAction.DASH) {
            float blend = TacticalPoseAnimator.pulse(pose.actionProgress(), 0.08F, 0.86F);
            event.setRoll(event.getRoll() - pose.lateralAmount() * 4.5F * blend);
            event.setPitch(event.getPitch() + 1.2F * blend);
        } else if (pose.action() == TacticalAction.SLIDE) {
            float blend = TacticalPoseAnimator.pulse(pose.actionProgress(), 0.14F, 0.86F);
            event.setRoll(event.getRoll() - pose.lateralAmount() * 2.2F * blend);
            event.setPitch(event.getPitch() + 2.5F * blend);
        }
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !canAnimate(player)) {
            return;
        }
        TacticalPoseData pose = sample(player, (float) event.getPartialTick());
        float addition = pose.sprinting() ? 1.25F * Mth.clamp(pose.movementSpeed() / 0.3F, 0.0F, 1.0F) : 0.0F;
        if (pose.action() == TacticalAction.DASH) {
            addition += 4.5F * TacticalPoseAnimator.pulse(pose.actionProgress(), 0.06F, 0.82F);
        } else if (pose.action() == TacticalAction.SLIDE) {
            addition += 2.0F * TacticalPoseAnimator.pulse(pose.actionProgress(), 0.14F, 0.82F);
        }
        event.setFOV(event.getFOV() + addition);
    }

    public static TacticalPoseData sample(Player player, float partialTick) {
        TacticalMovementState movement = TacticalMovement.get(player);
        double now = player.level().getGameTime() + partialTick;
        float progress = actionProgress(movement, now);

        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        double forwardX = -Mth.sin(yaw);
        double forwardZ = Mth.cos(yaw);
        double rightX = -forwardZ;
        double rightZ = forwardX;

        Vec3 direction;
        if (movement.action() != TacticalAction.NONE) {
            direction = new Vec3(movement.directionX(), 0.0, movement.directionZ());
        } else {
            Vec3 velocity = player.getDeltaMovement().multiply(1.0, 0.0, 1.0);
            direction = velocity.lengthSqr() > 1.0E-5 ? velocity.normalize() : Vec3.ZERO;
        }
        float forward = (float) Mth.clamp(direction.x * forwardX + direction.z * forwardZ, -1.0, 1.0);
        float lateral = (float) Mth.clamp(direction.x * rightX + direction.z * rightZ, -1.0, 1.0);
        float speed = (float) player.getDeltaMovement().horizontalDistance();

        ReloadState reload = ReloadState.get(player);
        float reloadProgress = 0.0F;
        if (reload.active()) {
            double duration = Math.max(1.0, reload.endTick() - reload.startTick());
            reloadProgress = (float) Mth.clamp((now - reload.startTick()) / duration, 0.0, 1.0);
        }
        float recoil = movement.lastShotTick() < 0L
                ? 0.0F
                : 1.0F - (float) Mth.clamp((now - movement.lastShotTick()) / 5.0, 0.0, 1.0);
        recoil *= (float) (1.0 - com.example.cyberdeck.cyberware.CyberwareStats
                .from(com.example.cyberdeck.cyberware.CyberwareAttachments.get(player))
                .recoilReduction());

        return new TacticalPoseData(
                movement.action(), progress, forward, lateral,
                player.isSprinting(),
                player.getMainHandItem().getItem() instanceof GunItem,
                reloadProgress, recoil, speed);
    }

    private static float actionProgress(TacticalMovementState state, double now) {
        if (state.action() == TacticalAction.NONE
                || state.actionEndTick() <= state.actionStartTick()) {
            return 0.0F;
        }
        return (float) Mth.clamp(
                (now - state.actionStartTick())
                        / (state.actionEndTick() - state.actionStartTick()),
                0.0,
                1.0);
    }

    private static boolean canAnimate(Player player) {
        return player.isAlive()
                && !player.isPassenger()
                && !player.isFallFlying()
                && !player.isSwimming()
                && !player.isSleeping();
    }
}
