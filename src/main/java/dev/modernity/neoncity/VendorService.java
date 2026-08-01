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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Registration, recovery, and immovable-anchor maintenance shared by every vendor site. */
final class VendorService {
    private static final double ANCHOR_SEARCH_RADIUS = 3.0;
    private static final String CURRENT_SITE_PREFIX = "vendor_stall_v2_";

    private VendorService() {
    }

    static String siteId(BlockPos sitePos) {
        return CURRENT_SITE_PREFIX + sitePos.asLong();
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
            entity.discard();
            return;
        }
        VendorAnchorData data = VendorAnchorData.get(level);
        VendorAnchorData.Anchor existing = data.anchor(siteId(sitePos)).orElse(null);
        if (existing != null
                && isCurrentAnchor(existing)
                && existing.role() == role
                && existing.district() == district) {
            register(
                    level,
                    entity,
                    role,
                    district,
                    sitePos,
                    existing.merchantPos(),
                    existing.yaw());
            return;
        }

        VendorAnchorData.Anchor legacy = data.anchors().stream()
                .filter(anchor -> isLegacyAnchor(anchor)
                        && anchor.role() == role
                        && anchor.district() == district
                        && anchor.sitePos().equals(sitePos))
                .findFirst()
                .orElse(null);
        if (legacy != null && currentAnchor(data, district, role).isEmpty()) {
            lockMerchant(entity, legacy.merchantPos(), legacy.yaw());
            data.bindEntity(legacy.siteId(), entity.getUUID());
            return;
        }
        entity.discard();
    }

    /** Keeps loaded vendors at their authoritative anchors and recreates a missing entity once. */
    static void maintainAnchors(ServerLevel level) {
        VendorAnchorData data = VendorAnchorData.get(level);
        for (VendorAnchorData.Anchor anchor : data.anchors()) {
            if (isLegacyAnchor(anchor)) {
                if (currentAnchor(data, anchor.district(), anchor.role()).isPresent()) {
                    retireLegacyAnchor(level, data, anchor);
                    continue;
                }
            }
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

    /** Ensures one persisted building stall for the fixer and every trading specialty. */
    static boolean ensureDistrictVendors(ServerLevel level, District district) {
        VendorAnchorData data = VendorAnchorData.get(level);
        long before = data.revision();
        for (MerchantTruckLibrary.MerchantRole role
                : VendorStallLibrary.plannedRoles(district)) {
            if (currentAnchor(data, district, role).isEmpty()) {
                VendorStallLibrary.ensure(level, district, role);
            }
            if (currentAnchor(data, district, role).isPresent()) {
                for (VendorAnchorData.Anchor anchor : data.anchors()) {
                    if (anchor.district() == district
                            && anchor.role() == role
                            && isLegacyAnchor(anchor)) {
                        retireLegacyAnchor(level, data, anchor);
                    }
                }
            }
        }
        return data.revision() != before;
    }

    static boolean ensureDistrictFixer(ServerLevel level, District district) {
        return ensureDistrictVendors(level, district);
    }

    static Optional<VendorAnchorData.Anchor> currentAnchor(
            VendorAnchorData data,
            District district,
            MerchantTruckLibrary.MerchantRole role) {
        return data.anchors().stream()
                .filter(anchor -> isCurrentAnchor(anchor)
                        && anchor.district() == district
                        && anchor.role() == role)
                .findFirst();
    }

    private static boolean isCurrentAnchor(VendorAnchorData.Anchor anchor) {
        return anchor.siteId().startsWith(CURRENT_SITE_PREFIX)
                && anchor.sitePos().equals(anchor.merchantPos());
    }

    private static boolean isLegacyAnchor(VendorAnchorData.Anchor anchor) {
        return !isCurrentAnchor(anchor);
    }

    private static void retireLegacyAnchor(
            ServerLevel level,
            VendorAnchorData data,
            VendorAnchorData.Anchor anchor) {
        anchor.entityUuid().map(level::getEntity).ifPresent(Entity::discard);
        if (level.isLoaded(anchor.merchantPos())) {
            AABB area = new AABB(anchor.merchantPos()).inflate(18.0, 8.0, 18.0);
            for (Villager merchant : level.getEntitiesOfClass(
                    Villager.class,
                    area,
                    entity -> MerchantTruckLibrary.merchantAnchor(entity)
                            .filter(anchor.sitePos()::equals).isPresent())) {
                merchant.discard();
            }
        }
        data.remove(anchor.siteId());
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
