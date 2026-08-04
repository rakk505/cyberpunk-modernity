package com.example.cyberdeck.client;

import com.example.cyberdeck.network.EntityControlInputPacket;
import com.example.cyberdeck.network.EntityControlStatePacket;
import com.example.cyberdeck.skill.DeviceQuickhack.DeviceKind;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.vehicle.QuickhackCar;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** Owns the client camera and input sampling for a server-authorized device control session. */
public final class EntityControlClient {
    private static final int TARGET_GRACE_TICKS = 20;
    private static final int SERVER_TIMEOUT_TICKS = 60;

    private static boolean active;
    private static long token;
    private static int targetId = -1;
    private static @Nullable UUID targetUuid;
    private static DeviceKind kind = DeviceKind.CAR;
    private static int sequence;
    private static int clientTicks;
    private static int lastServerPulse;
    private static int missingTargetTicks;
    private static boolean fireRequested;
    private static boolean exitRequested;
    private static boolean aimInitialized;
    private static float aimYaw;
    private static float aimPitch;
    private static float bodyYaw;
    private static float bodyPitch;
    private static @Nullable CameraType previousCameraType;
    private static @Nullable Entity previousCameraEntity;
    private static @Nullable Object sessionLevel;

    private EntityControlClient() {
    }

    public static void accept(EntityControlStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!packet.active()) {
            if (active && packet.token() == token) {
                clearLocal(minecraft);
            }
            return;
        }
        DeviceKind[] kinds = DeviceKind.values();
        if (packet.kind() < 0 || packet.kind() >= kinds.length) {
            return;
        }
        if (active && token == packet.token()) {
            lastServerPulse = clientTicks;
            return;
        }

        CameraType restoreCameraType = active && previousCameraType != null
                ? previousCameraType : minecraft.options.getCameraType();
        Entity restoreCameraEntity = active && previousCameraEntity != null
                ? previousCameraEntity : minecraft.getCameraEntity();
        clearLocal(minecraft);
        active = true;
        token = packet.token();
        targetId = packet.targetId();
        targetUuid = packet.targetUuid();
        kind = kinds[packet.kind()];
        lastServerPulse = clientTicks;
        sessionLevel = minecraft.level;
        previousCameraType = restoreCameraType;
        previousCameraEntity = restoreCameraEntity;
        if (minecraft.player != null) {
            bodyYaw = minecraft.player.getYRot();
            bodyPitch = minecraft.player.getXRot();
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isTurretControl() {
        return active && kind == DeviceKind.TURRET;
    }

    public static void requestFire() {
        if (isTurretControl()) {
            fireRequested = true;
        }
    }

    public static void requestExit() {
        if (active) {
            exitRequested = true;
        }
    }

    /** Samples raw bindings once per client tick while player movement itself remains suppressed. */
    public static void tick(Minecraft minecraft) {
        clientTicks++;
        if (!active) {
            return;
        }
        Player player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.level != sessionLevel
                || clientTicks - lastServerPulse > SERVER_TIMEOUT_TICKS) {
            clearLocal(minecraft);
            return;
        }
        if (minecraft.gui.screen() != null) {
            sendExit();
            clearLocal(minecraft);
            return;
        }
        if (exitRequested) {
            sendExit();
            clearLocal(minecraft);
            return;
        }

        Entity target = resolve(minecraft);
        if (target == null) {
            if (++missingTargetTicks > TARGET_GRACE_TICKS) {
                sendExit();
                clearLocal(minecraft);
            }
            return;
        }
        missingTargetTicks = 0;
        Entity cameraTarget = target;
        if (target instanceof QuickhackCar adapter) {
            Entity anchor = adapter.quickhackCameraAnchor();
            if (anchor != null && anchor.level() == minecraft.level && !anchor.isRemoved()) {
                cameraTarget = anchor;
            }
        }
        minecraft.setCameraEntity(cameraTarget);
        minecraft.options.setCameraType(
                kind == DeviceKind.CAR
                        ? CameraType.THIRD_PERSON_BACK
                        : CameraType.FIRST_PERSON);

        if (kind == DeviceKind.TURRET) {
            updateTurretAim(player, target);
        } else {
            aimYaw = target.getYRot();
            aimPitch = target.getXRot();
        }

        float forward = (minecraft.options.keyUp.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyDown.isDown() ? 1.0F : 0.0F);
        float turn = (minecraft.options.keyRight.isDown() ? 1.0F : 0.0F)
                - (minecraft.options.keyLeft.isDown() ? 1.0F : 0.0F);
        int buttons = 0;
        if (fireRequested) {
            buttons |= EntityControlInputPacket.BUTTON_FIRE;
        }
        if (kind == DeviceKind.CAR && minecraft.options.keyJump.isDown()) {
            buttons |= EntityControlInputPacket.BUTTON_BRAKE;
        }
        ClientPacketDistributor.sendToServer(new EntityControlInputPacket(
                token, sequence++, forward, turn, aimYaw, aimPitch, buttons));
        fireRequested = false;
    }

    public static void clear() {
        clearLocal(Minecraft.getInstance());
    }

    private static void updateTurretAim(Player player, Entity target) {
        if (!aimInitialized) {
            aimYaw = target.getYRot();
            aimPitch = target.getXRot();
            aimInitialized = true;
        }
        aimYaw += Mth.wrapDegrees(player.getYRot() - bodyYaw);
        aimPitch = Mth.clamp(aimPitch + player.getXRot() - bodyPitch, -45.0F, 45.0F);
        if (target instanceof KangTaoTurret turret) {
            aimYaw = KangTaoTurret.clampAimYaw(turret.getBaseYaw(), aimYaw);
        }
        player.setYRot(bodyYaw);
        player.setXRot(bodyPitch);
        target.setYRot(aimYaw);
        target.setXRot(aimPitch);
        target.yRotO = aimYaw;
        target.xRotO = aimPitch;
        if (target instanceof LivingEntity living) {
            living.setYHeadRot(aimYaw);
        }
    }

    private static @Nullable Entity resolve(Minecraft minecraft) {
        if (minecraft.level == null || targetUuid == null) {
            return null;
        }
        Entity entity = minecraft.level.getEntity(targetId);
        return entity != null && targetUuid.equals(entity.getUUID())
                && entity.isAlive() && !entity.isRemoved() ? entity : null;
    }

    private static void sendExit() {
        if (active) {
            ClientPacketDistributor.sendToServer(new EntityControlInputPacket(
                    token, sequence++, 0.0F, 0.0F, aimYaw, aimPitch,
                    EntityControlInputPacket.BUTTON_EXIT));
        }
    }

    private static void clearLocal(Minecraft minecraft) {
        if (previousCameraEntity != null && minecraft.level != null
                && previousCameraEntity.level() == minecraft.level
                && !previousCameraEntity.isRemoved()) {
            minecraft.setCameraEntity(previousCameraEntity);
        } else if (minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
        } else {
            minecraft.setCameraEntity(null);
        }
        if (previousCameraType != null) {
            minecraft.options.setCameraType(previousCameraType);
        }
        active = false;
        token = 0L;
        targetId = -1;
        targetUuid = null;
        sequence = 0;
        missingTargetTicks = 0;
        fireRequested = false;
        exitRequested = false;
        aimInitialized = false;
        previousCameraType = null;
        previousCameraEntity = null;
        sessionLevel = null;
    }
}
