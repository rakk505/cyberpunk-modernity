package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.server.level.ServerLevel;

/** Deterministic large-building fallback when an imported district has no safe authored profile. */
final class MainlineBuildingGenerator {
    private static final int INTERIOR_SIZE = 12;
    private static final int FLOOR_SPACING = 4;
    private static final int SEARCH_RADIUS_CHUNKS = 16;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;

    private MainlineBuildingGenerator() {
    }

    static MissionBuildingPlanner.Site generate(
            ServerLevel level,
            StoryMissionCatalog.StoryMission mission,
            MainlineQuestData reservations) {
        MegacityLayout layout = NeonCityGenerator.layout();
        MegacityLayout.Node center = layout.node(mission.primaryDistrict());
        int centerChunkX = Math.floorDiv(center.x(), 16);
        int centerChunkZ = Math.floorDiv(center.z(), 16);
        long seed = MegacityLayout.mix(
                level.getSeed() ^ layout.seed() ^ mission.id().hashCode(),
                mission.primaryDistrict().ordinal(), mission.requestedFloors());
        List<ChunkCandidate> candidates = new ArrayList<>();
        for (int dz = -SEARCH_RADIUS_CHUNKS; dz <= SEARCH_RADIUS_CHUNKS; dz++) {
            for (int dx = -SEARCH_RADIUS_CHUNKS; dx <= SEARCH_RADIUS_CHUNKS; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                int worldX = (chunkX << 4) + 8;
                int worldZ = (chunkZ << 4) + 8;
                MegacityLayout.Location location = layout.locateDistrict(worldX, worldZ);
                if (!location.insideCity() || location.district() != mission.primaryDistrict()
                        || ArnisPatchLibrary.select(layout, chunkX, chunkZ).isEmpty()) {
                    continue;
                }
                candidates.add(new ChunkCandidate(
                        chunkX, chunkZ, Math.max(Math.abs(dx), Math.abs(dz)),
                        MegacityLayout.mix(seed, chunkX, chunkZ)));
            }
        }
        candidates.sort(Comparator.comparingInt(ChunkCandidate::distance)
                .thenComparingLong(ChunkCandidate::score)
                .thenComparingInt(ChunkCandidate::chunkX)
                .thenComparingInt(ChunkCandidate::chunkZ));

        UUID reservationOwner = UUID.nameUUIDFromBytes(
                ("cyberdeck:mainline-site:" + mission.id()).getBytes(StandardCharsets.UTF_8));
        for (ChunkCandidate candidate : candidates) {
            BlockPos origin = new BlockPos(
                    (candidate.chunkX() << 4) + 2,
                    NeonCityGenerator.CITY_GROUND_Y + 1,
                    (candidate.chunkZ() << 4) + 2);
            MissionBuildingPlanner.Site site = createSite(
                    mission.primaryDistrict(), mission.id(), origin,
                    mission.requestedFloors(), candidate.score());
            if (reservations.conflicts(site, mission.id())
                    || MissionSiteData.get(level).isReservedByOther(
                            site.id(), site, reservationOwner)) {
                continue;
            }
            NeonCityGenerator.generateNow(level, candidate.chunkX(), candidate.chunkZ(), 1);
            Map<BlockPos, BlockState> originals = captureArea(level, site);
            if (originals == null || !level.getEntities(
                    (Entity) null,
                    new net.minecraft.world.phys.AABB(
                            site.bounds().minX(), site.bounds().minY(), site.bounds().minZ(),
                            site.bounds().maxX() + 1, site.bounds().maxY() + 1,
                            site.bounds().maxZ() + 1),
                    Entity::isAlive).isEmpty()) {
                continue;
            }
            buildTower(level, site, mission.primaryDistrict());
            String failure = MissionBuildingPlanner.preflightFailure(level, site);
            if (failure == null) return site;
            restoreArea(level, originals);
            Cyberdeck.LOGGER.warn(
                    "[Mainline] generated tower {} rejected for {}: {}",
                    site.id(), mission.id(), failure);
        }
        return null;
    }

