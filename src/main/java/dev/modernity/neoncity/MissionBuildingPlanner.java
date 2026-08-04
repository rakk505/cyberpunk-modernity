package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * Bounded live-world planner for mission interiors in imported Arnis buildings.
 *
 * <p>The Arnis catalog is tile based and has no room metadata. This planner first identifies
 * connected, enclosed structural volume, then synthesizes the entrance, bounded mission floors,
 * stairs, patrols, and furnishings. Generated features are validated before a site is advertised;
 * they are not prerequisites the imported building must already contain.</p>
 */
public final class MissionBuildingPlanner {
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final long SITE_SALT = 0x4D495353494F4E42L;
    private static final int MAX_SEARCH_RADIUS_CHUNKS = 16;
    private static final int MAX_PROFILE_ATTEMPTS = 16;
    private static final int MAX_ATLAS_REGION_RADIUS_CHUNKS = 8;
    private static final int MAX_ATLAS_BUILDING_LABELS = 256;
    private static final int MAX_ATLAS_SITES = 24;
    private static final int MAX_ATLAS_FLOORS = 32;
    private static final int MAX_ATLAS_PATHS = 128;
    private static final int MAX_ATLAS_BRANCHES = 4;
    private static final int MAX_ATLAS_PLAN_VARIANTS = 4;
    private static final int MAX_ENTRANCE_CANDIDATES = 24;
    private static final int MAX_ENTRANCE_WALL_DEPTH = 8;
    private static final int SCAN_MARGIN = 2;
    private static final int MAX_SCAN_HEIGHT = 72;
    private static final int MIN_FLOOR_CELLS = 64;
    private static final int MIN_FLOOR_SIDE = 7;
    private static final int MIN_PATROL_CELLS = 24;
    private static final int MIN_STORY_HEIGHT = 4;
    private static final int MAX_STORY_HEIGHT = 9;
    private static final int MAX_FLOORS = 5;
    private static final int MAX_ROUTE_POINTS = 8;
    private static final int STAIR_WIDTH = 2;
    private static final int STAIR_LANDING_DEPTH = 2;
    private static final int STAIR_HEADROOM = 3;
    private static final int MIN_STAIR_HORIZONTAL_GAP = 3;
    private static final int MAX_STAIR_CANDIDATES = 64;
    private static final int MAX_SITE_VOLUME = 40_000;
    private static final int MIN_THEMED_FLOOR_CELLS = 80;
    private static final int FULL_THEME_FLOOR_CELLS = 120;
    private static final int MAX_EXTERIOR_PATH_NODES = 4_096;
    private static final int EXTERIOR_SEARCH_MARGIN = 40;
    private static final int MAX_EXISTING_ENTRANCE_APPROACH_DISTANCE = 12;
    private static final int CORRIDOR_CLEARANCE = 1;
    private static final int MAX_EXPLOSIVE_CANISTERS_PER_SITE = 2;
    private static final int MAX_PARTITION_COLUMNS_PER_FLOOR = 12;
    private static final int MAX_BOUNDARY_PARTITION_COLUMNS_PER_FLOOR = 8;
    private static final int MAX_INTERNAL_PARTITION_COLUMNS_PER_FLOOR = 4;
    private static final int MAX_DECORATIONS = 1_024;
    private static final int MAX_MISSION_FLOOR_CELLS = 144;
    private static final int MAX_MISSION_WINDOW_CANDIDATES = 32;
    private static final int MAX_ENTRANCE_TO_MISSION_DISTANCE = 20;
    private static final int MAX_INTERIOR_PLAN_VARIANTS = 6;
    private static final int MASKED_SITE_VERSION = 2;
    private static final int CURRENT_SITE_VERSION = 3;
    private static final int LEGACY_SITE_VERSION = 1;
    private static final int MAX_RESTORATION_BLOCKS = 8_192;
    private static final int MAX_MISSION_TURRETS_PER_SITE = 2;
    private static final int TURRET_CLEARANCE_RADIUS = 1;
    private static final int TURRET_HEADROOM = 3;
    private static final int TURRET_FIRE_DISTANCE = 8;
    private static final int MIN_TURRET_FORWARD_ARC = 4;
    private static final int MIN_TURRET_TOTAL_ARC = 8;
    private static final long TURRET_SALT = 0x545552524554534CL;
    private static final long INTERIOR_SALT = 0x494E544552494F52L;
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
        EXPLOSIVE_CANISTER,
        // Appended because decoration plans persist enum ordinals.
        MISSION_TURRET,
        VENDING_MACHINE,
        COMPUTER_DESK,
        FULL_HEIGHT_PARTITION
    }

    private enum FloorTheme {
        LOBBY,
        OPEN_OFFICE,
        OPERATIONS,
        LOUNGE,
        STORAGE,
        EXECUTIVE
    }

    public record Entrance(BlockPos position, Direction outward, int wallDepth, boolean existing) {
        public Entrance {
            position = position.immutable();
            if (outward == null || outward.getAxis().isVertical()) {
                throw new IllegalArgumentException("mission entrance must face horizontally");
            }
            if (wallDepth < 0 || wallDepth > MAX_ENTRANCE_WALL_DEPTH) {
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

    /** Exact connected cells owned by one mission floor. */
    public record FloorMask(int floorY, List<BlockPos> cells) {
        public FloorMask {
            if (cells == null || cells.isEmpty()
                    || cells.size() > MAX_MISSION_FLOOR_CELLS) {
                throw new IllegalArgumentException("invalid mission floor mask size");
            }
            ArrayList<BlockPos> ordered = new ArrayList<>(cells.size());
            HashSet<BlockPos> distinct = new HashSet<>();
            for (BlockPos cell : cells) {
                if (cell == null || cell.getY() != floorY || !distinct.add(cell.immutable())) {
                    throw new IllegalArgumentException("invalid mission floor mask cell");
                }
                ordered.add(cell.immutable());
            }
            ordered.sort(Comparator.comparingInt((BlockPos position) -> position.getX())
                    .thenComparingInt(position -> position.getZ()));
            if (connectedComponent(distinct, ordered.getFirst()).size() != distinct.size()) {
                throw new IllegalArgumentException("mission floor mask is disconnected");
            }
            cells = List.copyOf(ordered);
        }

        boolean contains(BlockPos position) {
            return position != null && position.getY() == floorY && cells.contains(position);
        }
    }

    public record BlockSnapshot(BlockPos position, BlockState state) {
        public BlockSnapshot {
            if (position == null || state == null) {
                throw new IllegalArgumentException("incomplete mission block snapshot");
            }
            position = position.immutable();
        }
    }

    /** Original live-world blocks needed to remove a generated mission interior exactly. */
    public record RestorationSnapshot(List<BlockSnapshot> blocks) {
        public RestorationSnapshot {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            if (blocks.isEmpty() || blocks.size() > MAX_RESTORATION_BLOCKS
                    || blocks.stream().map(BlockSnapshot::position).distinct().count()
                            != blocks.size()) {
                throw new IllegalArgumentException("invalid mission restoration snapshot");
            }
        }

        public CompoundTag save(ServerLevel level) {
            if (level == null) {
                throw new IllegalArgumentException("mission snapshot needs registry access");
            }
            var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            CompoundTag tag = new CompoundTag();
            tag.putInt("Version", 1);
            ListTag encoded = new ListTag();
            for (BlockSnapshot block : blocks) {
                CompoundTag entry = new CompoundTag();
                putPos(entry, "Pos", block.position());
                entry.store("State", BlockState.CODEC, ops, block.state());
                encoded.add(entry);
            }
            tag.put("Blocks", encoded);
            return tag;
        }
    }

    record DfsAudit(boolean accessible, int visitedCells, List<BlockPos> unreachable) {
        DfsAudit {
            unreachable = unreachable == null ? List.of() : immutablePositions(
                    unreachable, MAX_ROUTE_POINTS * MAX_FLOORS + 4);
        }
    }

    /** One segmented multi-chunk building stack, including rejected planning diagnostics. */
    public record BuildingLabel(
            String id,
            String siteId,
            BoundingBox bounds,
            List<Integer> floorYs,
            List<Integer> walkableCellsPerFloor,
            boolean missionReady,
            String decision) {
        public BuildingLabel {
            bounds = copy(bounds);
            siteId = siteId == null ? "" : siteId;
            floorYs = List.copyOf(floorYs);
            walkableCellsPerFloor = List.copyOf(walkableCellsPerFloor);
            decision = decision == null ? "" : decision;
        }
    }

    /** Result of compiling one generated Arnis region into building labels and mission sites. */
    public record AtlasScan(
            District district,
            BoundingBox scanBounds,
            List<BuildingLabel> buildings,
            List<Site> sites,
            int walkableCellCount) {
        public AtlasScan {
            scanBounds = copy(scanBounds);
            buildings = List.copyOf(buildings);
            sites = List.copyOf(sites);
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
            List<FloorMask> floorMasks,
            long planSeed,
            String buildingId,
            BoundingBox buildingBounds) {
        public Site(
                String id,
                District district,
                BoundingBox bounds,
                List<Integer> floorYs,
                BlockPos target,
                Entrance entrance,
                List<StairRun> stairs,
                List<PatrolRoute> patrolRoutes,
                List<Decoration> decorations,
                List<FloorMask> floorMasks,
                long planSeed) {
            this(id, district, bounds, floorYs, target, entrance, stairs, patrolRoutes,
                    decorations, floorMasks, planSeed, id, bounds);
        }

        /** Compatibility constructor for legacy callers; maskless sites cannot be reinstalled. */
        public Site(
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
            this(id, district, bounds, floorYs, target, entrance, stairs, patrolRoutes,
                    decorations, List.of(), planSeed);
        }

        public Site {
            if (id == null || id.isBlank() || district == null || bounds == null
                    || target == null || entrance == null) {
                throw new IllegalArgumentException("incomplete mission building site");
            }
            bounds = copy(bounds);
            floorYs = floorYs == null ? List.of() : floorYs.stream().distinct().sorted().toList();
            if (floorYs.isEmpty() || floorYs.size() > MAX_FLOORS) {
                throw new IllegalArgumentException("mission site must contain one to five floors");
            }
            target = target.immutable();
            stairs = stairs == null ? List.of() : List.copyOf(stairs);
            patrolRoutes = patrolRoutes == null ? List.of() : List.copyOf(patrolRoutes);
            decorations = decorations == null ? List.of() : List.copyOf(decorations);
            floorMasks = floorMasks == null ? List.of() : List.copyOf(floorMasks);
            buildingId = buildingId == null || buildingId.isBlank() ? id : buildingId;
            buildingBounds = buildingBounds == null ? bounds : copy(buildingBounds);
            BoundingBox completeBuildingBounds = buildingBounds;
            if (stairs.size() != floorYs.size() - 1
                    || patrolRoutes.size() != floorYs.size()
                    || decorations.size() > MAX_DECORATIONS
                    || !stairFloorsMatch(floorYs, stairs)
                    || !patrolRoutes.stream().map(PatrolRoute::floorY)
                            .collect(java.util.stream.Collectors.toSet())
                            .equals(Set.copyOf(floorYs))
                    || !contains(bounds, target)
                    || buildingBounds.getXSpan() <= 0
                    || buildingBounds.getZSpan() <= 0
                    || buildingBounds.maxX() < bounds.minX()
                    || buildingBounds.minX() > bounds.maxX()
                    || buildingBounds.maxZ() < bounds.minZ()
                    || buildingBounds.minZ() > bounds.maxZ()
                    || !floorMasks.isEmpty() && floorMasks.stream()
                            .flatMap(mask -> mask.cells().stream())
                            .anyMatch(cell -> !contains(completeBuildingBounds, cell))
                    || !topologyWithinBounds(
                            bounds, floorYs, entrance, stairs, patrolRoutes, decorations)
                    || !floorMasks.isEmpty() && !validFloorMasks(
                            bounds, floorYs, target, entrance, stairs, patrolRoutes, floorMasks)) {
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

        /** Immutable cells available for actors, circulation, and objective approaches. */
        public Set<BlockPos> missionCells(int floorY) {
            return floorMasks.stream().filter(mask -> mask.floorY() == floorY)
                    .findFirst().map(mask -> Set.copyOf(mask.cells())).orElse(Set.of());
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Version", floorMasks.isEmpty()
                    ? LEGACY_SITE_VERSION : CURRENT_SITE_VERSION);
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
            if (!floorMasks.isEmpty()) {
                tag.putString("FloorMasks", encodeFloorMasks(floorMasks));
                tag.putString("BuildingId", buildingId);
                putBounds(tag, "Building", buildingBounds);
            }
            tag.putLong("PlanSeed", planSeed);
            return tag;
        }

        public static Optional<Site> load(CompoundTag tag) {
            try {
                int version = tag == null ? 0 : tag.getIntOr("Version", 0);
                if (version != LEGACY_SITE_VERSION
                        && version != MASKED_SITE_VERSION
                        && version != CURRENT_SITE_VERSION) {
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
                List<FloorMask> floorMasks = version >= MASKED_SITE_VERSION
                        ? decodeFloorMasks(tag.getStringOr("FloorMasks", ""))
                        : List.of();
                if (version >= MASKED_SITE_VERSION && floorMasks.isEmpty()) {
                    return Optional.empty();
                }
                BoundingBox siteBounds = readBounds(tag);
                String siteId = tag.getStringOr("Id", "");
                return Optional.of(new Site(
                        siteId,
                        districts[districtOrdinal],
                        siteBounds,
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
                        floorMasks,
                        tag.getLongOr("PlanSeed", 0L),
                        version >= CURRENT_SITE_VERSION
                                ? tag.getStringOr("BuildingId", siteId) : siteId,
                        version >= CURRENT_SITE_VERSION
                                ? readBounds(tag, "Building") : siteBounds));
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
        return findSite(
                level, district, origin, searchRadiusChunks, selectionSalt,
                minimumFloors, MAX_FLOORS, siteFilter);
    }

    static Optional<Site> findSite(
            ServerLevel level,
            District district,
            BlockPos origin,
            int searchRadiusChunks,
            long selectionSalt,
            int minimumFloors,
            int maximumFloors,
            Predicate<Site> siteFilter) {
        if (level == null || district == null || origin == null
                || siteFilter == null || !NeonCityGenerator.isMegacityWorld(level)) {
            return Optional.empty();
        }
        if (minimumFloors < 1 || minimumFloors > MAX_FLOORS) {
            throw new IllegalArgumentException("invalid minimum mission floor count");
        }
        if (maximumFloors < minimumFloors || maximumFloors > MAX_FLOORS) {
            throw new IllegalArgumentException("invalid maximum mission floor count");
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
        PlannedSiteCandidate best = null;
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
                    minimumFloors, maximumFloors);
            if (site.isPresent() && siteFilter.test(site.get())) {
                PlannedSiteCandidate planned = new PlannedSiteCandidate(
                        site.get(), candidate.distance(), candidate.score());
                if (best == null || compareSiteCandidates(level, planned, best) < 0) {
                    best = planned;
                }
            }
        }
        return Optional.ofNullable(best).map(PlannedSiteCandidate::site);
    }

    private static int compareSiteCandidates(
            ServerLevel level, PlannedSiteCandidate first, PlannedSiteCandidate second) {
        int groundY = NeonCityGenerator.CITY_GROUND_Y + 1;
        int compared = Integer.compare(
                Math.abs(first.site().entrance().position().getY() - groundY),
                Math.abs(second.site().entrance().position().getY() - groundY));
        if (compared != 0) return compared;
        compared = Integer.compare(
                entranceClarity(level, first.site().entrance()),
                entranceClarity(level, second.site().entrance()));
        if (compared != 0) return compared;
        compared = Integer.compare(
                second.site().floorYs().size(), first.site().floorYs().size());
        if (compared != 0) return compared;
        compared = Integer.compare(first.distance(), second.distance());
        if (compared != 0) return compared;
        return Long.compare(first.score(), second.score());
    }

    private static int entranceClarity(ServerLevel level, Entrance entrance) {
        return !entrance.existing()
                || level.getBlockState(entrance.position()).getBlock() instanceof DoorBlock
                ? 0 : 1;
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

    /**
     * Segments connected indoor floors across a generated Arnis chunk neighbourhood, labels
     * vertical building stacks, and compiles every stack that passes normal mission preflight.
     */
    public static AtlasScan scanArnisRegion(
            ServerLevel level,
            District district,
            int centerChunkX,
            int centerChunkZ,
            int radiusChunks,
            long planSeed,
            int minimumFloors,
            int maximumFloors) {
        if (level == null || district == null || !NeonCityGenerator.isMegacityWorld(level)) {
            throw new IllegalArgumentException("Arnis building scan needs a megacity level");
        }
        if (minimumFloors < 1 || minimumFloors > MAX_FLOORS
                || maximumFloors < minimumFloors || maximumFloors > MAX_FLOORS) {
            throw new IllegalArgumentException("invalid Arnis building floor range");
        }
        int radius = Math.max(0, Math.min(MAX_ATLAS_REGION_RADIUS_CHUNKS, radiusChunks));
        int minChunkX = centerChunkX - radius;
        int maxChunkX = centerChunkX + radius;
        int minChunkZ = centerChunkZ - radius;
        int maxChunkZ = centerChunkZ + radius;
        int minX = (minChunkX << 4) - SCAN_MARGIN;
        int maxX = (maxChunkX << 4) + 15 + SCAN_MARGIN;
        int minZ = (minChunkZ << 4) - SCAN_MARGIN;
        int maxZ = (maxChunkZ << 4) + 15 + SCAN_MARGIN;
        int contentMinX = minChunkX << 4;
        int contentMaxX = (maxChunkX << 4) + 15;
        int contentMinZ = minChunkZ << 4;
        int contentMaxZ = (maxChunkZ << 4) + 15;
        int minY = NeonCityGenerator.CITY_GROUND_Y + 1;
        int maxY = Math.min(level.getMaxY() - 2, minY + MAX_SCAN_HEIGHT - 1);
        BoundingBox scanBounds = new BoundingBox(minX, minY - 1, minZ, maxX, maxY + 1, maxZ);

        Set<Long> districtChunks = new HashSet<>();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                        NeonCityGenerator.layout(), chunkX, chunkZ).orElse(null);
                if (placement != null && placement.patch().district() == district
                        && NeonCityGenerator.isUsableArnisChunk(
                                level, chunkX << 4, chunkZ << 4)) {
                    districtChunks.add(ChunkPos.pack(chunkX, chunkZ));
                }
            }
        }

        Map<Integer, Set<BlockPos>> cellsByFloor = new HashMap<>();
        int walkableCellCount = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (!districtChunks.contains(ChunkPos.pack(
                            Math.floorDiv(x, 16), Math.floorDiv(z, 16)))) {
                        continue;
                    }
                    BlockPos position = new BlockPos(x, y, z);
                    if (isInteriorWalkable(level, position)) {
                        cellsByFloor.computeIfAbsent(y, ignored -> new HashSet<>()).add(position);
                        walkableCellCount++;
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

        List<FloorStack> stacks = floorStacks(
                profiles, planSeed, 1, MAX_ATLAS_FLOORS);
        List<BuildingLabel> labels = new ArrayList<>();
        List<Site> sites = new ArrayList<>();
        for (FloorStack stack : stacks) {
            if (labels.size() >= MAX_ATLAS_BUILDING_LABELS) break;
            List<FloorProfile> buildingFloors = stack.floors();
            BoundingBox buildingBounds = floorStackBounds(buildingFloors);
            long geometryHash = MegacityLayout.mix(
                    0x4255494C44494E47L,
                    buildingBounds.minX() * 31 + buildingBounds.maxX(),
                    buildingBounds.minZ() * 31 + buildingBounds.maxZ())
                    ^ buildingFloors.stream().mapToLong(FloorProfile::y).sum()
                    ^ (long) buildingFloors.size() << 48;
            String buildingId = district.resourceCode() + ":atlas:"
                    + Long.toUnsignedString(geometryHash, 16);
            long buildingSeed = MegacityLayout.mix(
                    planSeed ^ 0x41544C4153424C44L,
                    buildingBounds.minX() ^ buildingBounds.maxX(),
                    buildingBounds.minZ() ^ buildingBounds.maxZ())
                    ^ buildingFloors.size();
            boolean truncated = buildingBounds.minX() <= contentMinX
                    || buildingBounds.maxX() >= contentMaxX
                    || buildingBounds.minZ() <= contentMinZ
                    || buildingBounds.maxZ() >= contentMaxZ;
            List<FloorProfile> missionFloors = buildingFloors.size() < minimumFloors
                    ? List.of()
                    : List.copyOf(buildingFloors.subList(
                            0, Math.min(buildingFloors.size(), maximumFloors)));
            PlanningResult planning = truncated
                    ? PlanningResult.rejected("rejected: boundary-clipped structural volume")
                    : missionFloors.size() < minimumFloors
                            ? PlanningResult.rejected("rejected: insufficient floors")
                            : sites.size() >= MAX_ATLAS_SITES
                                    ? PlanningResult.rejected("rejected: scan site limit reached")
                                    : planAtlasSite(
                                            level, district, centerChunkX, centerChunkZ,
                                            buildingSeed, missionFloors, minimumFloors);
            Optional<Site> planned = planning.site().map(site ->
                    withBuildingReservation(site, buildingId, buildingBounds));
            if (planned.isPresent()) sites.add(planned.orElseThrow());
            labels.add(new BuildingLabel(
                    buildingId,
                    planned.map(Site::id).orElse(""),
                    buildingBounds,
                    buildingFloors.stream().map(FloorProfile::y).toList(),
                    buildingFloors.stream().map(floor -> floor.cells().size()).toList(),
                    planned.isPresent(),
                    planning.failure()));
        }
        return new AtlasScan(district, scanBounds, labels, sites, walkableCellCount);
    }

    private static PlanningResult planAtlasSite(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long buildingSeed,
            List<FloorProfile> floors,
            int minimumFloors) {
        PlanningResult best = PlanningResult.rejected("rejected: no viable mission layout");
        for (int floorCount = floors.size(); floorCount >= minimumFloors; floorCount--) {
            List<FloorProfile> selectedFloors = List.copyOf(floors.subList(0, floorCount));
            for (int variant = 0; variant < MAX_ATLAS_PLAN_VARIANTS; variant++) {
                long planSeed = variant == 0 && floorCount == floors.size()
                        ? buildingSeed
                        : MegacityLayout.mix(
                                buildingSeed ^ 0x504C414E56415249L,
                                variant + floorCount * MAX_ATLAS_PLAN_VARIANTS,
                                floors.getFirst().y());
                PlanningResult candidate = planSiteDetailed(
                        level, district, chunkX, chunkZ, planSeed, selectedFloors);
                if (candidate.site().isPresent()) return candidate;
                best = candidate;
            }
        }
        return best;
    }

    /** Captures every block the site installation or objective setup is allowed to replace. */
    public static RestorationSnapshot captureOriginalStates(ServerLevel level, Site site) {
        if (level == null || site == null || !loadSiteChunks(level, site.bounds())) {
            throw new IllegalArgumentException("cannot snapshot an unloaded mission site");
        }
        Map<BlockPos, BlockSnapshot> originals = new java.util.LinkedHashMap<>();
        for (Edit edit : edits(site)) {
            originals.putIfAbsent(edit.position(), new BlockSnapshot(
                    edit.position(), level.getBlockState(edit.position())));
        }
        for (BlockPos objectiveCell : List.of(
                site.target().below(), site.target(), site.target().above())) {
            originals.putIfAbsent(objectiveCell, new BlockSnapshot(
                    objectiveCell, level.getBlockState(objectiveCell)));
        }
        return new RestorationSnapshot(List.copyOf(originals.values()));
    }

    /** Decodes a bounded, registry-aware snapshot from persisted contract data. */
    public static Optional<RestorationSnapshot> loadRestorationSnapshot(
            ServerLevel level, CompoundTag tag) {
        if (level == null || tag == null || tag.getIntOr("Version", 0) != 1) {
            return Optional.empty();
        }
        ListTag encoded = tag.getListOrEmpty("Blocks");
        if (encoded.isEmpty() || encoded.size() > MAX_RESTORATION_BLOCKS) {
            return Optional.empty();
        }
        var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ArrayList<BlockSnapshot> blocks = new ArrayList<>(encoded.size());
        HashSet<BlockPos> positions = new HashSet<>();
        for (int index = 0; index < encoded.size(); index++) {
            CompoundTag entry = encoded.getCompoundOrEmpty(index);
            BlockPos position = readPos(entry, "Pos");
            BlockState state = entry.read("State", BlockState.CODEC, ops).orElse(null);
            if (state == null || !positions.add(position)) {
                return Optional.empty();
            }
            blocks.add(new BlockSnapshot(position, state));
        }
        try {
            return Optional.of(new RestorationSnapshot(blocks));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** Restores a captured site after mission actors and objective blocks have been removed. */
    public static boolean restoreOriginalStates(
            ServerLevel level, RestorationSnapshot snapshot) {
        if (level == null || snapshot == null) return false;
        boolean restored = true;
        List<BlockSnapshot> blocks = snapshot.blocks();
        for (int index = blocks.size() - 1; index >= 0; index--) {
            BlockSnapshot block = blocks.get(index);
            level.getChunkAt(block.position());
            if (!level.getBlockState(block.position()).equals(block.state())) {
                level.setBlock(block.position(), block.state(), PLACE_FLAGS);
            }
            restored &= level.getBlockState(block.position()).equals(block.state());
        }
        return restored;
    }

    /** Revalidates all touched blocks without modifying the world. */
    public static boolean preflight(ServerLevel level, Site site) {
        return preflightFailure(level, site) == null;
    }

    /** Package-visible rejection detail for deterministic GameTest diagnostics. */
    static String preflightFailure(ServerLevel level, Site site) {
        return preflightFailure(level, site, true);
    }

    private static String preflightFailure(
            ServerLevel level, Site site, boolean rejectOccupiedEdits) {
        if (level == null || site == null || !loadSiteChunks(level, site.bounds())) {
            return "site or chunks unavailable";
        }
        // Version-one plans still deserialize for active-contract cleanup, but cannot reinstall.
        if (site.floorMasks().isEmpty() || !stairsHaveHorizontalClearance(site.stairs())) {
            return "missing floor masks or stair clearance";
        }
        if (site.entrance().existing() && !isCompleteDoor(level, site.entrance().position())) {
            return "existing entrance is not a complete door";
        }
        List<Edit> edits = edits(site);
        if (!plannedEditsAreConsistent(edits)) return "conflicting planned edits";
        for (Edit edit : edits) {
            BlockState current = level.getBlockState(edit.position());
            if (!edit.matches(current) && !edit.policy().accepts(level, edit.position(), current)) {
                return "unsafe edit at " + edit.position();
            }
            if (rejectOccupiedEdits && !edit.matches(current) && edit.state().blocksMotion()
                    && !level.getEntitiesOfClass(
                            Entity.class, new AABB(edit.position()), Entity::isAlive).isEmpty()) {
                return "entity occupies edit at " + edit.position();
            }
        }
        if (rejectOccupiedEdits && !level.getEntitiesOfClass(
                Entity.class, new AABB(site.target()), Entity::isAlive).isEmpty()) {
            return "entity occupies target";
        }
        if (!routeCellsRemainClear(level, site)) return "route cell obstructed";
        if (!wallFixturesHaveBacking(level, site, edits)) return "wall fixture lacks backing";
        if (!missionTurretsPreserveAccess(level, site)) return "turret blocks access";
        DfsAudit audit = depthFirstAudit(level, site, edits, turretFootprint(site));
        if (!audit.accessible()) return "DFS cannot reach " + audit.unreachable();
        DfsAudit objectiveAudit = depthFirstAudit(
                level, site, withSolidObjective(site, edits), turretFootprint(site));
        if (!objectiveAudit.accessible()) {
            return "solid objective blocks DFS at " + objectiveAudit.unreachable();
        }
        return null;
    }

    private static boolean plannedEditsAreConsistent(List<Edit> edits) {
        Map<BlockPos, BlockState> planned = new HashMap<>();
        for (Edit edit : edits) {
            BlockState previous = planned.putIfAbsent(edit.position(), edit.state());
            if (previous != null && !previous.equals(edit.state())) return false;
        }
        return true;
    }

    /** Verifies the installed interior has a player-sized route to every floor and objective. */
    public static boolean hasAccessibleObjectivePath(ServerLevel level, Site site) {
        return level != null && site != null
                && missionTurretsPreserveAccess(level, site)
                && circulationRemainsAccessible(
                        level, site, List.of(), turretFootprint(site))
                && objectiveApproach(level, site).isPresent();
    }

    /** Returns a clear floor cell from which a player can interact with the objective. */
    public static Optional<BlockPos> objectiveApproach(ServerLevel level, Site site) {
        if (level == null || site == null) return Optional.empty();
        Set<BlockPos> reachable = reachableFloorCells(
                level, site, site.target().getY(), List.of(), turretFootprint(site));
        return java.util.Arrays.stream(HORIZONTAL)
                .map(site.target()::relative)
                .filter(reachable::contains)
                .filter(position -> isPassage(level, position))
                .findFirst();
    }

    /** Package-visible deterministic navigation audit for GameTests and mission diagnostics. */
    static DfsAudit auditDepthFirstTraversal(ServerLevel level, Site site) {
        if (level == null || site == null) {
            return new DfsAudit(false, 0, List.of());
        }
        return depthFirstAudit(level, site, List.of(), turretFootprint(site));
    }

    /** Exterior endpoint used by road navigation while the objective remains inside the site. */
    public static BlockPos navigationTarget(Site site) {
        Entrance entrance = site.entrance();
        return entrance.position().relative(
                entrance.outward(), Math.max(1, entrance.wallDepth()));
    }

    /** Adds deterministic entity slots after the selected building geometry has been installed. */
    static Site withMissionTurretPlan(ServerLevel level, Site site) {
        if (level == null || site == null) return site;
        List<Decoration> baseDecorations = site.decorations().stream()
                .filter(decoration -> decoration.kind() != DecorKind.MISSION_TURRET)
                .toList();
        Site base = copyWithDecorations(site, baseDecorations);
        List<Decoration> planned = planMissionTurrets(level, base);
        if (planned.isEmpty()) return base;
        ArrayList<Decoration> decorations = new ArrayList<>(baseDecorations);
        decorations.addAll(planned.subList(
                0, Math.min(planned.size(), MAX_DECORATIONS - decorations.size())));
        return copyWithDecorations(site, decorations);
    }

    /** Removes entity-only turret slots so uninstalled building edits can be preflighted safely. */
    static Site withoutMissionTurretPlan(Site site) {
        if (site == null) return null;
        return copyWithDecorations(site, site.decorations().stream()
                .filter(decoration -> decoration.kind() != DecorKind.MISSION_TURRET)
                .toList());
    }

    /** Reduces a catalog entry to topology; furnishings are generated when the contract activates. */
    static Site withoutMissionInteriorPlan(Site site) {
        return site == null ? null : copyWithDecorations(site, List.of());
    }

    /** Attaches the complete segmented-building identity used by cross-contract reservations. */
    static Site withBuildingReservation(
            Site site, String buildingId, BoundingBox buildingBounds) {
        if (site == null) return null;
        return new Site(
                site.id(), site.district(), site.bounds(), site.floorYs(), site.target(),
                site.entrance(), site.stairs(), site.patrolRoutes(), site.decorations(),
                site.floorMasks(), site.planSeed(), buildingId, buildingBounds);
    }

    /**
     * Dresses a selected structural site without making furnishings a site-selection prerequisite.
     * Different contracts can reuse one building with a stable, contract-specific floor treatment.
     */
    static Site withMissionInteriorPlan(ServerLevel level, Site site, long variationSalt) {
        return withMissionInteriorPlan(level, site, variationSalt, null);
    }

    /** Applies a cohesive floor program for the contract objective when its type is known. */
    static Site withMissionInteriorPlan(
            ServerLevel level,
            Site site,
            long variationSalt,
            MissionCatalog.MissionType missionType) {
        return withMissionInteriorPlan(level, site, variationSalt, missionType, "");
    }

    /** Applies an authored story-site program, falling back to the objective-type program. */
    static Site withMissionInteriorPlan(
            ServerLevel level,
            Site site,
            long variationSalt,
            MissionCatalog.MissionType missionType,
            String missionId) {
        if (level == null || site == null || site.floorMasks().isEmpty()) return site;
        Site original = withoutMissionTurretPlan(site);
        Site structural = withoutMissionInteriorPlan(site);
        List<FloorProfile> floors = floorProfiles(structural);
        if (floors.size() != structural.floorYs().size()) return original;
        long baseSeed = MegacityLayout.mix(
                structural.planSeed() ^ INTERIOR_SALT ^ variationSalt,
                structural.district().ordinal(), structural.floorYs().size());
        InteriorPlanCandidate best = null;
        ArrayList<String> failures = new ArrayList<>();
        for (int variant = 0; variant < MAX_INTERIOR_PLAN_VARIANTS; variant++) {
            long seed = MegacityLayout.mix(
                    baseSeed, variant, structural.target().getY());
            List<Decoration> decorations = planDecorations(
                    level, floors, structural.entrance(), structural.stairs(),
                    structural.patrolRoutes(), structural.target(), seed, missionType, missionId);
            if (decorations.isEmpty()) {
                failures.add(variant + "=empty");
                continue;
            }
            if (decorations.stream().noneMatch(
                    decoration -> decoration.kind() == DecorKind.EXPLOSIVE_CANISTER)) {
                failures.add(variant + "=missing canister");
                continue;
            }
            Site planned = copyWithDecorations(structural, decorations);
            String failure = preflightFailure(level, planned, false);
            if (failure != null) {
                Site repaired = repairInteriorPlan(
                        level, structural, decorations, floors, seed, missionType, missionId);
                if (repaired == null) {
                    failures.add(variant + "=" + failure);
                    continue;
                }
                planned = repaired;
                decorations = repaired.decorations();
            }
            if (!realizesFloorProgram(planned, missionType, missionId, seed)) {
                failures.add(variant + "=missing floor role");
                continue;
            }
            InteriorPlanCandidate candidate = new InteriorPlanCandidate(
                    planned,
                    interiorQuality(floors, decorations, seed, missionType, missionId),
                    variant);
            if (best == null || betterInterior(candidate, best)) best = candidate;
        }
        if (best != null) return best.site();
        if (hasExplosiveCanisterPlan(original)
                && realizesFloorProgram(original, missionType, missionId, baseSeed)
                && preflightFailure(level, original, false) == null) {
            return original;
        }
        Cyberdeck.LOGGER.warn(
                "[MissionInterior] no safe role-complete variant for {}; "
                        + "using structural plan so deployment can select another site: {}",
                structural.id(), failures);
        return structural;
    }

    private static List<FloorProfile> floorProfiles(Site site) {
        ArrayList<FloorProfile> floors = new ArrayList<>();
        for (int floorY : site.floorYs()) {
            Set<BlockPos> cells = site.missionCells(floorY);
            if (cells.isEmpty()) return List.of();
            floors.add(new FloorProfile(floorY, cells, rect(cells)));
        }
        return List.copyOf(floors);
    }

    /** Validates only entrance, stair, floor-mask, and objective geometry. */
    static boolean preflightSiteGeometry(ServerLevel level, Site site) {
        return siteGeometryFailure(level, site) == null;
    }

    static String siteGeometryFailure(ServerLevel level, Site site) {
        return preflightFailure(level, withoutMissionInteriorPlan(site), false);
    }

    static Site repairStructuralFloorMasks(ServerLevel level, Site site) {
        Site structural = withoutMissionInteriorPlan(site);
        String failure = siteGeometryFailure(level, structural);
        if (failure == null) return structural;
        if (!failure.startsWith("DFS cannot reach")) return null;
        DfsAudit audit = depthFirstAudit(
                level, structural, edits(structural), Set.of());
        Set<BlockPos> unreachable = Set.copyOf(audit.unreachable());
        LinkedHashSet<BlockPos> required = new LinkedHashSet<>();
        structural.patrolRoutes().forEach(route -> required.addAll(route.waypoints()));
        required.add(structural.target());
        BlockPos entranceInside = structural.entrance().position()
                .relative(structural.entrance().outward().getOpposite());
        required.add(entranceInside);
        if (!structural.entrance().existing()) {
            required.add(entranceInside.relative(
                    structural.entrance().outward().getClockWise()));
        }
        structural.stairs().forEach(stair -> required.addAll(stairLandingCells(stair)));
        if (required.stream().anyMatch(unreachable::contains)) return null;

        ArrayList<FloorMask> masks = new ArrayList<>();
        try {
            for (FloorMask mask : structural.floorMasks()) {
                List<BlockPos> retained = mask.cells().stream()
                        .filter(cell -> !unreachable.contains(cell))
                        .toList();
                masks.add(new FloorMask(mask.floorY(), retained));
            }
            Site repaired = new Site(
                    structural.id(), structural.district(), structural.bounds(),
                    structural.floorYs(), structural.target(), structural.entrance(),
                    structural.stairs(), structural.patrolRoutes(), List.of(), masks,
                    structural.planSeed(), structural.buildingId(), structural.buildingBounds());
            return siteGeometryFailure(level, repaired) == null ? repaired : null;
        } catch (IllegalArgumentException invalidMask) {
            return null;
        }
    }

    static boolean preflightInteriorPlan(ServerLevel level, Site site) {
        return preflightFailure(level, site, false) == null;
    }

    private static Site repairInteriorPlan(
            ServerLevel level,
            Site structural,
            List<Decoration> decorations,
            List<FloorProfile> floors,
            long seed,
            MissionCatalog.MissionType missionType,
            String missionId) {
        ArrayList<Decoration> retained = new ArrayList<>(decorations);
        HashSet<Decoration> protectedRoleAnchors = new HashSet<>();
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            FloorProfile floor = floors.get(floorIndex);
            FloorTheme theme = floorTheme(
                    seed, floorIndex, floors.size(), missionType, missionId);
            Decoration anchor = retained.stream()
                    .filter(decoration -> decoration.position().getY() == floor.y())
                    .filter(decoration -> anchorsTheme(theme, decoration.kind()))
                    .findFirst().orElse(null);
            if (anchor == null) return null;
            protectedRoleAnchors.add(anchor);
        }
        Set<Long> protectedColumns = retained.stream()
                .filter(decoration -> decoration.kind() == DecorKind.EXPLOSIVE_CANISTER)
                .map(decoration -> decoration.position().relative(
                        decoration.facing().getOpposite()))
                .map(position -> ChunkPos.pack(position.getX(), position.getZ()))
                .collect(java.util.stream.Collectors.toSet());
        HashSet<Long> removedColumns = new HashSet<>();
        for (int index = retained.size() - 1; index >= 0; index--) {
            Decoration decoration = retained.get(index);
            if (decoration.kind() == DecorKind.EXPLOSIVE_CANISTER
                    || protectedRoleAnchors.contains(decoration)) {
                continue;
            }
            if (decoration.kind() == DecorKind.FULL_HEIGHT_PARTITION) {
                long column = ChunkPos.pack(
                        decoration.position().getX(), decoration.position().getZ());
                if (protectedColumns.contains(column) || !removedColumns.add(column)) continue;
                retained.removeIf(candidate ->
                        candidate.kind() == DecorKind.FULL_HEIGHT_PARTITION
                                && ChunkPos.pack(
                                        candidate.position().getX(), candidate.position().getZ())
                                        == column);
            } else {
                retained.remove(index);
            }
            Site repaired = copyWithDecorations(structural, retained);
            if (hasExplosiveCanisterPlan(repaired)
                    && realizesFloorProgram(repaired, missionType, missionId, seed)
                    && preflightFailure(level, repaired, false) == null) {
                return repaired;
            }
            index = Math.min(index, retained.size());
        }
        return null;
    }

    static List<Decoration> missionTurretPlacements(Site site) {
        if (site == null) return List.of();
        return site.decorations().stream()
                .filter(decoration -> decoration.kind() == DecorKind.MISSION_TURRET)
                .toList();
    }

    static boolean hasMissionTurretPlan(Site site) {
        return site != null && !missionTurretPlacements(site).isEmpty();
    }

    static boolean hasExplosiveCanisterPlan(Site site) {
        return site != null && site.decorations().stream().anyMatch(
                decoration -> decoration.kind() == DecorKind.EXPLOSIVE_CANISTER);
    }

    static List<Decoration> computerDeskPlacements(Site site) {
        if (site == null) return List.of();
        return site.decorations().stream()
                .filter(decoration -> decoration.kind() == DecorKind.COMPUTER_DESK)
                .toList();
    }

    /** Revalidates persisted turret geometry without depending on transient entity positions. */
    static boolean isMissionTurretPlacementSafe(
            ServerLevel level, Site site, Decoration placement) {
        if (level == null || site == null || placement == null
                || placement.kind() != DecorKind.MISSION_TURRET
                || !site.floorYs().contains(placement.position().getY())
                || !site.missionCells(placement.position().getY())
                        .contains(placement.position())
                || !turretEnvelopeWithinBounds(site, placement.position())
                || !hasTurretClearance(level, placement.position())
                || !hasTurretFiringArc(level, placement.position(), placement.facing())
                || conflictsWithMissionTopology(site, placement.position())) {
            return false;
        }
        return circulationRemainsAccessible(
                        level, site, List.of(), turretFootprint(List.of(placement)))
                && circulationRemainsAccessible(
                        level, site, withSolidObjective(site, List.of()),
                        turretFootprint(List.of(placement)));
    }

    static boolean missionTurretsPreserveAccess(ServerLevel level, Site site) {
        if (level == null || site == null) return false;
        List<Decoration> turrets = missionTurretPlacements(site);
        if (turrets.isEmpty()) return true;
        if (turrets.size() > MAX_MISSION_TURRETS_PER_SITE
                || new HashSet<>(turrets.stream().map(Decoration::position).toList()).size()
                        != turrets.size()
                || turrets.stream().anyMatch(
                        placement -> !isMissionTurretPlacementSafe(level, site, placement))) {
            return false;
        }
        return circulationRemainsAccessible(level, site, List.of(), turretFootprint(turrets))
                && circulationRemainsAccessible(
                        level, site, withSolidObjective(site, List.of()),
                        turretFootprint(turrets));
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
        if (!circulationRemainsAccessible(
                level, site, List.of(), turretFootprint(site))) {
            for (int index = changedStates.size() - 1; index >= 0; index--) {
                OriginalState original = changedStates.get(index);
                level.setBlock(original.position(), original.state(), PLACE_FLAGS);
            }
            return InstallationResult.UNSAFE;
        }
        return changed ? InstallationResult.INSTALLED : InstallationResult.ALREADY_INSTALLED;
    }

    private static Site copyWithDecorations(Site site, List<Decoration> decorations) {
        return new Site(
                site.id(), site.district(), site.bounds(), site.floorYs(), site.target(),
                site.entrance(), site.stairs(), site.patrolRoutes(), decorations,
                site.floorMasks(), site.planSeed(), site.buildingId(), site.buildingBounds());
    }

    private static List<Decoration> planMissionTurrets(ServerLevel level, Site site) {
        ArrayList<TurretCandidate> candidates = new ArrayList<>();
        Entrance entrance = site.entrance();
        for (int floorY : site.floorYs()) {
            Set<BlockPos> reachable = reachableFloorCells(
                    level, site, floorY, List.of(), Set.of());
            for (BlockPos position : reachable) {
                if (!turretEnvelopeWithinBounds(site, position)
                        || !hasTurretClearance(level, position)
                        || conflictsWithMissionTopology(site, position)) {
                    continue;
                }
                boolean entranceArea = isEntranceTurretArea(entrance, position);
                Direction facing = entranceArea
                        && hasTurretFiringArc(level, position, entrance.outward())
                        ? entrance.outward()
                        : bestTurretFacing(level, position, site.planSeed());
                if (facing == null) continue;
                int arcScore = turretFiringArcScore(level, position, facing);
                int priority = entranceArea && facing == entrance.outward() ? 0 : 1;
                candidates.add(new TurretCandidate(
                        position, facing, priority, arcScore,
                        horizontalDistance(position, entrance.position()),
                        positionScore(site.planSeed() ^ TURRET_SALT,
                                position.getX(), position.getZ())));
            }
        }
        candidates.sort(Comparator
                .comparingInt(TurretCandidate::priority)
                .thenComparing(Comparator.comparingInt(TurretCandidate::arcScore).reversed())
                .thenComparingInt(TurretCandidate::entranceDistance)
                .thenComparingLong(TurretCandidate::tieBreaker)
                .thenComparingInt(candidate -> candidate.position().getY())
                .thenComparingInt(candidate -> candidate.position().getX())
                .thenComparingInt(candidate -> candidate.position().getZ()));

        ArrayList<Decoration> result = new ArrayList<>();
        Set<BlockPos> occupied = new HashSet<>();
        for (TurretCandidate candidate : candidates) {
            Decoration placement = new Decoration(
                    candidate.position(), DecorKind.MISSION_TURRET, candidate.facing());
            Set<BlockPos> footprint = turretFootprint(List.of(placement));
            if (footprint.stream().anyMatch(occupied::contains)) continue;
            HashSet<BlockPos> proposed = new HashSet<>(occupied);
            proposed.addAll(footprint);
            if (!circulationRemainsAccessible(level, site, List.of(), proposed)
                    || !circulationRemainsAccessible(
                            level, site, withSolidObjective(site, List.of()), proposed)) {
                continue;
            }
            result.add(placement);
            occupied.addAll(footprint);
            if (result.size() >= MAX_MISSION_TURRETS_PER_SITE) break;
        }
        return List.copyOf(result);
    }

    private static boolean isEntranceTurretArea(Entrance entrance, BlockPos position) {
        if (position.getY() != entrance.position().getY()) return false;
        Direction inward = entrance.outward().getOpposite();
        Direction across = entrance.outward().getClockWise();
        int dx = position.getX() - entrance.position().getX();
        int dz = position.getZ() - entrance.position().getZ();
        int depth = dx * inward.getStepX() + dz * inward.getStepZ();
        int lateral = dx * across.getStepX() + dz * across.getStepZ();
        return depth >= 3 && depth <= 9 && lateral >= -2 && lateral <= 3;
    }

    private static Direction bestTurretFacing(
            ServerLevel level, BlockPos position, long planSeed) {
        Direction best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Direction direction : orderedDirections(planSeed ^ TURRET_SALT, position)) {
            if (!hasTurretFiringArc(level, position, direction)) continue;
            int score = turretFiringArcScore(level, position, direction);
            if (score > bestScore) {
                best = direction;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean hasTurretClearance(ServerLevel level, BlockPos position) {
        if (!level.getBlockState(position.below()).blocksMotion()) return false;
        for (int dz = -TURRET_CLEARANCE_RADIUS; dz <= TURRET_CLEARANCE_RADIUS; dz++) {
            for (int dx = -TURRET_CLEARANCE_RADIUS; dx <= TURRET_CLEARANCE_RADIUS; dx++) {
                BlockPos column = position.offset(dx, 0, dz);
                for (int dy = 0; dy < TURRET_HEADROOM; dy++) {
                    if (!isPassable(level.getBlockState(column.above(dy)))) return false;
                }
            }
        }
        return true;
    }

    private static boolean turretEnvelopeWithinBounds(Site site, BlockPos position) {
        return turretEnvelopeWithinBounds(site.bounds(), position);
    }

    private static boolean turretEnvelopeWithinBounds(BoundingBox bounds, BlockPos position) {
        for (int dz = -TURRET_CLEARANCE_RADIUS; dz <= TURRET_CLEARANCE_RADIUS; dz++) {
            for (int dx = -TURRET_CLEARANCE_RADIUS; dx <= TURRET_CLEARANCE_RADIUS; dx++) {
                BlockPos column = position.offset(dx, 0, dz);
                if (!contains(bounds, column.below())
                        || !contains(bounds, column.above(TURRET_HEADROOM - 1))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasTurretFiringArc(
            ServerLevel level, BlockPos position, Direction facing) {
        int forward = clearFiringDistance(level, position, facing);
        int clockwise = clearFiringDistance(level, position, facing.getClockWise());
        int counterClockwise = clearFiringDistance(
                level, position, facing.getCounterClockWise());
        int usableRays = (forward >= 3 ? 1 : 0)
                + (clockwise >= 3 ? 1 : 0)
                + (counterClockwise >= 3 ? 1 : 0);
        return forward >= MIN_TURRET_FORWARD_ARC
                && forward + clockwise + counterClockwise >= MIN_TURRET_TOTAL_ARC
                && usableRays >= 2;
    }

    private static int turretFiringArcScore(
            ServerLevel level, BlockPos position, Direction facing) {
        int forward = clearFiringDistance(level, position, facing);
        return forward * 2
                + clearFiringDistance(level, position, facing.getClockWise())
                + clearFiringDistance(level, position, facing.getCounterClockWise());
    }

    private static int clearFiringDistance(
            ServerLevel level, BlockPos position, Direction direction) {
        int clear = 0;
        for (int distance = 1; distance <= TURRET_FIRE_DISTANCE; distance++) {
            BlockPos column = position.relative(direction, distance);
            if (!isPassable(level.getBlockState(column.above()))
                    || !isPassable(level.getBlockState(column.above(2)))) {
                break;
            }
            clear = distance;
        }
        return clear;
    }

    private static boolean conflictsWithMissionTopology(Site site, BlockPos position) {
        if (sameFloorWithin(position, site.target(), 3)
                || sameFloorWithin(position, site.entrance().position(), 3)) {
            return true;
        }
        for (PatrolRoute route : site.patrolRoutes()) {
            for (BlockPos waypoint : route.waypoints()) {
                if (sameFloorWithin(position, waypoint, 2)) return true;
            }
        }
        for (StairRun stair : site.stairs()) {
            for (BlockPos clearance : stairFloorClearanceCells(stair)) {
                if (sameFloorWithin(position, clearance, 2)) return true;
            }
        }
        for (Decoration decoration : site.decorations()) {
            if (decoration.kind() == DecorKind.COMPUTER_DESK
                    && sameFloorWithin(position, decoration.position(), 2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameFloorWithin(BlockPos first, BlockPos second, int distance) {
        return first.getY() == second.getY()
                && horizontalDistance(first, second) < distance;
    }

    private static Set<BlockPos> turretFootprint(Site site) {
        return turretFootprint(missionTurretPlacements(site));
    }

    private static Set<BlockPos> turretFootprint(List<Decoration> placements) {
        HashSet<BlockPos> footprint = new HashSet<>();
        for (Decoration placement : placements) {
            if (placement.kind() != DecorKind.MISSION_TURRET) continue;
            footprint.add(placement.position());
        }
        return Set.copyOf(footprint);
    }

    private static Optional<Site> profileChunk(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long planSeed,
            int minimumFloors) {
        return profileChunk(
                level, district, chunkX, chunkZ, planSeed, minimumFloors, MAX_FLOORS);
    }

    private static Optional<Site> profileChunk(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long planSeed,
            int minimumFloors,
            int maximumFloors) {
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
        for (int floorCount = Math.min(floors.size(), maximumFloors);
                floorCount >= minimumFloors; floorCount--) {
            Optional<Site> preferred = planSite(
                    level, district, chunkX, chunkZ, planSeed,
                    List.copyOf(floors.subList(0, floorCount)));
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        if (minimumFloors >= 2) {
            return Optional.empty();
        }
        for (FloorProfile floor : profiles.stream()
                .sorted(Comparator.comparingInt(FloorProfile::y)
                        .thenComparing(Comparator.comparingInt(
                                (FloorProfile profile) -> profile.cells().size()).reversed())
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
        return planSiteDetailed(
                level, district, chunkX, chunkZ, planSeed, floors).site();
    }

    private static PlanningResult planSiteDetailed(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long planSeed,
            List<FloorProfile> floors) {
        List<Entrance> entrances = findEntrances(level, floors.getFirst(), planSeed);
        if (entrances.isEmpty()) {
            return PlanningResult.rejected("rejected: no street-connected entrance");
        }
        boolean bounded = false;
        boolean stairs = floors.size() == 1;
        String failure = "rejected: no safe stair shaft";
        for (Entrance entrance : entrances) {
            for (List<FloorProfile> boundedFloors : boundedMissionFloorCandidates(
                    floors, entrance, planSeed)) {
                bounded = true;
                PlanningResult planned = planSiteFromEntrance(
                        level, district, chunkX, chunkZ, planSeed, boundedFloors, entrance);
                if (planned.site().isPresent()) return planned;
                if (!planned.failure().contains("no safe stair shaft")) {
                    stairs = true;
                    failure = planned.failure();
                }
            }
        }
        if (!bounded) {
            return PlanningResult.rejected("rejected: no common bounded floor window");
        }
        if (!stairs) {
            return PlanningResult.rejected("rejected: no safe stair shaft");
        }
        return PlanningResult.rejected(failure);
    }

    private static PlanningResult planSiteFromEntrance(
            ServerLevel level,
            District district,
            int chunkX,
            int chunkZ,
            long planSeed,
            List<FloorProfile> floors,
            Entrance entrance) {
        List<StairRun> stairs = findStairPlan(level, floors, planSeed);
        if (stairs.size() != floors.size() - 1) {
            return PlanningResult.rejected("rejected: no safe stair shaft");
        }

        BoundingBox bounds = siteBounds(floors, entrance);
        if ((long) bounds.getXSpan() * bounds.getYSpan() * bounds.getZSpan() > MAX_SITE_VOLUME) {
            return PlanningResult.rejected("rejected: mission volume exceeds limit");
        }
        Set<BlockPos> routeExclusions = routeExclusions(floors, entrance, stairs);
        List<PatrolRoute> routes = new ArrayList<>();
        for (FloorProfile floor : floors) {
            PatrolRoute route = patrolRoute(
                    floor, planSeed ^ floor.y(), routeExclusions);
            if (route == null) {
                return PlanningResult.rejected(
                        "rejected: no patrol route on floor " + floor.y());
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
            return PlanningResult.rejected("rejected: no safe objective position");
        }
        String id = district.resourceCode()
                + ":" + chunkX + ":" + chunkZ + ":"
                + Long.toUnsignedString(planSeed, 16);
        try {
            Site site = new Site(
                    id,
                    district,
                    bounds,
                    floors.stream().map(FloorProfile::y).toList(),
                    target,
                    entrance,
                    stairs,
                    routes,
                    List.of(),
                    floors.stream().map(floor -> new FloorMask(
                            floor.y(), List.copyOf(floor.cells()))).toList(),
                    planSeed);
            String preflight = preflightFailure(level, site, false);
            return preflight == null
                    ? PlanningResult.accepted(site)
                    : PlanningResult.rejected("rejected: " + preflight);
        } catch (IllegalArgumentException unsafe) {
            return PlanningResult.rejected("rejected: invalid persisted site plan");
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
        if (!hasCeiling(level, position, MAX_STORY_HEIGHT)) {
            return false;
        }
        int enclosingDirections = 0;
        for (Direction direction : HORIZONTAL) {
            for (int distance = 1; distance <= 16; distance++) {
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
        return floorStacks(profiles, seed, minimumFloors, MAX_FLOORS).stream()
                .map(FloorStack::floors)
                .findFirst()
                .orElse(List.of());
    }

    private static List<FloorStack> floorStacks(
            List<FloorProfile> profiles,
            long seed,
            int minimumFloors,
            int maximumFloors) {
        Map<String, FloorStack> stacks = new LinkedHashMap<>();
        List<FloorProfile> roots = profiles.stream()
                .filter(candidate -> profiles.stream().noneMatch(
                        lower -> canStackFloor(lower, candidate)))
                .toList();
        if (roots.isEmpty()) roots = profiles;
        for (FloorProfile root : roots) {
            enumerateFloorStacks(
                    profiles, seed, minimumFloors, maximumFloors,
                    new ArrayList<>(List.of(root)), stacks);
            if (stacks.size() >= MAX_ATLAS_PATHS) break;
        }
        return stacks.values().stream()
                .sorted(Comparator.comparingLong(FloorStack::score).reversed()
                        .thenComparingInt(stack -> stack.floors().getFirst().bounds().minX)
                        .thenComparingInt(stack -> stack.floors().getFirst().bounds().minZ))
                .toList();
    }

    private static void enumerateFloorStacks(
            List<FloorProfile> profiles,
            long seed,
            int minimumFloors,
            int maximumFloors,
            List<FloorProfile> path,
            Map<String, FloorStack> result) {
        if (result.size() >= MAX_ATLAS_PATHS) return;
        FloorProfile current = path.getLast();
        List<FloorProfile> eligible = profiles.stream()
                .filter(candidate -> canStackFloor(current, candidate))
                .filter(candidate -> !path.contains(candidate))
                .toList();
        int nextFloorY = eligible.stream()
                .mapToInt(FloorProfile::y)
                .min()
                .orElse(Integer.MAX_VALUE);
        List<FloorProfile> successors = eligible.stream()
                .filter(candidate -> candidate.y() == nextFloorY)
                .sorted(Comparator
                        .comparingInt((FloorProfile candidate) ->
                                footprintOverlap(current, candidate)).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (FloorProfile candidate) -> candidate.cells().size()).reversed())
                        .thenComparingLong(candidate -> positionScore(
                                seed, candidate.bounds().minX, candidate.bounds().minZ)))
                .limit(MAX_ATLAS_BRANCHES)
                .toList();
        if (path.size() >= maximumFloors || successors.isEmpty()) {
            if (path.size() >= minimumFloors) addFloorStack(seed, path, result);
            return;
        }
        for (FloorProfile successor : successors) {
            path.add(successor);
            enumerateFloorStacks(
                    profiles, seed, minimumFloors, maximumFloors, path, result);
            path.removeLast();
            if (result.size() >= MAX_ATLAS_PATHS) return;
        }
    }

    private static void addFloorStack(
            long seed, List<FloorProfile> path, Map<String, FloorStack> result) {
        List<FloorProfile> selected = List.copyOf(path);
        long area = selected.stream().mapToLong(value -> value.cells().size()).sum();
        long score = selected.size() * 100_000_000L + area * 100L
                - (long) Math.abs(
                        selected.getFirst().y() - (NeonCityGenerator.CITY_GROUND_Y + 1))
                        * 10_000_000_000L
                + Math.floorMod(positionScore(
                        seed, selected.getFirst().bounds().minX,
                        selected.getFirst().bounds().minZ), 100L);
        FloorProfile base = selected.getFirst();
        String key = base.y() + ":" + base.bounds().minX + ":" + base.bounds().maxX
                + ":" + base.bounds().minZ + ":" + base.bounds().maxZ
                + ":" + selected.stream().map(FloorProfile::y).toList();
        FloorStack candidate = new FloorStack(selected, score);
        result.merge(key, candidate,
                (first, second) -> first.score() >= second.score() ? first : second);
    }

    private static boolean canStackFloor(FloorProfile lower, FloorProfile upper) {
        int rise = upper.y() - lower.y();
        return rise >= MIN_STORY_HEIGHT && rise <= MAX_STORY_HEIGHT
                && overlaps(lower.bounds(), upper.bounds())
                && footprintOverlap(lower, upper)
                        >= STAIR_WIDTH * (MIN_STORY_HEIGHT + STAIR_LANDING_DEPTH);
    }

    private static int footprintOverlap(FloorProfile first, FloorProfile second) {
        Set<BlockPos> larger = first.cells().size() >= second.cells().size()
                ? first.cells() : second.cells();
        Set<BlockPos> smaller = first.cells().size() < second.cells().size()
                ? first.cells() : second.cells();
        int largerY = larger.iterator().next().getY();
        int overlap = 0;
        for (BlockPos position : smaller) {
            if (larger.contains(position.atY(largerY))) overlap++;
        }
        return overlap;
    }

    private static BoundingBox floorStackBounds(List<FloorProfile> floors) {
        int minX = floors.stream().mapToInt(floor -> floor.bounds().minX).min().orElseThrow();
        int maxX = floors.stream().mapToInt(floor -> floor.bounds().maxX).max().orElseThrow();
        int minZ = floors.stream().mapToInt(floor -> floor.bounds().minZ).min().orElseThrow();
        int maxZ = floors.stream().mapToInt(floor -> floor.bounds().maxZ).max().orElseThrow();
        return new BoundingBox(
                minX, floors.getFirst().y() - 1, minZ,
                maxX, floors.getLast().y() + MAX_STORY_HEIGHT, maxZ);
    }

    private static List<Entrance> findEntrances(
            ServerLevel level, FloorProfile floor, long seed) {
        List<BlockPos> cells = ordered(floor.cells(), seed);
        LinkedHashSet<Entrance> explicitDoors = new LinkedHashSet<>();
        LinkedHashSet<Entrance> generatedDoors = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction direction : orderedDirections(seed, cell)) {
                BlockPos first = cell.relative(direction);
                Direction across = direction.getClockWise();
                BlockPos secondInside = cell.relative(across);
                BlockPos second = first.relative(across);
                if (!floor.cells().contains(first)
                        && floor.cells().contains(secondInside)
                        && !floor.cells().contains(second)
                        && supportedEditableDoorway(level, first)
                        && supportedEditableDoorway(level, second)
                        && hasExteriorApproach(
                                level, floor, first.relative(direction), direction)
                        && hasExteriorApproach(
                                level, floor, second.relative(direction), direction)) {
                    Entrance entrance = new Entrance(first, direction, 0, true);
                    if (isCompleteDoor(level, first)) {
                        explicitDoors.add(entrance);
                    } else {
                        generatedDoors.add(new Entrance(first, direction, 1, false));
                    }
                }
            }
        }
        for (BlockPos cell : cells) {
            for (Direction direction : orderedDirections(seed ^ 0x6A09E667F3BCC909L, cell)) {
                int depth = doorwayDepth(level, floor, cell, direction);
                if (depth > 0) {
                    generatedDoors.add(new Entrance(
                            cell.relative(direction), direction, depth, false));
                }
            }
        }
        ArrayList<Entrance> entrances = new ArrayList<>(MAX_ENTRANCE_CANDIDATES);
        appendEntrances(entrances, explicitDoors);
        appendEntrances(entrances, generatedDoors.stream()
                .filter(entrance -> entrance.wallDepth() > 1)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        appendEntrances(entrances, generatedDoors.stream()
                .filter(entrance -> entrance.wallDepth() == 1)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return List.copyOf(entrances);
    }

    private static void appendEntrances(List<Entrance> result, Set<Entrance> candidates) {
        for (Entrance candidate : candidates) {
            if (result.size() >= MAX_ENTRANCE_CANDIDATES) return;
            result.add(candidate);
        }
    }

    /** Restricts every selected story to one coherent, entrance-oriented plan of at most 144 cells. */
    private static List<List<FloorProfile>> boundedMissionFloorCandidates(
            List<FloorProfile> floors, Entrance entrance, long seed) {
        BlockPos inside = entrance.position().relative(entrance.outward().getOpposite());
        Direction inward = entrance.outward().getOpposite();
        Direction across = entrance.outward().getClockWise();
        int preferredArea = MIN_FLOOR_CELLS + Math.floorMod(
                (int) positionScore(seed, inside.getX(), inside.getZ()),
                MAX_MISSION_FLOOR_CELLS - MIN_FLOOR_CELLS + 1);
        int requiredStairAxis = 0;
        for (int floorIndex = 1; floorIndex < floors.size(); floorIndex++) {
            requiredStairAxis = Math.max(
                    requiredStairAxis,
                    floors.get(floorIndex).y() - floors.get(floorIndex - 1).y()
                            + 2 * STAIR_LANDING_DEPTH);
        }
        ArrayList<FloorWindow> windows = new ArrayList<>();
        for (int depth = MIN_FLOOR_SIDE; depth <= 16; depth++) {
            for (int width = MIN_FLOOR_SIDE; width <= 16; width++) {
                int area = depth * width;
                if (area < MIN_FLOOR_CELLS || area > MAX_MISSION_FLOOR_CELLS) continue;
                if (Math.max(depth, width) < requiredStairAxis) continue;
                for (int lateralStart = 2 - width; lateralStart <= 0; lateralStart++) {
                    windows.add(new FloorWindow(
                            depth, width, lateralStart,
                            Math.abs(area - preferredArea),
                            positionScore(seed ^ 0x243F6A8885A308D3L,
                                    depth * 31 + width, lateralStart)));
                }
            }
        }
        windows.sort(Comparator.comparingInt(FloorWindow::areaDelta)
                .thenComparingLong(FloorWindow::score)
                .thenComparing(Comparator.comparingInt(FloorWindow::area).reversed()));

        ArrayList<List<FloorProfile>> results = new ArrayList<>();
        for (FloorWindow window : windows) {
            ArrayList<FloorProfile> bounded = new ArrayList<>(floors.size());
            Set<BlockPos> previous = Set.of();
            boolean valid = true;
            for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
                FloorProfile floor = floors.get(floorIndex);
                Set<BlockPos> candidates = floor.cells().stream()
                        .filter(position -> withinMissionWindow(
                                position, inside, inward, across, window))
                        .filter(position -> horizontalDistance(position, entrance.position())
                                <= MAX_ENTRANCE_TO_MISSION_DISTANCE)
                        .collect(java.util.stream.Collectors.toSet());
                Set<BlockPos> component = floorIndex == 0
                        ? componentContaining(candidates, inside.atY(floor.y()))
                        : componentWithMostOverlap(candidates, previous, floor.y());
                if (component.size() < MIN_FLOOR_CELLS
                        || component.size() > MAX_MISSION_FLOOR_CELLS
                        || floorIndex == 0 && !entrance.existing()
                                && !component.contains(inside.relative(across).atY(floor.y()))) {
                    valid = false;
                    break;
                }
                Rect bounds = rect(component);
                if (bounds.width() < MIN_FLOOR_SIDE || bounds.depth() < MIN_FLOOR_SIDE) {
                    valid = false;
                    break;
                }
                bounded.add(new FloorProfile(floor.y(), Set.copyOf(component), bounds));
                previous = component;
            }
            if (valid && bounded.size() == floors.size()) {
                results.add(List.copyOf(bounded));
                if (results.size() >= MAX_MISSION_WINDOW_CANDIDATES) break;
            }
        }
        return List.copyOf(results);
    }

    private static boolean withinMissionWindow(
            BlockPos position,
            BlockPos inside,
            Direction inward,
            Direction across,
            FloorWindow window) {
        int dx = position.getX() - inside.getX();
        int dz = position.getZ() - inside.getZ();
        int depth = dx * inward.getStepX() + dz * inward.getStepZ();
        int lateral = dx * across.getStepX() + dz * across.getStepZ();
        return depth >= 0 && depth < window.depth()
                && lateral >= window.lateralStart()
                && lateral < window.lateralStart() + window.width();
    }

    private static Set<BlockPos> componentContaining(
            Set<BlockPos> candidates, BlockPos required) {
        if (!candidates.contains(required)) return Set.of();
        return connectedComponent(candidates, required);
    }

    private static Set<BlockPos> componentWithMostOverlap(
            Set<BlockPos> candidates, Set<BlockPos> previous, int floorY) {
        if (previous.isEmpty()) return Set.of();
        int previousY = previous.iterator().next().getY();
        Set<BlockPos> remaining = new HashSet<>(candidates);
        Set<BlockPos> best = Set.of();
        int bestOverlap = 0;
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            Set<BlockPos> component = connectedComponent(remaining, seed);
            remaining.removeAll(component);
            int overlap = (int) component.stream().filter(position ->
                    position.getY() == floorY
                            && previous.contains(position.atY(previousY))).count();
            if (overlap > bestOverlap
                    || overlap == bestOverlap && component.size() > best.size()) {
                best = component;
                bestOverlap = overlap;
            }
        }
        return bestOverlap >= STAIR_WIDTH * (MIN_STORY_HEIGHT + STAIR_LANDING_DEPTH)
                ? best : Set.of();
    }

    private static Set<BlockPos> connectedComponent(Set<BlockPos> candidates, BlockPos seed) {
        HashSet<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> stack = new ArrayDeque<>();
        visited.add(seed);
        stack.push(seed);
        while (!stack.isEmpty()) {
            BlockPos current = stack.pop();
            for (Direction direction : HORIZONTAL) {
                BlockPos next = current.relative(direction);
                if (candidates.contains(next) && visited.add(next)) stack.push(next);
            }
        }
        return Set.copyOf(visited);
    }

    private static boolean existingAccess(
            ServerLevel level, FloorProfile floor, BlockPos first, Direction outward) {
        if (!isPassage(level, first) || outward == null || outward.getAxis().isVertical()) {
            return false;
        }
        int minX = floor.bounds().minX - EXTERIOR_SEARCH_MARGIN;
        int maxX = floor.bounds().maxX + EXTERIOR_SEARCH_MARGIN;
        int minZ = floor.bounds().minZ - EXTERIOR_SEARCH_MARGIN;
        int maxZ = floor.bounds().maxZ + EXTERIOR_SEARCH_MARGIN;
        Set<BlockPos> visited = new HashSet<>();
        Map<BlockPos, Integer> distanceFromEntrance = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(first);
        distanceFromEntrance.put(first, 0);
        queue.add(first);
        while (!queue.isEmpty() && visited.size() <= MAX_EXTERIOR_PATH_NODES) {
            BlockPos current = queue.removeFirst();
            int distance = distanceFromEntrance.get(current);
            if (outsideBuildingApproach(floor, current) && isExposedGroundPassage(level, current)) {
                return true;
            }
            if (distance >= MAX_EXISTING_ENTRANCE_APPROACH_DISTANCE) continue;
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
                    boolean hasStepHeadroom = dy <= 0
                            || isPassable(level.getBlockState(current.above(2)));
                    if (hasStepHeadroom && isPassage(level, next) && visited.add(next)) {
                        distanceFromEntrance.put(next, distance + 1);
                        queue.addLast(next);
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasExteriorApproach(
            ServerLevel level, FloorProfile floor, BlockPos first, Direction outward) {
        return isExposedGroundPassage(level, first)
                || existingAccess(level, floor, first, outward);
    }

    private static boolean isExposedGroundPassage(ServerLevel level, BlockPos position) {
        return isPassage(level, position)
                && (!NeonCityGenerator.isMegacityWorld(level)
                        || position.getY() == NeonCityGenerator.CITY_GROUND_Y + 1
                                && level.canSeeSky(position));
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
        for (int depth = 1; depth <= MAX_ENTRANCE_WALL_DEPTH; depth++) {
            boolean validWall = true;
            boolean facadePresent = false;
            for (int lane = 0; lane < 2 && validWall; lane++) {
                BlockPos wall = inside.relative(across, lane).relative(outward);
                for (int step = 0; step < depth && validWall; step++) {
                    BlockPos slice = wall.relative(outward, step);
                    if (!level.getBlockState(slice.below()).blocksMotion()) {
                        validWall = false;
                        break;
                    }
                    for (int y = 0; y < 3; y++) {
                        BlockPos carved = slice.above(y);
                        if (!isSafelyEditable(level, carved)) {
                            validWall = false;
                            break;
                        }
                        if (step == 0 && !level.getBlockState(carved).isAir()) {
                            facadePresent = true;
                        }
                    }
                }
                BlockPos outside = wall.relative(outward, depth);
                validWall &= hasExteriorApproach(level, floor, outside, outward);
            }
            if (validWall && facadePresent) {
                return depth;
            }
        }
        return 0;
    }

    private static boolean supportedEditableDoorway(ServerLevel level, BlockPos position) {
        if (!level.getBlockState(position.below()).blocksMotion()) return false;
        for (int height = 0; height < 3; height++) {
            if (!isSafelyEditable(level, position.above(height))) return false;
        }
        return true;
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
                for (int head = 0; head <= STAIR_HEADROOM; head++) {
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
        int firstUpperY = first.start().getY() + first.rise();
        int secondUpperY = second.start().getY() + second.rise();
        if (firstUpperY < second.start().getY() || secondUpperY < first.start().getY()) {
            return true;
        }
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
            List<FloorProfile> floors, Entrance entrance, List<StairRun> stairs) {
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
        reserveStairHeadroomProjections(excluded, floors, stairs, 0);
        return excluded;
    }

    private static BlockPos chooseTarget(
            FloorProfile top, Entrance entrance, long seed, Set<BlockPos> exclusions) {
        return top.cells().stream()
                .filter(position -> !exclusions.contains(position))
                .filter(position -> horizontalDistance(position, entrance.position())
                        <= MAX_ENTRANCE_TO_MISSION_DISTANCE)
                .filter(position -> java.util.Arrays.stream(HORIZONTAL)
                        .filter(direction -> top.cells().contains(position.relative(direction)))
                        .filter(direction -> !exclusions.contains(position.relative(direction)))
                        .count() >= 1)
                .filter(position -> removalPreservesConnectivity(top.cells(), position))
                .max(Comparator.comparingDouble(
                                (BlockPos position) -> position.distSqr(entrance.position()))
                        .thenComparingLong(position -> positionScore(
                                seed, position.getX(), position.getZ())))
                .orElse(null);
    }

    private static boolean removalPreservesConnectivity(
            Set<BlockPos> cells, BlockPos removed) {
        if (cells == null || removed == null || !cells.contains(removed) || cells.size() <= 1) {
            return false;
        }
        BlockPos seed = cells.stream().filter(position -> !position.equals(removed))
                .findFirst().orElse(null);
        if (seed == null) return false;
        Set<BlockPos> remaining = new HashSet<>(cells);
        remaining.remove(removed);
        return connectedComponent(remaining, seed).size() == remaining.size();
    }

    private static List<Decoration> planDecorations(
            ServerLevel level,
            List<FloorProfile> floors,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            long seed,
            MissionCatalog.MissionType missionType,
            String missionId) {
        List<Decoration> result = new ArrayList<>();
        Set<BlockPos> occupied = new HashSet<>();
        Set<BlockPos> blockedCells = new HashSet<>();
        reserveStructuralObstructions(
                level, floors, entrance, stairs, occupied, blockedCells);
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
        reserveStairHeadroomProjections(occupied, floors, stairs, 1);

        int explosiveCanisters = 0;
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            FloorProfile floor = floors.get(floorIndex);
            PatrolRoute route = routes.stream()
                    .filter(value -> value.floorY() == floor.y()).findFirst().orElseThrow();
            Set<BlockPos> circulation = circulationSpine(
                    floor, entrance, stairs, route, target, blockedCells);
            for (BlockPos position : circulation) {
                reserve(occupied, position, CORRIDOR_CLEARANCE);
            }

            Direction longAxis = floor.bounds().width() >= floor.bounds().depth()
                    ? Direction.EAST : Direction.SOUTH;
            Direction across = longAxis.getClockWise();
            FloorTheme theme = floorTheme(
                    seed, floorIndex, floors.size(), missionType, missionId);
            if (explosiveCanisters == 0
                    && (addWallBackedDecoration(
                                    level, result, occupied, blockedCells, floor,
                                    DecorKind.EXPLOSIVE_CANISTER,
                                    entrance, stairs, routes, target,
                                    seed ^ 0x4558504C4F534956L ^ floor.y())
                            || addPartitionBackedCanister(
                                    level, result, occupied, blockedCells, List.of(floor),
                                    entrance, stairs, routes, target, seed))) {
                explosiveCanisters++;
            }
            if (!addRequiredRoleAnchor(
                    result, occupied, blockedCells, floor, theme, longAxis,
                    entrance, stairs, routes, target, seed ^ floor.y())) {
                return List.of();
            }
            int floorPartitionLimit = maximumPartitionBases(floor.cells().size());
            int partitionColumns = Math.toIntExact(result.stream().filter(decoration ->
                            decoration.kind() == DecorKind.FULL_HEIGHT_PARTITION
                                    && decoration.position().getY() == floor.y())
                    .count());
            partitionColumns += addBoundaryPartitionBases(
                    level, result, occupied, blockedCells, floor,
                    entrance, stairs, routes, target,
                    Math.min(MAX_BOUNDARY_PARTITION_COLUMNS_PER_FLOOR,
                            floorPartitionLimit - partitionColumns));
            if (floor.cells().size() >= 96
                    && theme == FloorTheme.STORAGE
                    && partitionColumns < floorPartitionLimit) {
                int internalBudget = Math.min(
                        MAX_INTERNAL_PARTITION_COLUMNS_PER_FLOOR,
                        floorPartitionLimit - partitionColumns);
                addFullHeightPartitionBases(
                        level, result, occupied, blockedCells, floor, longAxis,
                        entrance, stairs, routes, target, theme, seed ^ floor.y(),
                        internalBudget);
            }
            if (theme == FloorTheme.LOBBY) {
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

            if (explosiveCanisters > 0
                    && explosiveCanisters < MAX_EXPLOSIVE_CANISTERS_PER_SITE
                    && (theme == FloorTheme.STORAGE || theme == FloorTheme.OPERATIONS)
                    && addWallBackedDecoration(
                            level, result, occupied, blockedCells, floor,
                            DecorKind.EXPLOSIVE_CANISTER, entrance, stairs, routes, target,
                            seed ^ 0x4558504C4F534956L ^ floor.y())) {
                explosiveCanisters++;
            }
            if (floor.cells().size() >= MIN_THEMED_FLOOR_CELLS) {
                int internalPartitionBudget = Math.min(
                        MAX_INTERNAL_PARTITION_COLUMNS_PER_FLOOR,
                        Math.max(0, floorPartitionLimit
                                - partitionBasesOnFloor(result, floor.y())));
                addThemedFloor(
                        level, result, occupied, blockedCells, floor, theme, longAxis,
                        entrance, stairs, routes, target, seed ^ floor.y(),
                        internalPartitionBudget);
            }

            if (theme == FloorTheme.LOBBY || theme == FloorTheme.LOUNGE) {
                addWallBackedDecoration(
                        level, result, occupied, blockedCells, floor,
                        DecorKind.VENDING_MACHINE, entrance, stairs, routes, target, seed);
            }
            if (theme == FloorTheme.OPEN_OFFICE
                    || theme == FloorTheme.OPERATIONS
                    || theme == FloorTheme.EXECUTIVE) {
                addComputerDesk(
                        level, result, occupied, blockedCells, floor,
                        entrance, stairs, routes, target, seed ^ floor.y());
            }
            if ((theme == FloorTheme.LOBBY
                    || theme == FloorTheme.LOUNGE
                    || theme == FloorTheme.EXECUTIVE)
                    && result.stream().noneMatch(decoration ->
                    decoration.position().getY() == floor.y()
                            && decoration.kind() == DecorKind.PLANTER)) {
                addFirstDecoration(
                        result, occupied, blockedCells, floor,
                        structuredFloorCandidates(floor, across),
                        DecorKind.PLANTER, longAxis,
                        entrance, stairs, routes, target, 1);
            }
            if ((theme == FloorTheme.LOBBY
                    || theme == FloorTheme.LOUNGE
                    || theme == FloorTheme.EXECUTIVE)
                    && result.stream().noneMatch(decoration ->
                    decoration.position().getY() == floor.y()
                            && decoration.kind() == DecorKind.COUCH)) {
                addFirstDecoration(
                        result, occupied, blockedCells, floor,
                        structuredFloorCandidates(floor, across).reversed(),
                        DecorKind.COUCH, across,
                        entrance, stairs, routes, target, 1);
            }

            int wanted = wantedFurnishings(floor, theme);
            if (furnishingsOnFloor(result, floor.y()) < wanted) {
                for (BlockPos candidate : structuredFloorCandidates(floor, longAxis)) {
                    if (furnishingsOnFloor(result, floor.y()) >= wanted) break;
                    DecorKind kind = fallbackDecoration(theme);
                    addDecoration(result, occupied, blockedCells, floor,
                            new Decoration(candidate, kind, longAxis),
                            entrance, stairs, routes, target, 1);
                }
            }
        }
        if (result.stream().noneMatch(
                decoration -> decoration.kind() == DecorKind.EXPLOSIVE_CANISTER)) {
            for (FloorProfile floor : floors) {
                if (addWallBackedDecoration(
                        level, result, occupied, blockedCells, floor,
                        DecorKind.EXPLOSIVE_CANISTER, entrance, stairs, routes, target,
                        seed ^ 0x4558504C4F534956L ^ floor.y())) {
                    break;
                }
            }
        }
        if (result.stream().noneMatch(
                        decoration -> decoration.kind() == DecorKind.EXPLOSIVE_CANISTER)
                && !addPartitionBackedCanister(
                        level, result, occupied, blockedCells, floors,
                        entrance, stairs, routes, target, seed)) {
            return List.of();
        }
        return expandFullHeightPartitions(level, floors, result);
    }

    private static void reserveStructuralObstructions(
            ServerLevel level,
            List<FloorProfile> floors,
            Entrance entrance,
            List<StairRun> stairs,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked) {
        ArrayList<Edit> structuralEdits = new ArrayList<>();
        if (!entrance.existing()) addEntranceEdits(structuralEdits, entrance);
        for (StairRun stair : stairs) addStairEdits(structuralEdits, stair);
        Map<BlockPos, BlockState> overlay = new HashMap<>();
        for (Edit edit : structuralEdits) overlay.put(edit.position(), edit.state());
        for (FloorProfile floor : floors) {
            for (BlockPos cell : floor.cells()) {
                if (plannedPassage(level, cell, overlay)) continue;
                occupied.add(cell);
                blocked.add(cell);
            }
        }
    }

    private static void reserveStairHeadroomProjections(
            Set<BlockPos> occupied,
            List<FloorProfile> floors,
            List<StairRun> stairs,
            int radius) {
        Set<Integer> floorYs = floors.stream().map(FloorProfile::y)
                .collect(java.util.stream.Collectors.toSet());
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step < stair.rise(); step++) {
                int stairY = stair.start().getY() + step;
                for (int floorY : floorYs) {
                    if (floorY < stairY || floorY > stairY + STAIR_HEADROOM) continue;
                    for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                        BlockPos projection = stair.start()
                                .relative(stair.ascending(), step)
                                .relative(across, lane).atY(floorY);
                        reserve(occupied, projection, radius);
                    }
                }
            }
        }
    }

    private static Set<BlockPos> circulationSpine(
            FloorProfile floor,
            Entrance entrance,
            List<StairRun> stairs,
            PatrolRoute route,
            BlockPos target,
            Set<BlockPos> blocked) {
        Set<BlockPos> traversable = new HashSet<>(floor.cells());
        traversable.removeAll(blocked);
        LinkedHashSet<BlockPos> portals = new LinkedHashSet<>();
        BlockPos entranceInside = entrance.position().relative(entrance.outward().getOpposite());
        if (entranceInside.getY() == floor.y()) {
            portals.add(entranceInside);
            portals.add(entranceInside.relative(entrance.outward().getClockWise()));
        }
        for (StairRun stair : stairs) {
            for (BlockPos landing : stairLandingCells(stair)) {
                if (landing.getY() == floor.y()) portals.add(landing);
            }
        }
        if (target.getY() == floor.y()) portals.add(target);
        portals.addAll(route.waypoints());
        portals.retainAll(traversable);
        LinkedHashSet<BlockPos> spine = new LinkedHashSet<>();
        BlockPos root = portals.stream().findFirst().orElse(null);
        if (root == null) return spine;
        spine.add(root);
        for (BlockPos portal : portals) {
            spine.addAll(shortestFloorPath(traversable, root, portal));
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

    private static FloorTheme floorTheme(
            long seed,
            int floorIndex,
            int floorCount,
            MissionCatalog.MissionType missionType,
            String missionId) {
        FloorTheme authored = authoredFloorTheme(missionId, floorIndex, floorCount);
        if (authored != null) return authored;
        if (floorIndex == 0) return FloorTheme.LOBBY;
        if (missionType != null) {
            boolean topFloor = floorIndex == floorCount - 1;
            return switch (missionType) {
                case ASSASSINATE_TARGET -> topFloor
                        ? FloorTheme.EXECUTIVE
                        : alternatingFloorTheme(
                                floorIndex, FloorTheme.OPEN_OFFICE, FloorTheme.OPERATIONS);
                case SHIP_ITEM -> topFloor
                        ? FloorTheme.STORAGE
                        : alternatingFloorTheme(
                                floorIndex, FloorTheme.LOUNGE, FloorTheme.STORAGE);
                case STEAL_DATA -> topFloor
                        ? FloorTheme.OPERATIONS
                        : switch (Math.floorMod(floorIndex - 1, 3)) {
                            case 0 -> FloorTheme.OPEN_OFFICE;
                            case 1 -> FloorTheme.OPERATIONS;
                            default -> FloorTheme.STORAGE;
                        };
                case NEUTRALIZE_CYBERPSYCHO -> topFloor
                        ? FloorTheme.OPERATIONS
                        : alternatingFloorTheme(
                                floorIndex, FloorTheme.STORAGE, FloorTheme.OPERATIONS);
            };
        }
        if (floorIndex == floorCount - 1) return FloorTheme.EXECUTIVE;
        FloorTheme[] middle = {
                FloorTheme.OPEN_OFFICE,
                FloorTheme.OPERATIONS,
                FloorTheme.LOUNGE,
                FloorTheme.STORAGE
        };
        int offset = Math.floorMod((int) Long.rotateRight(seed, 19), middle.length);
        return middle[(offset + floorIndex - 1) % middle.length];
    }

    private static FloorTheme authoredFloorTheme(
            String missionId, int floorIndex, int floorCount) {
        boolean topFloor = floorIndex == floorCount - 1;
        return switch (missionId == null ? "" : missionId) {
            case "m01_deliver_datashards" -> floorIndex == 0
                    ? FloorTheme.LOBBY
                    : topFloor ? FloorTheme.STORAGE : FloorTheme.LOUNGE;
            case "m02_assassinate_g_exec" -> floorIndex == 0
                    ? FloorTheme.LOBBY
                    : topFloor
                            ? FloorTheme.EXECUTIVE
                            : alternatingFloorTheme(
                                    floorIndex, FloorTheme.OPEN_OFFICE, FloorTheme.OPERATIONS);
            case "m03_steal_weights" -> floorIndex == 1
                    ? FloorTheme.OPEN_OFFICE
                    : floorIndex == floorCount - 2
                            ? FloorTheme.STORAGE : FloorTheme.OPERATIONS;
            case "m04_assassinate_fixer" -> floorIndex % 2 == 0
                    ? FloorTheme.STORAGE : FloorTheme.OPERATIONS;
            case "m05_kill_cyberpsycho" -> topFloor
                    ? FloorTheme.OPERATIONS : FloorTheme.STORAGE;
            default -> null;
        };
    }

    private static FloorTheme alternatingFloorTheme(
            int floorIndex, FloorTheme first, FloorTheme second) {
        return Math.floorMod(floorIndex - 1, 2) == 0 ? first : second;
    }

    static List<String> floorProgram(
            MissionCatalog.MissionType missionType, String missionId, int floorCount) {
        if (floorCount < 1 || floorCount > MAX_FLOORS) return List.of();
        return java.util.stream.IntStream.range(0, floorCount)
                .mapToObj(index -> floorTheme(
                        0L, index, floorCount, missionType, missionId).name())
                .toList();
    }

    static boolean realizesFloorProgram(
            Site site, MissionCatalog.MissionType missionType, String missionId) {
        return realizesFloorProgram(site, missionType, missionId,
                site == null ? 0L : site.planSeed());
    }

    private static boolean realizesFloorProgram(
            Site site,
            MissionCatalog.MissionType missionType,
            String missionId,
            long themeSeed) {
        if (site == null || site.floorYs().isEmpty()) return false;
        for (int floorIndex = 0; floorIndex < site.floorYs().size(); floorIndex++) {
            int floorY = site.floorYs().get(floorIndex);
            FloorTheme theme = floorTheme(
                    themeSeed, floorIndex, site.floorYs().size(), missionType, missionId);
            Set<DecorKind> treatment = site.decorations().stream()
                    .filter(decoration -> decoration.position().getY() == floorY)
                    .map(Decoration::kind)
                    .filter(MissionBuildingPlanner::isThemeFurnishing)
                    .collect(java.util.stream.Collectors.toSet());
            if (!realizesTheme(theme, treatment)) return false;
        }
        return true;
    }

    private static DecorKind fallbackDecoration(FloorTheme theme) {
        return requiredRoleAnchor(theme);
    }

    private static DecorKind requiredRoleAnchor(FloorTheme theme) {
        return switch (theme) {
            case OPEN_OFFICE -> DecorKind.CUBICLE_DESK;
            case LOUNGE -> DecorKind.COUCH;
            case OPERATIONS -> DecorKind.SERVER_RACK;
            case LOBBY -> DecorKind.RECEPTION_DESK;
            case STORAGE -> DecorKind.FILING_CABINET;
            case EXECUTIVE -> DecorKind.COMPUTER_DESK;
        };
    }

    private static boolean addRequiredRoleAnchor(
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            FloorTheme theme,
            Direction preferredFacing,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
        long seed) {
        DecorKind required = requiredRoleAnchor(theme);
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>(ordered(
                Set.copyOf(structuredFloorCandidates(floor, preferredFacing)), seed));
        candidates.addAll(ordered(floor.cells(), seed ^ 0x524F4C45414E4348L));
        for (BlockPos candidate : candidates) {
            for (Direction facing : orderedDirections(seed, candidate)) {
                if (addDecoration(
                        result, occupied, blocked, floor,
                        new Decoration(candidate, required, facing),
                        entrance, stairs, routes, target, 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addThemedFloor(
            ServerLevel level,
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            FloorTheme theme,
            Direction longAxis,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            long seed,
            int internalPartitionBudget) {
        Direction across = longAxis.getClockWise();
        boolean compact = floor.cells().size() < FULL_THEME_FLOOR_CELLS;
        List<BlockPos> structured = structuredFloorCandidates(floor, longAxis);
        List<BlockPos> reversed = structured.reversed();
        List<BlockPos> anchors = List.of(
                new BlockPos(
                        floor.bounds().minX + 2, floor.y(), floor.bounds().maxZ - 3),
                new BlockPos(
                        floor.bounds().maxX - 3, floor.y(), floor.bounds().minZ + 2),
                new BlockPos(
                        floor.bounds().maxX - 3, floor.y(), floor.bounds().maxZ - 3));
        switch (theme) {
            case LOBBY -> {
                addFirstDecoration(result, occupied, blocked, floor, anchors,
                        DecorKind.CONFERENCE_TABLE, longAxis,
                        entrance, stairs, routes, target, 1);
                addRepeatedDecorations(result, occupied, blocked, floor, reversed,
                        DecorKind.COUCH, across, compact ? 1 : 2,
                        entrance, stairs, routes, target, 1);
                addFirstDecoration(result, occupied, blocked, floor, anchors.reversed(),
                        DecorKind.WATER_COOLER, across,
                        entrance, stairs, routes, target, 1);
            }
            case OPEN_OFFICE -> {
                if ((!compact || floor.cells().size() >= 96)
                        && internalPartitionBudget > 0) {
                    addRoomPartitions(result, occupied, blocked, floor, longAxis,
                            entrance, stairs, routes, target, internalPartitionBudget);
                }
                addCubiclePods(result, occupied, blocked, floor, longAxis, compact ? 2 : 4,
                        entrance, stairs, routes, target);
                addFirstDecoration(result, occupied, blocked, floor, anchors,
                        DecorKind.CONFERENCE_TABLE, longAxis,
                        entrance, stairs, routes, target, 1);
                addRepeatedDecorations(result, occupied, blocked, floor, reversed,
                        DecorKind.FILING_CABINET, across, compact ? 1 : 2,
                        entrance, stairs, routes, target, 1);
            }
            case OPERATIONS -> {
                if ((!compact || floor.cells().size() >= 96)
                        && internalPartitionBudget > 0) {
                    addRoomPartitions(result, occupied, blocked, floor, longAxis,
                            entrance, stairs, routes, target, internalPartitionBudget);
                }
                for (int count = 0; count < (compact ? 1 : 3); count++) {
                    addComputerDesk(level, result, occupied, blocked, floor,
                            entrance, stairs, routes, target, seed + count);
                }
                addRepeatedDecorations(result, occupied, blocked, floor, reversed,
                        DecorKind.SERVER_RACK, across, compact ? 2 : 4,
                        entrance, stairs, routes, target, 1);
            }
            case LOUNGE -> {
                addFirstDecoration(result, occupied, blocked, floor, anchors,
                        DecorKind.CONFERENCE_TABLE, across,
                        entrance, stairs, routes, target, 1);
                addFirstDecoration(result, occupied, blocked, floor, anchors.reversed(),
                        DecorKind.WATER_COOLER, longAxis,
                        entrance, stairs, routes, target, 1);
                addRepeatedDecorations(result, occupied, blocked, floor, structured,
                        DecorKind.COUCH, longAxis, compact ? 2 : 3,
                        entrance, stairs, routes, target, 1);
                addRepeatedDecorations(result, occupied, blocked, floor, reversed,
                        DecorKind.PLANTER, across, compact ? 2 : 3,
                        entrance, stairs, routes, target, 1);
            }
            case STORAGE -> {
                addRepeatedDecorations(result, occupied, blocked, floor, structured,
                        DecorKind.SERVER_RACK, longAxis, compact ? 2 : 4,
                        entrance, stairs, routes, target, 1);
                addRepeatedDecorations(result, occupied, blocked, floor, reversed,
                        DecorKind.FILING_CABINET, across, compact ? 2 : 4,
                        entrance, stairs, routes, target, 1);
            }
            case EXECUTIVE -> {
                addFirstDecoration(result, occupied, blocked, floor, anchors,
                        DecorKind.CONFERENCE_TABLE, longAxis,
                        entrance, stairs, routes, target, 1);
                for (int count = 0; count < (compact ? 1 : 2); count++) {
                    addComputerDesk(level, result, occupied, blocked, floor,
                            entrance, stairs, routes, target, seed + count);
                }
                addRepeatedDecorations(result, occupied, blocked, floor, reversed,
                        DecorKind.COUCH, across, compact ? 1 : 2,
                        entrance, stairs, routes, target, 1);
                addRepeatedDecorations(result, occupied, blocked, floor, structured,
                        DecorKind.PLANTER, longAxis, compact ? 1 : 2,
                        entrance, stairs, routes, target, 1);
            }
        }
    }

    private static int addRepeatedDecorations(
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            List<BlockPos> candidates,
            DecorKind kind,
            Direction facing,
            int wanted,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            int clearance) {
        int added = 0;
        for (BlockPos candidate : candidates) {
            if (added >= wanted) break;
            if (addDecoration(result, occupied, blocked, floor,
                    new Decoration(candidate, kind, facing),
                    entrance, stairs, routes, target, clearance)) {
                added++;
            }
        }
        return added;
    }

    private static void addCubiclePods(
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            Direction facing,
            int wanted,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target) {
        int added = 0;
        for (int z = floor.bounds().minZ + 2;
                z <= floor.bounds().maxZ - 2 && added < wanted; z += 4) {
            for (int x = floor.bounds().minX + 2;
                    x <= floor.bounds().maxX - 2 && added < wanted; x += 4) {
                if (addDecoration(result, occupied, blocked, floor,
                        new Decoration(new BlockPos(x, floor.y(), z),
                                DecorKind.CUBICLE_POD, facing),
                        entrance, stairs, routes, target, 1)) {
                    added++;
                }
            }
        }
    }

    private static int addRoomPartitions(
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            Direction longAxis,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            int limit) {
        if (limit <= 0) return 0;
        boolean splitAlongX = longAxis.getAxis() == Direction.Axis.X;
        int fixed = splitAlongX
                ? floor.bounds().minX + floor.bounds().width() * 2 / 3
                : floor.bounds().minZ + floor.bounds().depth() * 2 / 3;
        int start = splitAlongX ? floor.bounds().minZ + 1 : floor.bounds().minX + 1;
        int end = splitAlongX ? floor.bounds().maxZ - 1 : floor.bounds().maxX - 1;
        Direction facing = splitAlongX ? Direction.EAST : Direction.SOUTH;
        int added = 0;
        for (int variable = start; variable <= end && added < limit; variable++) {
            BlockPos position = splitAlongX
                    ? new BlockPos(fixed, floor.y(), variable)
                    : new BlockPos(variable, floor.y(), fixed);
            if (addDecoration(result, occupied, blocked, floor,
                    new Decoration(position, DecorKind.ROOM_PARTITION, facing),
                    entrance, stairs, routes, target, 0)) {
                added++;
            }
        }
        return added;
    }

    private static void addFullHeightPartitionBases(
            ServerLevel level,
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            Direction longAxis,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            FloorTheme theme,
            long seed,
            int limit) {
        if (limit <= 0) return;
        boolean fixedX = longAxis.getAxis() == Direction.Axis.X;
        int fixed = bestPartitionCoordinate(floor, occupied, fixedX, seed);
        if (fixed == Integer.MIN_VALUE) return;
        int start = fixedX ? floor.bounds().minZ + 1 : floor.bounds().minX + 1;
        int end = fixedX ? floor.bounds().maxZ - 1 : floor.bounds().maxX - 1;
        int columns = addFullHeightPartitionLine(
                level, result, occupied, blocked, floor, fixedX, fixed, start, end,
                entrance, stairs, routes, target, limit);
        if (floor.cells().size() < 132
                || (theme != FloorTheme.OPERATIONS && theme != FloorTheme.STORAGE)
                || columns >= limit) {
            return;
        }
        boolean secondaryFixedX = !fixedX;
        int secondaryFixed = bestPartitionCoordinate(
                floor, occupied, secondaryFixedX, seed ^ 0x9E3779B97F4A7C15L);
        if (secondaryFixed == Integer.MIN_VALUE) return;
        int secondaryStart = secondaryFixedX
                ? floor.bounds().minZ + 1 : floor.bounds().minX + 1;
        int secondaryEnd = secondaryFixedX
                ? floor.bounds().maxZ - 1 : floor.bounds().maxX - 1;
        addFullHeightPartitionLine(
                level, result, occupied, blocked, floor, secondaryFixedX, secondaryFixed,
                secondaryStart, secondaryEnd, entrance, stairs, routes, target,
                limit - columns);
    }

    private static int addFullHeightPartitionLine(
            ServerLevel level,
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            boolean fixedX,
            int fixed,
            int start,
            int end,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            int limit) {
        Direction facing = fixedX ? Direction.EAST : Direction.SOUTH;
        int columns = 0;
        for (int variable = start;
                variable <= end && columns < limit;
                variable++) {
            BlockPos position = fixedX
                    ? new BlockPos(fixed, floor.y(), variable)
                    : new BlockPos(variable, floor.y(), fixed);
            if (!fullHeightColumnClearOfStairs(level, position, stairs)) continue;
            if (addDecoration(result, occupied, blocked, floor,
                    new Decoration(position, DecorKind.FULL_HEIGHT_PARTITION, facing),
                    entrance, stairs, routes, target, 0)) {
                columns++;
            }
        }
        return columns;
    }

    private static int bestPartitionCoordinate(
            FloorProfile floor, Set<BlockPos> occupied, boolean fixedX, long seed) {
        int minimum = fixedX ? floor.bounds().minX + 2 : floor.bounds().minZ + 2;
        int maximum = fixedX ? floor.bounds().maxX - 2 : floor.bounds().maxZ - 2;
        if (minimum > maximum) return Integer.MIN_VALUE;
        int center = (minimum + maximum) / 2;
        int best = Integer.MIN_VALUE;
        long bestScore = Long.MIN_VALUE;
        for (int fixed = minimum; fixed <= maximum; fixed++) {
            int usable = 0;
            int start = fixedX ? floor.bounds().minZ + 1 : floor.bounds().minX + 1;
            int end = fixedX ? floor.bounds().maxZ - 1 : floor.bounds().maxX - 1;
            for (int variable = start; variable <= end; variable++) {
                BlockPos position = fixedX
                        ? new BlockPos(fixed, floor.y(), variable)
                        : new BlockPos(variable, floor.y(), fixed);
                if (floor.cells().contains(position) && !occupied.contains(position)) usable++;
            }
            long score = usable * 1_000L - Math.abs(fixed - center) * 10L
                    + Math.floorMod(positionScore(seed, fixed, floor.y()), 10L);
            if (usable >= 4 && score > bestScore) {
                best = fixed;
                bestScore = score;
            }
        }
        return best;
    }

    private static int addBoundaryPartitionBases(
            ServerLevel level,
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            int limit) {
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos position : floor.cells()) {
            for (Direction direction : HORIZONTAL) {
                BlockPos outside = position.relative(direction);
                if (!floor.cells().contains(outside) && isPassage(level, outside)) {
                    candidates.add(position);
                }
            }
        }
        int columns = 0;
        for (BlockPos position : candidates.stream()
                .filter(position -> fullHeightColumnClearOfStairs(
                        level, position, stairs))
                .sorted(Comparator.comparingInt((BlockPos position) -> position.getX())
                        .thenComparingInt(position -> position.getZ()))
                .toList()) {
            if (columns >= limit) break;
            if (addDecoration(
                    result, occupied, blocked, floor,
                    new Decoration(
                            position, DecorKind.FULL_HEIGHT_PARTITION,
                            boundaryFacing(floor, position)),
                    entrance, stairs, routes, target, 0)) {
                columns++;
            }
        }
        return columns;
    }

    private static boolean fullHeightColumnClearOfStairs(
            ServerLevel level,
            BlockPos position,
            List<StairRun> stairs) {
        int height = ceilingDistance(level, position);
        if (height < 2) return false;
        int wallMinY = position.getY();
        int wallMaxY = position.getY() + height - 1;
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step < stair.rise(); step++) {
                for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                    BlockPos stairBlock = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step);
                    if (stairBlock.getX() == position.getX()
                            && stairBlock.getZ() == position.getZ()
                            && wallMaxY >= stairBlock.getY()
                            && wallMinY <= stairBlock.getY() + STAIR_HEADROOM) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Direction boundaryFacing(FloorProfile floor, BlockPos position) {
        return java.util.Arrays.stream(HORIZONTAL)
                .filter(direction -> !floor.cells().contains(position.relative(direction)))
                .findFirst()
                .orElse(Direction.NORTH);
    }

    private static boolean addWallBackedDecoration(
            ServerLevel level,
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            DecorKind kind,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            long seed) {
        List<WallFixtureCandidate> candidates = new ArrayList<>();
        Set<BlockPos> plannedWalls = result.stream()
                .filter(decoration -> decoration.kind() == DecorKind.FULL_HEIGHT_PARTITION)
                .map(Decoration::position)
                .collect(java.util.stream.Collectors.toSet());
        for (BlockPos position : floor.cells()) {
            for (Direction facing : HORIZONTAL) {
                BlockPos backing = position.relative(facing.getOpposite());
                BlockPos approach = position.relative(facing);
                boolean solidBacking = level.getBlockState(backing).blocksMotion()
                        && level.getBlockState(backing.above()).blocksMotion();
                if ((!solidBacking && !plannedWalls.contains(backing))
                        || !floor.cells().contains(approach)
                        || occupied.contains(approach)
                        || blocked.contains(approach)
                        || !isPassage(level, approach)
                        || (kind == DecorKind.EXPLOSIVE_CANISTER
                                && !safeCanisterPosition(
                                        position, entrance, stairs, routes, target))) {
                    continue;
                }
                candidates.add(new WallFixtureCandidate(
                        position, facing,
                        positionScore(seed ^ 0xB7E151628AED2A6BL,
                                position.getX(), position.getZ())));
            }
        }
        candidates.sort(Comparator.comparingLong(WallFixtureCandidate::score)
                .thenComparingInt(candidate -> candidate.position().getX())
                .thenComparingInt(candidate -> candidate.position().getZ())
                .thenComparingInt(candidate -> candidate.facing().ordinal()));
        for (WallFixtureCandidate candidate : candidates) {
            if (addDecoration(result, occupied, blocked, floor,
                    new Decoration(candidate.position(), kind, candidate.facing()),
                    entrance, stairs, routes, target, 0)) {
                occupied.add(candidate.position().relative(candidate.facing()));
                return true;
            }
        }
        return false;
    }

    private static boolean addPartitionBackedCanister(
            ServerLevel level,
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            List<FloorProfile> floors,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            long seed) {
        if (result.size() + 2 > MAX_DECORATIONS) return false;
        for (int safetyPass = 0; safetyPass < 2; safetyPass++) {
            for (FloorProfile floor : floors) {
                if (partitionBasesOnFloor(result, floor.y())
                                >= maximumPartitionBases(floor.cells().size())
                        || furnishingsOnFloor(result, floor.y())
                                >= maximumFurnishings(floor)
                        || furnishingFootprintOnFloor(result, floor.y()) + 1
                                > maximumFurnishingFootprint(floor)) {
                    continue;
                }
                for (BlockPos position : ordered(
                        floor.cells(), seed ^ 0x43414E4953544552L ^ floor.y())) {
                    if (safetyPass == 0
                            ? !safeCanisterPosition(position, entrance, stairs, routes, target)
                            : !safeCompactCanisterPosition(
                                    position, entrance, stairs, routes, target)) {
                        continue;
                    }
                    for (Direction facing : orderedDirections(seed, position)) {
                        BlockPos backing = position.relative(facing.getOpposite());
                        BlockPos approach = position.relative(facing);
                        if (!floor.cells().contains(backing)
                                || !floor.cells().contains(approach)
                                || occupied.contains(position)
                                || occupied.contains(backing)
                                || occupied.contains(approach)
                                || blocked.contains(position)
                                || blocked.contains(backing)
                                || blocked.contains(approach)
                                || !isPassage(level, approach)
                                || !fullHeightColumnClearOfStairs(level, backing, stairs)
                                || !preservesFloorConnectivity(
                                        floor, List.of(backing, position), blocked,
                                        entrance, stairs, routes, target)) {
                            continue;
                        }
                        Decoration partition = new Decoration(
                                backing, DecorKind.FULL_HEIGHT_PARTITION, facing);
                        Decoration canister = new Decoration(
                                position, DecorKind.EXPLOSIVE_CANISTER, facing);
                        result.add(partition);
                        result.add(canister);
                        blocked.add(backing);
                        blocked.add(position);
                        occupied.add(backing);
                        occupied.add(position);
                        occupied.add(approach);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean addComputerDesk(
            ServerLevel level,
            List<Decoration> result,
            Set<BlockPos> occupied,
            Set<BlockPos> blocked,
            FloorProfile floor,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target,
            long seed) {
        Direction axis = floor.bounds().width() >= floor.bounds().depth()
                ? Direction.EAST : Direction.SOUTH;
        for (BlockPos position : structuredFloorCandidates(floor, axis)) {
            for (Direction facing : orderedDirections(seed ^ 0x9E3779B97F4A7C15L, position)) {
                BlockPos viewer = position.relative(facing);
                if (!floor.cells().contains(viewer)
                        || !isPassable(level.getBlockState(viewer.above()))) {
                    continue;
                }
                if (addDecoration(result, occupied, blocked, floor,
                        new Decoration(position, DecorKind.COMPUTER_DESK, facing),
                        entrance, stairs, routes, target, 1)) {
                    occupied.add(viewer);
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Decoration> expandFullHeightPartitions(
            ServerLevel level,
            List<FloorProfile> floors,
            List<Decoration> planned) {
        ArrayList<Decoration> expanded = planned.stream()
                .filter(decoration -> decoration.kind() != DecorKind.FULL_HEIGHT_PARTITION)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (expanded.size() >= MAX_DECORATIONS) {
            return List.copyOf(expanded.subList(0, MAX_DECORATIONS));
        }
        Map<Integer, List<Decoration>> columnsByFloor = new LinkedHashMap<>();
        for (FloorProfile floor : floors) columnsByFloor.put(floor.y(), new ArrayList<>());
        for (Decoration decoration : planned) {
            if (decoration.kind() == DecorKind.FULL_HEIGHT_PARTITION) {
                columnsByFloor.computeIfAbsent(
                        decoration.position().getY(), ignored -> new ArrayList<>()).add(decoration);
            }
        }

        int budget = MAX_DECORATIONS - expanded.size();
        Set<BlockPos> selectedColumns = new HashSet<>();
        Set<BlockPos> requiredBacking = planned.stream()
                .filter(decoration -> decoration.kind() == DecorKind.VENDING_MACHINE
                        || decoration.kind() == DecorKind.EXPLOSIVE_CANISTER)
                .map(decoration -> decoration.position().relative(
                        decoration.facing().getOpposite()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (List<Decoration> floorColumns : columnsByFloor.values()) {
            for (Decoration column : floorColumns) {
                if (!requiredBacking.contains(column.position())) continue;
                int used = appendFullHeightColumn(level, expanded, column, budget);
                if (used > 0) {
                    budget -= used;
                    selectedColumns.add(column.position());
                }
            }
        }

        Map<Integer, Integer> nextColumn = new LinkedHashMap<>();
        columnsByFloor.keySet().forEach(floorY -> nextColumn.put(floorY, 0));
        boolean considered;
        do {
            considered = false;
            for (Map.Entry<Integer, List<Decoration>> entry : columnsByFloor.entrySet()) {
                List<Decoration> columns = entry.getValue();
                int index = nextColumn.get(entry.getKey());
                while (index < columns.size()
                        && selectedColumns.contains(columns.get(index).position())) {
                    index++;
                }
                if (index >= columns.size()) {
                    nextColumn.put(entry.getKey(), index);
                    continue;
                }
                Decoration column = columns.get(index);
                nextColumn.put(entry.getKey(), index + 1);
                considered = true;
                int used = appendFullHeightColumn(level, expanded, column, budget);
                if (used > 0) {
                    budget -= used;
                    selectedColumns.add(column.position());
                }
            }
        } while (considered && budget >= 2);
        return List.copyOf(expanded);
    }

    private static int appendFullHeightColumn(
            ServerLevel level,
            List<Decoration> expanded,
            Decoration column,
            int budget) {
        int wallHeight = ceilingDistance(level, column.position());
        if (wallHeight < 2 || wallHeight > budget) return 0;
        for (int offset = 0; offset < wallHeight; offset++) {
            expanded.add(new Decoration(
                    column.position().above(offset),
                    DecorKind.FULL_HEIGHT_PARTITION,
                    column.facing()));
        }
        return wallHeight;
    }

    private static int ceilingDistance(ServerLevel level, BlockPos position) {
        for (int distance = 2; distance <= MAX_STORY_HEIGHT; distance++) {
            if (level.getBlockState(position.above(distance)).blocksMotion()) {
                return distance;
            }
        }
        return -1;
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
        if (isPartition(decoration.kind())
                && partitionBasesOnFloor(result, floor.y())
                        >= maximumPartitionBases(floor.cells().size())) {
            return false;
        }
        if (isFurnishing(decoration.kind())
                && (furnishingsOnFloor(result, floor.y()) >= maximumFurnishings(floor)
                        || furnishingFootprintOnFloor(result, floor.y()) + footprint.size()
                                > maximumFurnishingFootprint(floor))) {
            return false;
        }
        if (footprint.stream().anyMatch(occupied::contains)
                || footprint.stream().anyMatch(blocked::contains)
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

    private static boolean isPartition(DecorKind kind) {
        return kind == DecorKind.ROOM_PARTITION || kind == DecorKind.FULL_HEIGHT_PARTITION;
    }

    private static boolean isFurnishing(DecorKind kind) {
        return !isPartition(kind) && kind != DecorKind.MISSION_TURRET;
    }

    private static int partitionBasesOnFloor(List<Decoration> decorations, int floorY) {
        return Math.toIntExact(decorations.stream()
                .filter(decoration -> decoration.position().getY() == floorY)
                .filter(decoration -> isPartition(decoration.kind()))
                .count());
    }

    private static int furnishingFootprintOnFloor(
            List<Decoration> decorations, int floorY) {
        Set<BlockPos> footprint = new HashSet<>();
        decorations.stream()
                .filter(decoration -> decoration.position().getY() == floorY)
                .filter(decoration -> isFurnishing(decoration.kind()))
                .forEach(decoration -> footprint.addAll(decorationFootprint(
                        decoration.position(), decoration.kind(),
                        decoration.facing().getClockWise())));
        return footprint.size();
    }

    private static long furnishingsOnFloor(List<Decoration> decorations, int floorY) {
        return decorations.stream()
                .filter(decoration -> decoration.position().getY() == floorY)
                .filter(decoration -> switch (decoration.kind()) {
                    case ROOM_PARTITION, FULL_HEIGHT_PARTITION, MISSION_TURRET -> false;
                    default -> true;
                })
                .count();
    }

    private static int maximumFurnishings(FloorProfile floor) {
        return floor.cells().size() >= FULL_THEME_FLOOR_CELLS ? 5 : 4;
    }

    static int maximumPartitionBases(int floorCells) {
        return Math.min(
                MAX_PARTITION_COLUMNS_PER_FLOOR,
                Math.max(6, floorCells / 12));
    }

    private static int maximumFurnishingFootprint(FloorProfile floor) {
        return Math.min(18, Math.max(8, floor.cells().size() / 7));
    }

    private static int wantedFurnishings(FloorProfile floor, FloorTheme theme) {
        int base = switch (theme) {
            case LOBBY, LOUNGE, EXECUTIVE -> 3;
            case OPEN_OFFICE, OPERATIONS, STORAGE -> 4;
        };
        if (floor.cells().size() >= FULL_THEME_FLOOR_CELLS) base++;
        return Math.min(maximumFurnishings(floor), base);
    }

    private static InteriorQuality interiorQuality(
            List<FloorProfile> floors,
            List<Decoration> decorations,
            long seed,
            MissionCatalog.MissionType missionType,
            String missionId) {
        int floorsAtTarget = 0;
        int furnishingShortfall = 0;
        int furnishingExcess = 0;
        int totalFurnishings = 0;
        int partitionFloors = 0;
        int themeAnchors = 0;
        Set<DecorKind> furnishingKinds = new HashSet<>();
        Set<Set<DecorKind>> floorTreatments = new HashSet<>();
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            FloorProfile floor = floors.get(floorIndex);
            int actual = Math.toIntExact(furnishingsOnFloor(decorations, floor.y()));
            FloorTheme theme = floorTheme(
                    seed, floorIndex, floors.size(), missionType, missionId);
            int wanted = wantedFurnishings(floor, theme);
            totalFurnishings += actual;
            if (actual == wanted) floorsAtTarget++;
            furnishingShortfall += Math.max(0, wanted - actual);
            furnishingExcess += Math.max(0, actual - wanted);
            Set<DecorKind> treatment = decorations.stream()
                    .filter(decoration -> decoration.position().getY() == floor.y())
                    .map(Decoration::kind)
                    .filter(MissionBuildingPlanner::isThemeFurnishing)
                    .collect(java.util.stream.Collectors.toSet());
            furnishingKinds.addAll(treatment);
            floorTreatments.add(Set.copyOf(treatment));
            if (realizesTheme(theme, treatment)) {
                themeAnchors++;
            }
            if (decorations.stream().anyMatch(decoration ->
                    decoration.position().getY() == floor.y()
                            && (decoration.kind() == DecorKind.ROOM_PARTITION
                                    || decoration.kind()
                                            == DecorKind.FULL_HEIGHT_PARTITION))) {
                partitionFloors++;
            }
        }
        int canisters = Math.toIntExact(decorations.stream()
                .filter(decoration -> decoration.kind() == DecorKind.EXPLOSIVE_CANISTER)
                .count());
        return new InteriorQuality(
                canisters > 0,
                floorsAtTarget,
                furnishingShortfall,
                furnishingExcess,
                themeAnchors,
                floorTreatments.size(),
                furnishingKinds.size(),
                partitionFloors,
                totalFurnishings,
                canisters);
    }

    private static boolean betterInterior(
            InteriorPlanCandidate candidate, InteriorPlanCandidate current) {
        InteriorQuality next = candidate.quality();
        InteriorQuality best = current.quality();
        int comparison = Boolean.compare(next.hasCanister(), best.hasCanister());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(next.themeAnchors(), best.themeAnchors());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(next.floorsAtTarget(), best.floorsAtTarget());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(best.furnishingShortfall(), next.furnishingShortfall());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(best.furnishingExcess(), next.furnishingExcess());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(
                next.floorTreatmentDiversity(), best.floorTreatmentDiversity());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(best.totalFurnishings(), next.totalFurnishings());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(next.kindDiversity(), best.kindDiversity());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(best.partitionFloors(), next.partitionFloors());
        if (comparison != 0) return comparison > 0;
        comparison = Integer.compare(best.canisters(), next.canisters());
        if (comparison != 0) return comparison > 0;
        return candidate.variant() < current.variant();
    }

    private static boolean realizesTheme(FloorTheme theme, Set<DecorKind> treatment) {
        return treatment.contains(requiredRoleAnchor(theme));
    }

    private static boolean anchorsTheme(FloorTheme theme, DecorKind kind) {
        return kind == requiredRoleAnchor(theme);
    }

    private static boolean isThemeFurnishing(DecorKind kind) {
        return kind != DecorKind.ROOM_PARTITION
                && kind != DecorKind.FULL_HEIGHT_PARTITION
                && kind != DecorKind.MISSION_TURRET
                && kind != DecorKind.EXPLOSIVE_CANISTER;
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
        if (position.getY() == entrance.position().getY()
                        && horizontalDistance(position, entrance.position()) < 6
                || position.getY() == target.getY()
                        && horizontalDistance(position, target) < 6
                || stairs.stream().flatMap(stair -> stairFloorClearanceCells(stair).stream())
                        .anyMatch(clearance -> clearance.getY() == position.getY()
                                && horizontalDistance(position, clearance) < 5)
                || routes.stream().flatMap(route -> route.waypoints().stream()).anyMatch(
                        waypoint -> waypoint.getY() == position.getY()
                                && horizontalDistance(position, waypoint) < 4)) {
            return false;
        }
        return true;
    }

    private static boolean safeCompactCanisterPosition(
            BlockPos position,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            BlockPos target) {
        return (position.getY() != entrance.position().getY()
                        || horizontalDistance(position, entrance.position()) >= 3)
                && (position.getY() != target.getY()
                        || horizontalDistance(position, target) >= 3)
                && stairs.stream().flatMap(stair -> stairFloorClearanceCells(stair).stream())
                        .noneMatch(clearance -> clearance.getY() == position.getY()
                                && horizontalDistance(position, clearance) < 3)
                && routes.stream().flatMap(route -> route.waypoints().stream())
                        .noneMatch(waypoint -> waypoint.getY() == position.getY()
                                && horizontalDistance(position, waypoint) < 2);
    }

    private static int horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
                + Math.abs(first.getZ() - second.getZ());
    }

    private static int blockDistance(BlockPos first, BlockPos second) {
        return horizontalDistance(first, second)
                + Math.abs(first.getY() - second.getY());
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
        Set<BlockPos> available = new HashSet<>(floor.cells());
        available.removeAll(unavailable);
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
            for (BlockPos position : stairLandingCells(stair)) {
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
        ArrayDeque<BlockPos> stack = new ArrayDeque<>();
        visited.add(start);
        stack.push(start);
        while (!stack.isEmpty()) {
            BlockPos current = stack.pop();
            for (int index = HORIZONTAL.length - 1; index >= 0; index--) {
                Direction direction = HORIZONTAL[index];
                BlockPos next = current.relative(direction);
                if (floor.cells().contains(next)
                        && !unavailable.contains(next)
                        && visited.add(next)) {
                    stack.push(next);
                }
            }
        }
        return visited.containsAll(required) && visited.containsAll(available);
    }

    private static BoundingBox siteBounds(List<FloorProfile> floors, Entrance entrance) {
        int minX = floors.stream().mapToInt(value -> value.bounds().minX).min().orElseThrow();
        int maxX = floors.stream().mapToInt(value -> value.bounds().maxX).max().orElseThrow();
        int minZ = floors.stream().mapToInt(value -> value.bounds().minZ).min().orElseThrow();
        int maxZ = floors.stream().mapToInt(value -> value.bounds().maxZ).max().orElseThrow();
        BlockPos entranceEnd = entrance.position().relative(
                entrance.outward(), Math.max(1, entrance.wallDepth()));
        minX = Math.min(minX, Math.min(entrance.position().getX(), entranceEnd.getX()) - 1);
        maxX = Math.max(maxX, Math.max(entrance.position().getX(), entranceEnd.getX()) + 1);
        minZ = Math.min(minZ, Math.min(entrance.position().getZ(), entranceEnd.getZ()) - 1);
        maxZ = Math.max(maxZ, Math.max(entrance.position().getZ(), entranceEnd.getZ()) + 1);
        return new BoundingBox(
                minX, floors.getFirst().y() - 1, minZ,
                maxX, floors.getLast().y() + MAX_STORY_HEIGHT, maxZ);
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
                    .setValue(DoorBlock.OPEN, false);
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
                for (int headroom = 1; headroom <= STAIR_HEADROOM; headroom++) {
                    edits.add(new Edit(stair.above(headroom), Blocks.AIR.defaultBlockState(),
                            EditPolicy.SAFE_REPLACE));
                }
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
                edits.add(new Edit(decoration.position(), Blocks.CAULDRON.defaultBlockState(),
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
            case MISSION_TURRET -> {
                // Entity deployment is owned by MissionService after objective setup succeeds.
            }
            case VENDING_MACHINE -> {
                edits.add(new Edit(decoration.position(),
                        Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState(),
                        EditPolicy.AIR_ONLY));
                edits.add(new Edit(decoration.position().above(),
                        Blocks.GLAZED_TERRACOTTA.pick(DyeColor.CYAN).defaultBlockState()
                                .setValue(HorizontalDirectionalBlock.FACING,
                                        decoration.facing()),
                        EditPolicy.AIR_ONLY));
            }
            case COMPUTER_DESK -> {
                edits.add(new Edit(decoration.position(),
                        Blocks.SMOOTH_QUARTZ.defaultBlockState(), EditPolicy.AIR_ONLY));
                edits.add(new Edit(second,
                        Blocks.SMOOTH_QUARTZ.defaultBlockState(), EditPolicy.AIR_ONLY));
                edits.add(new Edit(decoration.position().above(),
                        Blocks.GLAZED_TERRACOTTA.pick(DyeColor.LIGHT_BLUE).defaultBlockState()
                                .setValue(HorizontalDirectionalBlock.FACING,
                                        decoration.facing()),
                        EditPolicy.AIR_ONLY));
            }
            case FULL_HEIGHT_PARTITION -> edits.add(new Edit(
                    decoration.position(),
                    Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState(),
                    EditPolicy.AIR_ONLY));
        }
    }

    private static BlockState explosiveCanisterState() {
        net.minecraft.world.level.block.Block canister = BuiltInRegistries.BLOCK.getValue(
                Identifier.fromNamespaceAndPath("cyberdeck", "explosive_canister"));
        return canister == null || canister == Blocks.AIR
                ? Blocks.GLAZED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState()
                : canister.defaultBlockState();
    }

    private static boolean wallFixturesHaveBacking(
            ServerLevel level, Site site, List<Edit> plannedEdits) {
        Map<BlockPos, BlockState> overlay = new HashMap<>();
        for (Edit edit : plannedEdits) overlay.put(edit.position(), edit.state());
        for (Decoration decoration : site.decorations()) {
            if (decoration.kind() != DecorKind.VENDING_MACHINE) continue;
            BlockPos backing = decoration.position().relative(
                    decoration.facing().getOpposite());
            BlockPos approach = decoration.position().relative(decoration.facing());
            if (!plannedState(level, backing, overlay).blocksMotion()
                    || !plannedState(level, backing.above(), overlay).blocksMotion()
                    || !floorMaskCells(site, approach.getY()).contains(approach)
                    || !plannedPassage(level, approach, overlay)) {
                return false;
            }
        }
        return true;
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
        return circulationRemainsAccessible(level, site, plannedEdits, Set.of());
    }

    private static boolean circulationRemainsAccessible(
            ServerLevel level,
            Site site,
            List<Edit> plannedEdits,
            Set<BlockPos> occupied) {
        return depthFirstAudit(level, site, plannedEdits, occupied).accessible();
    }

    private static DfsAudit depthFirstAudit(
            ServerLevel level,
            Site site,
            List<Edit> plannedEdits,
            Set<BlockPos> occupied) {
        ArrayList<BlockPos> unreachable = new ArrayList<>();
        if (site.floorMasks().isEmpty()) {
            return new DfsAudit(false, 0, List.of(site.entrance().position()));
        }
        Map<BlockPos, BlockState> overlay = new HashMap<>();
        for (Edit edit : plannedEdits) overlay.put(edit.position(), edit.state());
        if (!entranceRouteIsAccessible(level, site, overlay)) {
            unreachable.add(navigationTarget(site));
        }
        Map<BlockPos, List<BlockPos>> stairEdges = new HashMap<>();
        for (StairRun stair : site.stairs()) {
            if (!addValidatedStairEdges(level, site, stair, overlay, stairEdges)) {
                unreachable.add(stair.start());
            }
        }

        BlockPos start = site.entrance().position()
                .relative(site.entrance().outward().getOpposite());
        HashSet<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> stack = new ArrayDeque<>();
        if (floorMask(site, start.getY()).filter(mask -> mask.contains(start)).isPresent()
                && !occupied.contains(start) && plannedPassage(level, start, overlay)) {
            visited.add(start);
            stack.push(start);
        } else {
            unreachable.add(start);
        }
        Set<Integer> floorYs = Set.copyOf(site.floorYs());
        Map<Integer, Set<BlockPos>> cellsByFloor = new HashMap<>();
        for (FloorMask mask : site.floorMasks()) {
            cellsByFloor.put(mask.floorY(), Set.copyOf(mask.cells()));
        }
        while (!stack.isEmpty()) {
            BlockPos current = stack.pop();
            for (int index = HORIZONTAL.length - 1; index >= 0; index--) {
                BlockPos next = current.relative(HORIZONTAL[index]);
                if (!floorYs.contains(next.getY())
                        || !cellsByFloor.getOrDefault(next.getY(), Set.of()).contains(next)
                        || occupied.contains(next)
                        || !plannedPassage(level, next, overlay)
                        || !visited.add(next)) {
                    continue;
                }
                stack.push(next);
            }
            for (BlockPos next : stairEdges.getOrDefault(current, List.of())) {
                if (!occupied.contains(next)
                        && plannedPassage(level, next, overlay)
                        && visited.add(next)) {
                    stack.push(next);
                }
            }
        }

        LinkedHashSet<BlockPos> required = new LinkedHashSet<>();
        site.patrolRoutes().forEach(route -> required.addAll(route.waypoints()));
        required.add(start);
        if (!site.entrance().existing()) {
            required.add(start.relative(site.entrance().outward().getClockWise()));
        }
        site.stairs().forEach(stair -> required.addAll(stairLandingCells(stair)));
        BlockState targetState = plannedState(level, site.target(), overlay);
        if (isPassable(targetState)
                && isPassable(plannedState(level, site.target().above(), overlay))) {
            required.add(site.target());
        } else if (java.util.Arrays.stream(HORIZONTAL)
                .map(site.target()::relative).noneMatch(visited::contains)) {
            unreachable.add(site.target());
        }
        required.stream().filter(position -> !visited.contains(position))
                .forEach(unreachable::add);
        for (FloorMask mask : site.floorMasks()) {
            mask.cells().stream()
                    .filter(position -> !occupied.contains(position))
                    .filter(position -> plannedPassage(level, position, overlay))
                    .filter(position -> !visited.contains(position))
                    .forEach(unreachable::add);
        }
        return new DfsAudit(unreachable.isEmpty(), visited.size(), unreachable);
    }

    private static List<Edit> withSolidObjective(Site site, List<Edit> plannedEdits) {
        ArrayList<Edit> objectiveEdits = new ArrayList<>(plannedEdits);
        objectiveEdits.add(new Edit(
                site.target(), Blocks.POLISHED_DEEPSLATE.defaultBlockState(),
                EditPolicy.SAFE_REPLACE));
        return List.copyOf(objectiveEdits);
    }

    private static boolean addValidatedStairEdges(
            ServerLevel level,
            Site site,
            StairRun stair,
            Map<BlockPos, BlockState> overlay,
            Map<BlockPos, List<BlockPos>> edges) {
        Direction across = stair.ascending().getClockWise();
        for (int step = 0; step < stair.rise(); step++) {
            for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                BlockPos stairBlock = stair.start().relative(stair.ascending(), step)
                        .relative(across, lane).above(step);
                BlockState state = plannedState(level, stairBlock, overlay);
                if (!(state.getBlock() instanceof StairBlock)
                        || state.getValue(StairBlock.FACING) != stair.ascending()) {
                    return false;
                }
                for (int headroom = 1; headroom <= STAIR_HEADROOM; headroom++) {
                    if (!isPassable(plannedState(
                            level, stairBlock.above(headroom), overlay))) {
                        return false;
                    }
                }
            }
        }
        for (int lane = 0; lane < STAIR_WIDTH; lane++) {
            BlockPos lower = stair.start().relative(stair.ascending().getOpposite())
                    .relative(across, lane);
            BlockPos upper = stair.start().relative(stair.ascending(), stair.rise())
                    .relative(across, lane).above(stair.rise());
            if (!floorMaskCells(site, lower.getY()).contains(lower)
                    || !floorMaskCells(site, upper.getY()).contains(upper)
                    || !plannedPassage(level, lower, overlay)
                    || !plannedPassage(level, upper, overlay)) {
                return false;
            }
            edges.computeIfAbsent(lower, ignored -> new ArrayList<>()).add(upper);
            edges.computeIfAbsent(upper, ignored -> new ArrayList<>()).add(lower);
        }
        return true;
    }

    private static boolean entranceRouteIsAccessible(
            ServerLevel level, Site site, Map<BlockPos, BlockState> overlay) {
        Entrance entrance = site.entrance();
        Set<BlockPos> groundFloor = floorMaskCells(site, site.floorYs().getFirst());
        if (groundFloor.isEmpty()
                || !hasExteriorApproach(
                        level,
                        new FloorProfile(
                                site.floorYs().getFirst(), groundFloor, rect(groundFloor)),
                        navigationTarget(site), entrance.outward())) {
            return false;
        }
        Direction across = entrance.outward().getClockWise();
        int lanes = entrance.existing() ? 1 : 2;
        for (int lane = 0; lane < lanes; lane++) {
            BlockPos door = entrance.position().relative(across, lane);
            BlockPos inside = door.relative(entrance.outward().getOpposite());
            if (!floorMaskCells(site, inside.getY()).contains(inside)
                    || !plannedPassage(level, inside, overlay)) {
                return false;
            }
            for (int depth = 0; depth <= entrance.wallDepth(); depth++) {
                BlockPos corridor = door.relative(entrance.outward(), depth);
                if (!plannedPassage(level, corridor, overlay)) return false;
            }
        }
        return true;
    }

    private static Set<BlockPos> reachableFloorCells(
            ServerLevel level, Site site, int floorY, List<Edit> plannedEdits) {
        return reachableFloorCells(level, site, floorY, plannedEdits, Set.of());
    }

    private static Set<BlockPos> reachableFloorCells(
            ServerLevel level,
            Site site,
            int floorY,
            List<Edit> plannedEdits,
            Set<BlockPos> occupied) {
        Map<BlockPos, BlockState> overlay = new HashMap<>();
        for (Edit edit : plannedEdits) overlay.put(edit.position(), edit.state());
        BlockPos start = floorStart(site, floorY);
        Set<BlockPos> allowed = floorMaskCells(site, floorY);
        if (start == null || occupied.contains(start)
                || !allowed.contains(start)
                || !plannedPassage(level, start, overlay)) return Set.of();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> stack = new ArrayDeque<>();
        visited.add(start);
        stack.push(start);
        while (!stack.isEmpty()) {
            BlockPos current = stack.pop();
            for (int index = HORIZONTAL.length - 1; index >= 0; index--) {
                Direction direction = HORIZONTAL[index];
                BlockPos next = current.relative(direction);
                if (!allowed.contains(next) || visited.contains(next)
                        || occupied.contains(next)
                        || !plannedPassage(level, next, overlay)) {
                    continue;
                }
                visited.add(next);
                stack.push(next);
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
        int siteMinChunkX = Math.floorDiv(bounds.minX(), 16);
        int siteMaxChunkX = Math.floorDiv(bounds.maxX(), 16);
        int siteMinChunkZ = Math.floorDiv(bounds.minZ(), 16);
        int siteMaxChunkZ = Math.floorDiv(bounds.maxZ(), 16);
        if ((siteMaxChunkX - siteMinChunkX + 1)
                        * (siteMaxChunkZ - siteMinChunkZ + 1) > 9) {
            return false;
        }
        // Arnis templates can cross their origin chunk. Stabilize one neighboring chunk before
        // reading facade or interior states so a later backing-wall lookup cannot change a plan.
        int minChunkX = siteMinChunkX - 1;
        int maxChunkX = siteMaxChunkX + 1;
        int minChunkZ = siteMinChunkZ - 1;
        int maxChunkZ = siteMaxChunkZ + 1;
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

    private static boolean isCompleteDoor(ServerLevel level, BlockPos lowerPosition) {
        BlockState lower = level.getBlockState(lowerPosition);
        BlockState upper = level.getBlockState(lowerPosition.above());
        return lower.getBlock() instanceof DoorBlock
                && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && upper.getBlock() == lower.getBlock()
                && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    private static boolean isPassable(BlockState state) {
        return state.isAir() || state.canBeReplaced()
                || state.getBlock() instanceof DoorBlock
                        && (state.getValue(DoorBlock.OPEN) || !state.is(Blocks.IRON_DOOR));
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
                    WATER_COOLER, EXPLOSIVE_CANISTER, MISSION_TURRET,
                    VENDING_MACHINE, FULL_HEIGHT_PARTITION -> List.of(position);
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
        BlockPos navigationEnd = entrance.position().relative(
                entrance.outward(), entrance.wallDepth());
        if (!contains(bounds, entrance.position())
                || !contains(bounds, entranceEnd.above(2))
                || !contains(bounds, navigationEnd.above(2))) {
            return false;
        }
        if (!entrance.existing()) {
            Direction across = entrance.outward().getClockWise();
            BlockPos secondLane = entrance.position().relative(across);
            BlockPos secondEnd = secondLane.relative(
                    entrance.outward(), Math.max(0, entrance.wallDepth() - 1));
            BlockPos secondNavigationEnd = secondLane.relative(
                    entrance.outward(), entrance.wallDepth());
            if (!contains(bounds, secondLane)
                    || !contains(bounds, secondEnd.above(2))
                    || !contains(bounds, secondNavigationEnd.above(2))) {
                return false;
            }
        }
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step < stair.rise(); step++) {
                for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                    BlockPos position = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step + STAIR_HEADROOM);
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
                                && !contains(bounds, position.above())
                        || decoration.kind() == DecorKind.MISSION_TURRET
                                && !turretEnvelopeWithinBounds(bounds, position)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validFloorMasks(
            BoundingBox bounds,
            List<Integer> floorYs,
            BlockPos target,
            Entrance entrance,
            List<StairRun> stairs,
            List<PatrolRoute> routes,
            List<FloorMask> floorMasks) {
        if (floorMasks.size() != floorYs.size()
                || floorMasks.stream().map(FloorMask::floorY).distinct().count()
                        != floorMasks.size()
                || !floorMasks.stream().map(FloorMask::floorY)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(Set.copyOf(floorYs))) {
            return false;
        }
        Map<Integer, Set<BlockPos>> cellsByFloor = new HashMap<>();
        for (FloorMask mask : floorMasks) {
            Set<BlockPos> cells = Set.copyOf(mask.cells());
            if (cells.stream().anyMatch(cell -> !contains(bounds, cell)
                    || horizontalDistance(cell, entrance.position())
                            > MAX_ENTRANCE_TO_MISSION_DISTANCE)) {
                return false;
            }
            cellsByFloor.put(mask.floorY(), cells);
        }
        BlockPos inside = entrance.position().relative(entrance.outward().getOpposite());
        Set<BlockPos> entranceFloor = cellsByFloor.get(inside.getY());
        if (entranceFloor == null || !entranceFloor.contains(inside)) return false;
        if (!entrance.existing()
                && !entranceFloor.contains(inside.relative(entrance.outward().getClockWise()))) {
            return false;
        }
        if (!cellsByFloor.getOrDefault(target.getY(), Set.of()).contains(target)) return false;
        for (PatrolRoute route : routes) {
            if (!cellsByFloor.getOrDefault(route.floorY(), Set.of())
                    .containsAll(route.waypoints())) {
                return false;
            }
        }
        for (StairRun stair : stairs) {
            Direction across = stair.ascending().getClockWise();
            int upperY = stair.start().getY() + stair.rise();
            Set<BlockPos> lowerCells = cellsByFloor.getOrDefault(stair.start().getY(), Set.of());
            Set<BlockPos> upperCells = cellsByFloor.getOrDefault(upperY, Set.of());
            for (int lane = 0; lane < STAIR_WIDTH; lane++) {
                for (int step = 0; step < stair.rise(); step++) {
                    BlockPos projection = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane);
                    if (!lowerCells.contains(projection)
                            || !upperCells.contains(projection.atY(upperY))) {
                        return false;
                    }
                }
            }
            for (BlockPos landing : stairLandingCells(stair)) {
                if (!cellsByFloor.getOrDefault(landing.getY(), Set.of()).contains(landing)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Optional<FloorMask> floorMask(Site site, int floorY) {
        return site.floorMasks().stream()
                .filter(mask -> mask.floorY() == floorY)
                .findFirst();
    }

    private static Set<BlockPos> floorMaskCells(Site site, int floorY) {
        return site.missionCells(floorY);
    }

    private static boolean decorationUsesUpperBlock(DecorKind kind) {
        return switch (kind) {
            case PLANTER, CUBICLE_DESK, ROOM_PARTITION, CUBICLE_POD,
                    SERVER_RACK, WATER_COOLER, VENDING_MACHINE, COMPUTER_DESK -> true;
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
        putBounds(tag, "", bounds);
    }

    private static void putBounds(
            CompoundTag tag, String prefix, BoundingBox bounds) {
        tag.putInt(prefix + "MinX", bounds.minX());
        tag.putInt(prefix + "MinY", bounds.minY());
        tag.putInt(prefix + "MinZ", bounds.minZ());
        tag.putInt(prefix + "MaxX", bounds.maxX());
        tag.putInt(prefix + "MaxY", bounds.maxY());
        tag.putInt(prefix + "MaxZ", bounds.maxZ());
    }

    private static BoundingBox readBounds(CompoundTag tag) {
        return readBounds(tag, "");
    }

    private static BoundingBox readBounds(CompoundTag tag, String prefix) {
        return new BoundingBox(
                tag.getIntOr(prefix + "MinX", 0),
                tag.getIntOr(prefix + "MinY", 0),
                tag.getIntOr(prefix + "MinZ", 0),
                tag.getIntOr(prefix + "MaxX", -1),
                tag.getIntOr(prefix + "MaxY", -1),
                tag.getIntOr(prefix + "MaxZ", -1));
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

    private static String encodeFloorMasks(List<FloorMask> floorMasks) {
        return floorMasks.stream().map(mask -> mask.floorY() + "@" + mask.cells().stream()
                        .map(MissionBuildingPlanner::encodePos)
                        .collect(java.util.stream.Collectors.joining("|")))
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static List<FloorMask> decodeFloorMasks(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        ArrayList<FloorMask> result = new ArrayList<>();
        for (String entry : encoded.split(";", -1)) {
            if (entry.isBlank() || result.size() >= MAX_FLOORS) {
                throw new IllegalArgumentException("invalid mission floor masks");
            }
            String[] parts = entry.split("@", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid mission floor mask");
            }
            ArrayList<BlockPos> cells = new ArrayList<>();
            for (String cell : parts[1].split("\\|", -1)) {
                if (cell.isBlank() || cells.size() >= MAX_MISSION_FLOOR_CELLS) {
                    throw new IllegalArgumentException("invalid mission floor mask cells");
                }
                cells.add(decodePos(cell));
            }
            result.add(new FloorMask(Integer.parseInt(parts[0]), cells));
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

    private record InteriorQuality(
            boolean hasCanister,
            int floorsAtTarget,
            int furnishingShortfall,
            int furnishingExcess,
            int themeAnchors,
            int floorTreatmentDiversity,
            int kindDiversity,
            int partitionFloors,
            int totalFurnishings,
            int canisters) {
    }

    private record InteriorPlanCandidate(Site site, InteriorQuality quality, int variant) {
    }

    private record FloorStack(List<FloorProfile> floors, long score) {
        private FloorStack {
            floors = List.copyOf(floors);
        }
    }

    private record FloorWindow(
            int depth, int width, int lateralStart, int areaDelta, long score) {
        int area() { return depth * width; }
    }

    private record ChunkCandidate(int chunkX, int chunkZ, int distance, long score) {
    }

    private record PlannedSiteCandidate(Site site, int distance, long score) {
    }

    private record PlanningResult(Optional<Site> site, String failure) {
        private static PlanningResult accepted(Site site) {
            return new PlanningResult(Optional.of(site), "accepted");
        }

        private static PlanningResult rejected(String failure) {
            return new PlanningResult(Optional.empty(), failure);
        }
    }

    private record StairCandidate(StairRun run, int edits, long score) {
    }

    private record WallFixtureCandidate(BlockPos position, Direction facing, long score) {
    }

    private record TurretCandidate(
            BlockPos position,
            Direction facing,
            int priority,
            int arcScore,
            int entranceDistance,
            long tieBreaker) {
    }
}
