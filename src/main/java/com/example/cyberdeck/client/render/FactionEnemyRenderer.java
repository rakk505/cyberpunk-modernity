package com.example.cyberdeck.client.render;

import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.weapon.GunItem;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Renders faction soldiers as player-shaped humanoids — their own entity, not a zombie. It uses the
 * standard humanoid player model (so the held gun and dyed faction armor render on a normal biped)
 * and textures the body with the default Steve skin. Nothing here inherits any zombie behavior or
 * appearance; it is a plain {@link HumanoidMobRenderer} with a humanoid armor layer.
 */
public final class FactionEnemyRenderer
        extends HumanoidMobRenderer<FactionEnemy, HumanoidRenderState,
                HumanoidModel<HumanoidRenderState>> {

    /** Vanilla default (wide) Steve skin. */
    private static final Identifier STEVE_SKIN =
            Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public FactionEnemyRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        this.addLayer(new HumanoidArmorLayer<>(this,
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
                context.getEquipmentRenderer()));
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return STEVE_SKIN;
    }

    @Override
    protected HumanoidModel.ArmPose getArmPose(FactionEnemy enemy, HumanoidArm arm) {
        if (enemy.getMainHandItem().getItem() instanceof GunItem
                && arm == enemy.getMainArm()) {
            return enemy.isWeaponGlitching() || enemy.isGunReloading()
                    ? HumanoidModel.ArmPose.CROSSBOW_CHARGE
                    : HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        return super.getArmPose(enemy, arm);
    }

    @Override
    public void extractRenderState(FactionEnemy enemy, HumanoidRenderState state,
                                   float partialTick) {
        super.extractRenderState(enemy, state, partialTick);

        // The off-hand slot is this entity's holster, not a second simultaneously wielded gun.
        // Hide that model so the synchronized hand swap visibly replaces the primary with the
        // sidearm instead of making the soldier look like it is dual-wielding both weapons.
        if (enemy.getOffhandItem().getItem() instanceof GunItem) {
            if (enemy.getMainArm() == HumanoidArm.RIGHT) {
                state.leftHandItemState.clear();
                state.leftHandItemStack = net.minecraft.world.item.ItemStack.EMPTY;
                state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            } else {
                state.rightHandItemState.clear();
                state.rightHandItemStack = net.minecraft.world.item.ItemStack.EMPTY;
                state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
            }
        }

        if (enemy.getMainHandItem().getItem() instanceof GunItem
                && enemy.isWeaponGlitching()) {
            double renderTick = enemy.level().getGameTime() + partialTick;
            long phaseStart = enemy.getWeaponGlitchStartTick();
            long phaseEnd = enemy.getWeaponGlitchEndTick();
            float duration = Math.max(1.0F, phaseEnd - phaseStart);
            float elapsed = Mth.clamp((float) (renderTick - phaseStart), 0.0F, duration);

            state.isUsingItem = true;
            state.useItemHand = net.minecraft.world.InteractionHand.MAIN_HAND;
            state.walkAnimationSpeed = 0.0F;
            // Looking down plus the repeated native two-arm crossbow charge motion reads as the
            // soldier inspecting and working the failed gun without a custom animation packet.
            state.xRot = 27.0F + (float) Math.sin(renderTick * 0.75) * 5.0F;
            if (enemy.isWeaponMalfunctioning()) {
                float cycle = elapsed % 16.0F;
                float triangle = 1.0F - Math.abs(cycle / 8.0F - 1.0F);
                state.maxCrossbowChargeDuration = 8.0F;
                state.ticksUsingItem = triangle * 8.0F;
            } else {
                // After the server swaps equipment, smoothly ready the newly drawn sidearm before
                // the ranged goal is allowed to fire again.
                state.maxCrossbowChargeDuration = duration;
                state.ticksUsingItem = elapsed;
            }
        } else if (enemy.getMainHandItem().getItem() instanceof GunItem
                && enemy.isGunReloading()) {
            double renderTick = enemy.level().getGameTime() + partialTick;
            long reloadStart = enemy.getGunReloadStartTick();
            long reloadEnd = enemy.getGunReloadEndTick();
            float duration = reloadEnd - reloadStart;
            float elapsed = (float) (renderTick - reloadStart);
            // CROSSBOW_CHARGE is a native two-arm loading pose. Supplying the real gun reload
            // duration makes the support arm move through the animation while the weapon stays
            // attached to the primary hand's third-person item transform.
            state.isUsingItem = true;
            state.useItemHand = net.minecraft.world.InteractionHand.MAIN_HAND;
            state.maxCrossbowChargeDuration = duration;
            state.ticksUsingItem = Mth.clamp(elapsed, 0.0F, duration);
        }
    }
}
