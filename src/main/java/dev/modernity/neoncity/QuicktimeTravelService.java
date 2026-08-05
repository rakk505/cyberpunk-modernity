package dev.modernity.neoncity;

import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.network.OpenQuicktimeStationPacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** Server-authoritative discovery, routing, and teleport safety for Quicktime stations. */
public final class QuicktimeTravelService {
    static final double MAX_USE_DISTANCE_SQUARED = 36.0;
    static final int TRAVEL_COOLDOWN_TICKS = 40;

    private static final int STATION_CLEAR_RADIUS = 1;
    private static final int[] ARRIVAL_X = {0, 1, 0, -1, 1, 1, -1, -1};
    private static final int[] ARRIVAL_Z = {-1, 0, 1, 0, -1, 1, 1, -1};
    private static final TicketType QUICKTIME_TRAVEL_TICKET = new TicketType(
            12_347L, TicketType.FLAG_LOADING);
    private static final Map<UUID, Long> NEXT_TRAVEL_TICK = new ConcurrentHashMap<>();
    private static final Set<UUID> PENDING_TRAVEL = ConcurrentHashMap.newKeySet();

    private QuicktimeTravelService() {
    }

    /** Opens the station destination list after validating the physical source terminal. */
    public static void open(ServerPlayer player, BlockPos source) {
        ServerLevel level = player.level();
        District sourceDistrict = validSource(player, level, source);
        if (sourceDistrict == null) {
            return;
        }
        QuicktimeStationData data = QuicktimeStationData.get(level);
        data.add(source);

        List<OpenQuicktimeStationPacket.Destination> destinations = new ArrayList<>();
        for (District district : destinationDistricts(sourceDistrict)) {
            Optional<BlockPos> selected = nearestStation(
                    NeonCityGenerator.layout(), district, source, candidates(data, district));
            if (selected.isEmpty()) {
                continue;
            }
            BlockPos destination = selected.get();
            int distance = (int) Math.round(horizontalDistance(source, destination));
            destinations.add(new OpenQuicktimeStationPacket.Destination(
                    district.ordinal(), distance));
        }
        destinations.sort(Comparator
                .comparingInt(OpenQuicktimeStationPacket.Destination::distanceBlocks)
                .thenComparingInt(OpenQuicktimeStationPacket.Destination::districtOrdinal));
        PacketDistributor.sendToPlayer(player, new OpenQuicktimeStationPacket(
                source.asLong(), sourceDistrict.ordinal(), destinations));
    }

