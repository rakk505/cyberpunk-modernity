package dev.modernity.neoncity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/** Deterministic park clusters containing at most two merchant trucks. */
final class MerchantTruckLibrary {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CATALOG_RESOURCE = "/data/neoncity/merchant_trucks/catalog.json";
    private static final String MERCHANT_TAG = "cyberdeck_park_merchant";
    private static final String ROLE_TAG = "cyberdeck_merchant_role";
    private static final String DISTRICT_TAG = "cyberdeck_merchant_district";
    private static final String ANCHOR_TAG = "cyberdeck_merchant_anchor";
    private static final long PLACEMENT_SALT = 0x5350554454525543L;
    private static final long ROLE_SALT = 0x4D45524348414E54L;
    private static final int CLUSTER_CHUNKS = 2;
    private static final int MAX_TRUCKS_PER_CLUSTER = 2;
    private static final int MAX_PLAN_CACHE = 8_192;
    private static final int MAX_BLACK_SEARCH_RINGS = 48;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final TruckAsset TRUCK = loadCatalog();
    private static final Map<PlanKey, ClusterPlan> PLAN_CACHE = new HashMap<>();
    private static final Map<BlackKey, Optional<TruckCandidate>> BLACK_TRUCK_CACHE = new HashMap<>();

    private MerchantTruckLibrary() {
    }

    enum MerchantRole {
        GUN("Gun Merchant", DyeColor.GRAY),
        CYBERWARE("Cyberware Merchant", DyeColor.YELLOW),
        CLOTHING("Clothing Merchant", DyeColor.CYAN),
        CONSUMABLE("Food Merchant", DyeColor.BROWN),
        QUEST("Fixer", DyeColor.BLACK);

        private final String displayName;
        private final DyeColor truckColor;

        MerchantRole(String displayName, DyeColor truckColor) {
            this.displayName = displayName;
            this.truckColor = truckColor;
        }

        String displayName() {
            return displayName;
        }

        DyeColor truckColor() {
            return truckColor;
        }
    }

    record TruckAsset(
            Identifier templateId,
            int sizeX,
            int sizeY,
            int sizeZ,
            int blockCount,
            String sha256) {
    }

    record TruckCandidate(
            int clusterX,
            int clusterZ,
            int chunkX,
            int chunkZ,
            int localX,
            int localZ,
            Rotation rotation,
            District district,
            int groundY,
            long selectionHash) {
        int sizeX() {
            return rotation == Rotation.NONE ? TRUCK.sizeX() : TRUCK.sizeZ();
        }

        int sizeZ() {
            return rotation == Rotation.NONE ? TRUCK.sizeZ() : TRUCK.sizeX();
        }

        int minX() {
            return (chunkX << 4) + localX;
        }

        int minZ() {
            return (chunkZ << 4) + localZ;
        }

        BlockPos base() {
            return new BlockPos(minX(), groundY + 1, minZ());
        }

        BlockPos merchantSpawn() {
            // Stand the merchant on the service counter so its hitbox remains visible and
            // reachable from ground level. Rotate this source-space cell with the truck.
            if (rotation == Rotation.NONE) {
                return base().offset(3, 2, 4);
            }
            return base().offset(TRUCK.sizeZ() - 1 - 4, 2, 3);
        }
    }

    record ClusterPlan(List<TruckCandidate> trucks) {
        ClusterPlan {
            trucks = List.copyOf(trucks);
        }
    }

    private record PlanKey(long seed, int clusterX, int clusterZ) {
    }

    private record BlackKey(long seed, District district) {
    }

    static TruckAsset truck() {
        return TRUCK;
    }

