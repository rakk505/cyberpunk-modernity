package com.example.cyberdeck.client.render;

import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.Cyberware;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Renders two iron swords sticking horizontally out of the player's forearms
 * while the {@link Cyberware#MANTIS_BLADES} augment is installed.
 *
 * <p>26.2 uses an Avatar-based render pipeline where layers receive only a
 * {@link AvatarRenderState}. To decide whether to draw the blades we look the
 * player back up from the render state's entity id and read the (client-synced)
 * {@link CyberwareData} attachment. The blade item model is built once per frame
 * with the {@link ItemModelResolver} and submitted at each arm using the parent
 * {@link PlayerModel}'s hand transform.</p>
 */
public final class MantisBladesLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private final ItemStackRenderState bladeState = new ItemStackRenderState();

    public MantisBladesLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                       int lightCoords, AvatarRenderState state, float yRot, float xRot) {
        if (state.isInvisible) {
            return;
        }
        if (!hasMantisBlades(state)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ItemModelResolver resolver = mc.getItemModelResolver();
        ItemStack blade = new ItemStack(Items.IRON_SWORD);
        resolver.updateForTopItem(
                bladeState, blade, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                mc.level, null, 0);
        if (bladeState.isEmpty()) {
            return;
        }

        submitBlade(state, HumanoidArm.RIGHT, poseStack, submitNodeCollector, lightCoords);
        submitBlade(state, HumanoidArm.LEFT, poseStack, submitNodeCollector, lightCoords);
    }

    private void submitBlade(AvatarRenderState state, HumanoidArm arm, PoseStack poseStack,
                             SubmitNodeCollector submitNodeCollector, int lightCoords) {
        boolean isLeft = arm == HumanoidArm.LEFT;
        poseStack.pushPose();
        // Anchor at the hand and apply vanilla's base "held item" orientation so
        // the sword sits in the fist exactly like ItemInHandLayer does.
        this.getParentModel().translateToHand(state, arm, poseStack);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        // From that orientation the blade tip points down out of the grip; tilt it
        // forward so it becomes a horizontal arm-mounted blade extending past the fist.
        poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        // Slide the blade forward so the hilt tucks against the forearm and the
        // point reaches well past the fist.
        poseStack.translate((isLeft ? -1.0F : 1.0F) / 16.0F, 4.0F / 16.0F, -6.0F / 16.0F);
        poseStack.scale(1.15F, 1.15F, 1.15F);

        bladeState.submit(poseStack, submitNodeCollector, lightCoords,
                OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
    }

    private static boolean hasMantisBlades(AvatarRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        Entity entity = mc.level.getEntity(state.id);
        if (!(entity instanceof Player player)) {
            return false;
        }
        CyberwareData data = CyberwareAttachments.get(player);
        return data.has(Cyberware.MANTIS_BLADES);
    }
}
