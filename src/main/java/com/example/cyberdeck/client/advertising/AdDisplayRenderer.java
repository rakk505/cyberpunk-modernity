package com.example.cyberdeck.client.advertising;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.advertising.AdClip;
import com.example.cyberdeck.advertising.AdDisplayBlock;
import com.example.cyberdeck.advertising.AdDisplayBlockEntity;
import com.example.cyberdeck.advertising.FreestandingAdType;
import com.example.cyberdeck.advertising.LargeAdSurfaceValidator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Full-bright renderer for wall displays and synchronized multi-face street ads. */
public final class AdDisplayRenderer
        implements BlockEntityRenderer<AdDisplayBlockEntity, AdDisplayRenderState> {
    private static final Identifier FRAME_TEXTURE = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "textures/ads/frame.png");
    private static final int FULL_BRIGHT = 15_728_880;
    private static final float FRAME_DEPTH = 0.435F;
    private static final float VIDEO_DEPTH = 0.455F;
    private static final float STREET_FRAME_OUTSET = 0.01F;
    private static final float STREET_VIDEO_OUTSET = 0.025F;
    private static final float BORDER = 0.10F;
    private static final float VIDEO_ASPECT = 16.0F / 9.0F;

    public AdDisplayRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public AdDisplayRenderState createRenderState() {
        return new AdDisplayRenderState();
    }

    @Override
    public void extractRenderState(
            AdDisplayBlockEntity blockEntity,
            AdDisplayRenderState state,
            float partialTicks,
            Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(
                blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.renderable = blockEntity.hasConfiguredLayout(blockEntity.getBlockState());
        if (!state.renderable) {
            return;
        }
        state.freestandingType = blockEntity.freestandingType().orElse(null);
        state.longAxis = blockEntity.longAxis();
        if (state.freestandingType == null) {
            state.facing = blockEntity.getBlockState().getValue(AdDisplayBlock.FACING);
        }
        state.width = blockEntity.displayWidth();
        state.height = blockEntity.displayHeight();
        state.generatedPlacement = blockEntity.generatedPlacement();

        if (blockEntity.usesLogoAds()) {
            state.texture = blockEntity.currentLogo().texture();
            state.u0 = 0.0F;
            state.v0 = 0.0F;
            state.u1 = 1.0F;
            state.v1 = 1.0F;
            return;
        }

        AdClip clip = blockEntity.currentClip();
        int frame = clip.frameAt(blockEntity.playbackTicks() + partialTicks);
        int localFrame = frame % AdClip.FRAMES_PER_SHEET;
        int column = localFrame % AdClip.SHEET_COLUMNS;
        int row = localFrame / AdClip.SHEET_COLUMNS;
        state.texture = clip.sheetTexture(frame);
        state.u0 = column / (float) AdClip.SHEET_COLUMNS;
        state.v0 = row / (float) AdClip.SHEET_ROWS;
        state.u1 = (column + 1) / (float) AdClip.SHEET_COLUMNS;
        state.v1 = (row + 1) / (float) AdClip.SHEET_ROWS;
    }

    @Override
    public void submit(
            AdDisplayRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera) {
        if (!state.renderable) {
            return;
        }
        if (state.freestandingType != null) {
            submitFreestanding(state, poseStack, submitNodeCollector);
            return;
        }

        Direction facing = state.facing;
        Direction right = LargeAdSurfaceValidator.rightOf(facing);
        float originX = 0.5F - right.getStepX() * 0.5F - facing.getStepX() * FRAME_DEPTH;
        float originZ = 0.5F - right.getStepZ() * 0.5F - facing.getStepZ() * FRAME_DEPTH;

        submitSurface(state, poseStack, submitNodeCollector, facing,
                originX, originZ, state.width, state.height,
                VIDEO_DEPTH - FRAME_DEPTH, state.generatedPlacement);
    }

    private static void submitFreestanding(
            AdDisplayRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector) {
        FreestandingAdType type = state.freestandingType;
        int sizeX = type.sizeX(state.longAxis);
        int sizeZ = type.sizeZ(state.longAxis);
        for (Direction facing : type.displayFaces(state.longAxis)) {
            float originX;
            float originZ;
            switch (facing) {
                case NORTH -> {
                    originX = sizeX;
                    originZ = -STREET_FRAME_OUTSET;
                }
                case SOUTH -> {
                    originX = 0.0F;
                    originZ = sizeZ + STREET_FRAME_OUTSET;
                }
                case EAST -> {
                    originX = sizeX + STREET_FRAME_OUTSET;
                    originZ = sizeZ;
                }
                case WEST -> {
                    originX = -STREET_FRAME_OUTSET;
                    originZ = 0.0F;
                }
                default -> throw new IllegalStateException("Vertical advertising face");
            }
            submitSurface(state, poseStack, collector, facing,
                    originX, originZ, type.faceLength(), type.height(),
                    STREET_VIDEO_OUTSET, true);
        }
    }

    private static void submitSurface(
            AdDisplayRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Direction facing,
            float originX,
            float originZ,
            float width,
            float height,
            float videoOutset,
            boolean fillFace) {
        Direction right = LargeAdSurfaceValidator.rightOf(facing);
        submitQuad(poseStack, collector, FRAME_TEXTURE, facing, right,
                originX, 0.0F, originZ, width, height,
                0.0F, 0.0F, 1.0F, 1.0F);

        float availableWidth = width - BORDER * 2.0F;
        float availableHeight = height - BORDER * 2.0F;
        float videoWidth = fillFace
                ? availableWidth
                : Math.min(availableWidth, availableHeight * VIDEO_ASPECT);
        float videoHeight = fillFace ? availableHeight : videoWidth / VIDEO_ASPECT;
        float horizontalInset = (width - videoWidth) * 0.5F;
        float verticalInset = (height - videoHeight) * 0.5F;
        float videoOriginX = originX + right.getStepX() * horizontalInset
                + facing.getStepX() * videoOutset;
        float videoOriginZ = originZ + right.getStepZ() * horizontalInset
                + facing.getStepZ() * videoOutset;
        submitQuad(poseStack, collector, state.texture, facing, right,
                videoOriginX, verticalInset, videoOriginZ,
                videoWidth, videoHeight,
                state.u0, state.v0, state.u1, state.v1);
    }

    private static void submitQuad(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Identifier texture,
            Direction facing,
            Direction right,
            float x,
            float y,
            float z,
            float width,
            float height,
            float u0,
            float v0,
            float u1,
            float v1) {
        collector.submitCustomGeometry(
                poseStack,
                RenderTypes.entitySolid(texture),
                (pose, buffer) -> addQuad(pose, buffer, facing, right,
                        x, y, z, width, height, u0, v0, u1, v1));
    }

    private static void addQuad(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            Direction facing,
            Direction right,
            float x,
            float y,
            float z,
            float width,
            float height,
            float u0,
            float v0,
            float u1,
            float v1) {
        float rightX = right.getStepX() * width;
        float rightZ = right.getStepZ() * width;
        addVertex(pose, buffer, facing, x, y + height, z, u0, v0);
        addVertex(pose, buffer, facing, x, y, z, u0, v1);
        addVertex(pose, buffer, facing, x + rightX, y, z + rightZ, u1, v1);
        addVertex(pose, buffer, facing,
                x + rightX, y + height, z + rightZ, u1, v0);
    }

    private static void addVertex(
            PoseStack.Pose pose,
            VertexConsumer buffer,
            Direction facing,
            float x,
            float y,
            float z,
            float u,
            float v) {
        buffer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, facing.getStepX(), 0.0F, facing.getStepZ());
    }

    @Override
    public int getViewDistance() {
        return 256;
    }

    @Override
    public AABB getRenderBoundingBox(AdDisplayBlockEntity blockEntity) {
        BlockPos anchor = blockEntity.getBlockPos();
        if (!blockEntity.hasConfiguredLayout(blockEntity.getBlockState())) {
            return new AABB(anchor);
        }
        FreestandingAdType type = blockEntity.freestandingType().orElse(null);
        if (type != null) {
            return new AABB(
                    anchor.getX() - 0.1,
                    anchor.getY(),
                    anchor.getZ() - 0.1,
                    anchor.getX() + type.sizeX(blockEntity.longAxis()) + 0.1,
                    anchor.getY() + type.height(),
                    anchor.getZ() + type.sizeZ(blockEntity.longAxis()) + 0.1);
        }
        Direction right = LargeAdSurfaceValidator.rightOf(
                blockEntity.getBlockState().getValue(AdDisplayBlock.FACING));
        BlockPos far = anchor.relative(right, blockEntity.displayWidth() - 1)
                .above(blockEntity.displayHeight() - 1);
        return new AABB(
                Math.min(anchor.getX(), far.getX()),
                anchor.getY(),
                Math.min(anchor.getZ(), far.getZ()),
                Math.max(anchor.getX(), far.getX()) + 1.0,
                anchor.getY() + blockEntity.displayHeight(),
                Math.max(anchor.getZ(), far.getZ()) + 1.0);
    }
}
