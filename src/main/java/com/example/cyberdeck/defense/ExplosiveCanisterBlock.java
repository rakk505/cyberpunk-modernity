package com.example.cyberdeck.defense;

import java.util.List;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.ints.IntList;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A decorated-pot-shaped explosive that detonates when struck by a projectile or gunshot. */
public final class ExplosiveCanisterBlock extends Block {
    public static final MapCodec<ExplosiveCanisterBlock> CODEC =
            simpleCodec(ExplosiveCanisterBlock::new);
    private static final VoxelShape SHAPE = Block.column(14.0, 0.0, 16.0);
    private static final float EXPLOSION_RADIUS = 3.0F;
    private static final DustParticleOptions YELLOW_SPARK =
            new DustParticleOptions(0xFFD21A, 1.35F);
    private static final DustParticleOptions ORANGE_SPARK =
            new DustParticleOptions(0xFF7A00, 1.35F);
    private static final FireworkExplosion FIREWORK_BURST = new FireworkExplosion(
            FireworkExplosion.Shape.BURST,
            IntList.of(0xFFD21A, 0xFF7A00),
            IntList.of(0xFF7A00, 0xFFD21A),
            true,
            true);

    public ExplosiveCanisterBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void onProjectileHit(
            Level level, BlockState state, BlockHitResult hitResult, Projectile projectile) {
        if (level instanceof ServerLevel serverLevel) {
            detonate(serverLevel, hitResult.getBlockPos(), projectile);
        }
    }

    public static boolean detonate(
            ServerLevel level, BlockPos pos, @Nullable Entity source) {
        if (!level.getBlockState(pos).is(DefenseContent.EXPLOSIVE_CANISTER.get())) {
            return false;
        }

        level.removeBlock(pos, false);
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.6;
        double z = pos.getZ() + 0.5;
        level.sendParticles(ParticleTypes.FLAME,
                x, y, z, 48, 0.45, 0.55, 0.45, 0.08);
        level.sendParticles(ParticleTypes.FIREWORK,
                x, y, z, 36, 0.5, 0.6, 0.5, 0.12);
        level.sendParticles(YELLOW_SPARK,
                x, y, z, 24, 0.5, 0.6, 0.5, 0.1);
        level.sendParticles(ORANGE_SPARK,
                x, y, z, 24, 0.5, 0.6, 0.5, 0.1);
        spawnColoredFirework(level, source, x, y, z);
        level.explode(source, x, y, z, EXPLOSION_RADIUS, Level.ExplosionInteraction.NONE);
        return true;
    }

    private static void spawnColoredFirework(
            ServerLevel level, @Nullable Entity source, double x, double y, double z) {
        ItemStack firework = new ItemStack(Items.FIREWORK_ROCKET);
        firework.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(FIREWORK_BURST)));
        FireworkRocketEntity visual = new FireworkRocketEntity(level, source, x, y, z, firework);
        visual.setSilent(true);
        level.addFreshEntity(visual);
        level.broadcastEntityEvent(visual, (byte) 17);
        visual.discard();
    }
}
