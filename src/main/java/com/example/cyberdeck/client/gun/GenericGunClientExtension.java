package com.example.cyberdeck.client.gun;

import com.example.cyberdeck.client.movement.TacticalFirstPerson;
import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.ReloadState;
import com.example.cyberdeck.weapon.WeaponComponents;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.EnumMap;
import java.util.Map;

/** Shared procedural first-person animation for guns without a native Bedrock animation rig. */
public final class GenericGunClientExtension implements IClientItemExtensions {
    public static final GenericGunClientExtension INSTANCE = new GenericGunClientExtension();

    private final Map<GunType, Integer> lastMagazine = new EnumMap<>(GunType.class);
    private final Map<GunType, Long> recoilStarted = new EnumMap<>(GunType.class);

    private GenericGunClientExtension() {}

    @Override
    public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand,
                                             ItemStack stack) {
        if (!(stack.getItem() instanceof GunItem) || hand != InteractionHand.MAIN_HAND) {
            return null;
        }
        return entity instanceof net.minecraft.world.entity.player.Player player
                && ReloadState.get(player).active()
                ? HumanoidModel.ArmPose.CROSSBOW_CHARGE
                : HumanoidModel.ArmPose.CROSSBOW_HOLD;
    }

    @Override
    public boolean applyForgeHandTransform(PoseStack poseStack, LocalPlayer player, HumanoidArm arm,
                                           ItemStack stack, float partialTick, float equipProgress,
                                           float swingProgress) {
        if (!(stack.getItem() instanceof GunItem gunItem)) {
            return false;
        }

        TacticalFirstPerson.apply(poseStack, player, partialTick);
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        // Vanilla's stable hand anchor. Returning true below prevents the generic melee swing from
        // turning firearms sideways while preserving each model's first-person display transform.
        poseStack.translate(side * 0.56F, -0.52F - equipProgress * 0.6F, -0.72F);

        // Match the Blockbench-authored root stance used by native Bedrock gun rigs. These fallback
        // JSON models have no bones to sample, so the same subtle lowering/step sway is applied to
        // the hand transform directly.
        float crouchBlend = CrouchAnimationController.update(player);
        if (crouchBlend > 0.0F) {
            float age = player.tickCount + partialTick;
            float stride = CrouchAnimationController.isMoving(player)
                    ? Mth.sin(age * 0.40F)
                    : Mth.sin(age * 0.05F) * 0.2F;
            poseStack.translate(
                    side * stride * 0.005F * crouchBlend,
                    (-0.045F + Math.abs(stride) * 0.006F) * crouchBlend,
                    0.028F * crouchBlend);
            poseStack.mulPose(Axis.XP.rotationDegrees((4.0F + Math.abs(stride) * 2.0F)
                    * crouchBlend));
            poseStack.mulPose(Axis.ZP.rotationDegrees(side * stride * 0.8F * crouchBlend));
        }

        GunType gun = gunItem.gun();
        int magazine = magazine(stack, gun);
        Integer previous = lastMagazine.put(gun, magazine);
        if (previous != null && magazine < previous) {
            recoilStarted.put(gun, System.nanoTime());
        }

        // Subtle breathing/weapon sway keeps every static mesh alive without disturbing aim.
        float age = player.tickCount + partialTick;
        float breathe = Mth.sin(age * 0.055F);
        poseStack.translate(side * breathe * 0.004F, breathe * 0.006F, 0.0F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(side * breathe * 0.35F));

        ReloadState reload = ReloadState.get(player);
        if (reload.active()) {
            double duration = Math.max(1.0, reload.endTick() - reload.startTick());
            float progress = (float) Mth.clamp(
                    (player.level().getGameTime() + partialTick - reload.startTick()) / duration,
                    0.0, 1.0);
            float arc = Mth.sin(progress * (float) Math.PI);
            poseStack.translate(side * 0.08F * arc, -0.28F * arc, 0.10F * arc);
            poseStack.mulPose(Axis.ZP.rotationDegrees(side * 32.0F * arc));
            poseStack.mulPose(Axis.XP.rotationDegrees(12.0F * arc));
        } else {
            Long started = recoilStarted.get(gun);
            if (started != null) {
                float elapsed = (System.nanoTime() - started) / 1_000_000_000.0F;
                float recoil = 1.0F - Mth.clamp(elapsed / 0.18F, 0.0F, 1.0F);
                if (recoil <= 0.0F) {
                    recoilStarted.remove(gun);
                } else {
                    poseStack.translate(0.0F, 0.025F * recoil, 0.10F * recoil);
                    poseStack.mulPose(Axis.XP.rotationDegrees(-9.0F * recoil));
                }
            }
        }

        return true;
    }

    private static int magazine(ItemStack stack, GunType gun) {
        Integer value = stack.get(WeaponComponents.MAGAZINE.get());
        return value == null ? gun.magazineSize() : value;
    }
}
