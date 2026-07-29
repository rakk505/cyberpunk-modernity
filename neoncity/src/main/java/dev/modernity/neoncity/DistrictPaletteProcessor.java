package dev.modernity.neoncity;

import com.mojang.serialization.MapCodec;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColorCollection;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

/**
 * Applies a district material language to imported Arnis buildings at placement time.
 *
 * <p>The processor deliberately leaves the source street surface and its first detail layer
 * untouched. Above that line, only generic full structural blocks and glass are substituted;
 * doors, stairs, slabs, roofs, vegetation, signs, and every authored block position remain the
 * original Arnis geometry. Each Corp therefore keeps the source city's morphology while gaining
 * a visibly distinct Project Moon palette.</p>
 */
public final class DistrictPaletteProcessor implements StructureProcessor {
    private static final Map<Block, Integer> STRUCTURAL_BLOCKS = buildStructuralIndex();
    private static final Map<Block, Boolean> GLASS_BLOCKS = buildGlassIndex();

    private final Palette palette;
    private final int surfaceOffset;

    public DistrictPaletteProcessor(District district, int surfaceOffset) {
        this.palette = palette(district);
        this.surfaceOffset = surfaceOffset;
    }

    @Override
    public MapCodec<? extends StructureProcessor> codec() {
        // This processor is constructed inline and is never serialized in a processor list.
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
        // originalBlockInfo is in source-template coordinates, so this remains correct after a
        // mirror or rotation. Preserve roads, pavements, and low street furniture exactly.
        if (originalBlockInfo.pos().getY() <= surfaceOffset + 1) {
            return processedBlockInfo;
        }

        BlockState source = processedBlockInfo.state();
        Boolean pane = GLASS_BLOCKS.get(source.getBlock());
        if (pane != null) {
            Block target = pane ? palette.glassPane() : palette.glass();
            // Pane variants share the directional connection properties. Full glass blocks have
            // no useful state properties, so do not attempt to copy unrelated state into them.
            BlockState replacement = pane
                    ? target.withPropertiesOf(source)
                    : target.defaultBlockState();
            return replace(processedBlockInfo, replacement);
        }

        Integer materialIndex = STRUCTURAL_BLOCKS.get(source.getBlock());
        if (materialIndex == null) {
            return processedBlockInfo;
        }
        int role = Math.floorMod(materialIndex, 7);
        Block target = role == 0
                ? palette.accent()
                : role <= 3 ? palette.secondary() : palette.primary();
        // All entries in STRUCTURAL_BLOCKS and every palette target are full blocks. Using the
        // default state avoids copying properties between incompatible block families.
        return replace(processedBlockInfo, target.defaultBlockState());
    }

    private static StructureTemplate.StructureBlockInfo replace(
            StructureTemplate.StructureBlockInfo source, BlockState replacement) {
        if (source.state() == replacement) {
            return source;
        }
        return new StructureTemplate.StructureBlockInfo(
                source.pos(), replacement, source.nbt());
    }

