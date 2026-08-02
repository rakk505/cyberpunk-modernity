package dev.modernity.neoncity;

import com.mojang.brigadier.CommandDispatcher;
import java.util.HashSet;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Focused command and marker-plan coverage for the building atlas inspector. */
final class BuildingInspectionGameTests {
    private BuildingInspectionGameTests() {
    }

    static void commandAndOverlayPlan(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        NeonCityCommand.register(dispatcher);
        var neonCity = dispatcher.getRoot().getChild("neoncity");
        var buildings = neonCity == null ? null : neonCity.getChild("buildings");
        helper.assertTrue(buildings != null
                        && buildings.getChild("summary") != null
                        && buildings.getChild("inspect") != null
                        && buildings.getChild("inspect").getChild("off") != null,
                "building inspection command tree is incomplete");

        BoundingBox sharedBounds = new BoundingBox(4, 64, 4, 24, 96, 24);
        MissionBuildingPlanner.BuildingLabel rejected = label(
                "rejected", sharedBounds, false, "rejected: no street-connected entrance");
        MissionBuildingPlanner.BuildingLabel ready = label(
                "ready", sharedBounds, true, "accepted");
        helper.assertTrue(BuildingInspectionService.nearestBuilding(
                        List.of(rejected, ready), new BlockPos(10, 70, 10))
                        .orElseThrow() == ready,
                "equidistant inspection did not prefer the mission-ready building");

        List<BuildingInspectionService.DebugPoint> points =
                BuildingInspectionService.debugPoints(rejected, null);
        HashSet<Integer> visualizedFloors = new HashSet<>();
        points.forEach(point -> visualizedFloors.add(point.position().getY()));
        helper.assertTrue(!points.isEmpty() && points.size() <= 128,
                "building inspection marker plan exceeded its packet budget");
        helper.assertTrue(visualizedFloors.containsAll(rejected.floorYs()),
                "building inspection marker plan omitted a labeled floor");
        helper.succeed();
    }

    private static MissionBuildingPlanner.BuildingLabel label(
            String id, BoundingBox bounds, boolean ready, String decision) {
        List<Integer> floors = List.of(65, 69, 73, 77, 81, 85, 89, 93);
        return new MissionBuildingPlanner.BuildingLabel(
                id,
                ready ? "site:" + id : "",
                bounds,
                floors,
                floors.stream().map(ignored -> 144).toList(),
                ready,
                decision);
    }
}
