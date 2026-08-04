package com.example.cyberdeck.control;

import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.network.EntityControlInputPacket;
import com.example.cyberdeck.network.EntityControlStatePacket;
import com.example.cyberdeck.skill.DeviceQuickhack.DeviceKind;
import com.example.cyberdeck.vehicle.VehicleQuickhackService;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Server authority for exclusive, short-lived remote car and turret control sessions. */
public final class RemoteEntityControl {
    private static final double MAX_RANGE_SQR = 160.0 * 160.0;
    private static final int INPUT_TIMEOUT_TICKS = 60;
    private static final int SYNC_INTERVAL_TICKS = 20;

    private static final Map<UUID, Session> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, UUID> TARGET_OWNERS = new HashMap<>();

    private RemoteEntityControl() {
    }

    private static final class Session {
        private final UUID playerId;
        private final UUID targetUuid;
        private final int targetId;
        private final Entity targetReference;
        private final DeviceKind kind;
        private final long token;
        private int lastSequence = -1;
        private long lastInputTick;
        private long lastPacketTick = Long.MIN_VALUE;
        private long lastSyncTick;
        private final float bodyYaw;
        private final float bodyPitch;
        private float forward;
        private float turn;
        private boolean braking;

        private Session(ServerPlayer player, Entity target, DeviceKind kind, long token, long now) {
            this.playerId = player.getUUID();
            this.targetUuid = target.getUUID();
            this.targetId = target.getId();
            this.targetReference = target;
            this.kind = kind;
            this.token = token;
            this.lastInputTick = now;
            this.lastSyncTick = now;
            this.bodyYaw = player.getYRot();
            this.bodyPitch = player.getXRot();
        }
    }

    public static boolean begin(ServerPlayer player, Entity target, DeviceKind kind) {
        if (!(player.level() instanceof ServerLevel level)
                || !canBegin(player, target, kind)) {
            return false;
        }

        end(player);
        if (kind == DeviceKind.TURRET
                && (!(target instanceof KangTaoTurret turret)
                        || !turret.tryBeginRemoteControl(player))) {
            return false;
        }
        long token;
        do {
            token = ThreadLocalRandom.current().nextLong();
        } while (token == 0L);
        Session session = new Session(player, target, kind, token, level.getGameTime());
        BY_PLAYER.put(player.getUUID(), session);
        TARGET_OWNERS.put(target.getUUID(), player.getUUID());
        sendState(player, session, true);
        return true;
    }

    public static boolean canBegin(ServerPlayer player, Entity target, DeviceKind kind) {
        if (!validPlayer(player) || !validTarget(player, target, kind)) {
            return false;
        }
        UUID owner = TARGET_OWNERS.get(target.getUUID());
        if (owner != null && !player.getUUID().equals(owner)) {
            return false;
        }
        return kind != DeviceKind.TURRET
                || target instanceof KangTaoTurret turret
                        && (turret.getRemoteControllerId() == null
                                || player.getUUID().equals(turret.getRemoteControllerId()));
    }

