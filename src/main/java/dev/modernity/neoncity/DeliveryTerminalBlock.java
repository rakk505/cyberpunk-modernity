package dev.modernity.neoncity;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Mission-owned cargo locker that accepts only the matching contract shipment. */
public final class DeliveryTerminalBlock extends Block {
    public static final MapCodec<DeliveryTerminalBlock> CODEC =
            simpleCodec(DeliveryTerminalBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(
            box(1.0, 0.0, 1.0, 15.0, 3.0, 15.0),
            box(2.0, 3.0, 3.0, 14.0, 15.0, 13.0),
            box(4.0, 6.0, 1.0, 12.0, 12.0, 3.0),
            box(7.0, 4.0, 0.0, 9.0, 6.0, 2.0));

    public DeliveryTerminalBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends DeliveryTerminalBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            BlockHitResult hitResult) {
        return submit(level, position, player);
    }

    @Override
    protected InteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos position,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult) {
        return submit(level, position, player);
    }

    private static InteractionResult submit(Level level, BlockPos position, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            level.playSound(null, position, SoundEvents.IRON_DOOR_CLOSE,
                    SoundSource.BLOCKS, 0.65F, 1.2F);
            return MissionService.activateDeliveryTerminal(serverPlayer, position)
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