    private static Map<Block, Integer> buildStructuralIndex() {
        IdentityHashMap<Block, Integer> index = new IdentityHashMap<>();
        List<Block> generic = List.of(
                Blocks.STONE,
                Blocks.SMOOTH_STONE,
                Blocks.STONE_BRICKS,
                Blocks.CRACKED_STONE_BRICKS,
                Blocks.MOSSY_STONE_BRICKS,
                Blocks.COBBLESTONE,
                Blocks.MOSSY_COBBLESTONE,
                Blocks.ANDESITE,
                Blocks.POLISHED_ANDESITE,
                Blocks.DIORITE,
                Blocks.POLISHED_DIORITE,
                Blocks.GRANITE,
                Blocks.POLISHED_GRANITE,
                Blocks.BRICKS,
                Blocks.MUD_BRICKS,
                Blocks.TUFF,
                Blocks.POLISHED_TUFF,
                Blocks.TUFF_BRICKS,
                Blocks.DEEPSLATE,
                Blocks.COBBLED_DEEPSLATE,
                Blocks.POLISHED_DEEPSLATE,
                Blocks.DEEPSLATE_BRICKS,
                Blocks.DEEPSLATE_TILES,
                Blocks.POLISHED_BLACKSTONE,
                Blocks.POLISHED_BLACKSTONE_BRICKS,
                Blocks.SANDSTONE,
                Blocks.CUT_SANDSTONE,
                Blocks.SMOOTH_SANDSTONE,
                Blocks.RED_SANDSTONE,
                Blocks.CUT_RED_SANDSTONE,
                Blocks.SMOOTH_RED_SANDSTONE,
                Blocks.QUARTZ_BLOCK,
                Blocks.SMOOTH_QUARTZ,
                Blocks.QUARTZ_BRICKS,
                Blocks.CALCITE,
                Blocks.TERRACOTTA,
                Blocks.NETHER_BRICKS,
                Blocks.RED_NETHER_BRICKS,
                Blocks.END_STONE_BRICKS,
                Blocks.PRISMARINE,
                Blocks.PRISMARINE_BRICKS,
                Blocks.DARK_PRISMARINE,
                Blocks.OAK_PLANKS,
                Blocks.SPRUCE_PLANKS,
                Blocks.BIRCH_PLANKS,
                Blocks.JUNGLE_PLANKS,
                Blocks.ACACIA_PLANKS,
                Blocks.DARK_OAK_PLANKS,
                Blocks.MANGROVE_PLANKS,
                Blocks.CHERRY_PLANKS,
                Blocks.BAMBOO_PLANKS
        );
        for (Block block : generic) {
            index.putIfAbsent(block, index.size());
        }
        addCollection(index, Blocks.CONCRETE);
        addCollection(index, Blocks.DYED_TERRACOTTA);
        return index;
    }

    private static void addCollection(
            IdentityHashMap<Block, Integer> index, ColorCollection<Block> collection) {
        for (Block block : collection.asList()) {
            index.putIfAbsent(block, index.size());
        }
    }

    private static Map<Block, Boolean> buildGlassIndex() {
        IdentityHashMap<Block, Boolean> index = new IdentityHashMap<>();
        index.put(Blocks.GLASS, false);
        index.put(Blocks.TINTED_GLASS, false);
        index.put(Blocks.GLASS_PANE, true);
        for (Block block : Blocks.STAINED_GLASS.asList()) index.put(block, false);
        for (Block block : Blocks.STAINED_GLASS_PANE.asList()) index.put(block, true);
        return index;
    }

