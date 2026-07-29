package com.example.cyberdeck.city;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-world persistent flag recording whether the Cyberpunk city grid has already been placed.
 *
 * <p>Placing the twelve skyscraper clusters is a one-time, expensive operation. This saved data lives
 * on the overworld's {@code DimensionDataStorage} so a world only ever builds its city once, no matter
 * how many times it is loaded.
 */
public final class CityData extends SavedData {
    private static final Codec<CityData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("built").forGetter(d -> d.built)
    ).apply(instance, CityData::new));

    public static final SavedDataType<CityData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "city"),
            CityData::new,
            CODEC
    );

    private boolean built;

    public CityData() {
        this(false);
    }

    private CityData(boolean built) {
        this.built = built;
    }

    public boolean isBuilt() {
        return built;
    }

    public void markBuilt() {
        if (!built) {
            built = true;
            setDirty();
        }
    }
}
