package com.example.cyberdeck.client.render;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.gun.BedrockGeoData;
import com.example.cyberdeck.client.gun.BedrockModel;
import com.example.cyberdeck.client.gun.BedrockPart;
import com.example.cyberdeck.defense.KangTaoTurret;

/** Renders the supplied Bedrock turret model and rotates only its articulated gun assembly. */
public final class KangTaoTurretRenderer
        extends EntityRenderer<KangTaoTurret, KangTaoTurretRenderState> {
    private static final Identifier GEOMETRY = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "entity/kang_tao_turret.geo.json");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "textures/entity/kang_tao_turret.png");
    private static final int DESTROYED_TINT = 0xFF080808;

    private BedrockModel model;
    private boolean modelLoadAttempted;

    public KangTaoTurretRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.85F;
    }

    @Override
    public KangTaoTurretRenderState createRenderState() {
        return new KangTaoTurretRenderState();
    }

    @Override
    public void extractRenderState(
            KangTaoTurret turret, KangTaoTurretRenderState state, float partialTicks) {
        super.extractRenderState(turret, state, partialTicks);
        state.baseYaw = turret.getBaseYaw();
        state.aimYaw = turret.getYRot(partialTicks);
        state.aimPitch = turret.getXRot(partialTicks);
        state.destroyed = turret.isDestroyed();
    }

    @Override
    public void submit(
            KangTaoTurretRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera) {
        BedrockModel turretModel = this.getModel();
        if (turretModel != null && !state.isInvisible) {
            float relativeYaw = Mth.degreesDifference(state.baseYaw, state.aimYaw);
            float pitch = state.aimPitch;
            int color = state.destroyed ? DESTROYED_TINT : 0xFFFFFFFF;

            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(-state.baseYaw));
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.translate(0.0F, -1.501F, 0.0F);
            submitNodeCollector.submitCustomGeometry(
                    poseStack,
                    RenderTypes.entityCutout(TEXTURE),
                    (pose, buffer) -> {
                        turretModel.resetAnimation();
                        BedrockPart yawBone = turretModel.getBone("turret_yaw");
                        if (yawBone != null) {
                            yawBone.animRotation.rotationY((float) Math.toRadians(relativeYaw));
                        }
                        BedrockPart pitchBone = turretModel.getBone("gun_core");
                        if (pitchBone != null) {
                            pitchBone.animRotation.rotationX((float) Math.toRadians(-pitch));
                        }

                        PoseStack local = new PoseStack();
                        local.last().pose().set(pose.pose());
                        local.last().normal().set(pose.normal());
                        turretModel.render(local, buffer, state.lightCoords,
                                OverlayTexture.NO_OVERLAY, color);
                    });
            poseStack.popPose();
        }
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    private BedrockModel getModel() {
        if (this.model != null || this.modelLoadAttempted) {
            return this.model;
        }
        this.modelLoadAttempted = true;
        Optional<Resource> resource = Minecraft.getInstance()
                .getResourceManager()
                .getResource(GEOMETRY);
        if (resource.isEmpty()) {
            Cyberdeck.LOGGER.error("Missing Kang Tao turret geometry {}", GEOMETRY);
            return null;
        }

        try (InputStream input = resource.get().open();
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            this.model = new BedrockModel(BedrockGeoData.parse(json));
        } catch (Exception exception) {
            Cyberdeck.LOGGER.error("Failed to load Kang Tao turret geometry {}", GEOMETRY, exception);
        }
        return this.model;
    }
}
