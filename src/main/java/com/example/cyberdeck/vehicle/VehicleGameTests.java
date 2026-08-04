package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.mixin.NativeVehicleDrivetrainMixin;
import com.modernity.vehicle_mod.api.RemoteControllableVehicle;
import com.modernity.vehicle_mod.api.RemoteVehicleInput;
import com.modernity.vehicle_mod.api.VehicleApi;
import com.modernity.vehicle_mod.entity.FuelPoweredVehicleEntity;
import com.modernity.vehicle_mod.vehicle_mod;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** End-to-end contracts for native vehicle input, city traffic, takeover, and quickhack braking. */
public final class VehicleGameTests {
    private record RoadSite(BlockPos position, float yaw) {
    }

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Cyberdeck.MODID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            NATIVE_PLAYER_CONTROLS = register(
                    "vehicle_native_player_controls", VehicleGameTests::nativePlayerControls);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            LOCAL_CAR_AND_MOTORBIKE_TRAFFIC = register(
                    "vehicle_local_car_and_motorbike_traffic",
                    VehicleGameTests::localCarAndMotorbikeTraffic);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            PERSISTENCE_AND_TAKEOVER = register(
                    "vehicle_persistence_and_takeover",
                    VehicleGameTests::persistenceAndTakeover);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            NATIVE_BRAKE_QUICKHACK = register(
                    "vehicle_native_brake_quickhack", VehicleGameTests::nativeBrakeQuickhack);

    private VehicleGameTests() {
    }

    public static void bootstrap(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(VehicleGameTests::registerGameTests);
    }

    private static DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> register(
            String name, Consumer<GameTestHelper> test) {
        return TEST_FUNCTIONS.register(name, () -> test);
    }

    private static void nativePlayerControls(GameTestHelper helper) {
        prepareFlatRoad(helper, 2, 46, 2, 46);
        var vehicle = vehicle_mod.BMW_M3_GTR.get().create(
                helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(vehicle != null, "native control test could not create a car");
        if (vehicle == null) return;
        vehicle.setFuel(vehicle.getFuelCapacity());
        BlockPos spawn = helper.absolutePos(new BlockPos(20, 2, 12));
        vehicle.snapTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                0.0F, 0.0F);
        try {
            helper.assertTrue(helper.getLevel().addFreshEntity(vehicle),
                    "native control test could not add its car");
            Player driver = helper.makeMockPlayer(GameType.SURVIVAL);
            helper.assertTrue(vehicle instanceof NativeVehicleDrivetrainMixin,
                    "native control test could not access its drivetrain");
            NativeVehicleDrivetrainMixin drivetrain =
                    (NativeVehicleDrivetrainMixin) (Object) vehicle;

            // Run input samples in one server tick. Mock networking clears movement fields on its
            // own tick, so invoking the companion mod's Player mapping directly is deterministic.
            for (int sample = 0; sample < 22; sample++) {
                driver.zza = 1.0F;
                driver.xxa = 0.65F;
                driver.setJumping(false);
                drivetrain.cyberdeck$applyDriverInput(driver);
            }
            float forwardSpeed = vehicle.getDriveSpeed();
            helper.assertTrue(forwardSpeed > 0.05F
                            && Math.abs(vehicle.getSteeringInput()) > 0.01F,
                    "W/A input did not reach the native drivetrain and steering state");

            for (int sample = 0; sample < 46; sample++) {
                driver.zza = -1.0F;
                driver.xxa = -0.65F;
                driver.setJumping(false);
                drivetrain.cyberdeck$applyDriverInput(driver);
            }
            float reverseSpeed = vehicle.getDriveSpeed();
            helper.assertTrue(reverseSpeed < -0.01F,
                    "S input did not reverse the native drivetrain");

            for (int sample = 0; sample < 22; sample++) {
                driver.zza = 0.0F;
                driver.xxa = 0.0F;
                driver.setJumping(true);
                drivetrain.cyberdeck$applyDriverInput(driver);
            }
            helper.assertTrue(vehicle.isBraking()
                            && Math.abs(vehicle.getDriveSpeed())
                                    < Math.abs(reverseSpeed),
                    "space input did not brake the native drivetrain");
        } finally {
            vehicle.discard();
        }
        helper.succeed();
    }

