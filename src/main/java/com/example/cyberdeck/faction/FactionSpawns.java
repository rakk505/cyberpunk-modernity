package com.example.cyberdeck.faction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

/**
 * Natural generation of faction squads. Periodically, and only in the overworld, this picks a random
 * player and attempts to spawn a single-faction squad (all members share one {@link Faction}) a
 * short distance away on solid ground. Grouping by faction is the whole point: a spawn produces a
 * coherent Arasaka / Militech / Kang Tao unit rather than a mix.
 *
 * <p>Members spawn <em>passive</em> — they only turn hostile once the player lingers close enough to
 * build detection (see {@link FactionEnemy}).
 */
public final class FactionSpawns {
    /** How often (ticks) to attempt a squad spawn per level. */
    private static final int SPAWN_INTERVAL = 600; // ~30s
    /** Chance per attempt that a squad actually spawns. */
    private static final float SPAWN_CHANCE = 0.35f;
    /** Ring around the player where squads appear (blocks). */
    private static final int MIN_DISTANCE = 24;
    private static final int MAX_DISTANCE = 44;
    /** Squad size range. */
    private static final int MIN_SQUAD = 2;
    private static final int MAX_SQUAD = 4;
    /** Cap on how many faction enemies may exist near a player before we stop spawning more. */
    private static final int NEARBY_CAP = 12;
    private static final double NEARBY_RADIUS = 64.0;

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        Level generic = event.getLevel();
        if (!(generic instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        if (level.getGameTime() % SPAWN_INTERVAL != 0) {
            return;
        }
        RandomSource rng = level.getRandom();
        if (rng.nextFloat() > SPAWN_CHANCE) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer player = players.get(rng.nextInt(players.size()));
        trySpawnSquad(level, player, rng);
    }

    private void trySpawnSquad(ServerLevel level, ServerPlayer player, RandomSource rng) {
        // Don't overcrowd the area.
        AABB near = player.getBoundingBox().inflate(NEARBY_RADIUS);
        if (level.getEntitiesOfClass(FactionEnemy.class, near).size() >= NEARBY_CAP) {
            return;
        }

        BlockPos anchor = findSpawnAnchor(level, player, rng);
        if (anchor == null) {
            return;
        }

        Faction faction = Faction.VALUES[rng.nextInt(Faction.VALUES.length)];
        int size = MIN_SQUAD + rng.nextInt(MAX_SQUAD - MIN_SQUAD + 1);

        for (int i = 0; i < size; i++) {
            // Scatter members a few blocks around the anchor so they read as a group.
            int ox = rng.nextInt(7) - 3;
            int oz = rng.nextInt(7) - 3;
            BlockPos memberPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    anchor.offset(ox, 0, oz));
            spawnMember(level, memberPos, faction, rng);
        }
    }

    /** Picks a ground position in the spawn ring around the player, or null if none is suitable. */
    private BlockPos findSpawnAnchor(ServerLevel level, ServerPlayer player, RandomSource rng) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int dist = MIN_DISTANCE + rng.nextInt(MAX_DISTANCE - MIN_DISTANCE + 1);
            int x = (int) (player.getX() + Math.cos(angle) * dist);
            int z = (int) (player.getZ() + Math.sin(angle) * dist);
            BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    new BlockPos(x, 0, z));
            if (!level.isLoaded(ground)) {
                continue;
            }
            return ground;
        }
        return null;
    }

    private void spawnMember(ServerLevel level, BlockPos pos, Faction faction, RandomSource rng) {
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(level, EntitySpawnReason.NATURAL);
        if (enemy == null) {
            return;
        }
        enemy.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                rng.nextFloat() * 360.0f, 0.0f);
        enemy.finalizeSpawn(level, level.getCurrentDifficultyAt(pos),
                EntitySpawnReason.NATURAL, null);
        enemy.setHome(pos);
        FactionSquads.equip(enemy, faction, rng);
        level.addFreshEntity(enemy);
    }
}
