package dev.modernity.neoncity;

import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/** Focused regression tests registered by {@link ProjectMoonCityModule}. */
final class UrbanSystemsGameTests {
    private UrbanSystemsGameTests() {
    }

    static void districtAtmosphere(GameTestHelper helper) {
        helper.assertTrue(
                District.Y_CORP.isSharedWinter()
                        && District.AE_DISTRICT.isSharedWinter()
                        && District.YI_DISTRICT.isSharedWinter(),
                "the northern edge districts do not share Y Corp winter weather");
        helper.assertTrue(!District.D_CORP.isSharedWinter()
                        && !District.T_CORP.isSharedWinter(),
                "non-winter atmosphere districts were added to the snow cycle");

        DistrictAtmosphere.FogProfile dense = DistrictAtmosphere.fogProfile(District.D_CORP);
        DistrictAtmosphere.FogProfile smog = DistrictAtmosphere.fogProfile(District.T_CORP);
        helper.assertTrue(dense == DistrictAtmosphere.FogProfile.DENSE
                        && smog == DistrictAtmosphere.FogProfile.SMOG
                        && DistrictAtmosphere.fogProfile(District.A_CORP)
                                == DistrictAtmosphere.FogProfile.NONE,
                "district fog profiles are not isolated to D and T Corp");
        helper.assertTrue(dense.farPlane() == 26.0F
                        && smog.farPlane() == 60.0F
                        && smog.red() > smog.blue()
                        && smog.green() > smog.blue()
                        && dense.fadeIn() > 0.0F
                        && dense.fadeOut() > dense.fadeIn(),
                "D Corp fog is not denser and independently faded from T Corp smog");
        DistrictAtmosphere.PollutionProfile pollution =
                DistrictAtmosphere.pollutionProfile(District.T_CORP);
        helper.assertTrue(pollution != null
                        && pollution.particleCount() == 32
                        && pollution.height() == 7.0
                        && pollution.horizontalSpread() == 9.0
                        && pollution.verticalSpread() == 5.0
                        && pollution.speed() == 0.004
                        && DistrictAtmosphere.pollutionProfile(District.D_CORP) == null,
                "T Corp pollution particles are not dense, local, and district-specific");
        helper.assertTrue(DistrictAtmosphere.winterWeather(0)
                                == DistrictAtmosphere.WinterWeather.GENTLE
                        && DistrictAtmosphere.winterWeather(
                                DistrictAtmosphere.GENTLE_SNOW_TICKS)
                                == DistrictAtmosphere.WinterWeather.SNOWSTORM
                        && DistrictAtmosphere.winterWeather(
                                DistrictAtmosphere.WINTER_CYCLE_TICKS)
                                == DistrictAtmosphere.WinterWeather.GENTLE,
                "shared winter weather no longer cycles deterministically");
        helper.succeed();
    }

