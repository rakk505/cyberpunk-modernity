package com.example.cyberdeck.city;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColorCollection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A placement-time {@link StructureProcessor} that recolours a building's dyed blocks.
 *
 * <p>Each city block in the generated city is given a {@link DyeColor} "hue". When a building is
 * stamped, this processor rotates every dyed block it contains (wool, concrete, terracotta, glass,
 * carpet, glazed terracotta and their powders) by a fixed offset around the 16-colour wheel, so two
 * copies of the same building on different city blocks read as visually distinct. Non-dyed blocks are
 * passed through unchanged.
 *
 * <p>The processor is applied only via {@link StructurePlaceSettings#addProcessor} at placement time,
 * so it never needs a codec or registered {@code StructureProcessorType}.
 */
public final class ColorSwapProcessor implements StructureProcessor {
    /** All dyed {@link ColorCollection}s whose blocks we recolour. */
    private static final List<ColorCollection<Block>> DYED_COLLECTIONS = List.of(
            Blocks.WOOL,
            Blocks.CARPET,
            Blocks.CONCRETE,
            Blocks.CONCRETE_POWDER,
            Blocks.DYED_TERRACOTTA,
            Blocks.GLAZED_TERRACOTTA,
            Blocks.STAINED_GLASS,
            Blocks.STAINED_GLASS_PANE
    );

    /** The 16 dye colours in {@link ColorCollection} order, used to rotate a block's hue. */
    private static final List<DyeColor> COLORS = ColorCollection.VALUES.asList();

    /** Maps every dyed block to its (collection, colour index) so it can be recoloured. */
    private static final Map<Block, DyedEntry> DYED_BLOCKS = buildIndex();

    /** How many steps around the 16-colour wheel to rotate this building's dyed blocks. */
    private final int shift;

    public ColorSwapProcessor(int shift) {
        // Normalise into [0, 16) so shift 0 is an identity recolour.
        this.shift = ((shift % COLORS.size()) + COLORS.size()) % COLORS.size();
    }

    @Override
    public MapCodec<? extends StructureProcessor> codec() {
        // Applied inline at placement only; never serialized, so a unit codec suffices.
        return MapCodec.unit(this);
    }

    @Override
    public StructureTemplate.@Nullable StructureBlockInfo process(
            LevelReader level,
            BlockPos targetPosition,
            BlockPos referencePos,
            StructureTemplate.StructureBlockInfo originalBlockInfo,
            StructureTemplate.StructureBlockInfo processedBlockInfo,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        if (shift == 0) {
            return processedBlockInfo;
        }
        BlockState state = processedBlockInfo.state();
        DyedEntry entry = DYED_BLOCKS.get(state.getBlock());
        if (entry == null) {
            return processedBlockInfo;
        }
        DyeColor target = COLORS.get((entry.colorIndex() + shift) % COLORS.size());
        Block recoloured = entry.collection().pick(target);
        if (recoloured == state.getBlock()) {
            return processedBlockInfo;
        }
        BlockState newState = recoloured.withPropertiesOf(state);
        return new StructureTemplate.StructureBlockInfo(
                processedBlockInfo.pos(), newState, processedBlockInfo.nbt());
    }

    private static Map<Block, DyedEntry> buildIndex() {
        Map<Block, DyedEntry> index = new IdentityHashMap<>();
        for (ColorCollection<Block> collection : DYED_COLLECTIONS) {
            List<Block> blocks = collection.asList();
            for (int i = 0; i < blocks.size(); i++) {
                index.put(blocks.get(i), new DyedEntry(collection, i));
            }
        }
        return index;
    }

    /** A dyed block's owning collection and its position in the 16-colour order. */
    private record DyedEntry(ColorCollection<Block> collection, int colorIndex) {
    }
}
