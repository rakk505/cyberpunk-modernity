package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Maintains a modest pedestrian population around players, only in explicit city presets. */
public final class CityNpcSpawns {
    // Retry quickly as players enter a freshly generated city; the population cap keeps this cheap.
    private static final int SPAWN_INTERVAL = 40;
    private static final int TARGET_NEARBY = 12;
    private static final int NEARBY_CAP = 16;
    private static final int SPAWN_BATCH = 3;
    private static final double NEARBY_RADIUS = 64.0;
    private static final int[][] OFFSETS = {{0, 0}, {2, 0}, {-2, 1}, {1, -2}, {-1, -2}};

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % SPAWN_INTERVAL != 0
                || !CityWorlds.isCity(level)) {
            return;
        }

        // Nearby players share one population budget instead of independently duplicating crowds.
        Set<Long> processedPopulationCells = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            int cellX = Math.floorDiv(player.getBlockX(), 96);
            int cellZ = Math.floorDiv(player.getBlockZ(), 96);
            long cell = ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
            if (processedPopulationCells.add(cell)) {
                replenish(level, player);
            }
        }
    }

    private void replenish(ServerLevel level, ServerPlayer player) {
        AABB area = player.getBoundingBox().inflate(NEARBY_RADIUS);
        List<CityNpc> nearby = level.getEntitiesOfClass(CityNpc.class, area, CityNpc::isAlive);
        if (nearby.size() >= TARGET_NEARBY) {
            return;
        }

        int wanted = Math.min(SPAWN_BATCH,
                Math.min(TARGET_NEARBY - nearby.size(), NEARBY_CAP - nearby.size()));
        if (wanted <= 0) {
            return;
        }
        RandomSource random = level.getRandom();
        BlockPos anchor = CityWorlds.findStreetNear(
                level, player.blockPosition(), 16, 42, 32, random);
        if (anchor == null) {
            return;
        }

        int rotation = random.nextInt(4);
        int spawned = 0;
        for (int[] offset : OFFSETS) {
            if (spawned >= wanted) {
                break;
            }
            int ox = rotateX(offset[0], offset[1], rotation);
            int oz = rotateZ(offset[0], offset[1], rotation);
            BlockPos position = anchor.offset(ox, 0, oz);
            if (!CityWorlds.isWalkableStreet(level, position)) {
                continue;
            }
            CityNpc npc = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.NATURAL);
            if (npc == null) {
                continue;
            }
            npc.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                    random.nextFloat() * 360.0f, 0.0f);
            if (!level.noCollision(npc)) {
                npc.discard();
                continue;
            }
            npc.finalizeSpawn(level, level.getCurrentDifficultyAt(position),
                    EntitySpawnReason.NATURAL, null);
            npc.setSkinVariant(skinVariant(position, spawned));
            npc.setHomeTo(anchor, 48);
            if (level.addFreshEntity(npc)) {
                spawned++;
            }
        }
    }

    public static int skinVariant(BlockPos position, int memberIndex) {
        long value = position.asLong() ^ (long) memberIndex * 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return Math.floorMod((int) (value ^ (value >>> 31)), CityNpc.SKIN_COUNT);
    }

    private static int rotateX(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    private static int rotateZ(int x, int z, int rotation) {
        return switch (rotation & 3) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }
}
