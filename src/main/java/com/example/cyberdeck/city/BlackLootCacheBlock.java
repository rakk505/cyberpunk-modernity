package com.example.cyberdeck.city;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** A two-block-wide black cache model backed by one persistent 54-slot inventory. */
public final class BlackLootCacheBlock extends BaseEntityBlock {
    public static final MapCodec<BlackLootCacheBlock> CODEC =
            simpleCodec(BlackLootCacheBlock::new);
    public static final EnumProperty<Direction> FACING =
            BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShape NORTH_SOUTH_SHAPE =
            Block.box(-8.0, 0.0, 1.0, 24.0, 12.0, 15.0);
    private static final VoxelShape EAST_WEST_SHAPE =
            Block.box(1.0, 0.0, -8.0, 15.0, 12.0, 24.0);

    public BlackLootCacheBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BlackLootCacheBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new BlackLootCacheBlockEntity(position, state);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            BlockHitResult hitResult) {
        if (level instanceof ServerLevel
                && level.getBlockEntity(position) instanceof BlackLootCacheBlockEntity cache) {
            player.openMenu(cache);
            player.awardStat(Stats.OPEN_CHEST);
            level.playSound(null, position, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS,
                    0.65F, 0.8F);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos position, BlockState state,
                                        Player player) {
        if (!level.isClientSide()
                && level.getBlockEntity(position) instanceof BlackLootCacheBlockEntity cache) {
            if (!player.preventsBlockDrops()) {
                Containers.dropContents(level, position, cache);
            }
            cache.clearContent();
        }
        return super.playerWillDestroy(level, position, state, player);
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state, ServerLevel level, BlockPos position, boolean movedByPiston) {
        Containers.updateNeighboursAfterDestroy(state, level, position);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(
            BlockState state, Level level, BlockPos position, Direction direction) {
        return net.minecraft.world.inventory.AbstractContainerMenu
                .getRedstoneSignalFromBlockEntity(level.getBlockEntity(position));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(
                FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z
                ? NORTH_SOUTH_SHAPE : EAST_WEST_SHAPE;
    }
}
