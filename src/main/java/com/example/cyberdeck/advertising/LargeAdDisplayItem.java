package com.example.cyberdeck.advertising;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/** Places one validated 8x4 display while consuming only one ticking block entity. */
public final class LargeAdDisplayItem extends Item {
    public LargeAdDisplayItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        Direction facing = context.getClickedFace();
        BlockPos anchor = context.getClickedPos().relative(facing);
        LargeAdSurfaceValidator.Result validation =
                LargeAdSurfaceValidator.validate(level, anchor, facing);
        if (!validation.valid()) {
            showFailure(player, level, validation.failure().translationKey());
            return InteractionResult.FAIL;
        }

        ItemStack stack = context.getItemInHand();
        List<BlockPos> targets = LargeAdSurfaceValidator.targets(anchor, facing);
        if (player != null && (!player.mayBuild()
                || targets.stream().anyMatch(pos -> !player.mayUseItemAt(pos, facing, stack)))) {
            showFailure(player, level, "message.cyberdeck.ad_display.protected");
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        List<BlockState> previousStates = new ArrayList<>(targets.size());
        for (BlockPos target : targets) {
            previousStates.add(level.getBlockState(target));
        }

        BlockState anchorState = AdvertisingContent.AD_DISPLAY_ANCHOR.get()
                .defaultBlockState().setValue(AdDisplayBlock.FACING, facing);
        BlockState panelState = AdvertisingContent.AD_DISPLAY_PANEL.get()
                .defaultBlockState().setValue(AdPanelBlock.FACING, facing);
        for (int index = 0; index < targets.size(); index++) {
            BlockState newState = index == 0 ? anchorState : panelState;
            if (!level.setBlock(targets.get(index), newState, Block.UPDATE_ALL)) {
                rollback(level, targets, previousStates, index);
                showFailure(player, level, "message.cyberdeck.ad_display.failed");
                return InteractionResult.FAIL;
            }
        }

        if (player instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.PLACED_BLOCK.trigger(serverPlayer, anchor, stack);
        }
        level.playSound(null, anchor, anchorState.getSoundType().getPlaceSound(),
                SoundSource.BLOCKS, 1.0F, 0.8F);
        level.gameEvent(GameEvent.BLOCK_PLACE, anchor, GameEvent.Context.of(player, anchorState));
        stack.consume(1, player);
        return InteractionResult.SUCCESS;
    }

    private static void rollback(
            Level level, List<BlockPos> targets, List<BlockState> states, int lastPlaced) {
        for (int index = 0; index <= lastPlaced; index++) {
            level.setBlock(targets.get(index), states.get(index), Block.UPDATE_ALL);
        }
    }

    private static void showFailure(Player player, Level level, String translationKey) {
        if (player instanceof ServerPlayer serverPlayer && !level.isClientSide()) {
            serverPlayer.sendSystemMessage(Component.translatable(translationKey), true);
        }
    }
}
