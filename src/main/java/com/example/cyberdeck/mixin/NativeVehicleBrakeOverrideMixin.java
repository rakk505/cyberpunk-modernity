package com.example.cyberdeck.mixin;

import com.example.cyberdeck.vehicle.VehicleQuickhackService;
import com.modernity.vehicle_mod.entity.HypercarEntity;
import com.modernity.vehicle_mod.entity.MotorbikeEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents held player throttle from overriding an active native brake quickhack. */
@Mixin(value = {HypercarEntity.class, MotorbikeEntity.class}, remap = false)
public abstract class NativeVehicleBrakeOverrideMixin {
    @Inject(
            method = "updateDrivetrain(Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void cyberdeck$overridePlayerInputWhileBraking(
            Player driver, CallbackInfo callback) {
        Entity vehicle = (Entity) (Object) this;
        if (!VehicleQuickhackService.isBrakeActive(vehicle)) return;
        ((NativeVehicleDrivetrainMixin) this)
                .cyberdeck$applyDrivetrainInput(0.0F, 0.0F, true);
        callback.cancel();
    }
}
