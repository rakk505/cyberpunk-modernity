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
        /**
         * A* visits the same (x,z) columns thousands of times per tick across every navigating NPC,
         * and the road lookup below is expensive (it audits Arnis tile placements). Without a cache
         * a crowd of NPCs pathfinding at once (e.g. fleeing gunfire) can blow a single server tick
         * past the watchdog limit. The road layout is deterministic for a fixed-seed megacity, so a
         * bounded column cache is safe and collapses the cost to one lookup per column.
         */
        private static final int MAX_ROAD_CACHE = 16_384;
        private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> ROAD_CACHE =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z,
                                         Mob mob) {
            PathType base = super.getPathTypeOfMob(context, x, y, z, mob);
            if (mob.level() instanceof ServerLevel level
                    && CityWorlds.kind(level) == CityWorlds.Kind.NEON_MEGACITY
                    && mob.getPathfindingMalus(base) >= 0.0F
                    && isCautiousRoad(x, z)) {
                return PathType.DAMAGE_CAUTIOUS;
            }
            return base;
        }

        private static boolean isCautiousRoad(int x, int z) {
            long key = ((long) x & 0xFFFFFFFFL) << 32 | ((long) z & 0xFFFFFFFFL);
            Boolean cached = ROAD_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            if (ROAD_CACHE.size() > MAX_ROAD_CACHE) {
                ROAD_CACHE.clear();
            }
            boolean result = NeonCityGenerator.isHighwayRoadClass(NeonCityGenerator.roadAt(x, z))
                    || NeonCityGenerator.isAtlasTrafficRoadAt(x, z);
            ROAD_CACHE.put(key, result);
            return result;
        }
    }
}
