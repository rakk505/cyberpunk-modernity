package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.QuickhackScannerClient;
import com.example.cyberdeck.client.QuickhackUploadClient;
import com.example.cyberdeck.network.QuickhackUploadPacket;
import com.example.cyberdeck.skill.DeviceQuickhack;
import com.example.cyberdeck.skill.Skill;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.jspecify.annotations.Nullable;

/** A world-anchored square that fills while a queued quickhack uploads. */
public final class QuickhackUploadOverlay implements GuiLayer {
    private static final int SIZE = 24;
    private static final int BACKGROUND = 0xD0061118;
    private static final int BORDER = 0xFF58F4EC;
    private static final int BORDER_DIM = 0xFF247B82;
    private static final int PROGRESS = 0x9A20CFC9;
    private static final int PROGRESS_EDGE = 0xFFE0FFFF;
    private static final int ACCENT = 0xFFFF5B55;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!QuickhackUploadClient.isUploading()
                || QuickhackScannerClient.isActive() && !QuickhackScannerClient.isQuickhacking()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || minecraft.gui.hud.isHidden() || minecraft.gui.screen() != null) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        double gameTime = minecraft.level.getGameTime() + partialTick;
        for (QuickhackUploadPacket.TargetUpload upload : QuickhackUploadClient.uploads()) {
            Skill skill = Skill.fromSlot(upload.activeSkillOrdinal());
            DeviceQuickhack deviceQuickhack = DeviceQuickhack.fromWireId(
                    upload.activeSkillOrdinal());
            Entity entity = minecraft.level.getEntity(upload.targetId());
            ItemStack icon = skill != null ? skill.stack()
                    : deviceQuickhack != null ? deviceQuickhack.stack() : ItemStack.EMPTY;
            if (icon.isEmpty() || entity == null || !entity.isAlive() || entity.isRemoved()) {
                continue;
            }

            ScreenPoint point = projectAboveTarget(minecraft, entity, partialTick,
                    graphics.guiWidth(), graphics.guiHeight());
            if (point == null) {
                continue;
            }

            float progress = QuickhackUploadClient.uploadProgress(upload, gameTime);
            int x = Mth.clamp(Math.round(point.x()) - SIZE / 2, 4,
                    Math.max(4, graphics.guiWidth() - SIZE - 4));
            int y = Mth.clamp(Math.round(point.y()) - SIZE - 5, 4,
                    Math.max(4, graphics.guiHeight() - SIZE - 4));
            drawMarker(graphics, icon, x, y, progress, minecraft.level.getGameTime());
        }
    }

    private static void drawMarker(GuiGraphicsExtractor graphics, ItemStack icon, int x, int y,
        float progress, long gameTime) {
        fillCutRect(graphics, x, y, SIZE, SIZE, BACKGROUND);
        graphics.item(icon, x + 4, y + 4);

        int innerX = x + 3;
        int innerY = y + 3;
        int innerSize = SIZE - 6;
        int filled = Mth.clamp(Math.round(innerSize * progress), 0, innerSize);
        int fillTop = innerY + innerSize - filled;
        if (filled > 0) {
            graphics.fill(innerX, fillTop, innerX + innerSize, innerY + innerSize, PROGRESS);
            graphics.fill(innerX, fillTop, innerX + innerSize, fillTop + 1, PROGRESS_EDGE);
        }
        drawCutBorder(graphics, x, y, SIZE, SIZE, BORDER);

        int pulse = (gameTime / 4L) % 2L == 0L ? BORDER : BORDER_DIM;
        graphics.horizontalLine(x - 4, x - 1, y + SIZE / 2, pulse);
        graphics.horizontalLine(x + SIZE, x + SIZE + 3, y + SIZE / 2, pulse);
        graphics.verticalLine(x + SIZE / 2, y + SIZE, y + SIZE + 4, ACCENT);
        graphics.fill(x + SIZE / 2 - 1, y - 3, x + SIZE / 2 + 2, y - 1, ACCENT);
    }

    private static @Nullable ScreenPoint projectAboveTarget(Minecraft minecraft,
                                                             Entity target,
                                                             float partialTick,
                                                             int screenWidth,
                                                             int screenHeight) {
        Camera camera = minecraft.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Vec3 marker = new Vec3(
                Mth.lerp(partialTick, target.xOld, target.getX()),
                Mth.lerp(partialTick, target.yOld, target.getY())
                        + target.getBbHeight() + 0.45,
                Mth.lerp(partialTick, target.zOld, target.getZ()));
        Vec3 relative = marker.subtract(eye);
        Vec3 forward = Vec3.directionFromRotation(camera.xRot(), camera.yRot()).normalize();
        Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0e-6) {
            return null;
        }
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();

        double depth = relative.dot(forward);
        if (depth <= 0.05) {
            return null;
        }

        float cameraFov = camera.getFov() > 0.0F
                ? camera.getFov() : minecraft.options.fov().get();
        double fovRadians = Math.toRadians(cameraFov);
        double halfVertical = depth * Math.tan(fovRadians * 0.5);
        if (halfVertical <= 1.0e-5) {
            return null;
        }
        double aspect = screenWidth / (double) Math.max(1, screenHeight);
        double ndcX = relative.dot(right) / (halfVertical * aspect);
        double ndcY = relative.dot(up) / halfVertical;
        if (Math.abs(ndcX) > 1.08 || Math.abs(ndcY) > 1.08) {
            return null;
        }

        return new ScreenPoint(
                (float) ((ndcX + 1.0) * 0.5 * screenWidth),
                (float) ((1.0 - ndcY) * 0.5 * screenHeight));
    }

    private static void fillCutRect(GuiGraphicsExtractor graphics, int x, int y, int width,
                                    int height, int color) {
        graphics.fill(x + 3, y, x + width - 3, y + height, color);
        graphics.fill(x, y + 3, x + width, y + height - 3, color);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, color);
    }

    private static void drawCutBorder(GuiGraphicsExtractor graphics, int x, int y, int width,
                                      int height, int color) {
        int right = x + width - 1;
        int bottom = y + height - 1;
        graphics.horizontalLine(x + 3, right - 3, y, color);
        graphics.horizontalLine(x + 3, right - 3, bottom, color);
        graphics.verticalLine(x, y + 3, bottom - 3, color);
        graphics.verticalLine(right, y + 3, bottom - 3, color);
        pixelLine(graphics, x, y + 3, x + 3, y, color);
        pixelLine(graphics, right - 3, y, right, y + 3, color);
        pixelLine(graphics, right, bottom - 3, right - 3, bottom, color);
        pixelLine(graphics, x + 3, bottom, x, bottom - 3, color);
    }

    private static void pixelLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
                                  int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i++) {
            int x = Math.round(Mth.lerp(i / (float) steps, x0, x1));
            int y = Math.round(Mth.lerp(i / (float) steps, y0, y1));
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private record ScreenPoint(float x, float y) {
    }
}
