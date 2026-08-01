package dev.modernity.neoncity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private static final Codec<NeonCitySavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("format_version").forGetter(NeonCitySavedData::formatVersion),
            Codec.STRING.fieldOf("generator_fingerprint").forGetter(NeonCitySavedData::generatorFingerprint),
            Codec.LONG.listOf().fieldOf("generated_chunks").forGetter(NeonCitySavedData::serializedChunks)
    ).apply(instance, NeonCitySavedData::new));

    public static final SavedDataType<NeonCitySavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("neoncity", "generated_chunks_v1"),
            NeonCitySavedData::new,
            CODEC
    );

    private final int formatVersion;
    private final String generatorFingerprint;
    private final Set<Long> generatedChunks;

    public NeonCitySavedData() {
        this(FORMAT_VERSION, NeonCityGenerator.generatorFingerprint(), List.of());
    }

    private NeonCitySavedData(int formatVersion, String generatorFingerprint, List<Long> generatedChunks) {
        this.formatVersion = formatVersion;
        this.generatorFingerprint = generatorFingerprint;
        this.generatedChunks = new HashSet<>(generatedChunks);
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

    private List<Long> serializedChunks() {
        ArrayList<Long> chunks = new ArrayList<>(generatedChunks);
        chunks.sort(Long::compare);
        return chunks;
    }
}
