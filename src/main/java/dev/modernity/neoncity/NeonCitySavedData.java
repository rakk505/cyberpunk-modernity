package dev.modernity.neoncity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persistent, crash-safe ledger for procedural megacity chunks.
 *
 * <p>The generator only records a chunk after every block in that chunk has
 * been stamped. A crash during a stamp therefore causes an idempotent replay
 * rather than permanently recording a partial building. The fingerprint is
 * deliberately part of the save format: changing the deterministic layout
 * without migrating an existing world must not silently overwrite player
 * builds.</p>
 */
public final class NeonCitySavedData extends SavedData {
    public static final int FORMAT_VERSION = 1;
    /** Forces one safe facade re-audit when the generated-surface catalog changes. */
    static final int AD_SAFETY_VERSION = 2;
    /**
     * Forces one safe re-scan of highway facades when the scan itself gets smarter. A chunk that
     * found nothing under an older, narrower scan is recorded as done and would otherwise never be
     * revisited, so existing saves would keep their bare corridors forever.
     */
    static final int HIGHWAY_AD_VERSION = 1;

    private static final Codec<DeferredBanner> DEFERRED_BANNER_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("x").forGetter(DeferredBanner::x),
                    Codec.INT.fieldOf("y").forGetter(DeferredBanner::y),
                    Codec.INT.fieldOf("z").forGetter(DeferredBanner::z),
                    Codec.INT.fieldOf("outward").forGetter(DeferredBanner::outwardOrdinal),
                    Codec.INT.fieldOf("district").forGetter(DeferredBanner::districtOrdinal)
            ).apply(instance, DeferredBanner::new));

    private static final Codec<NeonCitySavedData> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.INT.fieldOf("format_version")
                            .forGetter(NeonCitySavedData::formatVersion),
                    Codec.STRING.fieldOf("generator_fingerprint")
                            .forGetter(NeonCitySavedData::generatorFingerprint),
                    Codec.LONG.listOf().fieldOf("generated_chunks")
                            .forGetter(NeonCitySavedData::serializedChunks),
                    DEFERRED_BANNER_CODEC.listOf()
                            .optionalFieldOf("pending_banners", List.of())
                            .forGetter(NeonCitySavedData::serializedBanners),
                    Codec.INT.optionalFieldOf("ad_safety_version", 0)
                            .forGetter(ignored -> AD_SAFETY_VERSION),
                    Codec.LONG.listOf()
                            .optionalFieldOf("ad_decorated_chunks", List.of())
                            .forGetter(NeonCitySavedData::serializedAdDecoratedChunks),
                    Codec.LONG.listOf()
                            .optionalFieldOf("freestanding_ad_decorated_chunks", List.of())
                            .forGetter(
                                    NeonCitySavedData::serializedFreestandingAdDecoratedChunks),
                    Codec.LONG.listOf()
                            .optionalFieldOf("highway_ad_decorated_chunks", List.of())
                            .forGetter(NeonCitySavedData::serializedHighwayAdDecoratedChunks),
                    Codec.INT.optionalFieldOf("highway_ad_version", 0)
                            .forGetter(ignored -> HIGHWAY_AD_VERSION)
            ).apply(instance, NeonCitySavedData::new));

    public static final SavedDataType<NeonCitySavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("neoncity", "generated_chunks_v1"),
            NeonCitySavedData::new,
            CODEC
    );

    private final int formatVersion;
    private final String generatorFingerprint;
    private final Set<Long> generatedChunks;
    private final LinkedHashMap<Long, DeferredBanner> pendingBanners;
    private final Set<Long> adDecoratedChunks;
    private final Set<Long> freestandingAdDecoratedChunks;
    private final Set<Long> highwayAdDecoratedChunks;

    public record DeferredBanner(
            int x, int y, int z, int outwardOrdinal, int districtOrdinal) {
        long key() {
            return net.minecraft.core.BlockPos.asLong(x, y, z);
        }
    }

    public NeonCitySavedData() {
        this(FORMAT_VERSION, NeonCityGenerator.generatorFingerprint(),
                List.of(), List.of(), AD_SAFETY_VERSION, List.of(), List.of(), List.of(),
                HIGHWAY_AD_VERSION);
    }

    private NeonCitySavedData(
            int formatVersion,
            String generatorFingerprint,
            List<Long> generatedChunks,
            List<DeferredBanner> pendingBanners,
            int loadedAdSafetyVersion,
            List<Long> adDecoratedChunks,
            List<Long> freestandingAdDecoratedChunks,
            List<Long> highwayAdDecoratedChunks,
            int loadedHighwayAdVersion) {
        this.formatVersion = formatVersion;
        this.generatorFingerprint = generatorFingerprint;
        this.generatedChunks = new HashSet<>(generatedChunks);
        this.pendingBanners = new LinkedHashMap<>();
        for (DeferredBanner banner : pendingBanners) {
            this.pendingBanners.put(banner.key(), banner);
        }
        this.adDecoratedChunks = loadedAdSafetyVersion >= AD_SAFETY_VERSION
                ? new HashSet<>(adDecoratedChunks)
                : new HashSet<>();
        this.adDecoratedChunks.retainAll(this.generatedChunks);
        this.freestandingAdDecoratedChunks = new HashSet<>(freestandingAdDecoratedChunks);
        this.freestandingAdDecoratedChunks.retainAll(this.generatedChunks);
        this.highwayAdDecoratedChunks = loadedHighwayAdVersion >= HIGHWAY_AD_VERSION
                ? new HashSet<>(highwayAdDecoratedChunks)
                : new HashSet<>();
        this.highwayAdDecoratedChunks.retainAll(this.generatedChunks);
    }

    public int formatVersion() {
        return formatVersion;
    }

    public String generatorFingerprint() {
        return generatorFingerprint;
    }

    public boolean isCompatible(String expectedFingerprint) {
        return formatVersion == FORMAT_VERSION && generatorFingerprint.equals(expectedFingerprint);
    }

    public boolean contains(long chunkKey) {
        return generatedChunks.contains(chunkKey);
    }

    public boolean markGenerated(long chunkKey, String expectedFingerprint) {
        if (!isCompatible(expectedFingerprint) || !generatedChunks.add(chunkKey)) {
            return false;
        }
        setDirty();
        return true;
    }

    public Set<Long> snapshot() {
        return Set.copyOf(generatedChunks);
    }

    public boolean isAdDecorated(long chunkKey) {
        return adDecoratedChunks.contains(chunkKey);
    }

    public boolean markAdDecorated(long chunkKey) {
        if (!generatedChunks.contains(chunkKey) || !adDecoratedChunks.add(chunkKey)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean isFreestandingAdDecorated(long chunkKey) {
        return freestandingAdDecoratedChunks.contains(chunkKey);
    }

    public boolean markFreestandingAdDecorated(long chunkKey) {
        if (!generatedChunks.contains(chunkKey)
                || !freestandingAdDecoratedChunks.add(chunkKey)) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean isHighwayAdDecorated(long chunkKey) {
        return highwayAdDecoratedChunks.contains(chunkKey);
    }

    public boolean markHighwayAdDecorated(long chunkKey) {
        if (!generatedChunks.contains(chunkKey)
                || !highwayAdDecoratedChunks.add(chunkKey)) {
            return false;
        }
        setDirty();
        return true;
    }

    public List<DeferredBanner> pendingBanners() {
        return List.copyOf(pendingBanners.values());
    }

    public boolean addPendingBanner(DeferredBanner banner) {
        if (pendingBanners.putIfAbsent(banner.key(), banner) != null) return false;
        setDirty();
        return true;
    }

    public DeferredBanner removePendingBanner(long key) {
        DeferredBanner removed = pendingBanners.remove(key);
        if (removed != null) setDirty();
        return removed;
    }

    public DeferredBanner removeOldestPendingBanner() {
        if (pendingBanners.isEmpty()) return null;
        Map.Entry<Long, DeferredBanner> oldest = pendingBanners.entrySet().iterator().next();
        pendingBanners.remove(oldest.getKey());
        setDirty();
        return oldest.getValue();
    }

    private List<Long> serializedChunks() {
        ArrayList<Long> chunks = new ArrayList<>(generatedChunks);
        chunks.sort(Long::compare);
        return chunks;
    }

    private List<DeferredBanner> serializedBanners() {
        return List.copyOf(pendingBanners.values());
    }

    private List<Long> serializedAdDecoratedChunks() {
        ArrayList<Long> chunks = new ArrayList<>(adDecoratedChunks);
        chunks.sort(Long::compare);
        return chunks;
    }

    private List<Long> serializedFreestandingAdDecoratedChunks() {
        ArrayList<Long> chunks = new ArrayList<>(freestandingAdDecoratedChunks);
        chunks.sort(Long::compare);
        return chunks;
    }

    private List<Long> serializedHighwayAdDecoratedChunks() {
        ArrayList<Long> chunks = new ArrayList<>(highwayAdDecoratedChunks);
        chunks.sort(Long::compare);
        return chunks;
    }
}
