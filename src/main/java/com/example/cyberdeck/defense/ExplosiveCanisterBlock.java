package com.example.cyberdeck.defense;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        return detonateChain(level, pos, source) > 0;
    }

    static int detonateChain(
            ServerLevel level, BlockPos pos, @Nullable Entity source) {
        BlockPos first = pos.immutable();
        if (!level.getBlockState(first).is(DefenseContent.EXPLOSIVE_CANISTER.get())) {
            return 0;
        }

        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> queued = new HashSet<>();
        pending.addLast(first);
        queued.add(first);

        int detonated = 0;
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            if (!level.getBlockState(current).is(DefenseContent.EXPLOSIVE_CANISTER.get())
                    || !level.removeBlock(current, false)) {
                continue;
            }

            enqueueNearbyCanisters(level, current, pending, queued);
            explodeRemovedCanister(level, current, source);
            detonated++;
        }
        return detonated;
    }

    private static void enqueueNearbyCanisters(
            ServerLevel level,
            BlockPos origin,
            ArrayDeque<BlockPos> pending,
            Set<BlockPos> queued) {
        int range = (int) Math.ceil(EXPLOSION_RADIUS);
        double rangeSquared = EXPLOSION_RADIUS * EXPLOSION_RADIUS;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    int distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (distanceSquared == 0 || distanceSquared > rangeSquared) {
                        continue;
                    }
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (!level.isInWorldBounds(candidate)
                            || !level.hasChunkAt(candidate)
                            || !level.getBlockState(candidate)
                                    .is(DefenseContent.EXPLOSIVE_CANISTER.get())) {
                        continue;
                    }
                    candidate = candidate.immutable();
                    if (queued.add(candidate)) {
                        pending.addLast(candidate);
                    }
                }
            }
        }
    }

    private static void explodeRemovedCanister(
            ServerLevel level, BlockPos pos, @Nullable Entity source) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.6;
        double z = pos.getZ() + 0.5;
        explodeAt(level, x, y, z, source);
    }

    /**
     * Replays the canister blast at an entity position without changing nearby blocks. This keeps
     * entity quickhacks visually and mechanically consistent with a shot canister.
     */
    public static void explodeAt(
            ServerLevel level, double x, double y, double z, @Nullable Entity source) {
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
    }

    /** Entity-origin canister blast, including chain ignition without terrain destruction. */
    public static void explodeDeviceAt(
            ServerLevel level, double x, double y, double z, @Nullable Entity source) {
        int range = (int) Math.ceil(EXPLOSION_RADIUS);
        BlockPos center = BlockPos.containing(x, y, z);
        double rangeSquared = EXPLOSION_RADIUS * EXPLOSION_RADIUS;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos candidate = center.offset(dx, dy, dz);
                    double blockX = candidate.getX() + 0.5 - x;
                    double blockY = candidate.getY() + 0.5 - y;
                    double blockZ = candidate.getZ() + 0.5 - z;
                    if (blockX * blockX + blockY * blockY + blockZ * blockZ
                                    > rangeSquared
                            || !level.isInWorldBounds(candidate)
                            || !level.hasChunkAt(candidate)
                            || !level.getBlockState(candidate)
                                    .is(DefenseContent.EXPLOSIVE_CANISTER.get())) {
                        continue;
                    }
                    detonateChain(level, candidate, source);
                }
            }
        }
        explodeAt(level, x, y, z, source);
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
