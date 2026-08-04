package com.example.cyberdeck.mixin;

import com.example.cyberdeck.vehicle.CityTrafficService;
import com.modernity.vehicle_mod.entity.MotorbikeEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the native remote drivetrain drive a motorbike while its visible traffic NPC rides it. */
@Mixin(value = MotorbikeEntity.class, remap = false)
public abstract class MotorbikeTrafficDriverMixin {
    @Inject(method = "getControllingPassenger", at = @At("RETURN"), cancellable = true)
    private void cyberdeck$ignoreAmbientTrafficDriver(
            CallbackInfoReturnable<LivingEntity> callback) {
        if (CityTrafficService.isTrafficDriver(callback.getReturnValue())) {
            callback.setReturnValue(null);
        }
    }
}