    static void urbanSupplyCrates(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        ChunkPos selected = findSelectedChunk(layout.seed());
        NeonCityGenerator.UrbanSample[][] samples = syntheticSamples(
                layout, NeonCityGenerator.CITY_GROUND_Y,
                NeonCityGenerator.RoadClass.HIGHWAY_BUFFER);

        List<UrbanCrateGeneration.CratePlan> first = UrbanCrateGeneration.plans(
                layout.seed(), selected, samples);
        List<UrbanCrateGeneration.CratePlan> second = UrbanCrateGeneration.plans(
                layout.seed(), selected, samples);
        helper.assertTrue(first.equals(second)
                        && first.size() == 24
                        && new HashSet<>(first).size() == first.size(),
                "urban crate candidates are not deterministic, unique, and bounded");
        Set<BlockPos> positions = new HashSet<>();
        for (UrbanCrateGeneration.CratePlan plan : first) {
            positions.add(plan.position());
            helper.assertTrue(ChunkPos.containing(plan.position()).equals(selected)
                            && plan.position().getX() >= selected.getMinBlockX() + 2
                            && plan.position().getX() <= selected.getMinBlockX() + 13
                            && plan.position().getZ() >= selected.getMinBlockZ() + 2
                            && plan.position().getZ() <= selected.getMinBlockZ() + 13,
                    "urban crate candidate escaped its chunk-safe placement margin");
        }
        helper.assertTrue(positions.size() == first.size(),
                "two urban crate candidates occupy the same block");

        BlockPos floor = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos cratePosition = floor.above();
        for (NeonCityGenerator.RoadClass roadClass : List.of(
                NeonCityGenerator.RoadClass.SERVICE_ALLEY,
                NeonCityGenerator.RoadClass.HIGHWAY_BUFFER,
                NeonCityGenerator.RoadClass.EXTRACTION_SITE,
                NeonCityGenerator.RoadClass.CONTAINER_PORT)) {
            prepareCrateSite(helper, floor);
            UrbanCrateGeneration.CratePlan placementPlan = new UrbanCrateGeneration.CratePlan(
                    cratePosition,
                    Direction.SOUTH,
                    UrbanCrateGeneration.LootTier.COMMON,
                    0x4352415445544553L ^ roadClass.ordinal(),
                    roadClass);
            helper.assertTrue(!UrbanCrateGeneration.placePlannedCrate(
                            helper.getLevel(), placementPlan),
                    roadClass + " supply crate spawned in an open area");
            helper.getLevel().setBlock(
                    cratePosition.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            helper.getLevel().setBlock(
                    cratePosition.above(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(!UrbanCrateGeneration.placePlannedCrate(
                            helper.getLevel(), placementPlan),
                    roadClass + " supply crate accepted a non-air block overhead");
            helper.getLevel().setBlock(
                    cratePosition.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(UrbanCrateGeneration.placePlannedCrate(
                            helper.getLevel(), placementPlan)
                            && helper.getLevel().getBlockState(cratePosition)
                                    .getValue(BarrelBlock.FACING) == Direction.SOUTH
                            && helper.getLevel().isEmptyBlock(cratePosition.above())
                            && hasAdjacentWall(helper, cratePosition),
                    roadClass + " wall-backed supply crate was not placed facing open space");
            helper.getLevel().setBlock(
                    cratePosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }

        prepareCrateSite(helper, floor);
        helper.getLevel().setBlock(
                cratePosition.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        UrbanCrateGeneration.CratePlan livePlan = new UrbanCrateGeneration.CratePlan(
                cratePosition,
                Direction.SOUTH,
                UrbanCrateGeneration.LootTier.COMMON,
                0x4352415445544553L,
                NeonCityGenerator.RoadClass.HIGHWAY_BUFFER);
        helper.assertTrue(UrbanCrateGeneration.placePlannedCrate(helper.getLevel(), livePlan),
                "a wall-backed urban supply crate refused placement");
        helper.assertTrue(helper.getLevel().getBlockEntity(cratePosition)
                        instanceof RandomizableContainerBlockEntity,
                "urban supply crate did not create a randomizable container");
        RandomizableContainerBlockEntity container =
                (RandomizableContainerBlockEntity) helper.getLevel().getBlockEntity(cratePosition);
        helper.assertTrue(livePlan.tier().lootTable().equals(container.getLootTable())
                        && container.getLootTableSeed() == livePlan.lootSeed(),
                "urban supply crate lost its deterministic loot table or seed");
        helper.assertTrue(!container.isEmpty(),
                "urban supply crate loot table is missing or generated no supplies");
        helper.assertTrue(containsAmmo(container),
                "urban supply crate did not contain ammunition");
        helper.assertTrue(UrbanCrateGeneration.isManagedCrate(
                                helper.getLevel(), cratePosition)
                        && !UrbanCrateGeneration.placePlannedCrate(
                                helper.getLevel(), livePlan),
                "an opened urban supply crate lost its persistent generation marker");

        for (UrbanCrateGeneration.LootTier tier : UrbanCrateGeneration.LootTier.values()) {
            for (int seed = 0; seed < 32; seed++) {
                helper.getLevel().setBlock(
                        cratePosition, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                UrbanCrateGeneration.CratePlan ammoPlan = new UrbanCrateGeneration.CratePlan(
                        cratePosition,
                        Direction.SOUTH,
                        tier,
                        MegacityLayout.mix(0x414D4D4F43524154L, tier.ordinal(), seed),
                        NeonCityGenerator.RoadClass.HIGHWAY_BUFFER);
                helper.assertTrue(UrbanCrateGeneration.placePlannedCrate(
                                helper.getLevel(), ammoPlan)
                                && helper.getLevel().getBlockEntity(cratePosition)
                                        instanceof RandomizableContainerBlockEntity ammoContainer
                                && !ammoContainer.isEmpty()
                                && containsAmmo(ammoContainer),
                        tier + " supply crate seed " + seed + " did not guarantee ammunition");
            }
        }
        helper.succeed();
    }

    private static void prepareCrateSite(GameTestHelper helper, BlockPos floor) {
        BlockPos position = floor.above();
        helper.getLevel().setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(position.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.getLevel().setBlock(
                    position.relative(direction), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static boolean hasAdjacentWall(GameTestHelper helper, BlockPos position) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = position.relative(direction);
            if (helper.getLevel().getBlockState(neighbour).isFaceSturdy(
                    helper.getLevel(), neighbour, direction.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAmmo(RandomizableContainerBlockEntity container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            for (AmmoType type : AmmoType.values()) {
                if (container.getItem(slot).is(AmmoItems.item(type).get())) return true;
            }
        }
        return false;
    }

    private static ChunkPos findSelectedChunk(long layoutSeed) {
        for (int chunkZ = -32; chunkZ <= 32; chunkZ++) {
            for (int chunkX = -32; chunkX <= 32; chunkX++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                if (UrbanCrateGeneration.isSelectedChunk(layoutSeed, chunk)) {
                    return chunk;
                }
            }
        }
        throw new IllegalStateException("no deterministic urban crate chunk in search window");
    }

    private static NeonCityGenerator.UrbanSample[][] syntheticSamples(
            MegacityLayout layout,
            int groundY,
            NeonCityGenerator.RoadClass roadClass) {
        MegacityLayout.Node primary = layout.node(District.A_CORP);
        MegacityLayout.Node secondary = layout.node(District.B_CORP);
        MegacityLayout.Location location = new MegacityLayout.Location(
                primary,
                secondary,
                MegacityLayout.Zone.BACKSTREETS,
                0.7,
                0.3,
                null,
                Double.MAX_VALUE);
        NeonCityGenerator.UrbanSample[][] samples = new NeonCityGenerator.UrbanSample[18][18];
        for (int z = 0; z < samples.length; z++) {
            for (int x = 0; x < samples[z].length; x++) {
                samples[z][x] = new NeonCityGenerator.UrbanSample(
                        location,
                        District.A_CORP,
                        MegacityLayout.Zone.BACKSTREETS,
                        roadClass,
                        groundY,
                        0,
                        District.A_CORP.parcelSize(),
                        false,
                        0,
                        0,
                        x,
                        z,
                        MegacityLayout.mix(layout.seed(), x, z));
            }
        }
        return samples;
    }
}