    public static void handleInput(ServerPlayer player, EntityControlInputPacket packet) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session == null || session.token != packet.token()
                || packet.sequence() <= session.lastSequence
                || !Float.isFinite(packet.forward()) || !Float.isFinite(packet.turn())
                || !Float.isFinite(packet.yaw()) || !Float.isFinite(packet.pitch())) {
            return;
        }
        long now = player.level().getGameTime();
        session.lastSequence = packet.sequence();
        session.lastInputTick = now;
        if ((packet.buttons() & EntityControlInputPacket.BUTTON_EXIT) != 0) {
            end(player);
            return;
        }
        if (session.lastPacketTick == now) {
            return;
        }
        session.lastPacketTick = now;

        Entity target = resolve(player, session);
        if (!validTarget(player, target, session.kind)) {
            end(player);
            return;
        }
        session.forward = Mth.clamp(packet.forward(), -1.0F, 1.0F);
        session.turn = Mth.clamp(packet.turn(), -1.0F, 1.0F);
        session.braking = (packet.buttons() & EntityControlInputPacket.BUTTON_BRAKE) != 0;

        if (session.kind == DeviceKind.TURRET && target instanceof KangTaoTurret turret) {
            turret.updateRemoteAim(player, packet.yaw(), packet.pitch());
            if ((packet.buttons() & EntityControlInputPacket.BUTTON_FIRE) != 0) {
                turret.fireControlled(player, packet.yaw(), packet.pitch());
            }
        }
    }

    /** Maintains validation, car input, heartbeat timeouts, and client camera liveness. */
    public static void tick(ServerPlayer player) {
        Session session = BY_PLAYER.get(player.getUUID());
        if (session == null) {
            return;
        }
        long now = player.level().getGameTime();
        Entity target = resolve(player, session);
        if (!validPlayer(player) || !validTarget(player, target, session.kind)
                || now - session.lastInputTick > INPUT_TIMEOUT_TICKS) {
            end(player);
            return;
        }
        player.setYRot(session.bodyYaw);
        player.setXRot(session.bodyPitch);
        player.setYHeadRot(session.bodyYaw);
        player.setYBodyRot(session.bodyYaw);
        if (session.kind == DeviceKind.CAR && player.level() instanceof ServerLevel level) {
            VehicleQuickhackService.applyRemoteInput(level, target, session.forward,
                    session.turn, session.braking);
        }
        if (now - session.lastSyncTick >= SYNC_INTERVAL_TICKS) {
            session.lastSyncTick = now;
            sendState(player, session, true);
        }
    }

    public static boolean isControlling(ServerPlayer player) {
        return BY_PLAYER.containsKey(player.getUUID());
    }

    public static void end(ServerPlayer player) {
        Session session = BY_PLAYER.remove(player.getUUID());
        if (session == null) {
            return;
        }
        TARGET_OWNERS.remove(session.targetUuid, session.playerId);
        if (session.kind == DeviceKind.CAR) {
            VehicleQuickhackService.clearRemoteInput(session.targetReference);
        }
        if (session.targetReference instanceof KangTaoTurret turret) {
            turret.endRemoteControl(player);
        }
        sendState(player, session, false);
    }

    public static void endForTarget(Entity target) {
        VehicleQuickhackService.clearRemoteInput(target);
        UUID ownerId = TARGET_OWNERS.get(target.getUUID());
        if (ownerId == null || !(target.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            end(owner);
        } else {
            TARGET_OWNERS.remove(target.getUUID());
            BY_PLAYER.remove(ownerId);
        }
    }

    public static void forget(UUID playerId) {
        Session session = BY_PLAYER.remove(playerId);
        if (session != null) {
            TARGET_OWNERS.remove(session.targetUuid, session.playerId);
        }
    }

    public static void clearAll() {
        BY_PLAYER.clear();
        TARGET_OWNERS.clear();
    }

    private static boolean validPlayer(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && CyberwareEffects.canQuickhack(player);
    }

    private static boolean validTarget(
            ServerPlayer player, @Nullable Entity target, DeviceKind kind) {
        if (target == null || target.isRemoved() || !target.isAlive()
                || target.level() != player.level()
                || player.distanceToSqr(target) > MAX_RANGE_SQR) {
            return false;
        }
        return switch (kind) {
            case CAR -> VehicleQuickhackService.isCar(target);
            case TURRET -> target instanceof KangTaoTurret turret
                    && !turret.isDestroyed() && !turret.isDeactivated()
                    && turret.hasSufficientCyberdeck(player);
        };
    }

    private static @Nullable Entity resolve(ServerPlayer player, Session session) {
        Entity target = player.level().getEntity(session.targetId);
        return target != null && session.targetUuid.equals(target.getUUID()) ? target : null;
    }

    private static void sendState(ServerPlayer player, Session session, boolean active) {
        if (player.connection != null
                && NetworkRegistry.hasChannel(player.connection,
                        EntityControlStatePacket.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, new EntityControlStatePacket(
                    active, session.token, session.targetId, session.targetUuid,
                    session.kind.ordinal()));
        }
    }
}
