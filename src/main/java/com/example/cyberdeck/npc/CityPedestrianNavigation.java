package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import dev.modernity.neoncity.NeonCityGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
        private static final int MAX_ROAD_CACHE = 131_072;
        private static final java.util.concurrent.ConcurrentHashMap<RoadKey, Boolean>
                ATLAS_ROAD_CACHE =
                new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.concurrent.ConcurrentLinkedQueue<RoadKey> ROAD_CACHE_ORDER =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final BlockPos.MutableBlockPos floorPosition = new BlockPos.MutableBlockPos();

        private record RoadKey(long seed, int x, int z) {}

        @Override
        public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z,
                                         Mob mob) {
            PathType base = super.getPathTypeOfMob(context, x, y, z, mob);
            if (mob.level() instanceof ServerLevel level
                    && CityWorlds.kind(level) == CityWorlds.Kind.NEON_MEGACITY
                    && mob.getPathfindingMalus(base) >= 0.0F
                    && isCautiousRoad(context, x, y, z)) {
                return PathType.DAMAGE_CAUTIOUS;
            }
            return base;
        }

        private boolean isCautiousRoad(PathfindingContext context, int x, int y, int z) {
            BlockState floor = context.getBlockState(floorPosition.set(x, y - 1, z));
            boolean generatedHighway = floor.is(Blocks.CONCRETE.pick(DyeColor.BLACK))
                    || floor.is(Blocks.CONCRETE.pick(DyeColor.YELLOW));
            if (generatedHighway) return true;

            RoadKey key = new RoadKey(NeonCityGenerator.layout().seed(), x, z);
            Boolean cached = ATLAS_ROAD_CACHE.get(key);
            if (cached != null) return cached;
            boolean result = NeonCityGenerator.isAtlasTrafficRoadAtFast(x, z);
            Boolean raced = ATLAS_ROAD_CACHE.putIfAbsent(key, result);
            if (raced != null) return raced;
            ROAD_CACHE_ORDER.add(key);
            while (ATLAS_ROAD_CACHE.size() > MAX_ROAD_CACHE) {
                RoadKey oldest = ROAD_CACHE_ORDER.poll();
                if (oldest == null) break;
                ATLAS_ROAD_CACHE.remove(oldest);
            }
            return result;
        }
    }
}
