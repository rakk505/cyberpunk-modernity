package com.example.cyberdeck.city;

import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jspecify.annotations.Nullable;

/** One-shot grey cache that converts a left click directly into random ammunition. */
public final class AmmoCacheBlock extends HorizontalDirectionalBlock {
    public static final int MIN_REWARD = 100;
    public static final int MAX_REWARD = 250;
    public static final int REWARD_STEP = 25;
    public static final MapCodec<AmmoCacheBlock> CODEC = simpleCodec(AmmoCacheBlock::new);

    public record AmmoReward(AmmoType type, int amount) {
    }

    public AmmoCacheBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends AmmoCacheBlock> codec() {
        return CODEC;
    }

    @Override
    protected void attack(BlockState state, Level level, BlockPos position, Player player) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            claim(serverLevel, position, serverPlayer, level.getRandom());
        }
    }

    /** Atomically consumes one cache and grants its reward. */
    public static @Nullable AmmoReward claim(
            ServerLevel level, BlockPos position, ServerPlayer player, RandomSource random) {
        BlockState state = level.getBlockState(position);
        if (!state.is(CityLootBlocks.AMMO_CACHE.get())) {
            return null;
        }

        AmmoType[] types = AmmoType.values();
        AmmoType type = types[random.nextInt(types.length)];
        int steps = (MAX_REWARD - MIN_REWARD) / REWARD_STEP;
        int amount = MIN_REWARD + random.nextInt(steps + 1) * REWARD_STEP;
        ItemStack reward = new ItemStack(AmmoItems.item(type).get(), amount);

        // Remove first so duplicated packets cannot claim the same cache twice.
        level.setBlock(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                Block.UPDATE_ALL);
        level.levelEvent(2001, position, Block.getId(state));
        player.getInventory().add(reward);
        if (!reward.isEmpty()) {
            player.drop(reward, false);
        }
        player.getInventory().setChanged();
        player.sendSystemMessage(Component.translatable(
                "message.cyberdeck.ammo_cache.claimed",
                amount,
                Component.translatable("item.cyberdeck." + type.itemId())), true);
        level.playSound(null, position, SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.PLAYERS, 0.8F, 1.25F);
        return new AmmoReward(type, amount);
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
}
