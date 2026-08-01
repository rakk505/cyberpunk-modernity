package dev.modernity.neoncity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Registration, recovery, and immovable-anchor maintenance shared by every vendor site. */
final class VendorService {
    private static final double ANCHOR_SEARCH_RADIUS = 3.0;

    private VendorService() {
    }

    static String siteId(BlockPos sitePos) {
        return "vendor_" + sitePos.asLong();
    }

    static VendorAnchorData.Anchor register(
            ServerLevel level,
            Entity merchant,
            MerchantTruckLibrary.MerchantRole role,
            District district,
            BlockPos sitePos,
            BlockPos merchantPos,
            float yaw) {
        lockMerchant(merchant, merchantPos, yaw);
        return VendorAnchorData.get(level).register(
                siteId(sitePos), role, district, sitePos, merchantPos, yaw, merchant.getUUID());
    }

    static void registerLoadedMerchant(ServerLevel level, Entity entity) {
        if (!MerchantTruckLibrary.isMerchant(entity)) {
            return;
        }
        MerchantTruckLibrary.MerchantRole role =
                MerchantTruckLibrary.merchantRole(entity).orElse(null);
        District district = MerchantTruckLibrary.merchantDistrict(entity).orElse(null);
        BlockPos sitePos = MerchantTruckLibrary.merchantAnchor(entity).orElse(null);
        if (role == null || district == null || sitePos == null) {
            return;
        }
        VendorAnchorData data = VendorAnchorData.get(level);
        VendorAnchorData.Anchor existing = data.anchor(siteId(sitePos)).orElse(null);
        BlockPos merchantPos = existing == null ? entity.blockPosition() : existing.merchantPos();
        float yaw = existing == null ? entity.getYRot() : existing.yaw();
        register(level, entity, role, district, sitePos, merchantPos, yaw);
    }

    /** Keeps loaded vendors at their authoritative anchors and recreates a missing entity once. */
    static void maintainAnchors(ServerLevel level) {
        VendorAnchorData data = VendorAnchorData.get(level);
        for (VendorAnchorData.Anchor anchor : data.anchors()) {
            if (!level.isLoaded(anchor.merchantPos())) {
                continue;
            }

            List<Villager> matches = new ArrayList<>(level.getEntitiesOfClass(
                    Villager.class,
                    new AABB(anchor.merchantPos()).inflate(ANCHOR_SEARCH_RADIUS),
                    merchant -> MerchantTruckLibrary.isMerchant(merchant)
                            && MerchantTruckLibrary.merchantAnchor(merchant)
                            .filter(anchor.sitePos()::equals).isPresent()));
            anchor.entityUuid().map(level::getEntity)
                    .filter(Villager.class::isInstance)
                    .map(Villager.class::cast)
                    .filter(MerchantTruckLibrary::isMerchant)
                    .filter(merchant -> !matches.contains(merchant))
                    .ifPresent(matches::add);

            matches.sort(Comparator.comparing(entity -> entity.getUUID().toString()));
            Villager keeper = preferred(matches, anchor.entityUuid()).orElse(null);
            if (keeper == null) {
                keeper = MerchantTruckLibrary.createMerchant(
                        level,
                        anchor.merchantPos(),
                        anchor.yaw(),
                        anchor.role(),
                        anchor.district(),
                        anchor.sitePos());
                if (keeper == null || !level.addFreshEntity(keeper)) {
                    continue;
                }
                matches.add(keeper);
            }

            for (Villager merchant : matches) {
                if (merchant != keeper) {
                    merchant.discard();
                }
            }
            lockMerchant(keeper, anchor.merchantPos(), anchor.yaw());
            data.bindEntity(anchor.siteId(), keeper.getUUID());
        }
    }

    /** Ensures a district has a persisted fixer, using its truck or a compact stall fallback. */
    static boolean ensureDistrictFixer(ServerLevel level, District district) {
        VendorAnchorData data = VendorAnchorData.get(level);
        long before = data.revision();
        if (data.fixer(district).isPresent()) {
            return false;
        }

        Optional<MerchantTruckLibrary.TruckCandidate> truck =
                MerchantTruckLibrary.canonicalBlackTruck(district);
        if (truck.isPresent()) {
            MerchantTruckLibrary.TruckCandidate candidate = truck.get();
            ChunkPos chunk = new ChunkPos(candidate.chunkX(), candidate.chunkZ());
            if (!NeonCityGenerator.isGenerated(chunk)) {
                NeonCityGenerator.generateNow(level, chunk.x(), chunk.z(), 0);
            }
            registerExistingAt(level, candidate.base());
            if (data.fixer(district).isEmpty()
                    && MerchantTruckLibrary.hasTruckBlocks(level, candidate)) {
                Villager merchant = MerchantTruckLibrary.spawnMerchant(
                        level, candidate, MerchantTruckLibrary.MerchantRole.QUEST);
                if (merchant != null && level.addFreshEntity(merchant)) {
                    register(
                            level,
                            merchant,
                            MerchantTruckLibrary.MerchantRole.QUEST,
                            candidate.district(),
                            candidate.base(),
                            candidate.merchantSpawn(),
                            candidate.rotation() == net.minecraft.world.level.block.Rotation.NONE
                                    ? 0.0F : 90.0F);
                }
            }
        }

        if (data.fixer(district).isEmpty()) {
            VendorStallLibrary.canonical(district).ifPresent(candidate -> {
                ChunkPos chunk = ChunkPos.containing(candidate.sitePos());
                if (!NeonCityGenerator.isGenerated(chunk)) {
                    NeonCityGenerator.generateNow(level, chunk.x(), chunk.z(), 0);
                }
                if (data.fixer(district).isEmpty()) {
                    VendorStallLibrary.place(
                            level, candidate, MerchantTruckLibrary.MerchantRole.QUEST);
                }
            });
        }
        return data.revision() != before;
    }

    private static void registerExistingAt(ServerLevel level, BlockPos sitePos) {
        AABB area = new AABB(sitePos).inflate(18.0, 8.0, 18.0);
        for (Villager merchant : level.getEntitiesOfClass(
                Villager.class, area, MerchantTruckLibrary::isMerchant)) {
            if (MerchantTruckLibrary.merchantAnchor(merchant)
                    .filter(sitePos::equals).isPresent()) {
                registerLoadedMerchant(level, merchant);
            }
        }
    }

    private static Optional<Villager> preferred(
            List<Villager> merchants, Optional<UUID> preferredUuid) {
        if (preferredUuid.isPresent()) {
            for (Villager merchant : merchants) {
                if (merchant.getUUID().equals(preferredUuid.get())) {
                    return Optional.of(merchant);
                }
            }
        }
        return merchants.stream().findFirst();
    }

    private static void lockMerchant(Entity entity, BlockPos anchor, float yaw) {
        entity.setInvulnerable(true);
        entity.setNoGravity(false);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0F;
        if (entity instanceof Villager villager) {
            villager.setNoAi(true);
            villager.setPersistenceRequired();
        }
        if (entity.blockPosition().equals(anchor)
                && entity.position().distanceToSqr(Vec3.atBottomCenterOf(anchor)) < 0.0025) {
            entity.setYRot(yaw);
            entity.setXRot(0.0F);
            return;
        }
        entity.snapTo(anchor, yaw, 0.0F);
    }
}
