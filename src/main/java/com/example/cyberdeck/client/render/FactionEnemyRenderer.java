package com.example.cyberdeck.client.render;

import com.example.cyberdeck.client.movement.TacticalPoseData;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.CyberpsychoEntity;
import com.example.cyberdeck.faction.TacticalManeuver;
import com.example.cyberdeck.movement.TacticalAction;
import com.example.cyberdeck.weapon.GunItem;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/**
 * Renders faction soldiers as player-shaped humanoids — their own entity, not a zombie. Tactical
 * locomotion and weapon recoil are layered over the normal humanoid pose on both the body and armor
 * models, while held items continue to use vanilla's stable hand transforms.
 */
public final class FactionEnemyRenderer
        extends HumanoidMobRenderer<FactionEnemy, FactionEnemyRenderState,
                TacticalFactionModel> {

    private static final Identifier[] TACTICAL_SKINS =
            new Identifier[FactionEnemy.TACTICAL_SKIN_COUNT];
    static {
        for (int index = 0; index < TACTICAL_SKINS.length; index++) {
            TACTICAL_SKINS[index] = Identifier.fromNamespaceAndPath(
                    "cyberdeck", "textures/entity/faction_enemy/tactical_" + index + ".png");
        }
    }
    private static final Identifier CYBERPSYCHO_SKIN = Identifier.fromNamespaceAndPath(
            "cyberdeck", "textures/entity/cyberpsycho.png");
    private static final Identifier FOG_MOTHER_SKIN = Identifier.fromNamespaceAndPath(
            "cyberdeck", "textures/entity/fog_mother.png");
    private static final Identifier TRAUMA_TEAM_SKIN = Identifier.fromNamespaceAndPath(
            "cyberdeck", "textures/entity/trauma_team.png");
    private static final Identifier EXCISION_SKIN = Identifier.fromNamespaceAndPath(
            "cyberdeck", "textures/entity/excision_agent.png");
    private static final float RECOIL_TICKS = 4.0F;

    public FactionEnemyRenderer(EntityRendererProvider.Context context) {
        super(context, new TacticalFactionModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
        this.addLayer(new HumanoidArmorLayer<>(this,
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(),
                        TacticalFactionModel::new),
                context.getEquipmentRenderer()));
    }

    @Override
    public FactionEnemyRenderState createRenderState() {
        return new FactionEnemyRenderState();
    }

    @Override
    public Identifier getTextureLocation(FactionEnemyRenderState state) {
        if (state.excision) {
            return EXCISION_SKIN;
        }
        if (state.traumaTeam) {
            return TRAUMA_TEAM_SKIN;
        }
        if (state.cyberpsycho) {
            return state.skinVariant == 1 ? FOG_MOTHER_SKIN : CYBERPSYCHO_SKIN;
        }
        return TACTICAL_SKINS[Math.floorMod(state.skinVariant, TACTICAL_SKINS.length)];
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
    public void extractRenderState(FactionEnemy enemy, FactionEnemyRenderState state,
                                   float partialTick) {
        super.extractRenderState(enemy, state, partialTick);
        state.cyberpsycho = enemy instanceof CyberpsychoEntity;
        state.traumaTeam = enemy.isTraumaTeam();
        state.excision = enemy.isExcision();
        state.skinVariant = enemy.getSkinVariant();

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

        double renderTick = enemy.level().getGameTime() + partialTick;
        if (enemy.getMainHandItem().getItem() instanceof GunItem
                && enemy.isWeaponGlitching()) {
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

        state.tacticalPose = extractTacticalPose(enemy, renderTick);
    }

    private static TacticalPoseData extractTacticalPose(FactionEnemy enemy, double renderTick) {
        TacticalManeuver maneuver = enemy.getTacticalManeuver();
        TacticalAction action = switch (maneuver) {
            // The sandevistan blink reuses the dash animation so the new maneuver id renders cleanly.
            case DASH_LEFT, DASH_RIGHT, SANDEVISTAN_DASH -> TacticalAction.DASH;
            case SLIDE_FORWARD -> TacticalAction.SLIDE;
            case NONE -> TacticalAction.NONE;
        };
        boolean interrupted = enemy.isWeaponGlitching() || enemy.isGunReloading();
        if (interrupted) {
            action = TacticalAction.NONE;
        }

        float actionProgress = 0.0F;
        if (action != TacticalAction.NONE) {
            long start = enemy.getTacticalManeuverStartTick();
            long end = enemy.getTacticalManeuverEndTick();
            float duration = Math.max(1.0F, end - start);
            actionProgress = Mth.clamp((float) (renderTick - start) / duration, 0.0F, 1.0F);
        }

        float directionX = enemy.getTacticalDirectionX();
        float directionZ = enemy.getTacticalDirectionZ();
        float yaw = enemy.getYRot() * Mth.DEG_TO_RAD;
        float forwardX = -Mth.sin(yaw);
        float forwardZ = Mth.cos(yaw);
        float rightX = -forwardZ;
        float rightZ = forwardX;
        float forwardAmount = directionX * forwardX + directionZ * forwardZ;
        float lateralAmount = directionX * rightX + directionZ * rightZ;

        float movementSpeed = (float) enemy.getDeltaMovement().horizontalDistance();
        boolean sprinting = !interrupted
                && (enemy.isSprinting() || (enemy.isTriggered() && movementSpeed > 0.17F));

        float reloadProgress = 0.0F;
        if (enemy.isGunReloading()) {
            long start = enemy.getGunReloadStartTick();
            long end = enemy.getGunReloadEndTick();
            reloadProgress = Mth.clamp(
                    (float) (renderTick - start) / Math.max(1.0F, end - start),
                    0.0F,
                    1.0F);
        }

        float recoil = 0.0F;
        long lastShot = enemy.getLastGunShotTick();
        if (lastShot >= 0L) {
            recoil = 1.0F - Mth.clamp(
                    (float) (renderTick - lastShot) / RECOIL_TICKS,
                    0.0F,
                    1.0F);
        }

        return new TacticalPoseData(
                action,
                actionProgress,
                forwardAmount,
                lateralAmount,
                sprinting,
                enemy.getMainHandItem().getItem() instanceof GunItem,
                reloadProgress,
                recoil,
                interrupted ? 0.0F : movementSpeed);
    }
}
