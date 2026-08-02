package com.example.cyberdeck.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunFiring;
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
        helper.assertTrue(KangTaoTurret.WEAPON_PROFILE.ammo() == AmmoType.HEAVY,
                "turret rounds must use the heavy-ammo damage profile");
        helper.assertTrue(KangTaoTurret.RELOAD_TICKS
                        == KangTaoTurret.WEAPON_PROFILE.reloadTimeTicks(),
                "turret reload downtime must match its weapon profile");

        KangTaoTurret.BurstSchedule fiveSecondBurst =
                KangTaoTurret.burstSchedule(200L, 0L, 0);
        KangTaoTurret.BurstSchedule sixSecondBurst =
                KangTaoTurret.burstSchedule(200L, 0L, 1);
        helper.assertTrue(fiveSecondBurst.burstEndTick()
                        - fiveSecondBurst.burstStartTick() == 100L,
                "the short deterministic burst must sustain fire for five seconds");
        helper.assertTrue(sixSecondBurst.burstEndTick()
                        - sixSecondBurst.burstStartTick() == 120L,
                "the long deterministic burst must sustain fire for six seconds");
        helper.assertTrue(fiveSecondBurst.reloadEndTick()
                        - fiveSecondBurst.burstEndTick() == KangTaoTurret.RELOAD_TICKS,
                "each burst must be followed by a full reload phase");
        helper.assertTrue((fiveSecondBurst.burstEndTick()
                        - fiveSecondBurst.burstStartTick()) / KangTaoTurret.FIRE_INTERVAL_TICKS == 20L
                        && (sixSecondBurst.burstEndTick()
                        - sixSecondBurst.burstStartTick())
                        / KangTaoTurret.FIRE_INTERVAL_TICKS == 24L,
                "five- and six-second bursts must fire 20 and 24 assault-rifle rounds");

        Vec3 forward = KangTaoTurret.aimDirection(0.0F, 0.0F);
        Vec3 muzzle = KangTaoTurret.muzzleOffset(0.0F, 0.0F);
        Vec3 muzzleFromPivot = muzzle.subtract(0.0, KangTaoTurret.GUN_PIVOT_HEIGHT, 0.0);
        helper.assertTrue(forward.z > 0.999 && Math.abs(forward.x) < 0.001,
                "yaw zero must aim toward the model barrel's authored +Z direction");
        helper.assertTrue(muzzleFromPivot.dot(forward) > 0.0,
                "shots must originate in front of the gun pivot, not behind the turret");
        helper.assertTrue(Math.abs(muzzleFromPivot.length() - KangTaoTurret.MUZZLE_REACH) < 0.001,
                "the firing origin must reach the modeled muzzle");
        helper.assertTrue(KangTaoTurretItem.snappedPlacementYaw(0.0F) == 0.0F
                        && KangTaoTurretItem.snappedPlacementYaw(90.0F) == 90.0F,
                "placement must preserve the renderer and hitscan yaw convention");

        KangTaoTurret turret = helper.spawn(
                DefenseContent.KANG_TAO_TURRET.get(), new BlockPos(1, 1, 1));
        FactionEnemy alliedGuard = helper.spawn(
                FactionEntities.FACTION_ENEMY.get(), new BlockPos(2, 1, 1));
        alliedGuard.setFaction(Faction.KANG_TAO);
        FactionEnemy hostileGuard = helper.spawn(
                FactionEntities.FACTION_ENEMY.get(), new BlockPos(3, 1, 1));
        hostileGuard.setFaction(Faction.ARASAKA);
        CityNpc civilian = helper.spawn(
                CityNpcEntities.CITY_NPC.get(), new BlockPos(4, 1, 1));
        helper.assertFalse(GunFiring.canHitTarget(turret, alliedGuard),
                "turret hitscan must ignore allied Kang Tao guards");
        helper.assertFalse(GunFiring.canHitTarget(turret, civilian),
                "turret hitscan must ignore city civilians");
        helper.assertTrue(GunFiring.canHitTarget(turret, hostileGuard),
                "turret hitscan must still accept hostile faction targets");
        turret.getPersistentData().putString(
                "cyberdeck_mission_instance", "turret-fire-contract");
        hostileGuard.getPersistentData().putString(
                "cyberdeck_mission_instance", "turret-fire-contract");
        helper.assertFalse(GunFiring.canHitTarget(turret, hostileGuard),
                "turret hitscan must ignore any living actor from its own mission instance");
        hostileGuard.getPersistentData().putString(
                "cyberdeck_mission_instance", "different-contract");
        helper.assertTrue(GunFiring.canHitTarget(turret, hostileGuard),
                "mission filtering must not protect hostile actors from another contract");
        turret.discard();
        alliedGuard.discard();
        hostileGuard.discard();
        civilian.discard();
        helper.succeed();
    }

    static void turretDestruction(GameTestHelper helper) {
        BlockPos protectedBlock = new BlockPos(1, 1, 3);
        helper.setBlock(protectedBlock, Blocks.STONE);
        var victim = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 1));
        float healthBeforeExplosion = victim.getHealth();
        KangTaoTurret turret = helper.spawn(
                DefenseContent.KANG_TAO_TURRET.get(), new BlockPos(1, 1, 1));
        turret.hurtServer(helper.getLevel(), turret.damageSources().generic(), 1000.0F);

        helper.assertTrue(turret.isDestroyed(),
                "lethal damage must synchronize the blackened wreck state");
        helper.assertTrue(victim.getHealth() < healthBeforeExplosion,
                "turret destruction must deal real explosion damage to nearby entities");
        helper.assertBlockPresent(Blocks.STONE, protectedBlock);
        victim.discard();
        helper.succeed();
    }

    static void canisterExplosion(GameTestHelper helper) {
        BlockPos canister = new BlockPos(1, 1, 1);
        BlockPos protectedBlock = new BlockPos(1, 1, 3);
        helper.setBlock(canister, DefenseContent.EXPLOSIVE_CANISTER.get());
        helper.setBlock(protectedBlock, Blocks.STONE);
        var victim = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 1));
        float healthBeforeExplosion = victim.getHealth();

        boolean detonated = ExplosiveCanisterBlock.detonate(
                helper.getLevel(), helper.absolutePos(canister), null);
        helper.assertTrue(detonated, "a placed canister must detonate");
        helper.assertBlockNotPresent(DefenseContent.EXPLOSIVE_CANISTER.get(), canister);
        helper.assertTrue(victim.getHealth() < healthBeforeExplosion,
                "canister detonation must deal real explosion damage to nearby entities");
        helper.assertBlockPresent(Blocks.STONE, protectedBlock);
        victim.discard();
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
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos absolutePlacement = helper.absolutePos(placement);
        player.snapTo(
                absolutePlacement.getX() + 0.5,
                absolutePlacement.getY(),
                absolutePlacement.getZ() + 0.5,
                0.0F,
                0.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, turretItem);
        UseOnContext context = new UseOnContext(
                player,
                InteractionHand.MAIN_HAND,
                new BlockHitResult(
                        Vec3.atCenterOf(absoluteFloor), Direction.UP, absoluteFloor, false));
        InteractionResult result = DefenseContent.KANG_TAO_TURRET_ITEM.get().useOn(context);
        helper.assertTrue(result.consumesAction(),
                "turret item must deploy even when the player stands close to the target");
        player.discard();

        KangTaoTurret turret = helper.getLevel().getEntitiesOfClass(
                        KangTaoTurret.class,
                        DefenseContent.KANG_TAO_TURRET.get().getSpawnAABB(
                                absoluteFloor.getX() + 0.5,
                                absoluteFloor.getY() + 1.0,
                                absoluteFloor.getZ() + 0.5).inflate(0.25))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("deployed turret entity was not found"));
        helper.assertTrue(turret.getBaseYaw() == 0.0F,
                "yaw-zero placement must point the visible barrel and hitscan toward +Z");
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
