package com.example.cyberdeck.client.gun;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.movement.TacticalFirstPerson;
import com.example.cyberdeck.weapon.GunItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;

/**
 * Draws cyberdeck guns in first person as animated Bedrock models, replacing the flat vanilla item
 * render. Listens for {@link RenderHandEvent}; when the held item is a {@link GunItem} that has an
 * animated model in {@link GunModelRegistry}, it cancels the default render, applies a first-person
 * hand transform, samples the current animation clip via {@link GunAnimationController}, and submits
 * the model geometry through the 26.2 {@link SubmitNodeCollector#submitCustomGeometry} pipeline.
 */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class FirstPersonGunRenderer {
    private FirstPersonGunRenderer() {}

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof GunItem gunItem)) {
            return;
        }
        GunModelRegistry.Entry entry = GunModelRegistry.get(gunItem.gun());
        if (entry == null) {
            return; // No animated model for this gun; leave the vanilla render alone.
        }

        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer player = mc.player;
        if (player == null) {
            return;
        }

        // We own this hand's render now.
        event.setCanceled(true);

        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        int light = event.getPackedLight();

        // Update animation state from cyberdeck's own gun state and sample it onto the bones.
        GunAnimationController controller = GunAnimationController.get();
        controller.update(player, stack, gunItem, entry.animation());
        String clipName = controller.clipName();
        BedrockAnimationData.Clip clip = entry.animation().clips.get(clipName);
        BedrockModel model = entry.model();
        if (layersOverHoldingPose(clipName)) {
            // Fire clips author recoil on the root but generally omit the hand branches.
            // Seed those unmentioned branches from static_idle so both arms stay on the grips,
            // then let the action clip override every channel it explicitly contains.
            GunAnimator.applyLayered(model, entry.animation().clips.get("static_idle"),
                    clip, controller.clipTime());
        } else {
            GunAnimator.apply(model, clip, controller.clipTime());
        }

        float crouchBlend = CrouchAnimationController.update(player);
        if (crouchBlend > 0.0F) {
            BedrockAnimationData stance = GunModelRegistry.crouchAnimation();
            String stanceName = CrouchAnimationController.isMoving(player)
                    ? "crouch_walk"
                    : "crouch_idle";
            GunAnimator.applyAdditive(
                    model,
                    stance.clips.get(stanceName),
                    CrouchAnimationController.playbackTime(player, event.getPartialTick()),
                    crouchBlend);
        }
        BedrockPart reloadAssembly = model.getBone("mag_and_hand");
        if (reloadAssembly != null) {
            reloadAssembly.visible = clipName.startsWith("reload_");
        }

        poseStack.pushPose();
        TacticalFirstPerson.apply(poseStack, player, event.getPartialTick());
        applyFirstPersonTransform(poseStack, entry.model());

        int overlay = OverlayTexture.NO_OVERLAY;
        // TaCZ renders its Bedrock gun models with entityCutoutNoCull (alpha-test, no back-face
        // culling). The 26.2 equivalent is RenderTypes.entityCutout, which is already no-cull.
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(entry.texture()),
                (pose, buffer) -> {
                    // Render into a temporary pose stack seeded from the submitted pose so the
                    // bone tree's own pushPose/popPose transforms compose correctly.
                    PoseStack local = new PoseStack();
                    local.last().pose().set(pose.pose());
                    local.last().normal().set(pose.normal());
                    model.render(local, buffer, light, overlay, 0xFFFFFFFF);
                });
        renderPlayerArms(mc, player, model, poseStack, collector, light);
        poseStack.popPose();
    }

    private static boolean layersOverHoldingPose(String clipName) {
        return clipName.startsWith("shoot");
    }

    /**
     * Renders the player's real skin at the rig's animated hand markers. The vanilla avatar
     * renderer supplies correct classic/slim geometry and the enabled sleeve layer, while the
     * Bedrock traversal supplies the complete reload/recoil transform chain for both arms.
     */
    private static void renderPlayerArms(Minecraft mc, AbstractClientPlayer player,
                                         BedrockModel model, PoseStack poseStack,
                                         SubmitNodeCollector collector, int light) {
        if (player.isInvisible()) {
            return;
        }
        Identifier skin = player.getSkin().body().texturePath();
        AvatarRenderer<AbstractClientPlayer> avatar =
                mc.getEntityRenderDispatcher().getPlayerRenderer(player);
        model.visitAnimated(poseStack, (name, armPose) -> {
            if (!BedrockModel.isPlayerArmAnchor(name)) {
                return;
            }
            armPose.pushPose();
            // Vanilla's arm model extends down from its shoulder pivot; the TaCZ marker cube
            // extends upward in the converted coordinate space. This rotation aligns them exactly.
            armPose.mulPose(Axis.ZP.rotationDegrees(180.0F));
            if ("righthand_pos".equals(name)) {
                avatar.renderRightHand(armPose, collector, light, skin,
                        player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE), player);
            } else {
                avatar.renderLeftHand(armPose, collector, light, skin,
                        player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE), player);
            }
            armPose.popPose();
        });
    }

    /**
     * Positions the gun in the first-person view exactly as TaCZ's
     * {@code AnimateGeoItemRenderer.renderFirstPerson} does. The {@link RenderHandEvent} pose is
     * already anchored at the camera (view origin) with view-bob applied, which matches the pose
     * TaCZ receives, so we do NOT apply any vanilla item-arm transform or the flat item model's
     * {@code firstperson_righthand} display transform (those are only for the vanilla/JSON render
     * path, not the animated Bedrock path).
     *
     * <p>The chain is:
     * <ol>
     *   <li>{@code translate(0, 1.5, 0)} + {@code rotateZ(180)} — TaCZ's base placement that stands
     *       the y-up, 24-unit-tall Bedrock rig upright in front of the camera;</li>
     *   <li>the "idle view" positioning: translate the model so its {@code idle_view} sight node
     *       lands at the camera. This is TaCZ's {@code applyFirstPersonPositioningTransform}
     *       collapsed to a single translate, since the {@code idle_view} path has no rotation.</li>
     * </ol>
     */
    private static void applyFirstPersonTransform(PoseStack poseStack, BedrockModel model) {
        // (1) TaCZ renderFirstPerson base.
        poseStack.translate(0.0F, 1.5F, 0.0F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));

        // RenderHandEvent is camera-relative, unlike TaCZ's internal item-render origin. Applying
        // the model's idle_view pivot here moved the entire gun above the camera because the
        // post-rotation X/Y axes are inverted. Anchor it explicitly in the lower-right of the
        // camera instead; recoil and reload animation continue to apply through the bone tree.
        float x = -1.0F;
        float y = 1.55F;
        float z = -2.5F;
        BedrockPart idle = model.getBone("idle_view");
        if (idle != null) {
            // Every TaCZ rig authors its own sight anchor. Offset it to Overture's verified anchor
            // so differently sized pistols, SMGs and rifles share the same camera framing.
            x += (OVERTURE_IDLE_X - idle.viewX()) / 16.0F;
            y += (OVERTURE_IDLE_Y - idle.viewY()) / 16.0F;
            z += (OVERTURE_IDLE_Z - idle.viewZ()) / 16.0F;
        }
        poseStack.translate(x, y, z);
        poseStack.scale(0.75F, 0.75F, 0.75F);
    }

    private static final float OVERTURE_IDLE_X = 3.0F;
    private static final float OVERTURE_IDLE_Y = 14.0F;
    private static final float OVERTURE_IDLE_Z = 15.0F;

}
