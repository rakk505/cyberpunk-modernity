package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent index of Quicktime stations in one server level. */
public final class QuicktimeStationData extends SavedData {
    private static final int FORMAT_VERSION = 1;

    private static final Codec<QuicktimeStationData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("format_version", FORMAT_VERSION)
                    .forGetter(data -> FORMAT_VERSION),
            Codec.LONG.listOf().optionalFieldOf("stations", List.of())
                    .forGetter(QuicktimeStationData::serializedStations),
            Codec.LONG.listOf().optionalFieldOf("canonical_stations_initialized", List.of())
                    .forGetter(QuicktimeStationData::serializedCanonicalStations)
    ).apply(instance, QuicktimeStationData::new));

    public static final SavedDataType<QuicktimeStationData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "quicktime_stations_v1"),
            QuicktimeStationData::new,
            CODEC
    );

    private final Set<Long> stationPositions;
    private final Set<Long> initializedCanonicalStations;
    private transient ServerLevel level;
    private transient MegacityLayout layout;
    private transient long layoutSeed = Long.MIN_VALUE;

    private QuicktimeStationData() {
        this(FORMAT_VERSION, List.of(), List.of());
    }

    private QuicktimeStationData(
            int ignoredFormatVersion,
            List<Long> stationPositions,
            List<Long> initializedCanonicalStations) {
        this.stationPositions = new HashSet<>(stationPositions);
        this.initializedCanonicalStations = new HashSet<>(initializedCanonicalStations);
    }

    public static QuicktimeStationData get(ServerLevel level) {
        QuicktimeStationData data = level.getDataStorage().computeIfAbsent(TYPE);
        data.bind(level);
        return data;
    }

    /** Adds a station only when its coordinates belong to an inhabited district. */
    public boolean add(BlockPos position) {
        if (district(position).isEmpty() || !stationPositions.add(position.asLong())) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean remove(BlockPos position) {
        if (!stationPositions.remove(position.asLong())) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean isCanonicalInitialized(BlockPos position) {
        return initializedCanonicalStations.contains(position.asLong());
    }

    public boolean markCanonicalInitialized(BlockPos position) {
        if (!initializedCanonicalStations.add(position.asLong())) {
            return false;
        }
        setDirty();
        return true;
    }

    public Set<BlockPos> stations() {
        return snapshot();
    }

    public Set<BlockPos> snapshot() {
        HashSet<BlockPos> positions = new HashSet<>();
        for (long packedPosition : stationPositions) {
            positions.add(BlockPos.of(packedPosition));
        }
        return Set.copyOf(positions);
    }

    public Optional<District> district(BlockPos position) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())
                || !NeonCityGenerator.isMegacityWorld(level)) {
            return Optional.empty();
        }
        return inhabitedDistrict(activeLayout().locate(position.getX(), position.getZ()));
    }

    public Optional<BlockPos> nearest(District district, BlockPos origin) {
        return stationPositions.stream()
                .map(BlockPos::of)
                .filter(position -> district(position).filter(district::equals).isPresent())
                .min(Comparator.comparingDouble(position -> position.distSqr(origin)));
    }

    public static Optional<District> districtAt(ServerLevel level, BlockPos position) {
        if (!Level.OVERWORLD.equals(level.dimension())
                || !NeonCityGenerator.isMegacityWorld(level)) {
            return Optional.empty();
        }
        return inhabitedDistrict(NeonCityGenerator.fixedLayout()
                .locate(position.getX(), position.getZ()));
    }

    private static Optional<District> inhabitedDistrict(MegacityLayout.Location location) {
        return switch (location.zone()) {
            case NEST, BACKSTREETS -> Optional.of(location.district());
            default -> Optional.empty();
        };
    }

    private void bind(ServerLevel level) {
        this.level = level;
        if (layoutSeed != NeonCityGenerator.contentSeed()) {
            layout = null;
            layoutSeed = NeonCityGenerator.contentSeed();
        }
    }

    private MegacityLayout activeLayout() {
        if (layout == null) {
            layout = NeonCityGenerator.fixedLayout();
        }
        return layout;
    }

    private List<Long> serializedStations() {
        ArrayList<Long> positions = new ArrayList<>(stationPositions);
        positions.sort(Long::compare);
        return positions;
    }

    private List<Long> serializedCanonicalStations() {
        ArrayList<Long> positions = new ArrayList<>(initializedCanonicalStations);
        positions.sort(Long::compare);
        return positions;
    }
}
