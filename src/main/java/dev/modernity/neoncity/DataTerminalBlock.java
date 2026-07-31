package dev.modernity.neoncity;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Secured mission terminal; its front-panel button steals data for the matching contract. */
public final class DataTerminalBlock extends Block {
    public static final MapCodec<DataTerminalBlock> CODEC = simpleCodec(DataTerminalBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(
            box(1.0, 0.0, 2.0, 15.0, 4.0, 14.0),
            box(3.0, 4.0, 4.0, 13.0, 15.0, 12.0),
            box(5.0, 7.0, 2.0, 11.0, 13.0, 4.0),
            box(7.0, 5.0, 1.0, 9.0, 7.0, 3.0));

    public DataTerminalBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DataTerminalBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            BlockHitResult hitResult) {
        if (player instanceof ServerPlayer serverPlayer) {
            level.playSound(null, position, SoundEvents.STONE_BUTTON_CLICK_ON,
                    SoundSource.BLOCKS, 0.65F, 1.35F);
            return MissionService.activateDataTerminal(serverPlayer, position)
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state,
            BlockGetter level,
            BlockPos position,
            CollisionContext context) {
        return SHAPE;
    }
}