    /** Handles a display-only client selection after recomputing and revalidating every input. */
    public static void travel(ServerPlayer player, BlockPos source, int targetDistrictOrdinal) {
        ServerLevel level = player.level();
        District sourceDistrict = validSource(player, level, source);
        District targetDistrict = district(targetDistrictOrdinal);
        if (sourceDistrict == null || targetDistrict == null || targetDistrict == sourceDistrict) {
            fail(player, "message.cyberdeck.quicktime.invalid_destination");
            return;
        }
        long gameTime = level.getGameTime();
        if (gameTime < NEXT_TRAVEL_TICK.getOrDefault(player.getUUID(), 0L)) {
            fail(player, "message.cyberdeck.quicktime.cooldown");
            return;
        }

        if (!PENDING_TRAVEL.add(player.getUUID())) {
            fail(player, "message.cyberdeck.quicktime.cooldown");
            return;
        }
        QuicktimeStationData data = QuicktimeStationData.get(level);
        List<BlockPos> destinations = new ArrayList<>(candidates(data, targetDistrict));
        destinations.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(source))
                .thenComparingLong(BlockPos::asLong));
        if (destinations.isEmpty()) {
            PENDING_TRAVEL.remove(player.getUUID());
            fail(player, "message.cyberdeck.quicktime.unavailable");
            return;
        }
        continueTravel(player, level, source, sourceDistrict, targetDistrict,
                data, List.copyOf(destinations), 0);
    }

    static void clearRuntimeState() {
        NEXT_TRAVEL_TICK.clear();
        PENDING_TRAVEL.clear();
    }

    static List<District> destinationDistricts(District sourceDistrict) {
        EnumSet<District> districts = EnumSet.allOf(District.class);
        districts.remove(sourceDistrict);
        return List.copyOf(districts);
    }

    static Optional<BlockPos> nearestStation(
            MegacityLayout layout,
            District targetDistrict,
            BlockPos origin,
            Iterable<BlockPos> stations) {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos station : stations) {
            MegacityLayout.Location location = layout.locate(station.getX(), station.getZ());
            if (inhabitedDistrict(location) != targetDistrict) {
                continue;
            }
            double distance = station.distSqr(origin);
            if (distance < nearestDistance
                    || (distance == nearestDistance
                    && nearest != null && station.asLong() < nearest.asLong())) {
                nearest = station;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    static BlockPos canonicalStation(District district) {
        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        int stationX = insetFromChunkBorder(node.x());
        int stationZ = insetFromChunkBorder(node.z());
        int groundY = NeonCityGenerator.sample(stationX, stationZ).groundY();
        return new BlockPos(stationX, groundY + 1, stationZ);
    }

    /** Adds the canonical central-plaza terminal when its city chunk is generated or revisited. */
    static void installCanonicalStations(ServerLevel level, ChunkPos chunk) {
        QuicktimeStationData data = QuicktimeStationData.get(level);
        for (District district : District.values()) {
            MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
            if ((node.x() >> 4) == chunk.x() && (node.z() >> 4) == chunk.z()) {
                BlockPos station = canonicalStation(district);
                if (!data.isCanonicalInitialized(station)) {
                    placeCanonicalStation(level, district, station);
                } else if (isStation(level, station)) {
                    data.add(station);
                }
            }
        }
    }

    private static void continueTravel(
            ServerPlayer player,
            ServerLevel level,
            BlockPos source,
            District sourceDistrict,
            District targetDistrict,
            QuicktimeStationData data,
            List<BlockPos> destinations,
            int startIndex) {
        for (int index = startIndex; index < destinations.size(); index++) {
            BlockPos candidate = destinations.get(index);
            ChunkPos chunk = ChunkPos.containing(candidate);
            if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                if (isStation(level, candidate) && stationDistrict(candidate) == targetDistrict) {
                    finishTravel(player, level, source, sourceDistrict, targetDistrict, candidate);
                    return;
                }
                data.remove(candidate);
                continue;
            }

            int nextIndex = index + 1;
            player.sendSystemMessage(Component.literal(
                    "Preparing Quicktime destination without blocking the server..."), true);
            try {
                level.getChunkSource().addTicketAndLoadWithRadius(
                        QUICKTIME_TRAVEL_TICKET, chunk, 0)
                        .whenComplete((ignored, failure) -> level.getServer().execute(() -> {
                            boolean tryNext = false;
                            try {
                                if (failure != null || level.getChunkSource().getChunkNow(
                                        chunk.x(), chunk.z()) == null) {
                                    endTravelFailure(
                                            player, "message.cyberdeck.quicktime.unavailable");
                                    return;
                                }
                                if (isStation(level, candidate)
                                        && stationDistrict(candidate) == targetDistrict) {
                                    finishTravel(player, level, source, sourceDistrict,
                                            targetDistrict, candidate);
                                    return;
                                }
                                data.remove(candidate);
                                tryNext = true;
                            } finally {
                                level.getChunkSource().removeTicketWithRadius(
                                        QUICKTIME_TRAVEL_TICKET, chunk, 0);
                            }
                            if (tryNext) {
                                continueTravel(player, level, source, sourceDistrict,
                                        targetDistrict, data, destinations, nextIndex);
                            }
                        }));
            } catch (RuntimeException exception) {
                level.getChunkSource().removeTicketWithRadius(
                        QUICKTIME_TRAVEL_TICKET, chunk, 0);
                endTravelFailure(player, "message.cyberdeck.quicktime.unavailable");
            }
            return;
        }
        endTravelFailure(player, "message.cyberdeck.quicktime.unavailable");
    }

    private static void finishTravel(
            ServerPlayer player,
            ServerLevel level,
            BlockPos source,
            District sourceDistrict,
            District targetDistrict,
            BlockPos destination) {
        try {
            if (level.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                    || player.level() != level) {
                return;
            }
            if (validSource(player, level, source) != sourceDistrict) {
                return;
            }
            Optional<BlockPos> arrival = findSafeArrival(level, player, destination);
            if (arrival.isEmpty()) {
                fail(player, "message.cyberdeck.quicktime.blocked");
                return;
            }

            BlockPos arrivalPos = arrival.get();
            if (!teleportPlayer(player, level, arrivalPos)) {
                fail(player, "message.cyberdeck.quicktime.teleport_failed");
                return;
            }
            NEXT_TRAVEL_TICK.put(
                    player.getUUID(), level.getGameTime() + TRAVEL_COOLDOWN_TICKS);
            level.playSound(null, source, SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.PLAYERS, 0.7F, 1.35F);
            level.playSound(null, arrivalPos, SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.PLAYERS, 0.7F, 1.55F);
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.quicktime.arrived", targetDistrict.label()), true);
        } finally {
            PENDING_TRAVEL.remove(player.getUUID());
        }
    }

    private static void endTravelFailure(ServerPlayer player, String translationKey) {
        try {
            if (player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player) {
                fail(player, translationKey);
            }
        } finally {
            PENDING_TRAVEL.remove(player.getUUID());
        }
    }

    private static Set<BlockPos> candidates(QuicktimeStationData data, District district) {
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos station : data.snapshot()) {
            if (stationDistrict(station) == district) {
                candidates.add(station);
            }
        }
        return candidates;
    }

    private static boolean placeCanonicalStation(
            ServerLevel level, District district, BlockPos station) {
        QuicktimeStationData data = QuicktimeStationData.get(level);
        if (stationDistrict(station) != district) {
            return false;
        }
        if (isStation(level, station)) {
            data.add(station);
            data.markCanonicalInitialized(station);
            return true;
        }
        if (data.isCanonicalInitialized(station)) {
            return false;
        }
        for (int dz = -STATION_CLEAR_RADIUS; dz <= STATION_CLEAR_RADIUS; dz++) {
            for (int dx = -STATION_CLEAR_RADIUS; dx <= STATION_CLEAR_RADIUS; dx++) {
                BlockPos floor = station.offset(dx, -1, dz);
                level.setBlock(floor, Blocks.POLISHED_DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlock(station.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
        level.setBlock(station, QuicktimeBlocks.QUICKTIME_STATION.get().defaultBlockState(),
                Block.UPDATE_ALL);
        if (!isStation(level, station)) {
            return false;
        }
        data.add(station);
        data.markCanonicalInitialized(station);
        return true;
    }

    static Optional<BlockPos> findSafeArrival(
            ServerLevel level, ServerPlayer player, BlockPos station) {
        for (int vertical = 0; vertical <= 2; vertical++) {
            int dy = vertical == 0 ? 0 : vertical == 1 ? 1 : -1;
            for (int index = 0; index < ARRIVAL_X.length; index++) {
                BlockPos candidate = station.offset(ARRIVAL_X[index], dy, ARRIVAL_Z[index]);
                if (isSafeArrival(level, player, candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    static boolean teleportPlayer(
            ServerPlayer player, ServerLevel level, BlockPos arrivalPos) {
        player.closeContainer();
        player.stopRiding();
        boolean teleported = player.teleportTo(
                level,
                arrivalPos.getX() + 0.5,
                arrivalPos.getY(),
                arrivalPos.getZ() + 0.5,
                Set.<Relative>of(),
                player.getYRot(),
                player.getXRot(),
                false);
        if (teleported) {
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
        }
        return teleported;
    }

    private static boolean isSafeArrival(
            ServerLevel level, ServerPlayer player, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!level.isInWorldBounds(feet)
                || !level.isInWorldBounds(head)
                || !level.getWorldBorder().isWithinBounds(feet)
                || !level.getBlockState(feet).isAir()
                || !level.getBlockState(head).isAir()
                || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(head).isEmpty()
                || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            return false;
        }
        double targetX = feet.getX() + 0.5;
        double targetZ = feet.getZ() + 0.5;
        AABB moved = player.getBoundingBox().move(
                targetX - player.getX(), feet.getY() - player.getY(), targetZ - player.getZ());
        return level.noCollision(player, moved);
    }

    private static @Nullable District validSource(
            ServerPlayer player, ServerLevel level, BlockPos source) {
        if (level != level.getServer().overworld()
                || !NeonCityGenerator.isMegacityWorld(level)
                || !level.isInWorldBounds(source)
                || !level.getWorldBorder().isWithinBounds(source)
                || player.distanceToSqr(Vec3.atCenterOf(source)) > MAX_USE_DISTANCE_SQUARED
                || !CityWorlds.hasFullyLoadedChunk(level, source)
                || !isStation(level, source)) {
            fail(player, "message.cyberdeck.quicktime.invalid_source");
            return null;
        }
        District district = stationDistrict(source);
        if (district == null) {
            fail(player, "message.cyberdeck.quicktime.outside_district");
        }
        return district;
    }

    private static @Nullable District stationDistrict(BlockPos pos) {
        return inhabitedDistrict(NeonCityGenerator.layout().locate(pos.getX(), pos.getZ()));
    }

    private static @Nullable District inhabitedDistrict(MegacityLayout.Location location) {
        return DistrictEntryNotifier.inhabitedDistrict(location.district(), location.zone());
    }

    private static boolean isStation(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(QuicktimeBlocks.QUICKTIME_STATION.get());
    }

    private static @Nullable District district(int ordinal) {
        District[] districts = District.values();
        return ordinal >= 0 && ordinal < districts.length ? districts[ordinal] : null;
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static int insetFromChunkBorder(int coordinate) {
        int chunkStart = (coordinate >> 4) << 4;
        int local = coordinate - chunkStart;
        return chunkStart + Math.max(STATION_CLEAR_RADIUS + 1,
                Math.min(15 - STATION_CLEAR_RADIUS - 1, local));
    }

    private static void fail(ServerPlayer player, String translationKey) {
        player.sendSystemMessage(Component.translatable(translationKey), true);
    }
}