    static int decorateChunk(
            ServerLevel level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] ignoredSamples) {
        ClusterPlan plan = planForChunk(chunk.x(), chunk.z());
        int placed = 0;
        for (TruckCandidate candidate : plan.trucks()) {
            if (candidate.chunkX() != chunk.x() || candidate.chunkZ() != chunk.z()) {
                continue;
            }
            MerchantRole role = role(candidate);
            if (role == MerchantRole.QUEST
                    && VendorAnchorData.get(level).fixer(candidate.district()).isPresent()) {
                role = tradingRole(candidate);
            }
            if (placeTruck(level, candidate, role)) {
                placed++;
            }
        }
        return placed;
    }

    static ClusterPlan planForChunk(int chunkX, int chunkZ) {
        return plan(
                Math.floorDiv(chunkX, CLUSTER_CHUNKS),
                Math.floorDiv(chunkZ, CLUSTER_CHUNKS));
    }

    static ClusterPlan plan(int clusterX, int clusterZ) {
        long seed = NeonCityGenerator.layout().seed();
        PlanKey key = new PlanKey(seed, clusterX, clusterZ);
        ClusterPlan cached = PLAN_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (PLAN_CACHE.size() >= MAX_PLAN_CACHE) {
            PLAN_CACHE.clear();
            BLACK_TRUCK_CACHE.clear();
        }

        List<TruckCandidate> candidates = new ArrayList<>();
        for (int chunkOffsetZ = 0; chunkOffsetZ < CLUSTER_CHUNKS; chunkOffsetZ++) {
            for (int chunkOffsetX = 0; chunkOffsetX < CLUSTER_CHUNKS; chunkOffsetX++) {
                int chunkX = clusterX * CLUSTER_CHUNKS + chunkOffsetX;
                int chunkZ = clusterZ * CLUSTER_CHUNKS + chunkOffsetZ;
                candidates.addAll(candidatesForChunk(clusterX, clusterZ, chunkX, chunkZ));
            }
        }
        candidates.sort(Comparator.comparingLong(TruckCandidate::selectionHash)
                .thenComparingInt(TruckCandidate::chunkX)
                .thenComparingInt(TruckCandidate::chunkZ));
        List<TruckCandidate> selected = new ArrayList<>(MAX_TRUCKS_PER_CLUSTER);
        for (TruckCandidate candidate : candidates) {
            if (selected.stream().anyMatch(existing -> overlaps(existing, candidate))) {
                continue;
            }
            selected.add(candidate);
            if (selected.size() == MAX_TRUCKS_PER_CLUSTER) {
                break;
            }
        }
        ClusterPlan result = new ClusterPlan(selected);
        PLAN_CACHE.put(key, result);
        return result;
    }

    static MerchantRole role(TruckCandidate candidate) {
        Optional<TruckCandidate> black = canonicalBlackTruck(candidate.district());
        if (black.isPresent() && black.get().equals(candidate)) {
            return MerchantRole.QUEST;
        }
        return tradingRole(candidate);
    }

    private static MerchantRole tradingRole(TruckCandidate candidate) {
        MerchantRole[] tradingRoles = {
                MerchantRole.GUN,
                MerchantRole.CYBERWARE,
                MerchantRole.CLOTHING,
                MerchantRole.CONSUMABLE
        };
        long hash = MegacityLayout.mix(
                NeonCityGenerator.layout().seed() ^ ROLE_SALT,
                candidate.minX(),
                candidate.minZ());
        return tradingRoles[Math.floorMod((int) (hash ^ (hash >>> 32)), tradingRoles.length)];
    }

    static Optional<TruckCandidate> canonicalBlackTruck(District district) {
        long seed = NeonCityGenerator.layout().seed();
        BlackKey key = new BlackKey(seed, district);
        Optional<TruckCandidate> cached = BLACK_TRUCK_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        int centerClusterX = Math.floorDiv(Math.floorDiv(node.x(), 16), CLUSTER_CHUNKS);
        int centerClusterZ = Math.floorDiv(Math.floorDiv(node.z(), 16), CLUSTER_CHUNKS);
        Optional<TruckCandidate> result = Optional.empty();
        for (int ring = 0; ring <= MAX_BLACK_SEARCH_RINGS && result.isEmpty(); ring++) {
            List<TruckCandidate> ringCandidates = new ArrayList<>();
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (TruckCandidate candidate
                            : plan(centerClusterX + dx, centerClusterZ + dz).trucks()) {
                        if (candidate.district() == district) {
                            ringCandidates.add(candidate);
                        }
                    }
                }
            }
            result = ringCandidates.stream().min(Comparator.comparingLong(candidate ->
                    MegacityLayout.mix(seed ^ ROLE_SALT, candidate.minX(), candidate.minZ())));
        }
        BLACK_TRUCK_CACHE.put(key, result);
        return result;
    }

    static boolean placeTruck(
            ServerLevel level,
            TruckCandidate candidate,
            MerchantRole role) {
        StructureTemplate template = level.getStructureManager().get(TRUCK.templateId()).orElse(null);
        if (template == null) {
            LOGGER.error("[NeonCity] missing merchant truck template {}", TRUCK.templateId());
            return false;
        }
        Vec3i templateSize = template.getSize();
        if (templateSize.getX() != TRUCK.sizeX()
                || templateSize.getY() != TRUCK.sizeY()
                || templateSize.getZ() != TRUCK.sizeZ()) {
            LOGGER.error(
                    "[NeonCity] merchant truck size {} disagrees with catalog {}x{}x{}",
                    templateSize, TRUCK.sizeX(), TRUCK.sizeY(), TRUCK.sizeZ());
            return false;
        }
        if (!isVolumeClear(level, candidate)) {
            return false;
        }

        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setKnownShape(true)
                .setMirror(Mirror.NONE)
                .setRotation(candidate.rotation())
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)
                .addProcessor(new TruckBodyColorProcessor(role.truckColor()));
        BlockPos desiredMin = candidate.base();
        BlockPos anchor = template.getZeroPositionWithTransform(
                desiredMin, Mirror.NONE, candidate.rotation());
        boolean structurePlaced = template.placeInWorld(
                level,
                anchor,
                anchor,
                settings,
                RandomSource.create(candidate.selectionHash()),
                PLACE_FLAGS);
        if (!structurePlaced) {
            return false;
        }
        Villager merchant = spawnMerchant(level, candidate, role);
        if (merchant == null || !level.addFreshEntity(merchant)) {
            LOGGER.error("[NeonCity] could not spawn {} at merchant truck {}",
                    role.displayName(), candidate.base());
            return false;
        }
        VendorService.register(
                level,
                merchant,
                role,
                candidate.district(),
                candidate.base(),
                candidate.merchantSpawn(),
                candidate.rotation() == Rotation.NONE ? 0.0F : 90.0F);
        LOGGER.debug("[NeonCity] placed {} truck in {} at {}",
                role, candidate.district().label(), candidate.base());
        return true;
    }

    static Villager spawnMerchant(
            ServerLevel level,
            TruckCandidate candidate,
            MerchantRole role) {
        BlockPos spawn = candidate.merchantSpawn();
        AABB bounds = new AABB(spawn).inflate(2.0);
        if (!level.getEntitiesOfClass(Villager.class, bounds,
                entity -> isMerchant(entity)
                        && merchantAnchor(entity).filter(candidate.base()::equals).isPresent())
                .isEmpty()) {
            return null;
        }
        return createMerchant(
                level,
                spawn,
                candidate.rotation() == Rotation.NONE ? 0.0F : 90.0F,
                role,
                candidate.district(),
                candidate.base());
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
        return merchant;
    }

    static boolean hasTruckBlocks(ServerLevel level, TruckCandidate candidate) {
        int occupied = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < TRUCK.sizeY() && occupied < 12; y++) {
            for (int z = 0; z < candidate.sizeZ() && occupied < 12; z++) {
                for (int x = 0; x < candidate.sizeX() && occupied < 12; x++) {
                    cursor.set(candidate.minX() + x, candidate.groundY() + 1 + y,
                            candidate.minZ() + z);
                    if (!level.isEmptyBlock(cursor)) {
                        occupied++;
                    }
                }
            }
        }
        return occupied >= 12;
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
        PLAN_CACHE.clear();
        BLACK_TRUCK_CACHE.clear();
        VendorStallLibrary.clearCache();
    }

    private static List<TruckCandidate> candidatesForChunk(
            int clusterX,
            int clusterZ,
            int chunkX,
            int chunkZ) {
        if (!mightContainTruckPark(chunkX, chunkZ)) {
            return List.of();
        }
        NeonCityGenerator.UrbanSample[][] samples = NeonCityGenerator.sampleChunk(
                chunkX << 4, chunkZ << 4);
        List<TruckCandidate> candidates = new ArrayList<>();
        for (Rotation rotation : List.of(Rotation.NONE, Rotation.CLOCKWISE_90)) {
            int sizeX = rotation == Rotation.NONE ? TRUCK.sizeX() : TRUCK.sizeZ();
            int sizeZ = rotation == Rotation.NONE ? TRUCK.sizeZ() : TRUCK.sizeX();
            for (int localZ = 0; localZ <= 16 - sizeZ; localZ++) {
                for (int localX = 0; localX <= 16 - sizeX; localX++) {
                    NeonCityGenerator.UrbanSample center = samples[
                            localZ + sizeZ / 2 + 1][localX + sizeX / 2 + 1];
                    if (!isParkFootprint(samples, localX, localZ, sizeX, sizeZ, center)) {
                        continue;
                    }
                    int worldX = (chunkX << 4) + localX;
                    int worldZ = (chunkZ << 4) + localZ;
                    long hash = MegacityLayout.mix(
                            NeonCityGenerator.layout().seed() ^ PLACEMENT_SALT
                                    ^ (rotation == Rotation.NONE ? 0L : Long.MIN_VALUE),
                            worldX,
                            worldZ);
                    candidates.add(new TruckCandidate(
                            clusterX,
                            clusterZ,
                            chunkX,
                            chunkZ,
                            localX,
                            localZ,
                            rotation,
                            center.district(),
                            center.groundY(),
                            hash));
                }
            }
        }
        return candidates;
    }

    private static boolean mightContainTruckPark(int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        // Every axis-aligned interval of seven blocks contains at least one member of this
        // four-block grid. A valid 14x7 or 7x14 footprint therefore cannot be rejected here.
        for (int localZ = 2; localZ < 16; localZ += 4) {
            for (int localX = 2; localX < 16; localX += 4) {
                if (NeonCityGenerator.sample(minX + localX, minZ + localZ).roadClass()
                        == NeonCityGenerator.RoadClass.PARK) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isParkFootprint(
            NeonCityGenerator.UrbanSample[][] samples,
            int minX,
            int minZ,
            int sizeX,
            int sizeZ,
            NeonCityGenerator.UrbanSample center) {
        if (center.roadClass() != NeonCityGenerator.RoadClass.PARK) {
            return false;
        }
        for (int localZ = minZ; localZ < minZ + sizeZ; localZ++) {
            for (int localX = minX; localX < minX + sizeX; localX++) {
                NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                if (sample.roadClass() != NeonCityGenerator.RoadClass.PARK
                        || (sample.zone() != MegacityLayout.Zone.NEST
                        && sample.zone() != MegacityLayout.Zone.BACKSTREETS)
                        || sample.district() != center.district()
                        || sample.groundY() != center.groundY()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean overlaps(TruckCandidate first, TruckCandidate second) {
        int padding = 1;
        return first.minX() - padding < second.minX() + second.sizeX() + padding
                && first.minX() + first.sizeX() + padding > second.minX() - padding
                && first.minZ() - padding < second.minZ() + second.sizeZ() + padding
                && first.minZ() + first.sizeZ() + padding > second.minZ() - padding;
    }

    private static boolean isVolumeClear(ServerLevel level, TruckCandidate candidate) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < TRUCK.sizeY(); y++) {
            for (int z = 0; z < candidate.sizeZ(); z++) {
                for (int x = 0; x < candidate.sizeX(); x++) {
                    cursor.set(candidate.minX() + x, candidate.groundY() + 1 + y,
                            candidate.minZ() + z);
                    if (!level.isEmptyBlock(cursor)) {
                        return false;
                    }
                }
            }
        }
        return true;
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

    private static TruckAsset loadCatalog() {
        try (InputStream stream = MerchantTruckLibrary.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing merchant truck catalog " + CATALOG_RESOURCE);
            }
            JsonObject truck = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("truck");
            JsonArray size = truck.getAsJsonArray("size");
            TruckAsset asset = new TruckAsset(
                    Identifier.parse(truck.get("template").getAsString()),
                    size.get(0).getAsInt(),
                    size.get(1).getAsInt(),
                    size.get(2).getAsInt(),
                    truck.get("blocks").getAsInt(),
                    truck.get("sha256").getAsString());
            if (asset.sizeX() != 14 || asset.sizeY() != 8 || asset.sizeZ() != 7
                    || asset.blockCount() != 298 || asset.sha256().length() != 64) {
                throw new IllegalStateException("merchant truck catalog contract changed");
            }
            return asset;
        } catch (IOException exception) {
            throw new IllegalStateException("could not load merchant truck catalog", exception);
        }
    }

    private static final class TruckBodyColorProcessor implements StructureProcessor {
        private final BlockState body;

        private TruckBodyColorProcessor(DyeColor color) {
            this.body = Blocks.CONCRETE.pick(color).defaultBlockState();
        }

        @Override
        public MapCodec<? extends StructureProcessor> codec() {
            return MapCodec.unit(this);
        }

        @Override
        public StructureTemplate.StructureBlockInfo processBlock(
                LevelReader level,
                BlockPos targetPosition,
                BlockPos referencePos,
                BlockPos placementPosition,
                StructureTemplate.StructureBlockInfo processedBlockInfo,
                StructurePlaceSettings settings) {
            BlockState source = processedBlockInfo.state();
            if (!source.is(Blocks.CONCRETE.pick(DyeColor.GRAY))
                    && !source.is(Blocks.CONCRETE_POWDER.pick(DyeColor.GRAY))
                    && !source.is(Blocks.GLAZED_TERRACOTTA.pick(DyeColor.GRAY))) {
                return processedBlockInfo;
            }
            return new StructureTemplate.StructureBlockInfo(
                    processedBlockInfo.pos(), body, processedBlockInfo.nbt());
        }
    }
}
