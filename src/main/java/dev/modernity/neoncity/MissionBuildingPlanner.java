package dev.modernity.neoncity;

import com.example.cyberdeck.city.CityWorlds;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Bounded live-world planner for mission interiors in imported Arnis buildings.
 *
 * <p>The Arnis catalog is tile based and has no room metadata. This planner therefore accepts only
 * buildings whose already-placed blocks prove that they contain connected, enclosed walkable
 * floors and enough safe space for an entrance, stairs, patrols, and cover. It never treats an
 * unverified catalog tile as a building.</p>
 */
public final class MissionBuildingPlanner {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final long SITE_SALT = 0x4D495353494F4E42L;
    private static final int MAX_SEARCH_RADIUS_CHUNKS = 16;
    private static final int MAX_PROFILE_ATTEMPTS = 16;
    private static final int SCAN_MARGIN = 2;
    private static final int MAX_SCAN_HEIGHT = 72;
    private static final int MIN_FLOOR_CELLS = 64;
    private static final int MIN_FLOOR_SIDE = 7;
    private static final int MIN_PATROL_CELLS = 24;
    private static final int MIN_STORY_HEIGHT = 4;
    private static final int MAX_STORY_HEIGHT = 9;
    private static final int MAX_FLOORS = 4;
    private static final int MAX_ROUTE_POINTS = 8;
    private static final int STAIR_WIDTH = 2;
    private static final int STAIR_LANDING_DEPTH = 2;
    private static final int MIN_STAIR_HORIZONTAL_GAP = 3;
    private static final int MAX_STAIR_CANDIDATES = 64;
    private static final int MAX_SITE_VOLUME = 40_000;
    private static final int LARGE_OFFICE_CELLS = 120;
    private static final int MAX_EXTERIOR_PATH_NODES = 4_096;
    private static final int EXTERIOR_SEARCH_MARGIN = 40;
    private static final int CORRIDOR_CLEARANCE = 1;
    private static final int MAX_EXPLOSIVE_CANISTERS_PER_SITE = 2;
    private static final int MAX_DECORATIONS = 256;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;

    private MissionBuildingPlanner() {
    }

    public enum InstallationResult {
        INSTALLED,
        ALREADY_INSTALLED,
        UNSAFE
    }

    public enum DecorKind {
        RECEPTION_DESK,
        PLANTER,
        CUBICLE_DESK,
        COUCH,
        ROOM_PARTITION,
        CUBICLE_POD,
        CONFERENCE_TABLE,
        SERVER_RACK,
        FILING_CABINET,
        WATER_COOLER,
        EXPLOSIVE_CANISTER
    }

    public record Entrance(BlockPos position, Direction outward, int wallDepth, boolean existing) {
        public Entrance {
            position = position.immutable();
            if (outward == null || outward.getAxis().isVertical()) {
                throw new IllegalArgumentException("mission entrance must face horizontally");
            }
            if (wallDepth < 0 || wallDepth > 2) {
                throw new IllegalArgumentException("invalid mission entrance depth " + wallDepth);
            }
        }
    }

    public record StairRun(BlockPos start, Direction ascending, int rise) {
        public StairRun {
            start = start.immutable();
            if (ascending == null || ascending.getAxis().isVertical()) {
                throw new IllegalArgumentException("mission stairs must run horizontally");
            }
            if (rise < MIN_STORY_HEIGHT || rise > MAX_STORY_HEIGHT) {
                throw new IllegalArgumentException("invalid mission stair rise " + rise);
            }
        }
    }

    public record PatrolRoute(int floorY, List<BlockPos> waypoints) {
        public PatrolRoute {
            waypoints = immutablePositions(waypoints, MAX_ROUTE_POINTS);
            if (waypoints.size() < 2) {
                throw new IllegalArgumentException("mission patrol route needs two waypoints");
            }
        }
    }

    public record Decoration(BlockPos position, DecorKind kind, Direction facing) {
        public Decoration {
            position = position.immutable();
            if (kind == null || facing == null || facing.getAxis().isVertical()) {
                throw new IllegalArgumentException("invalid mission decoration");
            }
        }
    }

    /** Complete deterministic plan; {@link #save()} and {@link #load(CompoundTag)} are stable. */
    public record Site(
            String id,
            District district,
            BoundingBox bounds,
            List<Integer> floorYs,
            BlockPos target,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> patrolRoutes,
            List<Decoration> decorations,
            long planSeed) {
        public Site {
            if (id == null || id.isBlank() || district == null || bounds == null
                    || target == null || entrance == null) {
                throw new IllegalArgumentException("incomplete mission building site");
            }
            bounds = copy(bounds);
            floorYs = floorYs == null ? List.of() : floorYs.stream().distinct().sorted().toList();
            if (floorYs.isEmpty() || floorYs.size() > MAX_FLOORS) {
                throw new IllegalArgumentException("mission site must contain one to four floors");
            }
            target = target.immutable();
            stairs = stairs == null ? List.of() : List.copyOf(stairs);
            patrolRoutes = patrolRoutes == null ? List.of() : List.copyOf(patrolRoutes);
            decorations = decorations == null ? List.of() : List.copyOf(decorations);
            if (stairs.size() != floorYs.size() - 1
                    || patrolRoutes.size() != floorYs.size()
                    || decorations.size() > MAX_DECORATIONS
                    || !stairFloorsMatch(floorYs, stairs)
                    || !patrolRoutes.stream().map(PatrolRoute::floorY)
                            .collect(java.util.stream.Collectors.toSet())
                            .equals(Set.copyOf(floorYs))
                    || !contains(bounds, target)
                    || !topologyWithinBounds(
                            bounds, floorYs, entrance, stairs, patrolRoutes, decorations)) {
                throw new IllegalArgumentException("mission site topology is incomplete");
            }
            long volume = (long) bounds.getXSpan() * bounds.getYSpan() * bounds.getZSpan();
            if (volume <= 0 || volume > MAX_SITE_VOLUME) {
                throw new IllegalArgumentException("mission site bounds are unsafe");
            }
        }

        public Optional<PatrolRoute> patrolRoute(int floorY) {
            return patrolRoutes.stream().filter(route -> route.floorY() == floorY).findFirst();
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Version", 1);
            tag.putString("Id", id);
            tag.putInt("District", district.ordinal());
            putBounds(tag, bounds);
            tag.putString("Floors", encodeIntegers(floorYs));
            putPos(tag, "Target", target);
            putPos(tag, "Entrance", entrance.position());
            tag.putInt("EntranceFacing", entrance.outward().ordinal());
            tag.putInt("EntranceDepth", entrance.wallDepth());
            tag.putBoolean("EntranceExisting", entrance.existing());
            tag.putString("Stairs", encodeStairs(stairs));
            tag.putString("Patrols", encodePatrols(patrolRoutes));
            tag.putString("Decor", encodeDecorations(decorations));
            tag.putLong("PlanSeed", planSeed);
            return tag;
        }

        public static Optional<Site> load(CompoundTag tag) {
            try {
                if (tag == null || tag.getIntOr("Version", 0) != 1) {
                    return Optional.empty();
                }
                District[] districts = District.values();
                int districtOrdinal = tag.getIntOr("District", -1);
                if (districtOrdinal < 0 || districtOrdinal >= districts.length) {
                    return Optional.empty();
                }
                Direction[] directions = Direction.values();
                int facingOrdinal = tag.getIntOr("EntranceFacing", -1);
                if (facingOrdinal < 0 || facingOrdinal >= directions.length) {
                    return Optional.empty();
                }
                return Optional.of(new Site(
                        tag.getStringOr("Id", ""),
                        districts[districtOrdinal],
                        readBounds(tag),
                        decodeIntegers(tag.getStringOr("Floors", ""), MAX_FLOORS),
                        readPos(tag, "Target"),
                        new Entrance(
                                readPos(tag, "Entrance"),
                                directions[facingOrdinal],
                                tag.getIntOr("EntranceDepth", 0),
                                tag.getBooleanOr("EntranceExisting", false)),
                        decodeStairs(tag.getStringOr("Stairs", "")),
                        decodePatrols(tag.getStringOr("Patrols", "")),
                        decodeDecorations(tag.getStringOr("Decor", "")),
                        tag.getLongOr("PlanSeed", 0L)));
            } catch (RuntimeException malformed) {
                return Optional.empty();
            }
        }
    }

    /**
     * Generates and inspects at most 16 deterministic Arnis candidates near {@code origin}.
     * Returning empty is expected when nearby imported geometry cannot be modified conservatively.
     */
    public static Optional<Site> findSite(
            ServerLevel level,
            District district,
            BlockPos origin,
            int searchRadiusChunks,
            long selectionSalt) {
        return findSite(level, district, origin, searchRadiusChunks, selectionSalt, 2);
    }

    /**
     * Finds a safe building while preferring a multistory plan. A one-floor fallback lets
     * contracts use otherwise suitable Arnis buildings instead of failing outright.
     */
    public static Optional<Site> findSite(
            ServerLevel level,
            District district,
            BlockPos origin,
            int searchRadiusChunks,
            long selectionSalt,
            int minimumFloors) {
        return findSite(
                level, district, origin, searchRadiusChunks, selectionSalt, minimumFloors,
                ignored -> true);
    }