    private static void localCarAndMotorbikeTraffic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(RoadsideVehicleSpawns.isMovingTrafficRoad(
                                NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD)
                        && !RoadsideVehicleSpawns.isMovingTrafficRoad(
                                NeonCityGenerator.RoadClass.LOCAL_STREET)
                        && RoadsideVehicleSpawns.supportsMovingTraffic(
                                NeonCityGenerator.RoadClass.LOCAL_STREET)
                        && RoadsideVehicleSpawns.supportsMovingTraffic(
                                NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD),
                "highway classification and combined local traffic policy diverged");
        RandomSource firstRolls = RandomSource.create(0xB1CE5L);
        RandomSource repeatedRolls = RandomSource.create(0xB1CE5L);
        int selectedMotorbikes = 0;
        for (int index = 0; index < 128; index++) {
            boolean first = RoadsideVehicleSpawns.selectsMotorbike(firstRolls, false);
            boolean repeated = RoadsideVehicleSpawns.selectsMotorbike(repeatedRolls, false);
            helper.assertTrue(first == repeated,
                    "seeded local vehicle selection was not deterministic");
            if (first) selectedMotorbikes++;
        }
        helper.assertTrue(selectedMotorbikes >= 32 && selectedMotorbikes <= 56
                        && !RoadsideVehicleSpawns.selectsMotorbike(
                                RandomSource.create(1L), true),
                "bounded local motorbike policy escaped its deterministic distribution");
        RoadSite street = findRoad(level, NeonCityGenerator.RoadClass.LOCAL_STREET, 0);
        RoadSite boulevard = findRoad(
                level,
                NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD,
                Math.max(1, NeonCityGenerator.layout().nodes().size() / 3));
        loadRoadChunks(level, street.position());
        loadRoadChunks(level, boulevard.position());
        prepareAbsoluteRoad(level, street.position());
        prepareAbsoluteRoad(level, boulevard.position());

