package dev.modernity.neoncity;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Deterministic hidden gig signals that players discover while traversing inhabited districts. */
public final class AmbientGigService {
    private static final String SEEN_SIGNALS = "cyberdeck_seen_ambient_gigs";
    private static final long SALT = 0x414D4249454E5447L;
    private static final int CELL_SIZE = 64;
    private static final int DISCOVERY_RADIUS = 16;
    private static final int MAX_SEEN = 64;
    private static final long ROTATION_TICKS = 20L * 60L * 20L;

    private AmbientGigService() {
    }

    public static void tick(ServerPlayer player) {
        if (player.isSpectator() || !player.isAlive()
                || MissionService.activeMission(player).isPresent()
                || !(player.level() instanceof ServerLevel level)
                || !NeonCityGenerator.isMegacityWorld(level)) {
            return;
        }
        int centerCellX = Math.floorDiv(player.getBlockX(), CELL_SIZE);
        int centerCellZ = Math.floorDiv(player.getBlockZ(), CELL_SIZE);
        long epoch = Math.floorDiv(level.getGameTime(), ROTATION_TICKS);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (tryDiscover(player, level, centerCellX + dx, centerCellZ + dz, epoch)) {
                    return;
                }
            }
        }
    }

    private static boolean tryDiscover(
            ServerPlayer player,
            ServerLevel level,
            int cellX,
            int cellZ,
            long epoch) {
        long hash = MegacityLayout.mix(
                level.getSeed() ^ NeonCityGenerator.layout().seed() ^ SALT ^ epoch,
                cellX, cellZ);
        int targetX = cellX * CELL_SIZE + 8 + Math.floorMod((int) hash, CELL_SIZE - 16);
        int targetZ = cellZ * CELL_SIZE + 8
                + Math.floorMod((int) Long.rotateLeft(hash, 31), CELL_SIZE - 16);
        double dx = player.getX() - targetX;
        double dz = player.getZ() - targetZ;
        if (dx * dx + dz * dz > DISCOVERY_RADIUS * DISCOVERY_RADIUS) return false;
        long signalId = MegacityLayout.mix(hash ^ SALT, (int) epoch, (int) (epoch >>> 32));
        if (seen(player, signalId)) return false;

        MegacityLayout.Location location = NeonCityGenerator.effectiveLocation(
                NeonCityGenerator.sample(targetX, targetZ));
        if (!location.insideCity()
                || location.zone() == MegacityLayout.Zone.WILDERNESS) return false;
        List<MissionCatalog.MissionDefinition> definitions = MissionCatalog.definitions().stream()
                .filter(definition -> definition.targetDistricts().contains(location.district()))
                .toList();
        if (definitions.isEmpty()) return false;
        MissionCatalog.MissionDefinition definition = definitions.get(
                Math.floorMod((int) Long.rotateRight(hash, 17), definitions.size()));
        int reward = definition.rewardMin() + Math.floorMod(
                (int) Long.rotateRight(hash, 43),
                definition.rewardMax() - definition.rewardMin() + 1);
        if (!MissionService.startAmbient(
                player, definition, location.district(), targetX, targetZ, reward)) {
            return false;
        }
        remember(player, signalId);
        return true;
    }

    private static boolean seen(ServerPlayer player, long signalId) {
        long[] seen = MissionPlayerData.persisted(player)
                .getLongArray(SEEN_SIGNALS).orElseGet(() -> new long[0]);
        for (long value : seen) if (value == signalId) return true;
        return false;
    }

    private static void remember(ServerPlayer player, long signalId) {
        CompoundTag data = MissionPlayerData.persisted(player);
        long[] previous = data.getLongArray(SEEN_SIGNALS).orElseGet(() -> new long[0]);
        int retained = Math.min(previous.length, MAX_SEEN - 1);
        long[] updated = new long[retained + 1];
        if (retained > 0) {
            System.arraycopy(previous, previous.length - retained, updated, 0, retained);
        }
        updated[retained] = signalId;
        data.putLongArray(SEEN_SIGNALS, updated);
    }
}