    static Optional<Site> findSite(
            ServerLevel level,
            District district,
            BlockPos origin,
            int searchRadiusChunks,
            long selectionSalt,
            int minimumFloors,
            Predicate<Site> siteFilter) {
        if (level == null || district == null || origin == null
                || siteFilter == null || !NeonCityGenerator.isMegacityWorld(level)) {
            return Optional.empty();
        }
        if (minimumFloors < 1 || minimumFloors > MAX_FLOORS) {
            throw new IllegalArgumentException("invalid minimum mission floor count");
        }
        int radius = Math.max(1, Math.min(MAX_SEARCH_RADIUS_CHUNKS, searchRadiusChunks));
        int centerChunkX = Math.floorDiv(origin.getX(), 16);
        int centerChunkZ = Math.floorDiv(origin.getZ(), 16);
        long seed = NeonCityGenerator.layout().seed() ^ SITE_SALT ^ selectionSalt;
        List<ChunkCandidate> candidates = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                Optional<ArnisPatchLibrary.Placement> placement = ArnisPatchLibrary.select(
                        NeonCityGenerator.layout(), chunkX, chunkZ);
                if (placement.isEmpty() || placement.get().patch().district() != district) {
                    continue;
                }
                long score = MegacityLayout.mix(seed, chunkX, chunkZ);
                candidates.add(new ChunkCandidate(
                        chunkX, chunkZ, Math.max(Math.abs(dx), Math.abs(dz)), score));
            }
        }
        candidates.sort(Comparator.comparingInt(ChunkCandidate::distance)
                .thenComparingLong(ChunkCandidate::score)
                .thenComparingInt(ChunkCandidate::chunkX)
                .thenComparingInt(ChunkCandidate::chunkZ));

        int attempts = 0;
        Site fallback = null;
        for (ChunkCandidate candidate : candidates) {
            if (!NeonCityGenerator.isUsableArnisChunk(
                    level, candidate.chunkX() << 4, candidate.chunkZ() << 4)) {
                continue;
            }
            if (attempts++ >= MAX_PROFILE_ATTEMPTS) {
                break;
            }
            NeonCityGenerator.generateNow(level, candidate.chunkX(), candidate.chunkZ(), 1);
            Optional<Site> site = profileChunk(
                    level, district, candidate.chunkX(), candidate.chunkZ(), candidate.score(),
                    minimumFloors);
            if (site.isPresent() && siteFilter.test(site.get())) {
                if (site.get().floorYs().size() >= 2 || minimumFloors >= 2) {
                    return site;
                }
                if (fallback == null) {
                    fallback = site.get();
                }
            }
        }
        return Optional.ofNullable(fallback);
    }

    public static Optional<Site> findSite(
            ServerLevel level,
            District district,
            int worldX,
            int worldZ,
            int searchRadiusChunks,
            long selectionSalt) {
        return findSite(level, district,
                new BlockPos(worldX, NeonCityGenerator.CITY_GROUND_Y + 1, worldZ),
                searchRadiusChunks, selectionSalt);
    }

    /** Profiles one caller-selected Arnis chunk after synchronously preparing its neighbors. */
    public static Optional<Site> profileSite(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long planSeed) {
        if (level == null || district == null || !NeonCityGenerator.isMegacityWorld(level)) {
            return Optional.empty();
        }
        NeonCityGenerator.generateNow(level, chunkX, chunkZ, 1);
        if (!NeonCityGenerator.isUsableArnisChunk(level, chunkX << 4, chunkZ << 4)) {
            return Optional.empty();
        }
        return profileChunk(level, district, chunkX, chunkZ, planSeed, 2);
    }

    /** Revalidates all touched blocks without modifying the world. */
    public static boolean preflight(ServerLevel level, Site site) {
        if (level == null || site == null || !loadSiteChunks(level, site.bounds())) {
            return false;
        }
        // Version-one plans still deserialize for active-contract cleanup, but cannot reinstall.
        if (!stairsHaveHorizontalClearance(site.stairs())) {
            return false;
        }
        List<Edit> edits = edits(site);
        for (Edit edit : edits) {
            BlockState current = level.getBlockState(edit.position());
            if (!edit.matches(current) && !edit.policy().accepts(level, edit.position(), current)) {
                return false;
            }
            if (!edit.matches(current) && edit.state().blocksMotion()
                    && !level.getEntitiesOfClass(
                            Entity.class, new AABB(edit.position()), Entity::isAlive).isEmpty()) {
                return false;
            }
        }
        if (!level.getEntitiesOfClass(
                Entity.class, new AABB(site.target()), Entity::isAlive).isEmpty()) {
            return false;
        }
        return routeCellsRemainClear(level, site)
                && circulationRemainsAccessible(level, site, edits);
    }

    /** Verifies the installed interior has a player-sized route to every floor and objective. */
    public static boolean hasAccessibleObjectivePath(ServerLevel level, Site site) {
        return level != null && site != null
                && circulationRemainsAccessible(level, site, List.of())
                && objectiveApproach(level, site).isPresent();
    }

    /** Returns a clear floor cell from which a player can interact with the objective. */
    public static Optional<BlockPos> objectiveApproach(ServerLevel level, Site site) {
        if (level == null || site == null) return Optional.empty();
        Set<BlockPos> reachable = reachableFloorCells(
                level, site, site.target().getY(), List.of());
        return java.util.Arrays.stream(HORIZONTAL)
                .map(site.target()::relative)
                .filter(reachable::contains)
                .filter(position -> isPassage(level, position))
                .findFirst();
    }

    /** Exterior endpoint used by road navigation while the objective remains inside the site. */
    public static BlockPos navigationTarget(Site site) {
        Entrance entrance = site.entrance();
        return entrance.position().relative(
                entrance.outward(), Math.max(0, entrance.wallDepth()));
    }

    /** Applies the exact site plan once; subsequent calls leave the installed geometry unchanged. */
    public static InstallationResult install(ServerLevel level, Site site) {
        if (!preflight(level, site)) {
            return InstallationResult.UNSAFE;
        }
        List<Edit> edits = edits(site);
        List<OriginalState> changedStates = new ArrayList<>();
        boolean changed = false;
        for (Edit edit : edits) {
            BlockState current = level.getBlockState(edit.position());
            if (edit.matches(current)) {
                continue;
            }
            changedStates.add(new OriginalState(edit.position(), current));
            if (!level.setBlock(edit.position(), edit.state(), PLACE_FLAGS)) {
                for (int index = changedStates.size() - 1; index >= 0; index--) {
                    OriginalState original = changedStates.get(index);
                    level.setBlock(original.position(), original.state(), PLACE_FLAGS);
                }
                return InstallationResult.UNSAFE;
            }
            changed = true;
        }
        if (!circulationRemainsAccessible(level, site, List.of())) {
            for (int index = changedStates.size() - 1; index >= 0; index--) {
                OriginalState original = changedStates.get(index);
                level.setBlock(original.position(), original.state(), PLACE_FLAGS);
            }
            return InstallationResult.UNSAFE;
        }
        return changed ? InstallationResult.INSTALLED : InstallationResult.ALREADY_INSTALLED;
    }

    private static Optional<Site> profileChunk(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long planSeed,
            int minimumFloors) {
        ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                NeonCityGenerator.layout(), chunkX, chunkZ).orElse(null);
        if (placement == null || placement.patch().district() != district) {
            return Optional.empty();
        }
        int minX = (chunkX << 4) - SCAN_MARGIN;
        int maxX = (chunkX << 4) + 15 + SCAN_MARGIN;
        int minZ = (chunkZ << 4) - SCAN_MARGIN;
        int maxZ = (chunkZ << 4) + 15 + SCAN_MARGIN;
        int minY = NeonCityGenerator.CITY_GROUND_Y + 1;
        int templateMinY = NeonCityGenerator.CITY_GROUND_Y
                - placement.patch().surfaceOffset();
        int maxY = Math.min(
                minY + MAX_SCAN_HEIGHT - 1,
                templateMinY + placement.patch().sizeY() - 2);
        int minimumScanHeight = minimumFloors >= 2 ? MIN_STORY_HEIGHT + 2 : 2;
        if (maxY - minY < minimumScanHeight) {
            return Optional.empty();
        }

        Map<Integer, Set<BlockPos>> cellsByFloor = new HashMap<>();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (isInteriorWalkable(level, position)) {
                        cellsByFloor.computeIfAbsent(y, ignored -> new HashSet<>()).add(position);
                    }
                }
            }
        }

        List<FloorProfile> profiles = new ArrayList<>();
        for (Map.Entry<Integer, Set<BlockPos>> entry : cellsByFloor.entrySet()) {
            profiles.addAll(floorComponents(entry.getKey(), entry.getValue()));
        }
        profiles.sort(Comparator.comparingInt(FloorProfile::y)
                .thenComparingInt(profile -> profile.bounds().minX)
                .thenComparingInt(profile -> profile.bounds().minZ));
        List<FloorProfile> floors = bestFloorStack(profiles, planSeed, minimumFloors);
        if (floors.size() >= minimumFloors) {
            Optional<Site> preferred = planSite(
                    level, district, chunkX, chunkZ, planSeed, floors);
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        if (minimumFloors >= 2) {
            return Optional.empty();
        }
        for (FloorProfile floor : profiles.stream()
                .sorted(Comparator.comparingInt(
                                (FloorProfile profile) -> profile.cells().size()).reversed()
                        .thenComparingLong(profile -> positionScore(
                                planSeed, profile.bounds().minX, profile.bounds().minZ)))
                .toList()) {
            Optional<Site> fallback = planSite(
                    level, district, chunkX, chunkZ, planSeed, List.of(floor));
            if (fallback.isPresent()) {
                return fallback;
            }
        }
        return Optional.empty();
    }

    private static Optional<Site> planSite(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long planSeed,
            List<FloorProfile> floors) {
        Entrance entrance = findEntrance(level, floors.getFirst(), planSeed);
        if (entrance == null) {
            return Optional.empty();
        }
        List<StairRun> stairs = findStairPlan(level, floors, planSeed);
        if (stairs.size() != floors.size() - 1) {
            return Optional.empty();
        }

        BoundingBox bounds = siteBounds(floors, entrance);
        if ((long) bounds.getXSpan() * bounds.getYSpan() * bounds.getZSpan() > MAX_SITE_VOLUME) {
            return Optional.empty();
        }
        Set<BlockPos> routeExclusions = routeExclusions(entrance, stairs);
        List<PatrolRoute> routes = new ArrayList<>();
        for (FloorProfile floor : floors) {
            PatrolRoute route = patrolRoute(
                    floor, planSeed ^ floor.y(), routeExclusions);
            if (route == null) {
                return Optional.empty();
            }
            routes.add(route);
        }
        Set<BlockPos> targetExclusions = new HashSet<>(routeExclusions);
        for (PatrolRoute route : routes) {
            for (BlockPos waypoint : route.waypoints()) {
                reserve(targetExclusions, waypoint, 2);
            }
        }
        BlockPos target = chooseTarget(
                floors.getLast(), entrance, planSeed, targetExclusions);
        if (target == null) {
            return Optional.empty();
        }
        List<Decoration> decorations = planDecorations(
                floors, entrance, stairs, routes, target, planSeed);
        if (!hasRequiredDecorations(floors, decorations)) {
            return Optional.empty();
        }
        String id = district.code().toLowerCase(java.util.Locale.ROOT)
                + ":" + chunkX + ":" + chunkZ + ":"
                + Long.toUnsignedString(planSeed, 16);
        try {
            return Optional.of(new Site(
                    id,
                    district,
                    bounds,
                    floors.stream().map(FloorProfile::y).toList(),
                    target,
                    entrance,
                    stairs,
                    routes,
                    decorations,
                    planSeed));
        } catch (IllegalArgumentException unsafe) {
            return Optional.empty();
        }
    }

    private static boolean isInteriorWalkable(ServerLevel level, BlockPos position) {
        if (!level.isLoaded(position)
                || !level.isEmptyBlock(position)
                || !level.isEmptyBlock(position.above())
                || !level.getBlockState(position.below()).blocksMotion()
                || hasBlockEntity(level, position)
                || hasBlockEntity(level, position.above())
                || hasBlockEntity(level, position.below())
                || level.canSeeSky(position)) {
            return false;
        }
        if (!hasCeiling(level, position, 7)) {
            return false;
        }
        int enclosingDirections = 0;
        for (Direction direction : HORIZONTAL) {
            for (int distance = 1; distance <= 10; distance++) {
                BlockState state = level.getBlockState(position.relative(direction, distance));
                if (state.blocksMotion()) {
                    enclosingDirections++;
                    break;
                }
            }
        }
        return enclosingDirections >= 2;
    }

    private static List<FloorProfile> floorComponents(int y, Set<BlockPos> cells) {
        Set<BlockPos> unvisited = new HashSet<>(cells);
        List<FloorProfile> result = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        while (!unvisited.isEmpty()) {
            BlockPos seed = unvisited.iterator().next();
            unvisited.remove(seed);
            queue.add(seed);
            Set<BlockPos> component = new HashSet<>();
            while (!queue.isEmpty()) {
                BlockPos position = queue.removeFirst();
                component.add(position);
                for (Direction direction : HORIZONTAL) {
                    BlockPos next = position.relative(direction);
                    if (unvisited.remove(next)) {
                        queue.addLast(next);
                    }
                }
            }
            if (component.size() < MIN_FLOOR_CELLS) {
                continue;
            }
            Rect bounds = rect(component);
            if (bounds.width() >= MIN_FLOOR_SIDE && bounds.depth() >= MIN_FLOOR_SIDE) {
                result.add(new FloorProfile(y, Set.copyOf(component), bounds));
            }
        }
        return result;
    }

    private static List<FloorProfile> bestFloorStack(
            List<FloorProfile> profiles, long seed, int minimumFloors) {
        List<FloorProfile> best = List.of();
        long bestScore = Long.MIN_VALUE;
        for (int start = 0; start < profiles.size(); start++) {
            List<FloorProfile> stack = new ArrayList<>();
            FloorProfile current = profiles.get(start);
            stack.add(current);
            while (stack.size() < MAX_FLOORS) {
                FloorProfile previous = current;
                current = profiles.stream()
                        .filter(candidate -> candidate.y() > previous.y())
                        .filter(candidate -> candidate.y() - previous.y() >= MIN_STORY_HEIGHT)
                        .filter(candidate -> candidate.y() - previous.y() <= MAX_STORY_HEIGHT)
                        .filter(candidate -> overlaps(previous.bounds(), candidate.bounds()))
                        .max(Comparator.comparingInt((FloorProfile value) -> value.cells().size())
                                .thenComparingLong(value -> positionScore(
                                        seed, value.bounds().minX, value.bounds().minZ)))
                        .orElse(null);
                if (current == null || stack.contains(current)) {
                    break;
                }
                stack.add(current);
            }
            long area = stack.stream().mapToLong(value -> value.cells().size()).sum();
            long score = stack.size() * 1_000_000L + area * 100L
                    - (long) (stack.getFirst().y() - NeonCityGenerator.CITY_GROUND_Y) * 10_000L
                    + Math.floorMod(positionScore(seed, start, stack.getFirst().y()), 100L);
            if (stack.size() >= minimumFloors && score > bestScore) {
                best = List.copyOf(stack);
                bestScore = score;
            }
        }
        return best;
    }

    private static Entrance findEntrance(ServerLevel level, FloorProfile floor, long seed) {
        List<BlockPos> cells = ordered(floor.cells(), seed);
        for (BlockPos cell : cells) {
            for (Direction direction : orderedDirections(seed, cell)) {
                BlockPos first = cell.relative(direction);
                if (!floor.cells().contains(first)
                        && existingAccess(level, floor, first)) {
                    return new Entrance(first, direction, 0, true);
                }
            }
        }
        for (BlockPos cell : cells) {
            for (Direction direction : orderedDirections(seed ^ 0x6A09E667F3BCC909L, cell)) {
                int depth = doorwayDepth(level, floor, cell, direction);
                if (depth > 0) {
                    return new Entrance(cell.relative(direction), direction, depth, false);
                }
            }
        }
        return null;
    }

    private static boolean existingAccess(
            ServerLevel level, FloorProfile floor, BlockPos first) {
        if (!isPassage(level, first)) {
            return false;
        }
        int minX = floor.bounds().minX - EXTERIOR_SEARCH_MARGIN;
        int maxX = floor.bounds().maxX + EXTERIOR_SEARCH_MARGIN;
        int minZ = floor.bounds().minZ - EXTERIOR_SEARCH_MARGIN;
        int maxZ = floor.bounds().maxZ + EXTERIOR_SEARCH_MARGIN;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(first);
        queue.add(first);
        while (!queue.isEmpty() && visited.size() <= MAX_EXTERIOR_PATH_NODES) {
            BlockPos current = queue.removeFirst();
            if (outsideBuildingApproach(floor, current)
                    && CityWorlds.isWalkableStreet(level, current)) {
                return true;
            }
            for (Direction direction : HORIZONTAL) {
                BlockPos horizontal = current.relative(direction);
                for (int dy : new int[] {0, 1, -1}) {
                    BlockPos next = horizontal.above(dy);
                    if (next.getX() < minX || next.getX() > maxX
                            || next.getZ() < minZ || next.getZ() > maxZ
                            || Math.abs(next.getY() - floor.y()) > 4
                            || floor.cells().contains(next.atY(floor.y()))) {
                        continue;
                    }
                    if (isPassage(level, next) && visited.add(next)) {
                        queue.addLast(next);
                    }
                }
            }
        }
        return false;
    }

    private static boolean outsideBuildingApproach(FloorProfile floor, BlockPos position) {
        return position.getX() <= floor.bounds().minX - 3
                || position.getX() >= floor.bounds().maxX + 3
                || position.getZ() <= floor.bounds().minZ - 3
                || position.getZ() >= floor.bounds().maxZ + 3;
    }

    private static int doorwayDepth(
            ServerLevel level, FloorProfile floor, BlockPos inside, Direction outward) {
        Direction across = outward.getClockWise();
        if (!floor.cells().contains(inside.relative(across))) {
            return 0;
        }
        for (int depth = 1; depth <= 2; depth++) {
            boolean validWall = true;
            for (int lane = 0; lane < 2 && validWall; lane++) {
                BlockPos wall = inside.relative(across, lane).relative(outward);
                if (!level.getBlockState(wall.below()).blocksMotion()) {
                    validWall = false;
                    continue;
                }
                for (int step = 0; step < depth && validWall; step++) {
                    BlockPos slice = wall.relative(outward, step);
                    for (int y = 0; y < 3; y++) {
                        if (!isCarvable(level, slice.above(y))) {
                            validWall = false;
                            break;
                        }
                    }
                }
                BlockPos outside = wall.relative(outward, depth);
                validWall &= isPassage(level, outside)
                        && existingAccess(level, floor, outside);
            }
            if (validWall) {
                return depth;
            }
        }
        return 0;
    }

    private static List<StairRun> findStairPlan(
            ServerLevel level, List<FloorProfile> floors, long seed) {
        List<List<StairCandidate>> candidatesByTransition = new ArrayList<>();
        for (int index = 1; index < floors.size(); index++) {
            List<StairCandidate> candidates = stairCandidates(
                    level,
                    floors.get(index - 1),
                    floors.get(index),
                    seed + index * 0x9E3779B97F4A7C15L);
            if (candidates.isEmpty()) {
                return List.of();
            }
            candidatesByTransition.add(candidates);
        }
        List<StairRun> selected = new ArrayList<>();
        if (!selectStairPlan(candidatesByTransition, 0, selected)) {
            return List.of();
        }
        return List.copyOf(selected);
    }

    private static List<StairCandidate> stairCandidates(
            ServerLevel level, FloorProfile lower, FloorProfile upper, long seed) {
        int rise = upper.y() - lower.y();
        List<StairCandidate> candidates = new ArrayList<>();
        for (BlockPos start : lower.cells()) {
            for (Direction direction : HORIZONTAL) {
                if (!stairLandingsFit(lower, upper, start, direction, rise)) {
                    continue;
                }
                int edits = stairEditCost(level, start, direction, rise);
                if (edits >= 0) {
                    candidates.add(new StairCandidate(
                            new StairRun(start, direction, rise), edits,
                            positionScore(seed, start.getX(), start.getZ())));
                }
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(StairCandidate::edits)
                        .thenComparingLong(StairCandidate::score))
                .limit(MAX_STAIR_CANDIDATES)
                .toList();
    }

    private static boolean selectStairPlan(
            List<List<StairCandidate>> candidatesByTransition,
            int transition,
            List<StairRun> selected) {
        if (transition == candidatesByTransition.size()) {
            return true;
        }
        for (StairCandidate candidate : candidatesByTransition.get(transition)) {
            if (selected.stream().anyMatch(
                    existing -> !stairRunsHaveHorizontalClearance(existing, candidate.run()))) {
                continue;
            }
            selected.add(candidate.run());
            if (selectStairPlan(candidatesByTransition, transition + 1, selected)) {
                return true;
            }
            selected.removeLast();
        }
        return false;
    }

    private static boolean stairLandingsFit(
            FloorProfile lower,
            FloorProfile upper,
            BlockPos start,
            Direction direction,
            int rise) {
        Direction across = direction.getClockWise();
        for (int lane = 0; lane < STAIR_WIDTH; lane++) {
            for (int step = 0; step < rise; step++) {
                BlockPos projection = start.relative(direction, step).relative(across, lane);
                if (!lower.cells().contains(projection)
                        || !upper.cells().contains(projection.atY(upper.y()))) {
                    return false;
                }
            }
            for (int depth = 1; depth <= STAIR_LANDING_DEPTH; depth++) {
                BlockPos approach = start.relative(direction.getOpposite(), depth)
                        .relative(across, lane);
                if (!lower.cells().contains(approach)) {
                    return false;
                }
            }
            for (int depth = 0; depth < STAIR_LANDING_DEPTH; depth++) {
                BlockPos exit = start.relative(direction, rise + depth)
                        .relative(across, lane).atY(upper.y());
                if (!upper.cells().contains(exit)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int stairEditCost(
            ServerLevel level, BlockPos start, Direction direction, int rise) {
        Direction across = direction.getClockWise();
        int edits = 0;
        for (int step = 0; step < rise; step++) {
            for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                BlockPos stair = start.relative(direction, step)
                        .relative(across, lane).above(step);
                for (int head = 0; head <= 2; head++) {
                    BlockPos position = stair.above(head);
                    if (!isSafelyEditable(level, position)) {
                        return -1;
                    }
                    if (!level.isEmptyBlock(position)) {
                        edits++;
                    }
                }
            }
        }
        return edits;
    }

    private static boolean stairsHaveHorizontalClearance(List<StairRun> stairs) {
        for (int first = 0; first < stairs.size(); first++) {
            for (int second = first + 1; second < stairs.size(); second++) {
                if (!stairRunsHaveHorizontalClearance(stairs.get(first), stairs.get(second))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean stairRunsHaveHorizontalClearance(StairRun first, StairRun second) {
        Rect firstBounds = stairHorizontalEnvelope(first);
        Rect secondBounds = stairHorizontalEnvelope(second);
        int xGap = Math.max(0, Math.max(
                firstBounds.minX() - secondBounds.maxX(),
                secondBounds.minX() - firstBounds.maxX()));
        int zGap = Math.max(0, Math.max(
                firstBounds.minZ() - secondBounds.maxZ(),
                secondBounds.minZ() - firstBounds.maxZ()));
        return xGap + zGap >= MIN_STAIR_HORIZONTAL_GAP;
    }

    private static Rect stairHorizontalEnvelope(StairRun stair) {
        Direction across = stair.ascending().getClockWise();
        BlockPos first = stair.start().relative(
                stair.ascending().getOpposite(), STAIR_LANDING_DEPTH);
        BlockPos last = stair.start()
                .relative(stair.ascending(), stair.rise() + STAIR_LANDING_DEPTH - 1)
                .relative(across, STAIR_WIDTH - 1);
        return new Rect(
                Math.min(first.getX(), last.getX()), Math.max(first.getX(), last.getX()),
                Math.min(first.getZ(), last.getZ()), Math.max(first.getZ(), last.getZ()));
    }

    private static List<BlockPos> stairLandingCells(StairRun stair) {
        Direction across = stair.ascending().getClockWise();
        List<BlockPos> result = new ArrayList<>(STAIR_WIDTH * STAIR_LANDING_DEPTH * 2);
        for (int lane = 0; lane < STAIR_WIDTH; lane++) {
            for (int depth = 1; depth <= STAIR_LANDING_DEPTH; depth++) {
                result.add(stair.start().relative(stair.ascending().getOpposite(), depth)
                        .relative(across, lane));
            }
            for (int depth = 0; depth < STAIR_LANDING_DEPTH; depth++) {
                result.add(stair.start().relative(stair.ascending(), stair.rise() + depth)
                        .relative(across, lane).above(stair.rise()));
            }
        }
        return result;
    }

    private static List<BlockPos> stairFloorClearanceCells(StairRun stair) {
        List<BlockPos> result = new ArrayList<>(stairLandingCells(stair));
        Direction across = stair.ascending().getClockWise();
        for (int lane = 0; lane < STAIR_WIDTH; lane++) {
            result.add(stair.start().relative(across, lane));
        }
        return result;
    }

    private static PatrolRoute patrolRoute(
            FloorProfile floor, long seed, Set<BlockPos> exclusions) {
        List<BlockPos> ordered = ordered(floor.cells().stream()
                .filter(position -> !exclusions.contains(position))
                .collect(java.util.stream.Collectors.toSet()), seed);
        if (ordered.size() < MIN_PATROL_CELLS) {
            return null;
        }
        List<BlockPos> route = new ArrayList<>();
        route.add(ordered.getFirst());
        while (route.size() < Math.min(4, MAX_ROUTE_POINTS)) {
            BlockPos next = ordered.stream()
                    .filter(candidate -> !route.contains(candidate))
                    .max(Comparator.comparingDouble(candidate -> route.stream()
                            .mapToDouble(existing -> existing.distSqr(candidate))
                            .min().orElse(0.0)))
                    .orElse(null);
            if (next == null) {
                break;
            }
            route.add(next);
        }
        return new PatrolRoute(floor.y(), route);
    }

    private static Set<BlockPos> routeExclusions(
            Entrance entrance, List<StairRun> stairs) {
        Set<BlockPos> excluded = new HashSet<>();
        reserve(excluded, entrance.position(), 1);
        Direction entranceAcross = entrance.outward().getClockWise();
        reserve(excluded, entrance.position().relative(entranceAcross), 1);
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step <= stair.rise(); step++) {
                for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                    BlockPos position = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step);
                    reserve(excluded, position, 1);
                }
            }
            for (BlockPos landing : stairFloorClearanceCells(stair)) {
                reserve(excluded, landing, 1);
            }
        }
        return excluded;
    }

    private static BlockPos chooseTarget(
            FloorProfile top, Entrance entrance, long seed, Set<BlockPos> exclusions) {
        return top.cells().stream()
                .filter(position -> !exclusions.contains(position))
                .filter(position -> java.util.Arrays.stream(HORIZONTAL)
                        .filter(direction -> top.cells().contains(position.relative(direction)))
                        .filter(direction -> !exclusions.contains(position.relative(direction)))
                        .count() >= 2)
                .max(Comparator.comparingDouble(
                                (BlockPos position) -> position.distSqr(entrance.position()))
                        .thenComparingLong(position -> positionScore(
                                seed, position.getX(), position.getZ())))
                .orElse(null);
    }

    private static List<Decoration> planDecorations(
            List<FloorProfile> floors,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            long seed) {
        List<Decoration> result = new ArrayList<>();
        Set<BlockPos> occupied = new HashSet<>();
        Set<BlockPos> blockedCells = new HashSet<>();
        reserve(occupied, target, 2);
        reserve(occupied, entrance.position(), 2);
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step <= stair.rise(); step++) {
                for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                    reserve(occupied, stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step), 1);
                }
            }
            for (BlockPos landing : stairFloorClearanceCells(stair)) {
                reserve(occupied, landing, 1);
            }
        }

        int explosiveCanisters = 0;
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            FloorProfile floor = floors.get(floorIndex);
            PatrolRoute route = routes.stream()
                    .filter(value -> value.floorY() == floor.y()).findFirst().orElseThrow();
            Set<BlockPos> circulation = circulationSpine(
                    floor, entrance, stairs, route, target);
            for (BlockPos position : circulation) {
                reserve(occupied, position, CORRIDOR_CLEARANCE);
            }

            Direction longAxis = floor.bounds().width() >= floor.bounds().depth()
                    ? Direction.EAST : Direction.SOUTH;
            Direction across = longAxis.getClockWise();
            if (floorIndex == 0) {
                BlockPos inside = entrance.position().relative(entrance.outward().getOpposite());
                Direction inward = entrance.outward().getOpposite();
                Direction lobbyAcross = inward.getClockWise();
                addFirstDecoration(
                        result, occupied, blockedCells, floor,
                        List.of(
                                inside.relative(inward, 3).relative(lobbyAcross, -3),
                                inside.relative(inward, 3).relative(lobbyAcross, 2),
                                inside.relative(inward, 5).relative(lobbyAcross, -3),
                                inside.relative(inward, 5).relative(lobbyAcross, 2)),
                        DecorKind.RECEPTION_DESK, entrance.outward(),
                        entrance, stairs, routes, target, 1);
                addDecoration(result, occupied, blockedCells, floor,
                        new Decoration(
                                inside.relative(inward, 2).relative(lobbyAcross, -4),
                                DecorKind.PLANTER, entrance.outward()),
                        entrance, stairs, routes, target, 1);
                addDecoration(result, occupied, blockedCells, floor,
                        new Decoration(
                                inside.relative(inward, 2).relative(lobbyAcross, 4),
                                DecorKind.PLANTER, entrance.outward()),
                        entrance, stairs, routes, target, 1);
                addDecoration(result, occupied, blockedCells, floor,
                        new Decoration(
                                inside.relative(inward, 6).relative(lobbyAcross, 3),
                                DecorKind.COUCH, inward),
                        entrance, stairs, routes, target, 1);
            }

            if (floor.cells().size() >= LARGE_OFFICE_CELLS) {
                addRoomPartitions(
                        result, occupied, blockedCells, floor, longAxis,
                        entrance, stairs, routes, target);
                int wantedPods = Math.min(8, Math.max(2, floor.cells().size() / 48));
                int pods = 0;
                for (int z = floor.bounds().minZ + 2;
                        z <= floor.bounds().maxZ - 2 && pods < wantedPods; z += 4) {
                    for (int x = floor.bounds().minX + 2;
                            x <= floor.bounds().maxX - 2 && pods < wantedPods; x += 4) {
                        if (addDecoration(result, occupied, blockedCells, floor,
                                new Decoration(
                                        new BlockPos(x, floor.y(), z),
                                        DecorKind.CUBICLE_POD, longAxis),
                                entrance, stairs, routes, target, 1)) {
                            pods++;
                        }
                    }
                }
                List<BlockPos> roomAnchors = List.of(
                        new BlockPos(
                                floor.bounds().minX + 2, floor.y(), floor.bounds().maxZ - 3),
                        new BlockPos(
                                floor.bounds().maxX - 3, floor.y(), floor.bounds().minZ + 2),
                        new BlockPos(
                                floor.bounds().maxX - 3, floor.y(), floor.bounds().maxZ - 3));
                addFirstDecoration(result, occupied, blockedCells, floor, roomAnchors,
                        DecorKind.CONFERENCE_TABLE, longAxis,
                        entrance, stairs, routes, target, 1);

                int racks = 0;
                for (int offset = 2; offset < Math.max(
                        floor.bounds().width(), floor.bounds().depth()) - 2 && racks < 3;
                        offset += 2) {
                    BlockPos rack = longAxis.getAxis() == Direction.Axis.X
                            ? new BlockPos(
                                    floor.bounds().maxX - 1, floor.y(), floor.bounds().minZ + offset)
                            : new BlockPos(
                                    floor.bounds().minX + offset, floor.y(), floor.bounds().maxZ - 1);
                    if (addDecoration(result, occupied, blockedCells, floor,
                            new Decoration(rack, DecorKind.SERVER_RACK, longAxis.getOpposite()),
                            entrance, stairs, routes, target, 0)) {
                        racks++;
                    }
                }
                addFirstDecoration(result, occupied, blockedCells, floor, roomAnchors.reversed(),
                        DecorKind.WATER_COOLER, across,
                        entrance, stairs, routes, target, 1);

                if (explosiveCanisters < MAX_EXPLOSIVE_CANISTERS_PER_SITE) {
                    for (BlockPos anchor : roomAnchors.reversed()) {
                        if (!safeCanisterPosition(anchor, entrance, stairs, routes, target)) {
                            continue;
                        }
                        if (addDecoration(result, occupied, blockedCells, floor,
                                new Decoration(
                                        anchor, DecorKind.EXPLOSIVE_CANISTER,
                                        longAxis.getOpposite()),
                                entrance, stairs, routes, target, 1)) {
                            explosiveCanisters++;
                            break;
                        }
                    }
                }
            }

            int wanted = floorIndex == 0 ? 3 : 4;
            if (decorationsOnFloor(result, floor.y()) < wanted) {
                for (BlockPos candidate : structuredFloorCandidates(floor, longAxis)) {
                    if (decorationsOnFloor(result, floor.y()) >= wanted) break;
                    DecorKind kind = floorIndex == 0
                            ? DecorKind.FILING_CABINET : DecorKind.CUBICLE_DESK;
                    addDecoration(result, occupied, blockedCells, floor,
                            new Decoration(candidate, kind, longAxis),
                            entrance, stairs, routes, target, 1);
                }
            }
        }
        return List.copyOf(result);
    }

    private static Set<BlockPos> circulationSpine(
            FloorProfile floor,
            Entrance entrance,
            List<StairRun> stairs,
            PatrolRoute route,
            BlockPos target) {
        LinkedHashSet<BlockPos> portals = new LinkedHashSet<>(route.waypoints());
        BlockPos entranceInside = entrance.position().relative(entrance.outward().getOpposite());
        if (entranceInside.getY() == floor.y()) {
            portals.add(entranceInside);
            portals.add(entranceInside.relative(entrance.outward().getClockWise()));
        }
        for (StairRun stair : stairs) {
            for (BlockPos landing : stairFloorClearanceCells(stair)) {
                if (landing.getY() == floor.y()) portals.add(landing);
            }
        }
        if (target.getY() == floor.y()) portals.add(target);
        portals.retainAll(floor.cells());
        LinkedHashSet<BlockPos> spine = new LinkedHashSet<>();
        BlockPos root = portals.stream().findFirst().orElse(null);
        if (root == null) return spine;
        spine.add(root);
        for (BlockPos portal : portals) {
            spine.addAll(shortestFloorPath(floor.cells(), root, portal));
        }
        return spine;
    }

    private static List<BlockPos> shortestFloorPath(
            Set<BlockPos> cells, BlockPos start, BlockPos target) {
        if (start.equals(target)) return List.of(start);
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        previous.put(start, start);
        queue.add(start);
        while (!queue.isEmpty() && !previous.containsKey(target)) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (cells.contains(next) && !previous.containsKey(next)) {
                    previous.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        if (!previous.containsKey(target)) return List.of();
        ArrayList<BlockPos> path = new ArrayList<>();
        BlockPos cursor = target;
        while (!cursor.equals(start)) {
            path.add(cursor);
            cursor = previous.get(cursor);
        }
        path.add(start);
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    private static void addRoomPartitions(
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            Direction longAxis,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target) {
        boolean splitAlongX = longAxis.getAxis() == Direction.Axis.X;
        int fixed = splitAlongX
                ? (floor.bounds().minX + floor.bounds().maxX()) / 2
                : (floor.bounds().minZ + floor.bounds().maxZ()) / 2;
        int start = splitAlongX ? floor.bounds().minZ + 1 : floor.bounds().minX + 1;
        int end = splitAlongX ? floor.bounds().maxZ - 1 : floor.bounds().maxX - 1;
        Direction facing = splitAlongX ? Direction.EAST : Direction.SOUTH;
        for (int variable = start; variable <= end; variable++) {
            BlockPos position = splitAlongX
                    ? new BlockPos(fixed, floor.y(), variable)
                    : new BlockPos(variable, floor.y(), fixed);
            addDecoration(result, occupied, blocked, floor,
                    new Decoration(position, DecorKind.ROOM_PARTITION, facing),
                    entrance, stairs, routes, target, 0);
        }
    }

    private static boolean addFirstDecoration(
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            List<BlockPos> candidates,
            DecorKind kind,
            Direction facing,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            int clearance) {
        for (BlockPos candidate : candidates) {
            if (addDecoration(result, occupied, blocked, floor,
                    new Decoration(candidate, kind, facing),
                    entrance, stairs, routes, target, clearance)) {
                return true;
            }
        }
        return false;
    }

    private static boolean addDecoration(
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            Decoration decoration,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            int clearance) {
        if (result.size() >= MAX_DECORATIONS) return false;
        Direction across = decoration.facing().getClockWise();
        List<BlockPos> footprint = decorationFootprint(
                decoration.position(), decoration.kind(), across);
        if (footprint.stream().anyMatch(occupied::contains)
                || footprint.stream().anyMatch(position -> !floor.cells().contains(position))
                || !preservesFloorConnectivity(
                        floor, footprint, blocked, entrance, stairs, routes, target)) {
            return false;
        }
        result.add(decoration);
        blocked.addAll(footprint);
        for (BlockPos position : footprint) reserve(occupied, position, clearance);
        return true;
    }

    private static long decorationsOnFloor(List<Decoration> decorations, int floorY) {
        return decorations.stream()
                .filter(decoration -> decoration.position().getY() == floorY)
                .count();
    }

    private static List<BlockPos> structuredFloorCandidates(
            FloorProfile floor, Direction longAxis) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        int xStep = longAxis.getAxis() == Direction.Axis.X ? 3 : 2;
        int zStep = longAxis.getAxis() == Direction.Axis.Z ? 3 : 2;
        for (int z = floor.bounds().minZ + 1; z <= floor.bounds().maxZ - 1; z += zStep) {
            for (int x = floor.bounds().minX + 1; x <= floor.bounds().maxX - 1; x += xStep) {
                candidates.add(new BlockPos(x, floor.y(), z));
            }
        }
        return List.copyOf(candidates);
    }

    private static boolean safeCanisterPosition(
            BlockPos position,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target) {
        if (horizontalDistance(position, entrance.position()) < 6
                || horizontalDistance(position, target) < 6
                || stairs.stream().anyMatch(stair ->
                        horizontalDistance(position, stair.start()) < 5)
                || routes.stream().flatMap(route -> route.waypoints().stream()).anyMatch(
                        waypoint -> waypoint.getY() == position.getY()
                                && horizontalDistance(position, waypoint) < 4)) {
            return false;
        }
        return true;
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getZ() - second.getZ());
    }

    private static boolean hasRequiredDecorations(
            List<FloorProfile> floors, List<Decoration> decorations) {
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            int floorY = floors.get(floorIndex).y();
            int required = floorIndex == 0 ? 3 : 4;
            long actual = decorations.stream()
                    .filter(decoration -> decoration.position().getY() == floorY)
                    .count();
            if (actual < required) {
                return false;
            }
            if (floorIndex == 0 && decorations.stream().noneMatch(decoration ->
                    decoration.position().getY() == floorY
                            && decoration.kind() == DecorKind.RECEPTION_DESK)) {
                return false;
            }
            if (floorIndex > 0 && decorations.stream().noneMatch(decoration ->
                    decoration.position().getY() == floorY
                            && switch (decoration.kind()) {
                                case CUBICLE_DESK, CUBICLE_POD, CONFERENCE_TABLE, SERVER_RACK -> true;
                                default -> false;
                            })) {
                return false;
            }
        }
        return true;
    }

    private static boolean preservesFloorConnectivity(
            FloorProfile floor,
            List<BlockPos> proposed,
            Set<BlockPos> blocked,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target) {
        Set<BlockPos> unavailable = new HashSet<>(blocked);
        unavailable.addAll(proposed);
        Set<BlockPos> required = new LinkedHashSet<>();
        routes.stream().filter(route -> route.floorY() == floor.y()).findFirst()
                .ifPresent(route -> required.addAll(route.waypoints()));
        if (target.getY() == floor.y()) {
            required.add(target);
        }
        BlockPos entranceInside = entrance.position().relative(entrance.outward().getOpposite());
        if (entranceInside.getY() == floor.y()) {
            required.add(entranceInside);
            required.add(entranceInside.relative(entrance.outward().getClockWise()));
        }
        for (StairRun stair : stairs) {
            for (BlockPos position : stairFloorClearanceCells(stair)) {
                if (position.getY() == floor.y()) {
                    required.add(position);
                }
            }
        }
        required.retainAll(floor.cells());
        if (required.stream().anyMatch(unavailable::contains)) {
            return false;
        }
        BlockPos start = required.stream().findFirst().orElse(null);
        if (start == null) {
            return true;
        }
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (floor.cells().contains(next)
                        && !unavailable.contains(next)
                        && visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited.containsAll(required);
    }

    private static BoundingBox siteBounds(List<FloorProfile> floors, Entrance entrance) {
        int minX = floors.stream().mapToInt(value -> value.bounds().minX).min().orElseThrow() - 1;
        int maxX = floors.stream().mapToInt(value -> value.bounds().maxX).max().orElseThrow() + 1;
        int minZ = floors.stream().mapToInt(value -> value.bounds().minZ).min().orElseThrow() - 1;
        int maxZ = floors.stream().mapToInt(value -> value.bounds().maxZ).max().orElseThrow() + 1;
        BlockPos entranceEnd = entrance.position().relative(
                entrance.outward(), Math.max(1, entrance.wallDepth()));
        minX = Math.min(minX, Math.min(entrance.position().getX(), entranceEnd.getX()) - 1);
        maxX = Math.max(maxX, Math.max(entrance.position().getX(), entranceEnd.getX()) + 1);
        minZ = Math.min(minZ, Math.min(entrance.position().getZ(), entranceEnd.getZ()) - 1);
        maxZ = Math.max(maxZ, Math.max(entrance.position().getZ(), entranceEnd.getZ()) + 1);
        return new BoundingBox(
                minX, floors.getFirst().y() - 1, minZ,
                maxX, floors.getLast().y() + 3, maxZ);
    }

    private static List<Edit> edits(Site site) {
        List<Edit> edits = new ArrayList<>();
        if (!site.entrance().existing()) {
            addEntranceEdits(edits, site.entrance());
        }
        for (StairRun stair : site.stairs()) {
            addStairEdits(edits, stair);
        }
        for (Decoration decoration : site.decorations()) {
            addDecorationEdits(edits, decoration);
        }
        return List.copyOf(edits);
    }

    private static void addEntranceEdits(List<Edit> edits, Entrance entrance) {
        Direction across = entrance.outward().getClockWise();
        for (int lane = 0; lane < 2; lane++) {
            BlockPos door = entrance.position().relative(across, lane);
            for (int depth = 0; depth < entrance.wallDepth(); depth++) {
                BlockPos slice = door.relative(entrance.outward(), depth);
                for (int y = 0; y < 3; y++) {
                    if (depth == 0 && y < 2) {
                        continue;
                    }
                    edits.add(new Edit(slice.above(y), Blocks.AIR.defaultBlockState(),
                            EditPolicy.SAFE_REPLACE));
                }
            }
            DoorHingeSide hinge = lane == 0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            BlockState lower = Blocks.COPPER_DOOR.waxed().weathered().defaultBlockState()
                    .setValue(DoorBlock.FACING, entrance.outward())
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE, hinge)
                    .setValue(DoorBlock.OPEN, true);
            BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            edits.add(new Edit(door, lower, EditPolicy.SAFE_REPLACE));
            edits.add(new Edit(door.above(), upper, EditPolicy.SAFE_REPLACE));
        }
    }

    private static void addStairEdits(List<Edit> edits, StairRun run) {
        Direction across = run.ascending().getClockWise();
        for (int step = 0; step < run.rise(); step++) {
            for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                BlockPos stair = run.start().relative(run.ascending(), step)
                        .relative(across, lane).above(step);
                edits.add(new Edit(stair.above(), Blocks.AIR.defaultBlockState(),
                        EditPolicy.SAFE_REPLACE));
                edits.add(new Edit(stair.above(2), Blocks.AIR.defaultBlockState(),
                        EditPolicy.SAFE_REPLACE));
                BlockState state = Blocks.POLISHED_DEEPSLATE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, run.ascending());
                edits.add(new Edit(stair, state, EditPolicy.SAFE_REPLACE));
            }
        }
    }

    private static void addDecorationEdits(List<Edit> edits, Decoration decoration) {
        Direction across = decoration.facing().getClockWise();
        Direction forward = decoration.facing().getOpposite();
        BlockPos second = decoration.position().relative(across);
        switch (decoration.kind()) {
            case RECEPTION_DESK -> {
                edits.add(new Edit(decoration.position(),
                        Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(second,
                        Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(),
                        EditPolicy.AIR_ONLY));
            }
            case PLANTER -> {
                edits.add(new Edit(decoration.position(), Blocks.MOSS_BLOCK.defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(decoration.position().above(),
                        Blocks.AZALEA_LEAVES.defaultBlockState(), EditPolicy.AIR_ONLY));
            }
            case CUBICLE_DESK -> {
                edits.add(new Edit(decoration.position(), Blocks.SMOOTH_QUARTZ.defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(second,
                        Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(second.above(),
                        Blocks.GLASS_PANE.defaultBlockState(), EditPolicy.AIR_ONLY));
            }
            case COUCH -> {
                BlockState couch = Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, decoration.facing());
                edits.add(new Edit(decoration.position(), couch, EditPolicy.AIR_ONLY));
                edits.add(new Edit(second, couch, EditPolicy.AIR_ONLY));
            }
            case ROOM_PARTITION -> {
                edits.add(new Edit(decoration.position(),
                        Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(decoration.position().above(),
                        Blocks.GLASS_PANE.defaultBlockState(), EditPolicy.AIR_ONLY));
            }
            case CUBICLE_POD -> {
                BlockPos back = decoration.position().relative(forward);
                BlockPos backSecond = back.relative(across);
                edits.add(new Edit(decoration.position(),
                        Blocks.SMOOTH_QUARTZ.defaultBlockState(), EditPolicy.AIR_ONLY));
                edits.add(new Edit(second,
                        Blocks.SMOOTH_QUARTZ.defaultBlockState(), EditPolicy.AIR_ONLY));
                edits.add(new Edit(back,
                        Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(backSecond,
                        Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(back.above(),
                        Blocks.GLASS_PANE.defaultBlockState(), EditPolicy.AIR_ONLY));
                edits.add(new Edit(backSecond.above(),
                        Blocks.GLASS_PANE.defaultBlockState(), EditPolicy.AIR_ONLY));
            }
            case CONFERENCE_TABLE -> {
                BlockState table = Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState();
                for (BlockPos position : decorationFootprint(
                        decoration.position(), decoration.kind(), across)) {
                    edits.add(new Edit(position, table, EditPolicy.AIR_ONLY));
                }
            }
            case SERVER_RACK -> {
                edits.add(new Edit(decoration.position(),
                        Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(decoration.position().above(),
                        Blocks.GLAZED_TERRACOTTA.pick(DyeColor.CYAN).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
            }
            case FILING_CABINET -> edits.add(new Edit(
                    decoration.position(), Blocks.IRON_BLOCK.defaultBlockState(),
                    EditPolicy.AIR_ONLY));
            case WATER_COOLER -> {
                edits.add(new Edit(decoration.position(),
                        Blocks.IRON_BLOCK.defaultBlockState(), EditPolicy.AIR_ONLY));
                edits.add(new Edit(decoration.position().above(),
                        Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
            }
            case EXPLOSIVE_CANISTER -> edits.add(new Edit(
                    decoration.position(), explosiveCanisterState(), EditPolicy.AIR_ONLY));
        }
    }

    private static BlockState explosiveCanisterState() {
        net.minecraft.world.level.block.Block canister = BuiltInRegistries.BLOCK.getValue(
                Identifier.fromNamespaceAndPath("cyberdeck", "explosive_canister"));
        return canister == null || canister == Blocks.AIR
                ? Blocks.GLAZED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState()
                : canister.defaultBlockState();
    }

    private static boolean routeCellsRemainClear(ServerLevel level, Site site) {
        Set<BlockPos> editedSolid = new HashSet<>();
        for (Edit edit : edits(site)) {
            if (!edit.state().isAir()) {
                editedSolid.add(edit.position());
            }
        }
        for (PatrolRoute route : site.patrolRoutes()) {
            for (BlockPos waypoint : route.waypoints()) {
                if (editedSolid.contains(waypoint) || editedSolid.contains(waypoint.above())) {
                    return false;
                }
                if (!isPassage(level, waypoint)) {
                    return false;
                }
            }
        }
        for (StairRun stair : site.stairs()) {
            for (BlockPos landing : stairLandingCells(stair)) {
                if (editedSolid.contains(landing)
                        || editedSolid.contains(landing.above())
                        || !isPassage(level, landing)) {
                    return false;
                }
            }
        }
        return !editedSolid.contains(site.target())
                && !editedSolid.contains(site.target().above())
                && isPassage(level, site.target());
    }

    private static boolean circulationRemainsAccessible(
            ServerLevel level, Site site, List<Edit> plannedEdits) {
        for (int floorY : site.floorYs()) {
            Set<BlockPos> reachable = reachableFloorCells(level, site, floorY, plannedEdits);
            if (reachable.isEmpty()) return false;
            LinkedHashSet<BlockPos> required = new LinkedHashSet<>();
            site.patrolRoute(floorY).ifPresent(route -> required.addAll(route.waypoints()));
            if (site.target().getY() == floorY) {
                Map<BlockPos, BlockState> overlay = new HashMap<>();
                for (Edit edit : plannedEdits) overlay.put(edit.position(), edit.state());
                BlockState targetState = plannedState(level, site.target(), overlay);
                boolean objectiveOccupiesTarget = targetState.is(
                                MissionBlocks.DELIVERY_TERMINAL.get())
                        || !isPassable(targetState)
                                && plannedState(level, site.target().above(), overlay)
                                        .is(MissionBlocks.DATA_TERMINAL.get());
                if (!objectiveOccupiesTarget) required.add(site.target());
                long approaches = java.util.Arrays.stream(HORIZONTAL)
                        .map(site.target()::relative)
                        .filter(reachable::contains)
                        .count();
                if (approaches < 1) return false;
            }
            BlockPos entranceInside = site.entrance().position()
                    .relative(site.entrance().outward().getOpposite());
            if (entranceInside.getY() == floorY) {
                required.add(entranceInside);
                if (!site.entrance().existing()) {
                    required.add(entranceInside.relative(
                            site.entrance().outward().getClockWise()));
                }
            }
            for (StairRun stair : site.stairs()) {
                for (BlockPos landing : stairLandingCells(stair)) {
                    if (landing.getY() == floorY) required.add(landing);
                }
            }
            if (!reachable.containsAll(required)) return false;
        }
        return true;
    }

    private static Set<BlockPos> reachableFloorCells(
            ServerLevel level, Site site, int floorY, List<Edit> plannedEdits) {
        Map<BlockPos, BlockState> overlay = new HashMap<>();
        for (Edit edit : plannedEdits) overlay.put(edit.position(), edit.state());
        BlockPos start = floorStart(site, floorY);
        if (start == null || !plannedPassage(level, start, overlay)) return Set.of();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (Direction direction : HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (next.getX() < site.bounds().minX() || next.getX() > site.bounds().maxX()
                        || next.getZ() < site.bounds().minZ()
                        || next.getZ() > site.bounds().maxZ()
                        || next.getY() != floorY || visited.contains(next)
                        || !plannedPassage(level, next, overlay)) {
                    continue;
                }
                visited.add(next);
                queue.addLast(next);
            }
        }
        return Set.copyOf(visited);
    }

    private static BlockPos floorStart(Site site, int floorY) {
        BlockPos entranceInside = site.entrance().position()
                .relative(site.entrance().outward().getOpposite());
        if (entranceInside.getY() == floorY) return entranceInside;
        for (StairRun stair : site.stairs()) {
            for (BlockPos landing : stairLandingCells(stair)) {
                if (landing.getY() == floorY) return landing;
            }
        }
        return site.patrolRoute(floorY).map(route -> route.waypoints().getFirst()).orElse(null);
    }

    private static boolean plannedPassage(
            ServerLevel level, BlockPos feet, Map<BlockPos, BlockState> overlay) {
        return isPassable(plannedState(level, feet, overlay))
                && isPassable(plannedState(level, feet.above(), overlay))
                && plannedState(level, feet.below(), overlay).blocksMotion();
    }

    private static BlockState plannedState(
            ServerLevel level, BlockPos position, Map<BlockPos, BlockState> overlay) {
        return overlay.getOrDefault(position, level.getBlockState(position));
    }

    private static boolean loadSiteChunks(ServerLevel level, BoundingBox bounds) {
        int minChunkX = Math.floorDiv(bounds.minX(), 16);
        int maxChunkX = Math.floorDiv(bounds.maxX(), 16);
        int minChunkZ = Math.floorDiv(bounds.minZ(), 16);
        int maxChunkZ = Math.floorDiv(bounds.maxZ(), 16);
        if ((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1) > 9) {
            return false;
        }
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                level.getChunk(chunkX, chunkZ);
                if (!NeonCityGenerator.isGenerated(new ChunkPos(chunkX, chunkZ))) {
                    NeonCityGenerator.generateNow(level, chunkX, chunkZ, 0);
                }
            }
        }
        return true;
    }

    private static boolean hasBlockEntity(ServerLevel level, BlockPos position) {
        return level.getBlockState(position).hasBlockEntity()
                || level.getBlockEntity(position) != null;
    }

    private static boolean isPassage(ServerLevel level, BlockPos feet) {
        return isPassable(level.getBlockState(feet))
                && isPassable(level.getBlockState(feet.above()))
                && level.getBlockState(feet.below()).blocksMotion();
    }

    private static boolean isPassable(BlockState state) {
        return state.isAir() || state.canBeReplaced()
                || state.getBlock() instanceof DoorBlock
                        && state.getValue(DoorBlock.OPEN);
    }

    private static boolean hasCeiling(ServerLevel level, BlockPos feet, int maxDistance) {
        for (int distance = 2; distance <= maxDistance; distance++) {
            if (level.getBlockState(feet.above(distance)).blocksMotion()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCarvable(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return !state.isAir() && isSafelyEditable(level, position);
    }

    private static boolean isSafelyEditable(ServerLevel level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return !hasBlockEntity(level, position)
                && state.getDestroySpeed(level, position) >= 0.0F
                && !state.is(Blocks.BEDROCK)
                && !state.is(Blocks.BARRIER)
                && !state.is(Blocks.END_PORTAL)
                && !state.is(Blocks.END_GATEWAY)
                && !state.is(Blocks.NETHER_PORTAL)
                && !state.is(Blocks.COMMAND_BLOCK)
                && !state.is(Blocks.CHAIN_COMMAND_BLOCK)
                && !state.is(Blocks.REPEATING_COMMAND_BLOCK);
    }

    private static List<BlockPos> decorationFootprint(
            BlockPos position, DecorKind kind, Direction across) {
        return switch (kind) {
            case PLANTER, ROOM_PARTITION, SERVER_RACK, FILING_CABINET,
                    WATER_COOLER, EXPLOSIVE_CANISTER -> List.of(position);
            case CUBICLE_POD, CONFERENCE_TABLE -> {
                Direction forward = across.getClockWise();
                yield List.of(
                        position,
                        position.relative(across),
                        position.relative(forward),
                        position.relative(forward).relative(across));
            }
            default -> List.of(position, position.relative(across));
        };
    }

    private static void reserve(Set<BlockPos> positions, BlockPos center, int radius) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (Math.abs(dx) + Math.abs(dz) <= radius) {
                    positions.add(center.offset(dx, 0, dz));
                }
            }
        }
    }

    private static List<BlockPos> ordered(Set<BlockPos> positions, long seed) {
        return positions.stream()
                .sorted(Comparator.comparingLong(
                                (BlockPos position) -> positionScore(
                                        seed, position.getX(), position.getZ()))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
    }

    private static List<Direction> orderedDirections(long seed, BlockPos position) {
        int offset = Math.floorMod(
                (int) positionScore(seed, position.getX(), position.getZ()), HORIZONTAL.length);
        List<Direction> result = new ArrayList<>(HORIZONTAL.length);
        for (int index = 0; index < HORIZONTAL.length; index++) {
            result.add(HORIZONTAL[(offset + index) % HORIZONTAL.length]);
        }
        return result;
    }

    private static long positionScore(long seed, int x, int z) {
        return MegacityLayout.mix(seed, x, z);
    }

    private static boolean overlaps(Rect first, Rect second) {
        int overlapX = Math.min(first.maxX, second.maxX) - Math.max(first.minX, second.minX) + 1;
        int overlapZ = Math.min(first.maxZ, second.maxZ) - Math.max(first.minZ, second.minZ) + 1;
        return overlapX >= 2 && overlapZ >= 2;
    }

    private static Rect rect(Set<BlockPos> positions) {
        int minX = positions.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = positions.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = positions.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = positions.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        return new Rect(minX, maxX, minZ, maxZ);
    }

    private static boolean contains(BoundingBox bounds, BlockPos position) {
        return position.getX() >= bounds.minX() && position.getX() <= bounds.maxX()
                && position.getY() >= bounds.minY() && position.getY() <= bounds.maxY()
                && position.getZ() >= bounds.minZ() && position.getZ() <= bounds.maxZ();
    }

    private static boolean topologyWithinBounds(
            BoundingBox bounds,
            List<Integer> floorYs,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            List<Decoration> decorations) {
        BlockPos entranceEnd = entrance.position().relative(
                entrance.outward(), Math.max(0, entrance.wallDepth() - 1));
        if (!contains(bounds, entrance.position())
                || !contains(bounds, entranceEnd.above(2))) {
            return false;
        }
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step < stair.rise(); step++) {
                for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                    BlockPos position = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step + 2);
                    if (!contains(bounds, position)) {
                        return false;
                    }
                }
            }
            for (BlockPos landing : stairLandingCells(stair)) {
                if (!contains(bounds, landing.above())) {
                    return false;
                }
            }
        }
        for (PatrolRoute route : routes) {
            if (!floorYs.contains(route.floorY())
                    || route.waypoints().stream().anyMatch(position -> !contains(bounds, position))) {
                return false;
            }
        }
        for (Decoration decoration : decorations) {
            Direction across = decoration.facing().getClockWise();
            for (BlockPos position : decorationFootprint(
                    decoration.position(), decoration.kind(), across)) {
                if (!contains(bounds, position)
                        || decorationUsesUpperBlock(decoration.kind())
                                && !contains(bounds, position.above())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean decorationUsesUpperBlock(DecorKind kind) {
        return switch (kind) {
            case PLANTER, CUBICLE_DESK, ROOM_PARTITION, CUBICLE_POD,
                    SERVER_RACK, WATER_COOLER -> true;
            default -> false;
        };
    }

    private static boolean stairFloorsMatch(
            List<Integer> floorYs, List<StairRun> stairs) {
        for (int index = 0; index < stairs.size(); index++) {
            StairRun stair = stairs.get(index);
            if (stair.start().getY() != floorYs.get(index)
                    || stair.rise() != floorYs.get(index + 1) - floorYs.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static BoundingBox copy(BoundingBox bounds) {
        return new BoundingBox(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions, int maximum) {
        if (positions == null) {
            return List.of();
        }
        return positions.stream()
                .filter(java.util.Objects::nonNull)
                .limit(maximum)
                .map(BlockPos::immutable)
                .toList();
    }

    private static void putBounds(CompoundTag tag, BoundingBox bounds) {
        tag.putInt("MinX", bounds.minX());
        tag.putInt("MinY", bounds.minY());
        tag.putInt("MinZ", bounds.minZ());
        tag.putInt("MaxX", bounds.maxX());
        tag.putInt("MaxY", bounds.maxY());
        tag.putInt("MaxZ", bounds.maxZ());
    }

    private static BoundingBox readBounds(CompoundTag tag) {
        return new BoundingBox(
                tag.getIntOr("MinX", 0), tag.getIntOr("MinY", 0), tag.getIntOr("MinZ", 0),
                tag.getIntOr("MaxX", -1), tag.getIntOr("MaxY", -1), tag.getIntOr("MaxZ", -1));
    }

    private static void putPos(CompoundTag tag, String prefix, BlockPos position) {
        tag.putInt(prefix + "X", position.getX());
        tag.putInt(prefix + "Y", position.getY());
        tag.putInt(prefix + "Z", position.getZ());
    }

    private static BlockPos readPos(CompoundTag tag, String prefix) {
        return new BlockPos(
                tag.getIntOr(prefix + "X", 0),
                tag.getIntOr(prefix + "Y", 0),
                tag.getIntOr(prefix + "Z", 0));
    }

    private static String encodeIntegers(List<Integer> values) {
        return values.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Integer> decodeIntegers(String value, int maximum) {
        List<Integer> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            if (result.size() >= maximum) break;
            result.add(Integer.parseInt(part));
        }
        return List.copyOf(result);
    }

    private static String encodeStairs(List<StairRun> stairs) {
        return stairs.stream().map(stair -> encodePos(stair.start()) + ","
                        + stair.ascending().ordinal() + "," + stair.rise())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static List<StairRun> decodeStairs(String encoded) {
        List<StairRun> result = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",", -1);
            if (fields.length != 5 || result.size() >= MAX_FLOORS - 1) continue;
            Direction direction = Direction.values()[Integer.parseInt(fields[3])];
            result.add(new StairRun(
                    new BlockPos(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2])),
                    direction,
                    Integer.parseInt(fields[4])));
        }
        return List.copyOf(result);
    }

    private static String encodePatrols(List<PatrolRoute> patrols) {
        return patrols.stream().map(route -> route.floorY() + "@" + route.waypoints().stream()
                        .map(MissionBuildingPlanner::encodePos)
                        .collect(java.util.stream.Collectors.joining("|")))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static List<PatrolRoute> decodePatrols(String encoded) {
        List<PatrolRoute> result = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String entry : encoded.split(";")) {
            String[] parts = entry.split("@", 2);
            if (parts.length != 2 || result.size() >= MAX_FLOORS) continue;
            List<BlockPos> points = new ArrayList<>();
            for (String point : parts[1].split("\\|")) {
                if (!point.isBlank() && points.size() < MAX_ROUTE_POINTS) {
                    points.add(decodePos(point));
                }
            }
            result.add(new PatrolRoute(Integer.parseInt(parts[0]), points));
        }
        return List.copyOf(result);
    }

    private static String encodeDecorations(List<Decoration> decorations) {
        return decorations.stream().map(decoration -> encodePos(decoration.position()) + ","
                        + decoration.kind().ordinal() + "," + decoration.facing().ordinal())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static List<Decoration> decodeDecorations(String encoded) {
        List<Decoration> result = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) return result;
        for (String entry : encoded.split(";")) {
            String[] fields = entry.split(",", -1);
            if (fields.length != 5 || result.size() >= MAX_DECORATIONS) continue;
            result.add(new Decoration(
                    new BlockPos(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2])),
                    DecorKind.values()[Integer.parseInt(fields[3])],
                    Direction.values()[Integer.parseInt(fields[4])]));
        }
        return List.copyOf(result);
    }

    private static String encodePos(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static BlockPos decodePos(String encoded) {
        String[] fields = encoded.split(",", -1);
        if (fields.length != 3) throw new IllegalArgumentException("invalid block position");
        return new BlockPos(
                Integer.parseInt(fields[0]),
                Integer.parseInt(fields[1]),
                Integer.parseInt(fields[2]));
    }

    private enum EditPolicy {
        AIR_ONLY {
            @Override
            boolean accepts(ServerLevel level, BlockPos position, BlockState current) {
                return !hasBlockEntity(level, position)
                        && (current.isAir() || current.canBeReplaced());
            }
        },
        SAFE_REPLACE {
            @Override
            boolean accepts(ServerLevel level, BlockPos position, BlockState current) {
                return isSafelyEditable(level, position);
            }
        };

        abstract boolean accepts(ServerLevel level, BlockPos position, BlockState current);
    }

    private record Edit(BlockPos position, BlockState state, EditPolicy policy) {
        private Edit {
            position = position.immutable();
        }

        boolean matches(BlockState current) {
            if (state.isAir()) return current.isAir();
            if (state.getBlock() instanceof DoorBlock) {
                return current.getBlock() == state.getBlock()
                        && current.getValue(DoorBlock.HALF) == state.getValue(DoorBlock.HALF)
                        && current.getValue(DoorBlock.FACING) == state.getValue(DoorBlock.FACING)
                        && current.getValue(DoorBlock.HINGE) == state.getValue(DoorBlock.HINGE);
            }
            return current.equals(state);
        }
    }

    private record OriginalState(BlockPos position, BlockState state) {
    }

    private record Rect(int minX, int maxX, int minZ, int maxZ) {
        int width() { return maxX - minX + 1; }
        int depth() { return maxZ - minZ + 1; }
    }

    private record FloorProfile(int y, Set<BlockPos> cells, Rect bounds) {
    }

    private record ChunkCandidate(int chunkX, int chunkZ, int distance, long score) {
    }

    private record StairCandidate(StairRun run, int edits, long score) {
    }
}
