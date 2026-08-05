package com.example.cyberdeck.faction;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.city.CityWorlds;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Reliable street-patrol generation. Each occupied spatial cell gets one attempt per epoch;
 * successful patrols contain exactly three or five soldiers in a bounded formation.
 */
public final class FactionSpawns {
    public static final int SPAWN_INTERVAL = 600;
    public static final int SMALL_PATROL_SIZE = 3;
    public static final int LARGE_PATROL_SIZE = 5;
    public static final int NEARBY_CAP = 16;
    public static final int LOADED_WORLD_CAP = 40;
    public static final int MAX_REACTIVE_AMBIENT_POPULATION =
            LOADED_WORLD_CAP + Math.floorDiv(LOADED_WORLD_CAP, SMALL_PATROL_SIZE)
                    * FactionSquads.REINFORCEMENT_COUNT;
    public static final int MIN_SPAWN_DISTANCE = 26;
    public static final int MAX_SPAWN_DISTANCE = 46;
    public static final int POPULATION_CELL_SIZE = 128;
    public static final double NEARBY_RADIUS = 72.0;
    public static final double PATROL_SEPARATION = 24.0;
    /** Hard budget for expensive city-column anchor checks in one server tick. */
    public static final int MAX_ANCHOR_CHECKS_PER_TICK = 4;
    /** Soft aggregate budget; one in-progress anchor check is always allowed to finish. */
    private static final long SEARCH_TIME_BUDGET_NANOS = 2_000_000L;
    private static final int RANDOM_ANCHOR_ATTEMPTS = 48;
    private static final int PERIMETER_ANCHOR_ATTEMPTS = 128;
    public static final int MAX_ANCHOR_ATTEMPTS =
            RANDOM_ANCHOR_ATTEMPTS + PERIMETER_ANCHOR_ATTEMPTS;
    private static final int POPULATION_REFRESH_TICKS = 20;
    private static final double MIN_PLAYER_DISTANCE = 24.0;
    private static final double MAX_OBSERVER_VISIBILITY_DISTANCE = 160.0;
    private static final double FORWARD_VIEW_DOT = 0.5;
    private static final long CLUSTER_SALT = 0x434C55535445524CL;
    private static final int[][] BASE_FORMATION = {
            {0, 0}, {2, 1}, {-2, 1}, {1, -2}, {-1, -2}, {3, 0}
    };
    private final Map<ServerLevel, SearchQueue> pendingSearches = new WeakHashMap<>();

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD
                || CityWorlds.kind(level) != CityWorlds.Kind.NEON_MEGACITY) {
            return;
        }

        if (level.getGameTime() % SPAWN_INTERVAL == 0) {
            enqueuePopulationSearches(level);
        }
        processPopulationSearches(level);
    }

    private void enqueuePopulationSearches(ServerLevel level) {
        long epoch = level.getGameTime() / SPAWN_INTERVAL;
        Map<Long, ServerPlayer> populationCells = new TreeMap<>();
        for (ServerPlayer player : level.players()) {
            if (!canDrivePatrolSpawns(player)) {
                continue;
            }
            int cellX = Math.floorDiv(player.getBlockX(), POPULATION_CELL_SIZE);
            int cellZ = Math.floorDiv(player.getBlockZ(), POPULATION_CELL_SIZE);
            long cellKey = pack(cellX, cellZ);
            populationCells.merge(cellKey, player, FactionSpawns::stableRepresentative);
        }
        if (populationCells.isEmpty()) {
            return;
        }

        SearchQueue queue = pendingSearches.computeIfAbsent(level, ignored -> new SearchQueue());
        for (Map.Entry<Long, ServerPlayer> entry : populationCells.entrySet()) {
            if (!queue.activeCells.add(entry.getKey())) {
                continue;
            }
            ServerPlayer player = entry.getValue();
            int cellX = Math.floorDiv(player.getBlockX(), POPULATION_CELL_SIZE);
            int cellZ = Math.floorDiv(player.getBlockZ(), POPULATION_CELL_SIZE);
            RandomSource random = RandomSource.create(clusterSeed(
                    level.getSeed(), epoch, cellX, cellZ));
            queue.jobs.addLast(SpawnSearch.create(entry.getKey(), player, random));
        }
    }

    private void processPopulationSearches(ServerLevel level) {
        SearchQueue queue = pendingSearches.get(level);
        if (queue == null || queue.jobs.isEmpty()) {
            pendingSearches.remove(level);
            return;
        }

        int loadedPatrols = queue.loadedPatrolCount(level);
        int checks = 0;
        long deadline = System.nanoTime() + SEARCH_TIME_BUDGET_NANOS;
        while (checks < MAX_ANCHOR_CHECKS_PER_TICK && !queue.jobs.isEmpty()) {
            if (checks > 0 && System.nanoTime() >= deadline) {
                break;
            }
            SpawnSearch search = queue.jobs.removeFirst();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(search.playerId);
            if (!canDrivePatrolSpawns(player) || player.level() != level
                    || pack(Math.floorDiv(player.getBlockX(), POPULATION_CELL_SIZE),
                            Math.floorDiv(player.getBlockZ(), POPULATION_CELL_SIZE))
                            != search.cellKey) {
                queue.finish(search);
                continue;
            }

            int requested = plannedPatrolSize(
                    search.largeRoll, LOADED_WORLD_CAP - loadedPatrols);
            SpawnCandidate candidate = search.nextCandidate();
            if (requested == 0 || candidate == null) {
                queue.finish(search);
                continue;
            }

            checks++;
            SpawnPlan plan = planAt(level, candidate.x, candidate.z, candidate.preferredY,
                    candidate.firstRotation, requested, search.samples);
            if (plan == null) {
                if (search.hasCandidates()) {
                    queue.jobs.addLast(search);
                } else {
                    queue.finish(search);
                }
                continue;
            }

            int nearby = level.getEntitiesOfClass(FactionEnemy.class,
                    player.getBoundingBox().inflate(NEARBY_RADIUS), FactionEnemy::isAlive).size();
            int capacity = Math.min(NEARBY_CAP - nearby, LOADED_WORLD_CAP - loadedPatrols);
            int actualSize = plannedPatrolSize(
                    search.largeRoll, Math.min(capacity, plan.positions().size()));
            if (actualSize == 0) {
                queue.finish(search);
                continue;
            }
            if (actualSize != plan.positions().size()) {
                plan = new SpawnPlan(plan.anchor(), plan.positions().subList(0, actualSize));
            }
            int spawned = spawnPlannedCluster(
                    level, player, search.random, plan, search.samples);
            loadedPatrols += spawned;
            queue.recordLoadedPatrolCount(level.getGameTime(), loadedPatrols);
            queue.finish(search);
        }

        if (queue.jobs.isEmpty()) {
            pendingSearches.remove(level);
        }
    }

    private static int spawnPlannedCluster(
            ServerLevel level, ServerPlayer player, RandomSource random, SpawnPlan plan,
            Map<Long, NeonCityGenerator.UrbanSample> samples) {
        int requested = plan.positions().size();

        District spawnDistrict = sampledDistrict(samples, plan.anchor());
        boolean rCorp = isRCorpPatrol(spawnDistrict, random.nextFloat());
        Faction faction = Faction.VALUES[random.nextInt(Faction.VALUES.length)];
        java.util.UUID patrolId = new java.util.UUID(random.nextLong(), random.nextLong());
        List<Integer> skinVariants = FactionSquads.uniqueSkinVariants(random, requested);
        List<EnemyCombatRole> rCorpRoles = rCorp
                ? FactionSquads.rCorpRolePlan(requested) : List.of();
        List<FactionEnemy> members = new ArrayList<>(requested);
        for (int index = 0; index < plan.positions().size(); index++) {
            FactionEnemy enemy = createMember(
                    level, plan.positions().get(index), plan.anchor(), faction,
                    rCorp ? rCorpRoles.get(index) : EnemyCombatRole.STANDARD,
                    skinVariants.get(index), sampledDistrict(
                            samples, plan.positions().get(index)), random);
            if (enemy == null) {
                for (FactionEnemy member : members) {
                    member.discard();
                }
                return 0;
            }
            enemy.setAlertGroupId(patrolId);
            members.add(enemy);
        }
        for (FactionEnemy member : members) {
            if (!level.addFreshEntity(member)) {
                // Roll back members already inserted and discard those not yet inserted so a
                // failed insertion can never leave a partial pseudo-squad behind.
                for (FactionEnemy squadMember : members) {
                    squadMember.discard();
                }
                return 0;
            }
        }
        Cyberdeck.LOGGER.info("Spawned {}-member {} patrol in {} near {}",
                members.size(), rCorp ? "R Corp paramilitary" : "corporate",
                members.getFirst().getDistrict().code(), player.getScoreboardName());
        return members.size();
    }

    private static District sampledDistrict(
            Map<Long, NeonCityGenerator.UrbanSample> samples, BlockPos position) {
        NeonCityGenerator.UrbanSample sample = samples.get(
                pack(position.getX(), position.getZ()));
        return sample == null
                ? NeonCityGenerator.sample(position.getX(), position.getZ()).district()
                : sample.district();
    }

    private static SpawnPlan planAt(
            ServerLevel level, int x, int z, int preferredY, int firstRotation, int size,
            Map<Long, NeonCityGenerator.UrbanSample> samples) {
        BlockPos anchor = CityWorlds.resolveStreetFeet(level, x, z, preferredY);
        if (anchor == null || !isPublicPatrolPosition(level, anchor, samples)
                || hasNearbyPatrol(level, anchor)) {
            return null;
        }
        Map<Long, Optional<BlockPos>> resolvedPositions = new HashMap<>();
        resolvedPositions.put(pack(anchor.getX(), anchor.getZ()), Optional.of(anchor));
        for (int rotationOffset = 0; rotationOffset < 4; rotationOffset++) {
            List<BlockPos> positions = resolveFormation(
                    level, anchor, firstRotation + rotationOffset, size,
                    samples, resolvedPositions);
            if (positions.size() == size && isAcceptableToPlayers(level, positions)) {
                return new SpawnPlan(anchor, positions);
            }
        }
        if (size == LARGE_PATROL_SIZE) {
            for (int rotationOffset = 0; rotationOffset < 4; rotationOffset++) {
                List<BlockPos> positions = resolveFormation(
                        level, anchor, firstRotation + rotationOffset, SMALL_PATROL_SIZE,
                        samples, resolvedPositions);
                if (positions.size() == SMALL_PATROL_SIZE
                        && isAcceptableToPlayers(level, positions)) {
                    return new SpawnPlan(anchor, positions);
                }
            }
        }
        return null;
    }

    private static boolean isAcceptableToPlayers(ServerLevel level, List<BlockPos> positions) {
        double minimumDistanceSquared = MIN_PLAYER_DISTANCE * MIN_PLAYER_DISTANCE;
        double visibilityDistanceSquared =
                MAX_OBSERVER_VISIBILITY_DISTANCE * MAX_OBSERVER_VISIBILITY_DISTANCE;
        for (ServerPlayer observer : level.players()) {
            if (!canDrivePatrolSpawns(observer)) continue;
            Vec3 eye = observer.getEyePosition();
            Vec3 look = observer.getLookAngle();
            for (BlockPos position : positions) {
                Vec3 target = Vec3.atBottomCenterOf(position).add(0.0, 0.9, 0.0);
                Vec3 offset = target.subtract(eye);
                double distanceSquared = offset.lengthSqr();
                if (distanceSquared < minimumDistanceSquared) {
                    return false;
                }
                if (distanceSquared <= visibilityDistanceSquared
                        && offset.normalize().dot(look) >= FORWARD_VIEW_DOT
                        && level.clip(new ClipContext(
                                eye, target, ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE, observer)).getType()
                                != HitResult.Type.BLOCK) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasNearbyPatrol(ServerLevel level, BlockPos anchor) {
        return !level.getEntitiesOfClass(
                FactionEnemy.class,
                new AABB(anchor).inflate(PATROL_SEPARATION),
                enemy -> enemy.isAlive() && enemy.isAmbientPatrol()).isEmpty();
    }

    private static List<BlockPos> resolveFormation(
            ServerLevel level, BlockPos anchor, int rotation, int size,
            Map<Long, NeonCityGenerator.UrbanSample> samples,
            Map<Long, Optional<BlockPos>> resolvedPositions) {
        List<BlockPos> positions = new ArrayList<>(size);
        for (BlockPos offset : formationOffsets(rotation, size)) {
            BlockPos horizontal = anchor.offset(offset.getX(), 0, offset.getZ());
            long columnKey = pack(horizontal.getX(), horizontal.getZ());
            Optional<BlockPos> resolved = resolvedPositions.get(columnKey);
            if (resolved == null) {
                BlockPos position;
                if (CityWorlds.isCity(level)) {
                    position = CityWorlds.resolveStreetFeet(
                            level, horizontal.getX(), horizontal.getZ(), anchor.getY());
                    if (position == null || position.getY() != anchor.getY()
                            || !isPublicPatrolPosition(level, position, samples)) {
                        resolved = Optional.empty();
                    } else {
                        resolved = Optional.of(position);
                    }
                } else {
                    position = level.getHeightmapPos(
                            Heightmap.Types.MOTION_BLOCKING, horizontal);
                    resolved = isSafeFeet(level, position)
                            ? Optional.of(position) : Optional.empty();
                }
                resolvedPositions.put(columnKey, resolved);
            }
            if (resolved.isEmpty()) {
                return List.of();
            }
            positions.add(resolved.get());
        }
        return positions;
    }

    private static FactionEnemy createMember(ServerLevel level, BlockPos position, BlockPos home,
                                             Faction faction, EnemyCombatRole role, int skinVariant,
                                             District district, RandomSource random) {
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.NATURAL);
        if (enemy == null) {
            return null;
        }
        enemy.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                random.nextFloat() * 360.0f, 0.0f);
        if (!level.noCollision(enemy)) {
            enemy.discard();
            return null;
        }
        enemy.finalizeSpawn(level, level.getCurrentDifficultyAt(position),
                EntitySpawnReason.NATURAL, null);
        enemy.setHome(home);
        enemy.setAmbientPatrol(true);
        if (role == EnemyCombatRole.STANDARD) {
            FactionSquads.equip(enemy, faction, random, skinVariant, district);
        } else {
            FactionSquads.equipRCorp(enemy, role, random, skinVariant, district);
        }
        return enemy;
    }

    /** Regional R Corp weighting: east/south dominate, center remains possible, elsewhere is rare. */
    public static float rCorpPatrolChance(District district) {
        if (district == null) {
            return 0.0F;
        }
        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        return rCorpPatrolChance(node.x(), node.z());
    }

    public static float rCorpPatrolChance(int districtX, int districtZ) {
        long radiusSquared = (long) districtX * districtX + (long) districtZ * districtZ;
        if (radiusSquared <= 1_600L * 1_600L) {
            return 0.18F;
        }
        boolean east = districtX >= 500;
        boolean south = districtZ >= 500;
        if (east && south) {
            return 0.72F;
        }
        if (east || south) {
            return 0.55F;
        }
        return 0.03F;
    }

    public static boolean isRCorpPatrol(District district, float roll) {
        return roll >= 0.0F && roll < rCorpPatrolChance(district);
    }

    /** Selects one of the only two authored squad sizes without ever clipping to a partial squad. */
    public static int plannedPatrolSize(boolean largeRoll, int capacity) {
        if (capacity < SMALL_PATROL_SIZE) {
            return 0;
        }
        if (capacity < LARGE_PATROL_SIZE) {
            return SMALL_PATROL_SIZE;
        }
        return largeRoll ? LARGE_PATROL_SIZE : SMALL_PATROL_SIZE;
    }

    /** Creative players still populate the city; only dead and spectator players are ignored. */
    public static boolean canDrivePatrolSpawns(ServerPlayer player) {
        return player != null && player.isAlive() && !player.isSpectator();
    }

    public static boolean isPublicPatrolArea(NeonCityGenerator.UrbanSample sample) {
        if (sample == null || sample.district() == null
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.OUTSKIRTS
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.BORDER_WALLED
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.BORDER_FOREST
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.BORDER_CLIFF
                || sample.zone() == dev.modernity.neoncity.MegacityLayout.Zone.WILDERNESS) {
            return false;
        }
        return isPublicPatrolRoadClass(sample.roadClass());
    }

    /** Level-aware public-space check used for spawn anchors and patrol destinations. */
    public static boolean isPublicPatrolPosition(ServerLevel level, BlockPos position) {
        NeonCityGenerator.UrbanSample sample =
                NeonCityGenerator.sample(position.getX(), position.getZ());
        return isPublicPatrolPosition(level, position, sample);
    }

    /** Reuses a caller's city sample instead of recursively classifying the same coordinate. */
    public static boolean isPublicPatrolPosition(
            ServerLevel level, BlockPos position, NeonCityGenerator.UrbanSample sample) {
        if (sample == null || position.getY() != sample.groundY() + 1
                || !isPublicPatrolArea(sample)) {
            return false;
        }
        return sample.roadClass() != NeonCityGenerator.RoadClass.NONE
                || NeonCityGenerator.isCivilianPedestrianArea(
                        level, position.getX(), position.getZ(), sample);
    }

    private static boolean isPublicPatrolPosition(
            ServerLevel level, BlockPos position,
            Map<Long, NeonCityGenerator.UrbanSample> samples) {
        NeonCityGenerator.UrbanSample sample = samples.computeIfAbsent(
                pack(position.getX(), position.getZ()),
                ignored -> NeonCityGenerator.sample(position.getX(), position.getZ()));
        return isPublicPatrolPosition(level, position, sample);
    }

    public static boolean isPublicPatrolRoadClass(NeonCityGenerator.RoadClass roadClass) {
        return switch (roadClass) {
            case NONE, CENTRAL_PLAZA, DISTRICT_BOULEVARD, LOCAL_STREET, SERVICE_ALLEY,
                    PARK, HARBOR, CONTAINER_PORT -> true;
            default -> false;
        };
    }

    private static boolean isSafeFeet(ServerLevel level, BlockPos position) {
        return level.isLoaded(position)
                && level.getWorldBorder().isWithinBounds(position)
                && level.getBlockState(position.below()).blocksMotion()
                && level.isEmptyBlock(position)
                && level.isEmptyBlock(position.above());
    }

    /** Pure, unique formation offsets used by tests and runtime. */
    public static List<BlockPos> formationOffsets(int rotation, int size) {
        int count = Math.min(Math.max(0, size), BASE_FORMATION.length);
        List<BlockPos> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int x = BASE_FORMATION[index][0];
            int z = BASE_FORMATION[index][1];
            int rotatedX = switch (rotation & 3) {
                case 1 -> -z;
                case 2 -> -x;
                case 3 -> z;
                default -> x;
            };
            int rotatedZ = switch (rotation & 3) {
                case 1 -> x;
                case 2 -> -z;
                case 3 -> -x;
                default -> z;
            };
            result.add(new BlockPos(rotatedX, 0, rotatedZ));
        }
        return List.copyOf(result);
    }

    public static long clusterSeed(long worldSeed, long epoch, int cellX, int cellZ) {
        long value = worldSeed ^ CLUSTER_SALT ^ epoch * 0x9E3779B97F4A7C15L
                ^ (long) cellX * 0xC2B2AE3D27D4EB4FL
                ^ (long) cellZ * 0x165667B19E3779F9L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static ServerPlayer stableRepresentative(ServerPlayer first, ServerPlayer second) {
        return first.getUUID().compareTo(second.getUUID()) <= 0 ? first : second;
    }

    private static final class SearchQueue {
        private final ArrayDeque<SpawnSearch> jobs = new ArrayDeque<>();
        private final HashSet<Long> activeCells = new HashSet<>();
        private long populationCountTick = Long.MIN_VALUE;
        private int loadedPatrolCount;

        private int loadedPatrolCount(ServerLevel level) {
            long gameTime = level.getGameTime();
            if (populationCountTick == Long.MIN_VALUE || gameTime < populationCountTick
                    || gameTime - populationCountTick >= POPULATION_REFRESH_TICKS) {
                loadedPatrolCount = 0;
                for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                    if (entity instanceof FactionEnemy enemy
                            && enemy.isAlive() && enemy.isAmbientPatrol()) {
                        loadedPatrolCount++;
                    }
                }
                populationCountTick = gameTime;
            }
            return loadedPatrolCount;
        }

        private void recordLoadedPatrolCount(long gameTime, int count) {
            populationCountTick = gameTime;
            loadedPatrolCount = count;
        }

        private void finish(SpawnSearch search) {
            activeCells.remove(search.cellKey);
        }
    }

    private static final class SpawnSearch {
        private final long cellKey;
        private final UUID playerId;
        private final RandomSource random;
        private final boolean largeRoll;
        private final List<SpawnCandidate> candidates;
        private final Map<Long, NeonCityGenerator.UrbanSample> samples = new HashMap<>();
        private int nextCandidate;

        private SpawnSearch(long cellKey, UUID playerId, RandomSource random,
                            boolean largeRoll, List<SpawnCandidate> candidates) {
            this.cellKey = cellKey;
            this.playerId = playerId;
            this.random = random;
            this.largeRoll = largeRoll;
            this.candidates = candidates;
        }

        private static SpawnSearch create(
                long cellKey, ServerPlayer player, RandomSource random) {
            boolean largeRoll = random.nextBoolean();
            int originX = player.getBlockX();
            int originY = player.getBlockY();
            int originZ = player.getBlockZ();
            List<SpawnCandidate> candidates = new ArrayList<>(MAX_ANCHOR_ATTEMPTS);
            for (int attempt = 0; attempt < RANDOM_ANCHOR_ATTEMPTS; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                int distance = MIN_SPAWN_DISTANCE
                        + random.nextInt(MAX_SPAWN_DISTANCE - MIN_SPAWN_DISTANCE + 1);
                candidates.add(new SpawnCandidate(
                        originX + (int) Math.round(Math.cos(angle) * distance),
                        originZ + (int) Math.round(Math.sin(angle) * distance),
                        originY, random.nextInt(4)));
            }

            // Keep the reliable deterministic perimeter fallback, but consume it incrementally.
            int rotation = random.nextInt(4);
            int perimeterAttempts = 0;
            perimeterSearch:
            for (int radius = MIN_SPAWN_DISTANCE;
                    radius <= MAX_SPAWN_DISTANCE; radius += 2) {
                for (int offset = -radius; offset <= radius; offset += 2) {
                    candidates.add(new SpawnCandidate(
                            originX + offset, originZ - radius, originY, rotation));
                    if (++perimeterAttempts >= PERIMETER_ANCHOR_ATTEMPTS) {
                        break perimeterSearch;
                    }
                    candidates.add(new SpawnCandidate(
                            originX + offset, originZ + radius, originY, rotation));
                    if (++perimeterAttempts >= PERIMETER_ANCHOR_ATTEMPTS) {
                        break perimeterSearch;
                    }
                    candidates.add(new SpawnCandidate(
                            originX - radius, originZ + offset, originY, rotation));
                    if (++perimeterAttempts >= PERIMETER_ANCHOR_ATTEMPTS) {
                        break perimeterSearch;
                    }
                    candidates.add(new SpawnCandidate(
                            originX + radius, originZ + offset, originY, rotation));
                    if (++perimeterAttempts >= PERIMETER_ANCHOR_ATTEMPTS) {
                        break perimeterSearch;
                    }
                }
            }
            return new SpawnSearch(cellKey, player.getUUID(), random, largeRoll,
                    List.copyOf(candidates));
        }

        private SpawnCandidate nextCandidate() {
            return hasCandidates() ? candidates.get(nextCandidate++) : null;
        }

        private boolean hasCandidates() {
            return nextCandidate < candidates.size();
        }
    }

    private record SpawnCandidate(int x, int z, int preferredY, int firstRotation) {}

    private record SpawnPlan(BlockPos anchor, List<BlockPos> positions) {
        private SpawnPlan {
            positions = List.copyOf(positions);
        }
    }
}