    static MissionBuildingPlanner.Site createSite(
            District district,
            String missionId,
            BlockPos origin,
            int floorCount,
            long planSeed) {
        if (floorCount < 2 || floorCount > 5) {
            throw new IllegalArgumentException("mainline tower floors outside 2..5");
        }
        List<Integer> floorYs = new ArrayList<>();
        List<MissionBuildingPlanner.FloorMask> masks = new ArrayList<>();
        List<MissionBuildingPlanner.PatrolRoute> routes = new ArrayList<>();
        List<MissionBuildingPlanner.StairRun> stairs = new ArrayList<>();
        List<MissionBuildingPlanner.Decoration> decorations = new ArrayList<>();
        MissionBuildingPlanner.Entrance entrance = new MissionBuildingPlanner.Entrance(
                origin.offset(5, 0, 0), Direction.NORTH, 3, false);

        for (int floor = 0; floor < floorCount; floor++) {
            int yOffset = floor * FLOOR_SPACING;
            int floorY = origin.getY() + yOffset;
            floorYs.add(floorY);
            masks.add(floorMask(origin, floorY));
            routes.add(new MissionBuildingPlanner.PatrolRoute(floorY, List.of(
                    origin.offset(3, yOffset, 3),
                    origin.offset(3, yOffset, 9),
                    origin.offset(9, yOffset, 9),
                    origin.offset(9, yOffset, 3))));
            addFloorDecorations(decorations, origin, floor, yOffset);
            if (floor + 1 < floorCount) {
                int stairX = floor % 2 == 0 ? 1 : 7;
                stairs.add(new MissionBuildingPlanner.StairRun(
                        origin.offset(stairX, yOffset, 7),
                        Direction.NORTH,
                        FLOOR_SPACING));
            }
        }
        int topY = floorYs.getLast();
        BoundingBox bounds = new BoundingBox(
                origin.getX(), origin.getY() - 1, origin.getZ() - 3,
                origin.getX() + INTERIOR_SIZE - 1, topY + FLOOR_SPACING - 1,
                origin.getZ() + INTERIOR_SIZE - 1);
        return new MissionBuildingPlanner.Site(
                "mainline:" + missionId + ":" + Math.floorDiv(origin.getX(), 16)
                        + ":" + Math.floorDiv(origin.getZ(), 16),
                district,
                bounds,
                floorYs,
                origin.offset(6, (floorCount - 1) * FLOOR_SPACING, 6),
                entrance,
                stairs,
                routes,
                decorations,
                masks,
                planSeed);
    }

    private static MissionBuildingPlanner.FloorMask floorMask(BlockPos origin, int floorY) {
        List<BlockPos> cells = new ArrayList<>(100);
        for (int z = 1; z <= 10; z++) {
            for (int x = 1; x <= 10; x++) {
                cells.add(new BlockPos(origin.getX() + x, floorY, origin.getZ() + z));
            }
        }
        return new MissionBuildingPlanner.FloorMask(floorY, cells);
    }

    private static void addFloorDecorations(
            List<MissionBuildingPlanner.Decoration> decorations,
            BlockPos origin,
            int floor,
            int yOffset) {
        if (floor == 0) {
            decorations.add(decoration(origin, 4, yOffset, 3,
                    MissionBuildingPlanner.DecorKind.RECEPTION_DESK, Direction.NORTH));
            decorations.add(decoration(origin, 9, yOffset, 2,
                    MissionBuildingPlanner.DecorKind.PLANTER, Direction.NORTH));
            decorations.add(decoration(origin, 4, yOffset, 7,
                    MissionBuildingPlanner.DecorKind.EXPLOSIVE_CANISTER, Direction.NORTH));
            decorations.add(decoration(origin, 10, yOffset, 8,
                    MissionBuildingPlanner.DecorKind.VENDING_MACHINE, Direction.WEST));
            decorations.add(decoration(origin, 4, yOffset, 9,
                    MissionBuildingPlanner.DecorKind.COUCH, Direction.NORTH));
            return;
        }
        decorations.add(decoration(origin, 4, yOffset, 2,
                MissionBuildingPlanner.DecorKind.CUBICLE_DESK, Direction.NORTH));
        decorations.add(decoration(origin, 4, yOffset, 5,
                MissionBuildingPlanner.DecorKind.COMPUTER_DESK, Direction.NORTH));
        decorations.add(decoration(origin, 4, yOffset, 8,
                MissionBuildingPlanner.DecorKind.COUCH, Direction.NORTH));
        decorations.add(decoration(origin, 9, yOffset, 4,
                MissionBuildingPlanner.DecorKind.SERVER_RACK, Direction.NORTH));
        decorations.add(decoration(origin, 9, yOffset, 7,
                MissionBuildingPlanner.DecorKind.EXPLOSIVE_CANISTER, Direction.NORTH));
        decorations.add(decoration(origin, 10, yOffset, 9,
                MissionBuildingPlanner.DecorKind.VENDING_MACHINE, Direction.WEST));
    }