    private static Palette palette(District district) {
        return switch (district) {
            case A_CORP -> p(Blocks.POLISHED_BLACKSTONE_BRICKS, concrete(DyeColor.BLACK),
                    Blocks.IRON_BLOCK, DyeColor.GRAY);
            case B_CORP -> p(Blocks.SMOOTH_STONE, Blocks.OAK_PLANKS,
                    Blocks.MOSS_BLOCK, DyeColor.LIGHT_BLUE);
            case C_CORP -> p(Blocks.BRICKS, Blocks.DARK_OAK_PLANKS,
                    copper(WeatheringCopper.WeatherState.UNAFFECTED), DyeColor.BROWN);
            case D_CORP -> p(concrete(DyeColor.LIGHT_GRAY), Blocks.SPRUCE_PLANKS,
                    Blocks.MOSS_BLOCK, DyeColor.LIGHT_BLUE);
            case E_CORP -> p(terracotta(DyeColor.ORANGE), Blocks.SANDSTONE,
                    terracotta(DyeColor.RED), DyeColor.CYAN);
            case F_CORP -> p(concrete(DyeColor.WHITE), concrete(DyeColor.CYAN),
                    concrete(DyeColor.PINK), DyeColor.LIGHT_BLUE);
            case G_CORP -> p(Blocks.MUD_BRICKS, concrete(DyeColor.GRAY),
                    copper(WeatheringCopper.WeatherState.EXPOSED), DyeColor.LIME);
            case H_CORP -> p(Blocks.DEEPSLATE_TILES, concrete(DyeColor.GRAY),
                    concrete(DyeColor.RED), DyeColor.CYAN);
            case I_CORP -> p(Blocks.SMOOTH_SANDSTONE, Blocks.QUARTZ_BRICKS,
                    Blocks.TERRACOTTA, DyeColor.YELLOW);
            case J_CORP -> p(concrete(DyeColor.WHITE), concrete(DyeColor.BLACK),
                    Blocks.GOLD_BLOCK, DyeColor.PURPLE);
            case K_CORP -> p(concrete(DyeColor.WHITE), Blocks.SMOOTH_QUARTZ,
                    Blocks.IRON_BLOCK, DyeColor.LIGHT_BLUE);
            case L_CORP -> p(concrete(DyeColor.LIGHT_GRAY), Blocks.QUARTZ_BLOCK,
                    concrete(DyeColor.PURPLE), DyeColor.BLUE);
            case M_CORP -> p(Blocks.STONE_BRICKS, concrete(DyeColor.LIGHT_GRAY),
                    concrete(DyeColor.RED), DyeColor.LIGHT_BLUE);
            case N_CORP -> p(Blocks.CALCITE, Blocks.SMOOTH_SANDSTONE,
                    Blocks.DARK_OAK_PLANKS, DyeColor.GRAY);
            case O_CORP -> p(Blocks.QUARTZ_BRICKS, Blocks.CALCITE,
                    copper(WeatheringCopper.WeatherState.OXIDIZED), DyeColor.YELLOW);
            case P_CORP -> p(Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS,
                    Blocks.GOLD_BLOCK, DyeColor.LIGHT_BLUE);
            case Q_CORP -> p(Blocks.STONE_BRICKS, concrete(DyeColor.WHITE),
                    concrete(DyeColor.ORANGE), DyeColor.BLUE);
            case R_CORP -> p(Blocks.DEEPSLATE_TILES, concrete(DyeColor.RED),
                    concrete(DyeColor.YELLOW), DyeColor.CYAN);
            case S_CORP -> p(terracotta(DyeColor.WHITE), Blocks.DARK_OAK_PLANKS,
                    terracotta(DyeColor.RED), DyeColor.LIGHT_BLUE);
            case T_CORP -> p(Blocks.TUFF_BRICKS, Blocks.BRICKS,
                    copper(WeatheringCopper.WeatherState.WEATHERED), DyeColor.ORANGE);
            case U_CORP -> p(concrete(DyeColor.GRAY), Blocks.IRON_BLOCK,
                    concrete(DyeColor.ORANGE), DyeColor.CYAN);
            case V_CORP -> p(Blocks.CALCITE, Blocks.SPRUCE_PLANKS,
                    Blocks.STONE_BRICKS, DyeColor.LIGHT_BLUE);
            case W_CORP -> p(concrete(DyeColor.LIGHT_GRAY), Blocks.IRON_BLOCK,
                    concrete(DyeColor.LIME), DyeColor.CYAN);
            case X_CORP -> p(Blocks.BRICKS, terracotta(DyeColor.YELLOW),
                    Blocks.IRON_BLOCK, DyeColor.GREEN);
            case Y_CORP -> p(Blocks.PACKED_ICE, concrete(DyeColor.WHITE),
                    concrete(DyeColor.RED), DyeColor.LIGHT_BLUE);
            case Z_CORP -> p(Blocks.POLISHED_BLACKSTONE_BRICKS, concrete(DyeColor.GRAY),
                    concrete(DyeColor.CYAN), DyeColor.MAGENTA);
        };
    }

    private static Palette p(Block primary, Block secondary, Block accent, DyeColor glass) {
        return new Palette(
                primary,
                secondary,
                accent,
                Blocks.STAINED_GLASS.pick(glass),
                Blocks.STAINED_GLASS_PANE.pick(glass));
    }

    private static Block concrete(DyeColor color) {
        return Blocks.CONCRETE.pick(color);
    }

    private static Block terracotta(DyeColor color) {
        return Blocks.DYED_TERRACOTTA.pick(color);
    }

    private static Block copper(WeatheringCopper.WeatherState state) {
        return Blocks.COPPER_BLOCK.waxed().pick(state);
    }

    private record Palette(
            Block primary,
            Block secondary,
            Block accent,
            Block glass,
            Block glassPane
    ) {}
}
