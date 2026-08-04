package com.example.cyberdeck.mixin;

import com.modernity.vehicle_mod.entity.HypercarEntity;
import com.modernity.vehicle_mod.entity.MotorbikeEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow bridge to the companion mod's native drivetrain for tests and emergency braking. */
@Mixin(value = {HypercarEntity.class, MotorbikeEntity.class}, remap = false)
public interface NativeVehicleDrivetrainMixin {
    @Invoker("updateDrivetrain")
    void cyberdeck$applyDriverInput(Player driver);

    @Invoker("updateDrivetrain")
    void cyberdeck$applyDrivetrainInput(float throttle, float steering, boolean braking);

    @Invoker("applyRemoteMovement")
    void cyberdeck$applyRemoteMovement();
}
