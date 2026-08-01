package com.example.cyberdeck.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.example.cyberdeck.weapon.GunType;

final class DefenseGameTests {
    private DefenseGameTests() {
    }

    static void turretArc(GameTestHelper helper) {
        helper.assertTrue(KangTaoTurret.isWithinFiringArc(0.0F, 135.0F),
                "positive edge of the 270-degree arc must be targetable");
        helper.assertTrue(KangTaoTurret.isWithinFiringArc(0.0F, -135.0F),
                "negative edge of the 270-degree arc must be targetable");
        helper.assertFalse(KangTaoTurret.isWithinFiringArc(0.0F, 136.0F),
                "the rear 90-degree blind spot must not be targetable");
        helper.assertTrue(Math.abs(KangTaoTurret.clampAimYaw(0.0F, 180.0F)) == 135.0F,
                "aim directly behind the turret must stop at either rotation limit");
        helper.assertTrue(KangTaoTurret.FIRE_INTERVAL_TICKS
                        == GunType.ASSAULT_RIFLE.cooldownTicks(),
                "turret cadence must match the cyberdeck assault rifle");
        helper.succeed();
    }

    static void turretDestruction(GameTestHelper helper) {
        BlockPos protectedBlock = new BlockPos(2, 1, 1);
        helper.setBlock(protectedBlock, Blocks.STONE);
        KangTaoTurret turret = helper.spawn(
                DefenseContent.KANG_TAO_TURRET.get(), new BlockPos(1, 1, 1));
        turret.hurtServer(helper.getLevel(), turret.damageSources().generic(), 1000.0F);

        helper.assertTrue(turret.isDestroyed(),
                "lethal damage must synchronize the blackened wreck state");
        helper.assertBlockPresent(Blocks.STONE, protectedBlock);
        helper.succeed();
    }

    static void canisterExplosion(GameTestHelper helper) {
        BlockPos canister = new BlockPos(1, 1, 1);
        BlockPos protectedBlock = new BlockPos(2, 1, 1);
        helper.setBlock(canister, DefenseContent.EXPLOSIVE_CANISTER.get());
        helper.setBlock(protectedBlock, Blocks.STONE);

        boolean detonated = ExplosiveCanisterBlock.detonate(
                helper.getLevel(), helper.absolutePos(canister), null);
        helper.assertTrue(detonated, "a placed canister must detonate");
        helper.assertBlockNotPresent(DefenseContent.EXPLOSIVE_CANISTER.get(), canister);
        helper.assertBlockPresent(Blocks.STONE, protectedBlock);
        helper.succeed();
    }

    static void turretPlacement(GameTestHelper helper) {
        BlockPos floor = new BlockPos(1, 1, 1);
        BlockPos placement = floor.above();
        helper.setBlock(floor, Blocks.STONE);
        helper.assertTrue(KangTaoTurretItem.canPlaceAt(
                        helper.getLevel(), helper.absolutePos(placement)),
                "turret must fit above a clear solid floor");

        BlockPos absoluteFloor = helper.absolutePos(floor);
        ItemStack turretItem = new ItemStack(DefenseContent.KANG_TAO_TURRET_ITEM.get());
        UseOnContext context = new UseOnContext(
                helper.getLevel(),
                null,
                InteractionHand.MAIN_HAND,
                turretItem,
                new BlockHitResult(
                        Vec3.atCenterOf(absoluteFloor), Direction.UP, absoluteFloor, false));
        InteractionResult result = DefenseContent.KANG_TAO_TURRET_ITEM.get().useOn(context);
        helper.assertTrue(result.consumesAction(), "turret item must deploy on a valid floor");

        KangTaoTurret turret = helper.getLevel().getEntitiesOfClass(
                        KangTaoTurret.class,
                        DefenseContent.KANG_TAO_TURRET.get().getSpawnAABB(
                                absoluteFloor.getX() + 0.5,
                                absoluteFloor.getY() + 1.0,
                                absoluteFloor.getZ() + 0.5).inflate(0.25))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("deployed turret entity was not found"));
        float initialYaw = turret.getYRot();
        turret.aiStep();
        helper.assertTrue(turret.getYRot() != initialYaw,
                "an idle turret must visibly sweep its aim");
        turret.discard();

        helper.setBlock(placement, Blocks.STONE);
        helper.assertFalse(KangTaoTurretItem.canPlaceAt(
                        helper.getLevel(), helper.absolutePos(placement)),
                "turret placement must reject an occupied volume");
        helper.succeed();
    }
}
