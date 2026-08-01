package dev.modernity.neoncity;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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
    private static final int MIN_FLOOR_CELLS = 12;
    private static final int MIN_STORY_HEIGHT = 4;
    private static final int MAX_STORY_HEIGHT = 9;
    private static final int MAX_FLOORS = 4;
    private static final int MAX_ROUTE_POINTS = 8;
    private static final int MAX_SITE_VOLUME = 40_000;
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
        COUCH
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
            if (floorYs.size() < 2 || floorYs.size() > MAX_FLOORS) {
                throw new IllegalArgumentException("mission site must contain two to four floors");
            }
            target = target.immutable();
            stairs = stairs == null ? List.of() : List.copyOf(stairs);
            patrolRoutes = patrolRoutes == null ? List.of() : List.copyOf(patrolRoutes);
            decorations = decorations == null ? List.of() : List.copyOf(decorations);
            if (stairs.size() != floorYs.size() - 1
                    || patrolRoutes.size() != floorYs.size()
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
     * Generates and inspects at most 24 deterministic Arnis candidates near {@code origin}.
     * Returning empty is expected when nearby imported geometry cannot be modified conservatively.
     */
    public static Optional<Site> findSite(
            ServerLevel level,
            District district,
            BlockPos origin,
            int searchRadiusChunks,
            long selectionSalt) {
        if (level == null || district == null || origin == null
                || !NeonCityGenerator.isMegacityWorld(level)) {
            return Optional.empty();
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
        for (ChunkCandidate candidate : candidates) {
            if (attempts++ >= MAX_PROFILE_ATTEMPTS) {
                break;
            }
            NeonCityGenerator.generateNow(level, candidate.chunkX(), candidate.chunkZ(), 1);
            if (!NeonCityGenerator.isUsableArnisChunk(
                    level, candidate.chunkX() << 4, candidate.chunkZ() << 4)) {
                continue;
            }
            Optional<Site> site = profileChunk(
                    level, district, candidate.chunkX(), candidate.chunkZ(), candidate.score());
            if (site.isPresent()) {
                return site;
            }
        }
        return Optional.empty();
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
        return profileChunk(level, district, chunkX, chunkZ, planSeed);
    }

    /** Revalidates all touched blocks without modifying the world. */
    public static boolean preflight(ServerLevel level, Site site) {
        if (level == null || site == null || !loadSiteChunks(level, site.bounds())) {
            return false;
        }
        if (containsBlockEntity(level, site.bounds())) {
            return false;
        }
        for (Edit edit : edits(site)) {
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
        return routeCellsRemainClear(level, site);
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
        return changed ? InstallationResult.INSTALLED : InstallationResult.ALREADY_INSTALLED;
    }

    private static Optional<Site> profileChunk(
            ServerLevel level, District district, int chunkX, int chunkZ, long planSeed) {
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
        if (maxY - minY < MIN_STORY_HEIGHT + 2) {
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
        List<FloorProfile> floors = bestFloorStack(profiles, planSeed);
        if (floors.size() < 2) {
            return Optional.empty();
        }

        Entrance entrance = findEntrance(level, floors.getFirst(), planSeed);
        if (entrance == null) {
            return Optional.empty();
        }
        List<StairRun> stairs = new ArrayList<>();
        for (int index = 1; index < floors.size(); index++) {
            StairRun run = findStairRun(level, floors.get(index - 1), floors.get(index),
                    planSeed + index * 0x9E3779B97F4A7C15L);
            if (run == null) {
                return Optional.empty();
            }
            stairs.add(run);
        }

        BoundingBox bounds = siteBounds(floors, entrance);
        if ((long) bounds.getXSpan() * bounds.getYSpan() * bounds.getZSpan() > MAX_SITE_VOLUME
                || containsBlockEntity(level, bounds)) {
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
        BlockPos target = chooseTarget(floors.getLast(), entrance, planSeed);
        List<Decoration> decorations = planDecorations(
                floors, entrance, stairs, routes, target, planSeed);
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
            if (bounds.width() >= 3 && bounds.depth() >= 3) {
                result.add(new FloorProfile(y, Set.copyOf(component), bounds));
            }
        }
        return result;
    }

    private static List<FloorProfile> bestFloorStack(List<FloorProfile> profiles, long seed) {
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
            if (stack.size() >= 2 && score > bestScore) {
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
        int minX = floor.bounds().minX - 8;
        int maxX = floor.bounds().maxX + 8;
        int minZ = floor.bounds().minZ - 8;
        int maxZ = floor.bounds().maxZ + 8;
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(first);
        queue.add(first);
        while (!queue.isEmpty() && visited.size() <= 512) {
            BlockPos current = queue.removeFirst();
            if (!hasCeiling(level, current, 8) || level.canSeeSky(current)) {
                return true;
            }
            for (Direction direction : HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (next.getX() < minX || next.getX() > maxX
                        || next.getZ() < minZ || next.getZ() > maxZ
                        || floor.cells().contains(next)
                        || !visited.add(next)) {
                    continue;
                }
                if (isPassage(level, next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
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
                        && (!hasCeiling(level, outside, 8) || level.canSeeSky(outside));
            }
            if (validWall) {
                return depth;
            }
        }
        return 0;
    }

    private static StairRun findStairRun(
            ServerLevel level, FloorProfile lower, FloorProfile upper, long seed) {
        int rise = upper.y() - lower.y();
        List<StairCandidate> candidates = new ArrayList<>();
        for (BlockPos start : lower.cells()) {
            for (Direction direction : HORIZONTAL) {
                Direction across = direction.getClockWise();
                if (!lower.cells().contains(start.relative(across))) {
                    continue;
                }
                BlockPos upperLanding = start.relative(direction, rise).atY(upper.y());
                if (!upper.cells().contains(upperLanding)
                        || !upper.cells().contains(upperLanding.relative(across))) {
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
                .min(Comparator.comparingInt(StairCandidate::edits)
                        .thenComparingLong(StairCandidate::score))
                .map(StairCandidate::run)
                .orElse(null);
    }

    private static int stairEditCost(
            ServerLevel level, BlockPos start, Direction direction, int rise) {
        Direction across = direction.getClockWise();
        int edits = 0;
        for (int step = 0; step < rise; step++) {
            for (int lane = 0; lane < 2; lane++) {
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

    private static PatrolRoute patrolRoute(
            FloorProfile floor, long seed, Set<BlockPos> exclusions) {
        List<BlockPos> ordered = ordered(floor.cells().stream()
                .filter(position -> !exclusions.contains(position))
                .collect(java.util.stream.Collectors.toSet()), seed);
        if (ordered.size() < 2) {
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
                for (int lane = 0; lane < 2; lane++) {
                    BlockPos position = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step);
                    reserve(excluded, position, 1);
                }
            }
        }
        return excluded;
    }

    private static BlockPos chooseTarget(FloorProfile top, Entrance entrance, long seed) {
        return top.cells().stream()
                .max(Comparator.comparingDouble(
                                (BlockPos position) -> position.distSqr(entrance.position()))
                        .thenComparingLong(position -> positionScore(
                                seed, position.getX(), position.getZ())))
                .orElseThrow();
    }

    private static List<Decoration> planDecorations(
            List<FloorProfile> floors,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            long seed) {
        Set<BlockPos> reserved = new HashSet<>();
        reserve(reserved, target, 2);
        reserve(reserved, entrance.position(), 2);
        for (PatrolRoute route : routes) {
            for (BlockPos waypoint : route.waypoints()) {
                reserve(reserved, waypoint, 1);
            }
        }
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step <= stair.rise(); step++) {
                for (int lane = 0; lane < 2; lane++) {
                    reserve(reserved, stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step), 1);
                }
            }
        }

        List<Decoration> result = new ArrayList<>();
        Set<BlockPos> occupied = new HashSet<>(reserved);
        Set<BlockPos> blockedCells = new HashSet<>();
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            FloorProfile floor = floors.get(floorIndex);
            int wanted = floorIndex == 0 ? 3 : 4;
            List<BlockPos> candidates = ordered(
                    floor.cells(), seed ^ (long) floor.y() * 0xD1B54A32D192ED03L);
            for (BlockPos candidate : candidates) {
                if (result.stream().filter(value -> value.position().getY() == floor.y()).count()
                        >= wanted) {
                    break;
                }
                Direction facing = HORIZONTAL[Math.floorMod(
                        (int) positionScore(seed, candidate.getX(), candidate.getZ()),
                        HORIZONTAL.length)];
                Direction across = facing.getClockWise();
                DecorKind kind = floorIndex == 0
                        ? result.stream().noneMatch(value -> value.position().getY() == floor.y())
                                ? DecorKind.RECEPTION_DESK : DecorKind.PLANTER
                        : (result.size() & 1) == 0
                                ? DecorKind.CUBICLE_DESK : DecorKind.COUCH;
                List<BlockPos> footprint = decorationFootprint(candidate, kind, across);
                if (footprint.stream().anyMatch(occupied::contains)
                        || footprint.stream().anyMatch(position -> !floor.cells().contains(position))
                        || !preservesFloorConnectivity(
                                floor, footprint, blockedCells, entrance, stairs, routes, target)) {
                    continue;
                }
                result.add(new Decoration(candidate, kind, facing));
                blockedCells.addAll(footprint);
                for (BlockPos position : footprint) {
                    reserve(occupied, position, 1);
                }
            }
        }
        return List.copyOf(result);
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
            Direction across = stair.ascending().getClockWise();
            if (stair.start().getY() == floor.y()) {
                required.add(stair.start());
                required.add(stair.start().relative(across));
            }
            int topY = stair.start().getY() + stair.rise();
            if (topY == floor.y()) {
                BlockPos landing = stair.start().relative(stair.ascending(), stair.rise())
                        .above(stair.rise());
                required.add(landing);
                required.add(landing.relative(across));
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
            BlockState lower = Blocks.IRON_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, entrance.outward())
                    .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE, hinge);
            BlockState upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            edits.add(new Edit(door, lower, EditPolicy.SAFE_REPLACE));
            edits.add(new Edit(door.above(), upper, EditPolicy.SAFE_REPLACE));
        }
    }

    private static void addStairEdits(List<Edit> edits, StairRun run) {
        Direction across = run.ascending().getClockWise();
        for (int step = 0; step < run.rise(); step++) {
            for (int lane = 0; lane < 2; lane++) {
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
            }
            case COUCH -> {
                BlockState couch = Blocks.POLISHED_BLACKSTONE_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, decoration.facing());
                edits.add(new Edit(decoration.position(), couch, EditPolicy.AIR_ONLY));
                edits.add(new Edit(second, couch, EditPolicy.AIR_ONLY));
            }
        }
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
        return !editedSolid.contains(site.target())
                && !editedSolid.contains(site.target().above())
                && isPassage(level, site.target());
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

    private static boolean containsBlockEntity(ServerLevel level, BoundingBox bounds) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    cursor.set(x, y, z);
                    if (hasBlockEntity(level, cursor)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        return state.isAir() || state.canBeReplaced() || state.getBlock() instanceof DoorBlock;
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
        return kind == DecorKind.PLANTER
                ? List.of(position)
                : List.of(position, position.relative(across));
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
                for (int lane = 0; lane < 2; lane++) {
                    BlockPos position = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step + 2);
                    if (!contains(bounds, position)) {
                        return false;
                    }
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
                        || decoration.kind() == DecorKind.PLANTER
                                && !contains(bounds, position.above())) {
                    return false;
                }
            }
        }
        return true;
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
            if (fields.length != 5 || result.size() >= 32) continue;
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
