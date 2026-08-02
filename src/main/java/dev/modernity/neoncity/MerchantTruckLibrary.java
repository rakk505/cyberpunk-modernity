package dev.modernity.neoncity;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;

/** Legacy-named merchant entity contract shared by persistent building stalls. */
final class MerchantTruckLibrary {
    // Retain these NBT keys so merchants in old saves can be identified and retired safely.
    private static final String MERCHANT_TAG = "cyberdeck_park_merchant";
    private static final String ROLE_TAG = "cyberdeck_merchant_role";
    private static final String DISTRICT_TAG = "cyberdeck_merchant_district";
    private static final String ANCHOR_TAG = "cyberdeck_merchant_anchor";
    private static final String OFFERS_VERSION_TAG = "cyberdeck_merchant_offers_version";
    private static final int CURRENT_OFFERS_VERSION = 2;

    private MerchantTruckLibrary() {
    }

    enum MerchantRole {
        GUN("Gun Merchant", DyeColor.GRAY),
        CYBERWARE("Cyberware Merchant", DyeColor.YELLOW),
        CLOTHING("Clothing Merchant", DyeColor.CYAN),
        CONSUMABLE("Food Merchant", DyeColor.BROWN),
        QUEST("Fixer", DyeColor.BLACK);

        private final String displayName;
        private final DyeColor stallColor;

        MerchantRole(String displayName, DyeColor stallColor) {
            this.displayName = displayName;
            this.stallColor = stallColor;
        }

        String displayName() {
            return displayName;
        }

        DyeColor stallColor() {
            return stallColor;
        }
    }

    /** Kept as a defensive no-op for compatibility with older internal callers. */
    static int decorateChunk(
            ServerLevel ignoredLevel,
            ChunkPos ignoredChunk,
            NeonCityGenerator.UrbanSample[][] ignoredSamples) {
        return 0;
    }

    static Villager createMerchant(
            ServerLevel level,
            BlockPos spawn,
            float yaw,
            MerchantRole role,
            District district,
            BlockPos anchor) {
        Villager merchant = EntityTypes.VILLAGER.create(level, EntitySpawnReason.STRUCTURE);
        if (merchant == null) {
            return null;
        }
        merchant.snapTo(spawn, yaw, 0.0F);
        merchant.finalizeSpawn(
                level,
                level.getCurrentDifficultyAt(spawn),
                EntitySpawnReason.STRUCTURE,
                null);
        merchant.setVillagerData(merchant.getVillagerData()
                .withProfession(level.registryAccess(), profession(role)));
        merchant.setVillagerDataFinalized(true);
        merchant.getPersistentData().putBoolean(MERCHANT_TAG, true);
        merchant.getPersistentData().putInt(ROLE_TAG, role.ordinal());
        merchant.getPersistentData().putInt(DISTRICT_TAG, district.ordinal());
        merchant.getPersistentData().putLong(ANCHOR_TAG, anchor.asLong());
        merchant.setPersistenceRequired();
        merchant.setNoAi(true);
        merchant.setInvulnerable(true);
        merchant.setCustomName(Component.literal(role.displayName()));
        merchant.setCustomNameVisible(true);
        merchant.getOffers().clear();
        merchant.getOffers().addAll(MerchantTradeCatalog.offers(role));
        merchant.getPersistentData().putInt(OFFERS_VERSION_TAG, CURRENT_OFFERS_VERSION);
        return merchant;
    }

    /** Replaces serialized pre-emerald offers once when a merchant is loaded after the upgrade. */
    static boolean refreshOffersIfNeeded(Villager merchant, MerchantRole role) {
        int version = merchant.getPersistentData().getInt(OFFERS_VERSION_TAG).orElse(0);
        if (version >= CURRENT_OFFERS_VERSION) {
            return false;
        }
        merchant.getOffers().clear();
        merchant.getOffers().addAll(MerchantTradeCatalog.offers(role));
        merchant.getPersistentData().putInt(OFFERS_VERSION_TAG, CURRENT_OFFERS_VERSION);
        return true;
    }

    static boolean isMerchant(Entity entity) {
        return entity instanceof Villager
                && entity.getPersistentData().getBoolean(MERCHANT_TAG).orElse(false);
    }

    static Optional<MerchantRole> merchantRole(Entity entity) {
        if (!isMerchant(entity)) {
            return Optional.empty();
        }
        int ordinal = entity.getPersistentData().getInt(ROLE_TAG).orElse(-1);
        MerchantRole[] roles = MerchantRole.values();
        return ordinal >= 0 && ordinal < roles.length
                ? Optional.of(roles[ordinal]) : Optional.empty();
    }

    static Optional<District> merchantDistrict(Entity entity) {
        if (!isMerchant(entity)) {
            return Optional.empty();
        }
        int ordinal = entity.getPersistentData().getInt(DISTRICT_TAG).orElse(-1);
        District[] districts = District.values();
        return ordinal >= 0 && ordinal < districts.length
                ? Optional.of(districts[ordinal]) : Optional.empty();
    }

    static Optional<BlockPos> merchantAnchor(Entity entity) {
        if (!isMerchant(entity)) {
            return Optional.empty();
        }
        return entity.getPersistentData().getLong(ANCHOR_TAG).map(BlockPos::of);
    }

    static void clearCaches() {
        VendorStallLibrary.clearCache();
    }

    private static net.minecraft.resources.ResourceKey<VillagerProfession> profession(
            MerchantRole role) {
        return switch (role) {
            case GUN -> VillagerProfession.WEAPONSMITH;
            case CYBERWARE -> VillagerProfession.CLERIC;
            case CLOTHING -> VillagerProfession.LEATHERWORKER;
            case CONSUMABLE -> VillagerProfession.BUTCHER;
            case QUEST -> VillagerProfession.CARTOGRAPHER;
        };
    }
}
