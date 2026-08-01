package com.example.cyberdeck.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PostSpawnProcessor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Places a turret only when its full footprint and height are clear. */
public final class KangTaoTurretItem extends Item {
    public KangTaoTurretItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }

        if (context.getClickedFace() == Direction.DOWN) {
            return fail(context.getPlayer(), "message.cyberdeck.turret.invalid_surface");
        }

        BlockPos pos = new BlockPlaceContext(context).getClickedPos();
        BlockPos support = pos.below();
        if (level.getBlockState(support).getCollisionShape(level, support).isEmpty()) {
            return fail(context.getPlayer(), "message.cyberdeck.turret.invalid_surface");
        }
        if (!canPlaceAt(level, pos)) {
            return fail(context.getPlayer(), "message.cyberdeck.turret.blocked");
        }

        EntityType<KangTaoTurret> type = DefenseContent.KANG_TAO_TURRET.get();
        PostSpawnProcessor<KangTaoTurret> config = EntityType.createDefaultStackConfig(
                level, context.getItemInHand(), context.getPlayer());
        KangTaoTurret turret = type.create(
                level, config, pos, EntitySpawnReason.SPAWN_ITEM_USE, true, true);
        if (turret == null || !level.noBlockCollision(turret, turret.getBoundingBox())) {
            if (turret != null) {
                turret.discard();
            }
            return fail(context.getPlayer(), "message.cyberdeck.turret.blocked");
        }

        float yaw = Mth.floor((Mth.wrapDegrees(context.getRotation() - 180.0F) + 22.5F)
                / 45.0F) * 45.0F;
        turret.setBaseYaw(yaw);
        level.addFreshEntityWithPassengers(turret);
        level.playSound(null, turret.getX(), turret.getY(), turret.getZ(),
                SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.8F, 0.75F);
        turret.gameEvent(GameEvent.ENTITY_PLACE, context.getPlayer());
        context.getItemInHand().consume(1, context.getPlayer());
        notify(context.getPlayer(), "message.cyberdeck.turret.deployed");
        return InteractionResult.SUCCESS_SERVER;
    }

    static boolean canPlaceAt(Level level, BlockPos pos) {
        EntityType<KangTaoTurret> type = DefenseContent.KANG_TAO_TURRET.get();
        Vec3 center = Vec3.atBottomCenterOf(pos);
        AABB bounds = type.getDimensions().makeBoundingBox(center.x(), center.y(), center.z());
        return level.noBlockCollision(null, bounds);
    }

    private static InteractionResult fail(Player player, String messageKey) {
        notify(player, messageKey);
        return InteractionResult.FAIL;
    }

    private static void notify(Player player, String messageKey) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable(messageKey), true);
        }
    }
}
