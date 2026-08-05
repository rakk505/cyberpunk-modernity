package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import dev.modernity.neoncity.NeonCityGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/** Civilian navigation that strongly prefers neighborhood paths over megacity highways. */
final class CityPedestrianNavigation extends GroundPathNavigation {
    CityPedestrianNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new CityPedestrianNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    private static final class CityPedestrianNodeEvaluator extends WalkNodeEvaluator {
        @Override
        public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z,
                                         Mob mob) {
            PathType base = super.getPathTypeOfMob(context, x, y, z, mob);
            if (mob.level() instanceof ServerLevel level
                    && CityWorlds.kind(level) == CityWorlds.Kind.NEON_MEGACITY
                    && mob.getPathfindingMalus(base) >= 0.0F
                    && (NeonCityGenerator.isHighwayRoadClass(
                                    NeonCityGenerator.roadAt(x, z))
                            || NeonCityGenerator.isAtlasTrafficRoadAt(x, z))) {
                return PathType.DAMAGE_CAUTIOUS;
            }
            return base;
        }
    }
}