        FuelPoweredVehicleEntity car = vehicle_mod.BMW_M3_GTR.get().create(
                level, EntitySpawnReason.COMMAND);
        FuelPoweredVehicleEntity motorbike = vehicle_mod.CYBERPUNK_MOTORBIKE.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(car != null && motorbike != null,
                "local traffic test could not create its native vehicles");
        if (car == null || motorbike == null) return;
        try {
            addVehicle(level, car, street.position(), street.yaw());
            addVehicle(level, motorbike, boulevard.position(), boulevard.yaw());
            RoadsideVehicleSpawns.markManagedVehicle(car);
            RoadsideVehicleSpawns.markManagedVehicle(motorbike);

            helper.assertTrue(CityTrafficService.assignDriver(
                            level, car, RandomSource.create(0xCA11L))
                            && CityTrafficService.assignDriver(
                                    level, motorbike, RandomSource.create(0xB1CEL)),
                    "local car and motorbike did not both accept traffic drivers");
            helper.assertTrue(CityTrafficService.plannedRouteStaysOnLocalRoad(car)
                            && CityTrafficService.plannedRouteStaysOnLocalRoad(motorbike),
                    "local traffic route escaped onto a highway or non-road surface");
            helper.assertTrue(CityTrafficService.plannedNodeCount(car) >= 2
                            && CityTrafficService.plannedNodeCount(motorbike) >= 2,
                    "local traffic did not compile directed lookahead nodes");
            helper.assertTrue(car instanceof NativeVehicleDrivetrainMixin
                            && motorbike instanceof NativeVehicleDrivetrainMixin,
                    "local traffic vehicles did not expose their native drivetrains");
            NativeVehicleDrivetrainMixin carDrivetrain =
                    (NativeVehicleDrivetrainMixin) (Object) car;
            NativeVehicleDrivetrainMixin motorbikeDrivetrain =
                    (NativeVehicleDrivetrainMixin) (Object) motorbike;
            Vec3 carStart = car.position();
            Vec3 motorbikeStart = motorbike.position();
            float motorbikeStartHealth = motorbike.getHealth();

            // These generated road chunks are sampled synchronously so the GameTest server cannot
            // unload them between samples. The real route/input path still runs on every sample.
            for (int sample = 0; sample < 12; sample++) {
                boolean carAccepted = CityTrafficService.tickManagedVehicleForTest(level, car);
                boolean motorbikeAccepted =
                        CityTrafficService.tickManagedVehicleForTest(level, motorbike);
                helper.assertTrue(carAccepted && motorbikeAccepted
                                && car.isRemoteControlActive()
                                && motorbike.isRemoteControlActive(),
                        "local managed traffic tick rejected: car=" + carAccepted
                                + " (alive=" + car.isAlive()
                                + ", driver=" + CityTrafficService.hasTrafficDriver(car)
                                + ", remote=" + car.isRemoteControlActive() + ")"
                                + ", motorbike=" + motorbikeAccepted
                                + " (alive=" + motorbike.isAlive()
                                + ", driver="
                                + CityTrafficService.hasTrafficDriver(motorbike)
                                + ", remote=" + motorbike.isRemoteControlActive()
                                + ", health=" + motorbike.getHealth() + "/"
                                + motorbikeStartHealth
                                + ", removal=" + motorbike.getRemovalReason()
                                + ", moved="
                                + horizontalDistanceSqr(
                                        motorbike.position(), motorbikeStart) + ")");
                // Absolute road chunks are available for graph sampling but GameTest does not
                // entity-tick them. Advance the exact native drivetrain and movement methods
                // without invoking Mob.tick's unrelated despawn/chunk lifecycle.
                carDrivetrain.cyberdeck$applyDrivetrainInput(0.65F, 0.0F, false);
                carDrivetrain.cyberdeck$applyRemoteMovement();
                motorbikeDrivetrain.cyberdeck$applyDrivetrainInput(0.65F, 0.0F, false);
                motorbikeDrivetrain.cyberdeck$applyRemoteMovement();
            }
            helper.assertTrue(Math.abs(car.getDriveSpeed()) > 0.01F
                            && horizontalDistanceSqr(car.position(), carStart) > 0.04,
                    "local native car compiled a route but did not move");
            helper.assertTrue(Math.abs(motorbike.getDriveSpeed()) > 0.01F
                            && horizontalDistanceSqr(motorbike.position(), motorbikeStart) > 0.04,
                    "local native motorbike compiled a route but did not move");
            helper.assertTrue(VehicleQuickhackService.brake(level, car)
                            && CityTrafficService.hasTrafficDriver(car)
                            && RoadsideVehicleSpawns.isManagedVehicle(car),
                    "transient CAR_BRAKE incorrectly relinquished ambient traffic ownership");
            helper.assertTrue(VehicleQuickhackService.isBrakeActive(car)
                            && CityTrafficService.hasTrafficDriver(car)
                            && RoadsideVehicleSpawns.isManagedVehicle(car),
                    "sustained CAR_BRAKE detached its ambient traffic driver");
        } finally {
            cleanupTraffic(car);
            cleanupTraffic(motorbike);
        }
        helper.succeed();
    }

    private static void persistenceAndTakeover(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RoadSite street = findRoad(
                level,
                NeonCityGenerator.RoadClass.LOCAL_STREET,
                Math.max(1, NeonCityGenerator.layout().nodes().size() / 2));
        loadRoadChunks(level, street.position());
        prepareAbsoluteRoad(level, street.position());
        FuelPoweredVehicleEntity vehicle = vehicle_mod.BMW_M3_GTR.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(vehicle != null, "persistence test could not create a native car");
        if (vehicle == null) return;
        Entity releasedDriver = null;
        try {
            addVehicle(level, vehicle, street.position(), street.yaw());
            RoadsideVehicleSpawns.markManagedVehicle(vehicle);
            helper.assertTrue(CityTrafficService.assignDriver(
                            level, vehicle, RandomSource.create(0x5A7EL)),
                    "persistence test could not assign its local traffic driver");
            releasedDriver = vehicle.getPassengers().getFirst();

            CityTrafficService.forgetInMemoryRoute(vehicle);
            helper.assertTrue(CityTrafficService.restorePersistedRoute(level, vehicle)
                            && CityTrafficService.hasTrafficDriver(vehicle)
                            && CityTrafficService.plannedRouteStaysOnLocalRoad(vehicle),
                    "persisted local traffic was not restored with its driver and local route");

            boolean takeoverAccepted = true;
            for (int sample = 0; sample < 3; sample++) {
                takeoverAccepted &= VehicleQuickhackService.applyRemoteInput(
                        level, vehicle, 0.65F, 0.2F, false);
            }
            helper.assertTrue(takeoverAccepted
                            && !CityTrafficService.hasTrafficDriver(vehicle)
                            && !RoadsideVehicleSpawns.isManagedVehicle(vehicle)
                            && vehicle.getPassengers().isEmpty()
                            && vehicle.isRemoteControlActive(),
                    "sustained takeover left ambient ownership or an NPC controller attached");
            VehicleQuickhackService.clearRemoteInput(vehicle);
        } finally {
            if (releasedDriver != null) releasedDriver.discard();
            cleanupTraffic(vehicle);
        }
        helper.succeed();
    }

    private static void nativeBrakeQuickhack(GameTestHelper helper) {
        prepareFlatRoad(helper, 2, 30, 2, 40);
        var vehicle = vehicle_mod.BMW_M3_GTR.get().create(
                helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(vehicle != null, "brake test could not create a native car");
        if (vehicle == null) return;
        vehicle.setFuel(vehicle.getFuelCapacity());
        vehicle.setPersistenceRequired();
        BlockPos spawn = helper.absolutePos(new BlockPos(15, 2, 8));
        vehicle.snapTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                0.0F, 0.0F);
        try {
            helper.assertTrue(helper.getLevel().addFreshEntity(vehicle),
                    "brake test could not add its native car");
            Player driver = helper.makeMockPlayer(GameType.SURVIVAL);
            RemoteControllableVehicle controller = VehicleApi.find(vehicle).orElse(null);
            helper.assertTrue(controller != null
                            && vehicle instanceof NativeVehicleDrivetrainMixin,
                    "native car did not expose VehicleApi and drivetrain control");
            if (controller == null) throw new IllegalStateException("VehicleApi lookup failed");
            NativeVehicleDrivetrainMixin drivetrain =
                    (NativeVehicleDrivetrainMixin) (Object) vehicle;

            Vec3 remoteStart = vehicle.position();
            int acceptedSamples = 0;
            int rejectedSamples = 0;
            for (int sample = 0; sample < 20; sample++) {
                if (controller.applyRemoteInput(
                        new RemoteVehicleInput(1.0F, 0.25F, false))) {
                    acceptedSamples++;
                } else {
                    rejectedSamples++;
                }
                // GameTest does not entity-tick this neighboring chunk. Apply the same accepted
                // values through the companion mod's native remote drivetrain and movement path.
                drivetrain.cyberdeck$applyDrivetrainInput(1.0F, 0.25F, false);
                drivetrain.cyberdeck$applyRemoteMovement();
            }
            float speedBeforeBrake = vehicle.getDriveSpeed();
            helper.assertTrue(acceptedSamples >= 10
                            && rejectedSamples == 0
                            && controller.isRemoteControlActive()
                            && speedBeforeBrake > 0.05F
                            && horizontalDistanceSqr(vehicle.position(), remoteStart) > 0.04,
                    "unoccupied VehicleApi input did not advance native drivetrain: accepted="
                            + acceptedSamples + ", rejected=" + rejectedSamples
                            + ", remoteActive=" + controller.isRemoteControlActive()
                            + ", fuel=" + vehicle.getFuel()
                            + ", speed=" + speedBeforeBrake);
            controller.clearRemoteInput();
            driver.zza = 1.0F;
            helper.assertTrue(VehicleQuickhackService.brake(helper.getLevel(), vehicle)
                            && VehicleQuickhackService.isBrakeActive(vehicle),
                    "CAR_BRAKE did not enter sustained native brake state");
            for (int sample = 0; sample < 34; sample++) {
                driver.zza = 1.0F;
                driver.xxa = 0.0F;
                driver.setJumping(false);
                drivetrain.cyberdeck$applyDriverInput(driver);
            }
            helper.assertTrue(driver.zza > 0.9F
                            && VehicleQuickhackService.isBrakeActive(vehicle)
                            && vehicle.isBraking()
                            && Math.abs(vehicle.getDriveSpeed()) < 0.01F
                            && Math.abs(vehicle.getDriveSpeed()) < speedBeforeBrake,
                    "CAR_BRAKE failed to suppress held W in the native drivetrain");
        } finally {
            vehicle.discard();
        }
        helper.succeed();
    }

    private static void addVehicle(
            ServerLevel level, FuelPoweredVehicleEntity vehicle, BlockPos position, float yaw) {
        vehicle.setFuel(vehicle.getFuelCapacity());
        // Production roadside spawns are persistent; match their lifecycle and save semantics.
        vehicle.setPersistenceRequired();
        vehicle.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                yaw, 0.0F);
        if (!level.addFreshEntity(vehicle)) {
            throw new IllegalStateException("could not add native vehicle to test level");
        }
    }

    private static void cleanupTraffic(FuelPoweredVehicleEntity vehicle) {
        CityTrafficService.retire(vehicle);
        vehicle.discard();
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    private static RoadSite findRoad(
            ServerLevel level,
            NeonCityGenerator.RoadClass roadClass,
            int firstNodeIndex) {
        List<MegacityLayout.Node> nodes = NeonCityGenerator.layout().nodes();
        for (int nodeIndex = Math.max(0, firstNodeIndex);
                nodeIndex < nodes.size();
                nodeIndex++) {
            MegacityLayout.Node node = nodes.get(nodeIndex);
            int limit = Math.max(64, Math.min(node.radiusX(), node.radiusZ()));
            for (int radius = 8; radius <= limit; radius += 2) {
                for (int offset = -radius; offset <= radius; offset += 2) {
                    BlockPos found = roadAt(node.x() + offset, node.z() - radius, roadClass);
                    RoadSite site = localGraphSite(level, found);
                    if (site != null) return site;
                    found = roadAt(node.x() + offset, node.z() + radius, roadClass);
                    site = localGraphSite(level, found);
                    if (site != null) return site;
                    found = roadAt(node.x() - radius, node.z() + offset, roadClass);
                    site = localGraphSite(level, found);
                    if (site != null) return site;
                    found = roadAt(node.x() + radius, node.z() + offset, roadClass);
                    site = localGraphSite(level, found);
                    if (site != null) return site;
                }
            }
        }
        throw new IllegalStateException("could not find generated road class " + roadClass);
    }

    private static BlockPos roadAt(
            int x, int z, NeonCityGenerator.RoadClass expected) {
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
        return sample.roadClass() == expected
                ? new BlockPos(x, sample.groundY() + 1, z) : null;
    }

    private static RoadSite localGraphSite(ServerLevel level, BlockPos position) {
        if (position == null) return null;
        loadRoadChunks(level, position);
        for (float yaw : new float[] {0.0F, 90.0F, 180.0F, 270.0F}) {
            CityTrafficGraph.LaneNode entry = CityTrafficGraph.enter(
                    level, Vec3.atCenterOf(position), yaw);
            if (entry != null && !CityTrafficGraph.successors(level, entry).isEmpty()) {
                return new RoadSite(position, yaw);
            }
        }
        return null;
    }

    private static void loadRoadChunks(ServerLevel level, BlockPos position) {
        int centerX = position.getX() >> 4;
        int centerZ = position.getZ() >> 4;
        for (int chunkX = centerX - 3; chunkX <= centerX + 3; chunkX++) {
            for (int chunkZ = centerZ - 3; chunkZ <= centerZ + 3; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static void prepareAbsoluteRoad(ServerLevel level, BlockPos center) {
        for (int x = center.getX() - 5; x <= center.getX() + 5; x++) {
            for (int z = center.getZ() - 5; z <= center.getZ() + 5; z++) {
                level.setBlock(new BlockPos(x, center.getY() - 1, z),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(new BlockPos(x, center.getY(), z),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(new BlockPos(x, center.getY() + 1, z),
                        Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void prepareFlatRoad(
            GameTestHelper helper, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
            }
        }
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "vehicle_pure"),
                new TestEnvironmentDefinition.AllOf(List.of()));
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("minecraft", "empty"),
                180,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                true,
                96);
        registerInstance(event, "vehicle_native_player_controls", NATIVE_PLAYER_CONTROLS, data);
        registerInstance(event, "vehicle_local_car_and_motorbike_traffic",
                LOCAL_CAR_AND_MOTORBIKE_TRAFFIC, data);
        registerInstance(event, "vehicle_persistence_and_takeover",
                PERSISTENCE_AND_TAKEOVER, data);
        registerInstance(event, "vehicle_native_brake_quickhack", NATIVE_BRAKE_QUICKHACK, data);
    }

    private static void registerInstance(
            RegisterGameTestsEvent event,
            String name,
            DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> function,
            TestData<Holder<TestEnvironmentDefinition<?>>> data) {
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, name),
                new FunctionGameTestInstance(function.getKey(), data));
    }
}
