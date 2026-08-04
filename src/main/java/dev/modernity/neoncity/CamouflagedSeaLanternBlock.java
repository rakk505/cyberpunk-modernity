package dev.modernity.neoncity;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/** A sea lantern whose baked top face matches an adjacent Arnis floor finish. */
public final class CamouflagedSeaLanternBlock extends Block {
    public static final MapCodec<CamouflagedSeaLanternBlock> CODEC =
            simpleCodec(CamouflagedSeaLanternBlock::new);
    public static final EnumProperty<SurfaceFinish> SURFACE =
            EnumProperty.create("surface", SurfaceFinish.class);

    public CamouflagedSeaLanternBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SURFACE, SurfaceFinish.SMOOTH_STONE));
    }

    @Override
    protected MapCodec<? extends CamouflagedSeaLanternBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SURFACE);
    }

    public enum SurfaceFinish implements StringRepresentable {
        BLACKSTONE("blackstone"),
        GRAY_CONCRETE("gray_concrete"),
        LIGHT_GRAY_CONCRETE("light_gray_concrete"),
        MUD_BRICKS("mud_bricks"),
        NETHER_BRICKS("nether_bricks"),
        OAK_PLANKS("oak_planks"),
        POLISHED_ANDESITE("polished_andesite"),
        SMOOTH_STONE("smooth_stone"),
        STONE_BRICKS("stone_bricks"),
        WHITE_CONCRETE("white_concrete");

        private final String serializedName;

        SurfaceFinish(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }
}
