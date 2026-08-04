package com.example.cyberdeck.skill;

import com.example.cyberdeck.control.RemoteEntityControl;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.vehicle.VehicleQuickhackService;
import com.example.cyberdeck.effect.CyberwareEffects;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Applies a completed device upload after the queue has committed its RAM cost. */
public final class DeviceQuickhackExecutor {
    private DeviceQuickhackExecutor() {
    }

    public static boolean canExecute(
            DeviceQuickhack quickhack, ServerPlayer caster, Entity target, ServerLevel level) {
        if (caster == null || !caster.isAlive() || caster.isSpectator()
                || caster.level() != level || !CyberwareEffects.canQuickhack(caster)
                || quickhack == null || !quickhack.supports(target) || target.level() != level
                || caster.distanceToSqr(target)
                        > QuickhackUploads.MAX_TARGET_RANGE * QuickhackUploads.MAX_TARGET_RANGE) {
            return false;
        }
        return switch (quickhack) {
            case CAR_TAKE_CONTROL -> RemoteEntityControl.canBegin(
                    caster, target, DeviceQuickhack.DeviceKind.CAR);
            case CAR_SPEED, CAR_BRAKE, CAR_DETONATE -> true;
            case TURRET_TAKE_CONTROL -> RemoteEntityControl.canBegin(
                    caster, target, DeviceQuickhack.DeviceKind.TURRET);
            case TURRET_DETONATE -> target instanceof KangTaoTurret turret
                    && turret.canAcceptQuickhack(caster);
            case TURRET_DEACTIVATE -> target instanceof KangTaoTurret turret
                    && !turret.isDeactivated() && turret.canAcceptQuickhack(caster);
        };
    }

    public static boolean execute(
            DeviceQuickhack quickhack, ServerPlayer caster, Entity target, ServerLevel level) {
        if (!canExecute(quickhack, caster, target, level)) {
            return false;
        }
        boolean applied = switch (quickhack) {
            case CAR_TAKE_CONTROL -> RemoteEntityControl.begin(
                    caster, target, DeviceQuickhack.DeviceKind.CAR);
            case CAR_SPEED -> VehicleQuickhackService.speed(level, target);
            case CAR_BRAKE -> VehicleQuickhackService.brake(level, target);
            case CAR_DETONATE -> {
                RemoteEntityControl.endForTarget(target);
                yield VehicleQuickhackService.detonate(level, target, caster);
            }
            case TURRET_TAKE_CONTROL -> RemoteEntityControl.begin(
                    caster, target, DeviceQuickhack.DeviceKind.TURRET);
            case TURRET_DETONATE -> {
                RemoteEntityControl.endForTarget(target);
                yield ((KangTaoTurret) target).tryDetonate(caster);
            }
            case TURRET_DEACTIVATE -> {
                RemoteEntityControl.endForTarget(target);
                yield ((KangTaoTurret) target).tryDeactivate(caster);
            }
        };
        if (!applied) {
            caster.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.device_quickhack_failed"), true);
        }
        return applied;
    }
}
