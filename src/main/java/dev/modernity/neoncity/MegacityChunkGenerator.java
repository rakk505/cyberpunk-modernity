package dev.modernity.neoncity;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Vanilla-noise generator with the deterministic megacity overlay attached to Minecraft's native
 * chunk pipeline. The delegate owns terrain, biomes, carvers, structures, and ordinary features;
 * the city is applied to the center chunk at the end of FEATURES while it is still a protochunk.
 */
public final class MegacityChunkGenerator extends ChunkGenerator {
    public static final MapCodec<MegacityChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(generator -> generator.biomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings")
                            .forGetter(generator -> generator.settings)
            ).apply(instance, instance.stable(MegacityChunkGenerator::new)));

    private final Holder<NoiseGeneratorSettings> settings;
    private final NoiseBasedChunkGenerator delegate;

    public MegacityChunkGenerator(
            BiomeSource biomeSource,
            Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource);
        this.settings = settings;
        this.delegate = new NoiseBasedChunkGenerator(biomeSource, settings);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(
            HolderLookup<StructureSet> structureSets,
            RandomState randomState,
            long levelSeed) {
        return delegate.createState(structureSets, randomState, levelSeed);
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState,
            Blender blender,
            StructureManager structureManager,
            ChunkAccess chunk) {
        return delegate.createBiomes(randomState, blender, structureManager, chunk);
    }

    @Override
    public void applyCarvers(
            WorldGenRegion region,
            long seed,
            RandomState randomState,
            BiomeManager biomeManager,
            StructureManager structureManager,
            ChunkAccess chunk) {
        delegate.applyCarvers(region, seed, randomState, biomeManager, structureManager, chunk);
    }

    @Override
    public void buildSurface(
            WorldGenRegion region,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess chunk) {
        delegate.buildSurface(region, structureManager, randomState, chunk);
    }

    @Override
    public void applyBiomeDecoration(
            WorldGenLevel level,
            ChunkAccess chunk,
            StructureManager structureManager) {
        delegate.applyBiomeDecoration(level, chunk, structureManager);
        if (level instanceof WorldGenRegion region) {
            NeonCityGenerator.generateWorldgenChunk(region, chunk);
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk) {
        return delegate.fillFromNoise(blender, randomState, structureManager, chunk);
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
        return NeonCityGenerator.CITY_GROUND_Y + 2;
    }

    @Override
    public int getGenDepth() {
        return delegate.getGenDepth();
    }

    @Override
    public int getSeaLevel() {
        return delegate.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return delegate.getMinY();
    }

    @Override
    public int getBaseHeight(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor heightAccessor,
            RandomState randomState) {
        return delegate.getBaseHeight(x, z, type, heightAccessor, randomState);
    }

    @Override
    public NoiseColumn getBaseColumn(
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            RandomState randomState) {
        return delegate.getBaseColumn(x, z, heightAccessor, randomState);
    }

    @Override
    public void addDebugScreenInfo(
            List<String> result,
            RandomState randomState,
            BlockPos feetPos) {
        delegate.addDebugScreenInfo(result, randomState, feetPos);
        result.add("Megacity native chunk overlay");
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        delegate.spawnOriginalMobs(region);
    }

    @Override
    public void refreshFeaturesPerStep() {
        super.refreshFeaturesPerStep();
        delegate.refreshFeaturesPerStep();
    }
}
