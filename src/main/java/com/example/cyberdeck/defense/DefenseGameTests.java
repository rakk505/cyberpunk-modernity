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
import com.example.cyberdeck.CyberdeckState;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunFiring;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.skill.DeviceQuickhack;
import com.example.cyberdeck.skill.QuickhackTargets;
import com.example.cyberdeck.skill.QuickhackUploads;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.vehicle.VehicleQuickhackService;

import java.util.List;

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

    static void turretQuickhacks(GameTestHelper helper) {
        CyberwareData tierOne = new CyberwareData();
        tierOne.install(Cyberware.byId("arasaka_mk_1_5_t1"));
        CyberwareData tierFive = new CyberwareData();
        tierFive.install(Cyberware.byId("arasaka_mk_1_5_t5_plus_plus"));
        helper.assertTrue(KangTaoTurret.cyberdeckSecurityLevel(tierOne) == 1
                        && KangTaoTurret.cyberdeckSecurityLevel(tierFive) == 5
                        && KangTaoTurret.cyberdeckSecurityLevel(new CyberwareData()) == 0,
                "cyberdeck grades must map to device security levels one through five");

        KangTaoTurret turret = helper.spawn(
                DefenseContent.KANG_TAO_TURRET.get(), new BlockPos(1, 1, 1));
        helper.assertTrue(turret.getSecurityLevel() >= KangTaoTurret.MIN_SECURITY_LEVEL
                        && turret.getSecurityLevel() <= KangTaoTurret.MAX_SECURITY_LEVEL,
                "new turrets must spawn with a security level from one through five");
        turret.setSecurityLevel(5);
        turret.setBaseYaw(0.0F);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.snapTo(
                turret.getX() + 2.0, turret.getY(), turret.getZ(),
                0.0F, 0.0F);
        CyberwareData installed = CyberwareAttachments.get(player);
        installed.install(Cyberware.byId("arasaka_mk_1_5_t4_plus"));
        helper.assertFalse(turret.canAcceptQuickhack(player),
                "a tier-four cyberdeck must not bypass a level-five turret");
        helper.assertFalse(turret.tryBeginRemoteControl(player),
                "under-tier remote-control attempts must be rejected server-side");

        installed.remove(BodySlot.OPERATING_SYSTEM);
        installed.install(Cyberware.byId("arasaka_mk_1_5_t5"));
        helper.assertTrue(turret.tryBeginRemoteControl(player),
                "a matching cyberdeck tier must authorize remote control");
        helper.assertTrue(turret.isRemotelyControlled()
                        && player.getCamera() == player,
                "server control must leave the player's body and camera entity in place");

        FactionEnemy hostile = helper.spawn(
                FactionEntities.FACTION_ENEMY.get(), new BlockPos(1, 1, 6));
        hostile.setFaction(Faction.ARASAKA);
        turret.getPersistentData().putString(
                "cyberdeck_mission_instance", "controlled-turret-contract");
        hostile.getPersistentData().putString(
                "cyberdeck_mission_instance", "controlled-turret-contract");
        helper.assertTrue(GunFiring.canHitTarget(turret, hostile),
                "remote control must override same-mission autonomous friendly fire");
        float healthBeforeShot = hostile.getHealth();
        helper.assertTrue(turret.fireControlled(player, 0.0F, 0.0F),
                "the remote controller must be able to fire the turret");
        helper.assertTrue(hostile.getHealth() < healthBeforeShot,
                "a controlled shot must resolve from the turret muzzle");
        helper.assertTrue(hostile.isTriggered() && hostile.getTarget() == turret,
                "a shot target must immediately become hostile to the controlled turret");
        hostile.setTarget(null);
        turret.aiStep();
        helper.assertTrue(hostile.getTarget() == turret,
                "a remotely provoked target must remain hostile until the turret is destroyed");

        helper.assertTrue(turret.tryDeactivate(player) && turret.isDeactivated(),
                "deactivate must disable an authorized turret");
        helper.assertFalse(turret.isRemotelyControlled(),
                "deactivation must end the remote-control session");
        turret.setDeactivated(false);
        helper.assertTrue(turret.tryDetonate(player) && turret.isDestroyed(),
                "detonate must destroy an authorized turret through its explosion death path");

        hostile.discard();
        player.discard();
        helper.succeed();
    }

    static void vehicleQuickhacks(GameTestHelper helper) {
        BlockPos protectedBlock = new BlockPos(1, 1, 3);
        BlockPos nearbyCanister = new BlockPos(1, 2, 2);
        helper.setBlock(protectedBlock, Blocks.STONE);
        helper.setBlock(nearbyCanister, DefenseContent.EXPLOSIVE_CANISTER.get());
        var victim = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 1));
        var car = helper.spawn(EntityTypes.MINECART, new BlockPos(1, 1, 1));
        VehicleQuickhackService.markCompatibleCar(car);

        helper.assertTrue(VehicleQuickhackService.isCar(car),
                "a compatibility-marked vehicle must expose car quickhacks");
        helper.assertTrue(DeviceQuickhack.actionsFor(car).equals(List.of(
                        DeviceQuickhack.CAR_TAKE_CONTROL,
                        DeviceQuickhack.CAR_SPEED,
                        DeviceQuickhack.CAR_BRAKE,
                        DeviceQuickhack.CAR_DETONATE))
                        && QuickhackTargets.actionCount(car) == 4,
                "cars must expose exactly the four ordered vehicle actions");
        helper.assertTrue(VehicleQuickhackService.SPEED_DURATION_TICKS == 120
                        && VehicleQuickhackService.speed(helper.getLevel(), car)
                        && VehicleQuickhackService.isSpeeding(helper.getLevel(), car)
                        && car.getDeltaMovement().horizontalDistance() > 1.0,
                "speed must immediately force forward motion for six seconds");
        helper.assertTrue(VehicleQuickhackService.brake(helper.getLevel(), car)
                        && !VehicleQuickhackService.isSpeeding(helper.getLevel(), car)
                        && car.getDeltaMovement().equals(Vec3.ZERO),
                "brake must cancel forced speed and stop the vehicle immediately");

        float healthBeforeExplosion = victim.getHealth();
        helper.assertTrue(VehicleQuickhackService.detonate(
                        helper.getLevel(), car, null) && car.isRemoved(),
                "detonate must remove the vehicle");
        helper.assertTrue(victim.getHealth() < healthBeforeExplosion,
                "vehicle detonation must apply canister-strength entity damage");
        helper.assertBlockNotPresent(
                DefenseContent.EXPLOSIVE_CANISTER.get(), nearbyCanister);
        helper.assertBlockPresent(Blocks.STONE, protectedBlock);
        victim.discard();
        helper.succeed();
    }

    static void turretQueueRevalidation(GameTestHelper helper) {
        KangTaoTurret turret = helper.spawn(
                DefenseContent.KANG_TAO_TURRET.get(), new BlockPos(2, 1, 4));
        turret.setSecurityLevel(5);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.snapTo(turret.getX(), turret.getY(), turret.getZ() - 3.0,
                0.0F, 0.0F);
        CyberwareData installed = CyberwareAttachments.get(player);
        installed.install(Cyberware.byId("arasaka_mk_1_5_t5"));
        RamAttachments.set(player, RamAttachments.MAX_RAM);
        CyberdeckState.setActive(player, true);

        helper.assertTrue(QuickhackTargets.isUnderScannerReticle(
                        player, turret, helper.getLevel()),
                "the server scanner ray must authorize only the aimed device");
        int ramBefore = RamAttachments.get(player);
        QuickhackUploads.EnqueueResult queued = QuickhackUploads.enqueueDevice(
                player, DeviceQuickhack.TURRET_DEACTIVATE, turret, helper.getLevel());
        helper.assertTrue(queued.accepted(),
                "a matching deck tier must reserve a turret upload");

        installed.remove(BodySlot.OPERATING_SYSTEM);
        installed.install(Cyberware.byId("arasaka_mk_1_5_t4_plus"));
        QuickhackUploads.tick(player, helper.getLevel());
        helper.assertFalse(QuickhackUploads.hasQueue(player),
                "a deck downgrade must cancel an under-tier pending turret upload");
        helper.assertTrue(RamAttachments.get(player) == ramBefore && !turret.isDeactivated(),
                "a canceled under-tier upload must not spend RAM or alter the turret");

        CyberdeckState.deactivate(player);
        turret.discard();
        player.discard();
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

    static void canisterChainReaction(GameTestHelper helper) {
        BlockPos root = new BlockPos(1, 1, 1);
        BlockPos link = new BlockPos(4, 1, 1);
        BlockPos transitive = new BlockPos(4, 4, 1);
        BlockPos isolated = new BlockPos(1, 4, 4);
        BlockPos protectedBlock = new BlockPos(2, 1, 3);
        helper.setBlock(root, DefenseContent.EXPLOSIVE_CANISTER.get());
        helper.setBlock(link, DefenseContent.EXPLOSIVE_CANISTER.get());
        helper.setBlock(transitive, DefenseContent.EXPLOSIVE_CANISTER.get());
        helper.setBlock(isolated, DefenseContent.EXPLOSIVE_CANISTER.get());
        helper.setBlock(protectedBlock, Blocks.STONE);
        var victim = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 1, 1));
        float healthBeforeExplosion = victim.getHealth();

        int detonated = ExplosiveCanisterBlock.detonateChain(
                helper.getLevel(), helper.absolutePos(root), null);
        helper.assertTrue(detonated == 3,
                "a canister chain must detonate each connected canister exactly once");
        helper.assertBlockNotPresent(DefenseContent.EXPLOSIVE_CANISTER.get(), root);
        helper.assertBlockNotPresent(DefenseContent.EXPLOSIVE_CANISTER.get(), link);
        helper.assertBlockNotPresent(DefenseContent.EXPLOSIVE_CANISTER.get(), transitive);
        helper.assertBlockPresent(DefenseContent.EXPLOSIVE_CANISTER.get(), isolated);
        helper.assertBlockPresent(Blocks.STONE, protectedBlock);
        helper.assertTrue(victim.getHealth() < healthBeforeExplosion,
                "a chained canister detonation must deal real explosion damage");
        helper.assertFalse(ExplosiveCanisterBlock.detonate(
                        helper.getLevel(), helper.absolutePos(root), null),
                "a consumed chain root must not detonate twice");
        victim.discard();
        helper.succeed();
    }

    static void turretPlacement(GameTestHelper helper) {
        helper.runAtTickTime(1, () -> turretPlacementAfterStructureLoad(helper));
    }

    private static void turretPlacementAfterStructureLoad(GameTestHelper helper) {
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