    private static MissionBuildingPlanner.Decoration decoration(
            BlockPos origin,
            int x,
            int y,
            int z,
            MissionBuildingPlanner.DecorKind kind,
            Direction facing) {
        return new MissionBuildingPlanner.Decoration(origin.offset(x, y, z), kind, facing);
    }

    private static void buildTower(ServerLevel level, MissionBuildingPlanner.Site site, District district) {
        BoundingBox bounds = site.bounds();
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                level.getChunkAt(new BlockPos(x, site.floorYs().getFirst(), z));
            }
        }
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                }
            }
        }

        BlockState floor = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState wall = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
        DyeColor accentColor = switch (Math.floorMod(district.ordinal(), 5)) {
            case 0 -> DyeColor.CYAN;
            case 1 -> DyeColor.RED;
            case 2 -> DyeColor.LIME;
            case 3 -> DyeColor.YELLOW;
            default -> DyeColor.MAGENTA;
        };
        BlockState accent = Blocks.CONCRETE.pick(accentColor).defaultBlockState();
        int minX = bounds.minX();
        int maxX = bounds.maxX();
        int minZ = site.entrance().position().getZ();
        int maxZ = minZ + INTERIOR_SIZE - 1;
        for (int floorY : site.floorYs()) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    level.setBlock(new BlockPos(x, floorY - 1, z), floor, PLACE_FLAGS);
                }
            }
            for (int dy = 0; dy < FLOOR_SPACING - 1; dy++) {
                int y = floorY + dy;
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        boolean boundary = x == minX || x == maxX || z == minZ || z == maxZ;
                        if (!boundary) continue;
                        boolean window = dy == 1 && (x + z) % 3 != 0;
                        BlockState state = window
                                ? Blocks.TINTED_GLASS.defaultBlockState()
                                : (x + z + dy) % 7 == 0 ? accent : wall;
                        level.setBlock(new BlockPos(x, y, z), state, PLACE_FLAGS);
                    }
                }
            }
        }
        int roofY = site.floorYs().getLast() + FLOOR_SPACING - 1;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                level.setBlock(new BlockPos(x, roofY, z), floor, PLACE_FLAGS);
            }
        }

        // Entrance facade and a supported approach to exposed district ground.
        BlockPos entrance = site.entrance().position();
        Direction across = site.entrance().outward().getClockWise();
        for (int lane = 0; lane < 2; lane++) {
            for (int dy = 0; dy < 3; dy++) {
                level.setBlock(entrance.relative(across, lane).above(dy), wall, PLACE_FLAGS);
            }
            for (int distance = 1; distance <= site.entrance().wallDepth(); distance++) {
                BlockPos approach = entrance.relative(across, lane)
                        .relative(site.entrance().outward(), distance);
                level.setBlock(approach.below(), floor, PLACE_FLAGS);
                for (int dy = 0; dy < 3; dy++) {
                    level.setBlock(approach.above(dy), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                }
            }
        }
        for (BlockPos exterior : exteriorColumns(site)) {
            for (int y = exterior.getY(); y < level.getMaxY(); y++) {
                level.setBlock(exterior.atY(y), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
            }
        }
    }

    private static Map<BlockPos, BlockState> captureArea(
            ServerLevel level, MissionBuildingPlanner.Site site) {
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        BoundingBox bounds = site.bounds();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    BlockPos position = new BlockPos(x, y, z);
                    if (level.getBlockEntity(position) != null) return null;
                    originals.put(position, level.getBlockState(position));
                }
            }
        }
        for (BlockPos exterior : exteriorColumns(site)) {
            for (int y = exterior.getY(); y < level.getMaxY(); y++) {
                BlockPos position = exterior.atY(y);
                if (level.getBlockEntity(position) != null) return null;
                originals.putIfAbsent(position, level.getBlockState(position));
            }
        }
        return originals;
    }

    private static List<BlockPos> exteriorColumns(MissionBuildingPlanner.Site site) {
        Direction across = site.entrance().outward().getClockWise();
        BlockPos exterior = MissionBuildingPlanner.navigationTarget(site);
        return List.of(exterior, exterior.relative(across));
    }

    private static void restoreArea(ServerLevel level, Map<BlockPos, BlockState> originals) {
        List<Map.Entry<BlockPos, BlockState>> entries = new ArrayList<>(originals.entrySet());
        for (int index = entries.size() - 1; index >= 0; index--) {
            Map.Entry<BlockPos, BlockState> entry = entries.get(index);
            level.setBlock(entry.getKey(), entry.getValue(), PLACE_FLAGS);
        }
    }

    private record ChunkCandidate(int chunkX, int chunkZ, int distance, long score) {
    }
}
