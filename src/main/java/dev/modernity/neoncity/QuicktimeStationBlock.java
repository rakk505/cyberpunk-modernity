package dev.modernity.neoncity;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** A district-bound terminal that opens the Quicktime destination selector. */
public final class QuicktimeStationBlock extends Block {
    public static final MapCodec<QuicktimeStationBlock> CODEC = simpleCodec(QuicktimeStationBlock::new);

    private static final VoxelShape SHAPE = Shapes.or(
            box(2.0, 0.0, 2.0, 14.0, 3.0, 14.0),
            box(4.0, 3.0, 5.0, 12.0, 14.0, 11.0),
            box(3.0, 7.0, 4.0, 13.0, 13.0, 12.0)
    );

    public QuicktimeStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends QuicktimeStationBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        if (level instanceof ServerLevel serverLevel
                && QuicktimeStationData.districtAt(serverLevel, context.getClickedPos()).isEmpty()) {
            Player player = context.getPlayer();
            if (player != null) {
                player.sendSystemMessage(
                        Component.translatable("message.cyberdeck.quicktime.invalid_district"));
            }
            return null;
        }
        return super.getStateForPlacement(context);
    }

    @Override
    protected void onPlace(
            BlockState state,
            Level level,
            BlockPos position,
            BlockState oldState,
            boolean movedByPiston) {
        super.onPlace(state, level, position, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !state.is(oldState.getBlock())) {
            QuicktimeStationData.get(serverLevel).add(position);
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state,
            ServerLevel level,
            BlockPos position,
            boolean movedByPiston
    ) {
        QuicktimeStationData.get(level).remove(position);
        super.affectNeighborsAfterRemoval(state, level, position, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            BlockHitResult hitResult
    ) {
        if (player instanceof ServerPlayer serverPlayer) {
            QuicktimeStationData data = QuicktimeStationData.get(serverPlayer.level());
            if (data.district(position).isEmpty()) {
                serverPlayer.sendSystemMessage(
                        Component.translatable("message.cyberdeck.quicktime.invalid_district"), true);
                return InteractionResult.FAIL;
            }
            data.add(position);
            QuicktimeTravelService.open(serverPlayer, position);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            CollisionContext context
    ) {
        return SHAPE;
    }
}
