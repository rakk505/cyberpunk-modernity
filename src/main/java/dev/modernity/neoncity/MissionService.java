package dev.modernity.neoncity;

import com.example.cyberdeck.faction.CyberpsychoEntity;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSquads;
import com.example.cyberdeck.network.MissionSyncPacket;
import com.example.cyberdeck.network.OpenCityMapPacket;
import com.example.cyberdeck.network.OpenMerchantQuestPacket;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.weapon.WeaponItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Server-authoritative lifecycle for configurable fixer missions. */
public final class MissionService {
    private static final String PREFIX = "cyberdeck_mission_";
    private static final String ACTIVE = PREFIX + "active";
    private static final String DEFINITION = PREFIX + "definition";
    private static final String TYPE = PREFIX + "type";
    private static final String TITLE = PREFIX + "title";
    private static final String BRIEFING = PREFIX + "briefing";
    private static final String OBJECTIVE = PREFIX + "objective";
    private static final String DISTRICT = PREFIX + "district";
    private static final String TARGET_X = PREFIX + "target_x";
    private static final String TARGET_Y = PREFIX + "target_y";
    private static final String TARGET_Z = PREFIX + "target_z";
    private static final String REWARD = PREFIX + "reward";
    private static final String ACTOR_UUID = PREFIX + "actor_uuid";
    private static final String CARGO_ITEM = PREFIX + "cargo_item";
    private static final String CARGO_COUNT = PREFIX + "cargo_count";
    private static final String ACCEPTED_TICK = PREFIX + "accepted_tick";

    private static final String ACTOR_TAG = "cyberdeck_mission_actor";
    private static final String ACTOR_OWNER = "cyberdeck_mission_owner";
    private static final String ACTOR_DEFINITION = "cyberdeck_mission_definition";
    private static final String ACTOR_ROLE = "cyberdeck_mission_role";
    private static final String ROLE_TARGET = "target";
    private static final String ROLE_GUARD = "guard";

    private static final long OFFER_SALT = 0x4D495353494F4E53L;
    private static final int TARGET_OFFSET = 144;
    private static final Map<UUID, Integer> LAST_SYNC = new HashMap<>();

    private MissionService() {
    }

    public record MissionOffer(
            String definitionId,
            MissionCatalog.MissionType type,
            String title,
            String briefing,
            String objective,
            int targetDistrictOrdinal,
            int targetX,
            int targetZ,
            int reward) {
    }

    public record ActiveMission(
            String definitionId,
            MissionCatalog.MissionType type,
            String title,
            String briefing,
            String objective,
            District targetDistrict,
            BlockPos target,
            int reward,
            String actorUuid,
            String cargoItem,
            int cargoCount,
            long acceptedTick) {
        String clientObjective(ServerPlayer player) {
            if (type != MissionCatalog.MissionType.SHIP_ITEM || cargoItem.isBlank()) {
                return objective;
            }
            Item item = item(Identifier.parse(cargoItem));
            int carried = item == null ? 0 : count(player, item);
            return objective + "  [" + Math.min(carried, cargoCount) + "/" + cargoCount + "]";
        }
    }

    public static void open(ServerPlayer player, Entity merchant) {
        if (!isValidFixer(player, merchant)) return;
        District source = MerchantTruckLibrary.merchantDistrict(merchant).orElse(null);
        BlockPos anchor = MerchantTruckLibrary.merchantAnchor(merchant).orElse(null);
        if (source == null || anchor == null) return;
        PacketDistributor.sendToPlayer(player, new OpenMerchantQuestPacket(
                merchant.getId(), source.ordinal(), offers((ServerLevel) player.level(), anchor, source)));
    }

    public static boolean accept(ServerPlayer player, int merchantEntityId, int offerIndex) {
        ServerLevel level = (ServerLevel) player.level();
        Entity merchant = level.getEntity(merchantEntityId);
        if (!isValidFixer(player, merchant)) return false;
        District source = MerchantTruckLibrary.merchantDistrict(merchant).orElse(null);
        BlockPos anchor = MerchantTruckLibrary.merchantAnchor(merchant).orElse(null);
        if (source == null || anchor == null) return false;
        List<MissionOffer> available = offers(level, anchor, source);
        if (offerIndex < 0 || offerIndex >= available.size()) return false;

        MissionOffer offer = available.get(offerIndex);
        MissionCatalog.MissionDefinition definition = MissionCatalog.definition(offer.definitionId());
        return deploy(player, definition, offer);
    }

    /** Starts a configured contract directly for operator testing and mission authoring. */
    public static boolean startConfigured(
            ServerPlayer player,
            String definitionId,
            District targetDistrict) {
        ServerLevel level = (ServerLevel) player.level();
        MissionCatalog.MissionDefinition definition;
        try {
            definition = MissionCatalog.definition(definitionId);
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.literal("Unknown mission: " + definitionId)
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (!definition.targetDistricts().contains(targetDistrict)) {
            player.sendSystemMessage(Component.literal(
                            definition.title() + " is not configured for " + targetDistrict.label() + ".")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        MegacityLayout layout = NeonCityGenerator.layout();
        MegacityLayout.Node node = layout.node(targetDistrict);
        UUID playerId = player.getUUID();
        long hash = MegacityLayout.mix(
                level.getSeed() ^ layout.seed() ^ definition.id().hashCode(),
                (int) playerId.getMostSignificantBits(),
                (int) playerId.getLeastSignificantBits());
        int targetX = node.x() - 96 + Math.floorMod((int) hash, 193);
        int targetZ = node.z() - 96 + Math.floorMod((int) Long.rotateLeft(hash, 29), 193);
        int reward = definition.rewardMin() + Math.floorMod(
                (int) Long.rotateRight(hash, 37),
                definition.rewardMax() - definition.rewardMin() + 1);
        MissionOffer offer = new MissionOffer(
                definition.id(), definition.type(), definition.title(), definition.briefing(),
                definition.objectiveText(), targetDistrict.ordinal(), targetX, targetZ, reward);
        return deploy(player, definition, offer);
    }

    /** Clears an active contract and all mission-owned actors without granting payment. */
    public static boolean abandon(ServerPlayer player) {
        ActiveMission mission = activeMission(player).orElse(null);
        if (mission == null) return false;
        cleanup((ServerLevel) player.level(), player, mission);
        clear(player);
        forceSync(player);
        player.sendSystemMessage(Component.literal("Mission abandoned: " + mission.title())
                .withStyle(ChatFormatting.YELLOW));
        return true;
    }

    private static boolean deploy(
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            MissionOffer offer) {
        if (activeMission(player).isPresent()) {
            player.sendSystemMessage(Component.literal("Finish your active mission first.")
                    .withStyle(ChatFormatting.RED));
            forceSync(player);
            return false;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos target = prepareTargetArea(level, definition, offer);
        if (target == null) {
            player.sendSystemMessage(Component.literal("The fixer could not establish a safe objective site.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        ActiveMission active = new ActiveMission(
                definition.id(), definition.type(), definition.title(), definition.briefing(),
                definition.objectiveText(), District.values()[offer.targetDistrictOrdinal()],
                target, offer.reward(), "",
                definition.cargoItem() == null ? "" : definition.cargoItem().toString(),
                definition.cargoCount(), level.getGameTime());
        save(player, active);

        ActiveMission spawned = switch (active.type()) {
            case ASSASSINATE_TARGET -> spawnAssassination(level, player, definition, active);
            case NEUTRALIZE_CYBERPSYCHO -> spawnCyberpsycho(level, player, definition, active);
            case STEAL_DATA -> installDataObjective(level, player, definition, active);
            case SHIP_ITEM -> issueCargo(level, player, definition, active);
        };
        if (spawned == null) {
            clear(player);
            player.sendSystemMessage(Component.literal("Mission deployment failed; no contract was consumed.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        save(player, spawned);
        player.sendSystemMessage(Component.literal(
                        "Mission accepted: " + spawned.title() + " // " + spawned.clientObjective(player))
                .withStyle(ChatFormatting.AQUA));
        forceSync(player);
        return true;
    }

    static List<MissionOffer> offers(ServerLevel level, BlockPos anchor, District source) {
        return offers(NeonCityGenerator.layout(), level.getSeed(), anchor, source);
    }

    static List<MissionOffer> offers(
            MegacityLayout layout, long worldSeed, BlockPos anchor, District source) {
        List<MissionOffer> offers = new ArrayList<>();
        int index = 0;
        for (MissionCatalog.MissionDefinition definition : MissionCatalog.definitions()) {
            long hash = MegacityLayout.mix(
                    worldSeed ^ layout.seed() ^ OFFER_SALT ^ definition.id().hashCode(),
                    anchor.getX(), anchor.getZ() + index++);
            List<District> choices = definition.targetDistricts().stream()
                    .filter(district -> district != source || definition.targetDistricts().size() == 1)
                    .toList();
            if (choices.isEmpty()) choices = definition.targetDistricts();
            District targetDistrict = choices.get(Math.floorMod((int) hash, choices.size()));
            MegacityLayout.Node node = layout.node(targetDistrict);
            int targetX = node.x() - TARGET_OFFSET
                    + Math.floorMod((int) Long.rotateLeft(hash, 17), TARGET_OFFSET * 2 + 1);
            int targetZ = node.z() - TARGET_OFFSET
                    + Math.floorMod((int) Long.rotateRight(hash, 23), TARGET_OFFSET * 2 + 1);
            int reward = definition.rewardMin() + Math.floorMod(
                    (int) Long.rotateRight(hash, 39),
                    definition.rewardMax() - definition.rewardMin() + 1);
            offers.add(new MissionOffer(
                    definition.id(), definition.type(), definition.title(), definition.briefing(),
                    definition.objectiveText(), targetDistrict.ordinal(), targetX, targetZ, reward));
        }
        return List.copyOf(offers);
    }

    public static Optional<ActiveMission> activeMission(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ACTIVE).orElse(false)) return Optional.empty();
        try {
            MissionCatalog.MissionType type = MissionCatalog.MissionType.valueOf(
                    data.getString(TYPE).orElseThrow());
            int district = data.getInt(DISTRICT).orElseThrow();
            if (district < 0 || district >= District.values().length) throw new IllegalStateException();
            return Optional.of(new ActiveMission(
                    data.getString(DEFINITION).orElseThrow(),
                    type,
                    data.getString(TITLE).orElse("Mission"),
                    data.getString(BRIEFING).orElse(""),
                    data.getString(OBJECTIVE).orElse("Complete the objective"),
                    District.values()[district],
                    new BlockPos(
                            data.getInt(TARGET_X).orElse(0),
                            data.getInt(TARGET_Y).orElse(NeonCityGenerator.CITY_GROUND_Y + 1),
                            data.getInt(TARGET_Z).orElse(0)),
                    Math.max(1, data.getInt(REWARD).orElse(1)),
                    data.getString(ACTOR_UUID).orElse(""),
                    data.getString(CARGO_ITEM).orElse(""),
                    Math.max(0, data.getInt(CARGO_COUNT).orElse(0)),
                    data.getLong(ACCEPTED_TICK).orElse(0L)));
        } catch (RuntimeException exception) {
            clear(player);
            return Optional.empty();
        }
    }

    public static void tickPlayer(ServerPlayer player, MegacityLayout.Location location) {
        Optional<ActiveMission> optional = activeMission(player);
        if (optional.isEmpty()) {
            syncIfChanged(player, null);
            return;
        }
        ActiveMission mission = optional.get();
        if (mission.type() == MissionCatalog.MissionType.SHIP_ITEM
                && location.insideCity()
                && location.district() == mission.targetDistrict()) {
            Item cargo = mission.cargoItem().isBlank()
                    ? null : item(Identifier.parse(mission.cargoItem()));
            if (cargo != null && count(player, cargo) >= mission.cargoCount()) {
                remove(player, cargo, mission.cargoCount());
                complete(player, mission);
                return;
            }
            if (player.level().getGameTime() % 100L == 0L) {
                player.sendSystemMessage(Component.literal(
                        "Delivery requires all configured cargo items in your inventory.")
                        .withStyle(ChatFormatting.RED), true);
            }
        }
        syncIfChanged(player, mission);
    }

    public static boolean activateDataTerminal(ServerPlayer player, BlockPos position) {
        ActiveMission mission = activeMission(player).orElse(null);
        if (mission == null || mission.type() != MissionCatalog.MissionType.STEAL_DATA
                || !mission.target().equals(position)) {
            player.sendSystemMessage(Component.literal("ACCESS DENIED // NO MATCHING CONTRACT")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        player.level().playSound(null, position, SoundEvents.VAULT_OPEN_SHUTTER,
                SoundSource.BLOCKS, 0.8F, 1.4F);
        complete(player, mission);
        return true;
    }

    public static void onEntityDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        CompoundTag tags = entity.getPersistentData();
        if (!tags.getBoolean(ACTOR_TAG).orElse(false)
                || !ROLE_TARGET.equals(tags.getString(ACTOR_ROLE).orElse(""))) {
            return;
        }
        UUID owner;
        try {
            owner = UUID.fromString(tags.getString(ACTOR_OWNER).orElseThrow());
        } catch (RuntimeException exception) {
            return;
        }
        ServerPlayer player = event.getSource().getEntity() instanceof ServerPlayer killer
                && killer.getUUID().equals(owner) ? killer
                : entity.level().getServer() == null ? null
                : entity.level().getServer().getPlayerList().getPlayer(owner);
        if (player == null) return;
        ActiveMission mission = activeMission(player).orElse(null);
        if (mission == null
                || !mission.definitionId().equals(tags.getString(ACTOR_DEFINITION).orElse(""))) {
            return;
        }
        if (event.getSource().getEntity() instanceof ServerPlayer killer
                && killer.getUUID().equals(owner)) {
            complete(player, mission);
        } else {
            fail(player, mission, "Target lost before you neutralized it.");
        }
    }

    public static Optional<OpenCityMapPacket.Marker> activeMarker(ServerPlayer player) {
        return activeMission(player).map(mission -> new OpenCityMapPacket.Marker(
                OpenCityMapPacket.MarkerKind.ACTIVE_MISSION,
                mission.target().getX(), mission.target().getZ(),
                mission.targetDistrict().ordinal(), "literal:" + mission.title()));
    }

    public static boolean isMissionActor(Entity entity) {
        return entity.getPersistentData().getBoolean(ACTOR_TAG).orElse(false);
    }

    public static void forceSync(ServerPlayer player) {
        LAST_SYNC.remove(player.getUUID());
        syncIfChanged(player, activeMission(player).orElse(null));
    }

    public static void forgetPlayer(ServerPlayer player) {
        LAST_SYNC.remove(player.getUUID());
    }

    public static void reset() {
        LAST_SYNC.clear();
    }

    static ActiveMission spawnAssassination(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        CityNpc target = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.EVENT);
        if (target == null) return null;
        target.snapTo(mission.target().getX() + 0.5, mission.target().getY(),
                mission.target().getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        target.finalizeSpawn(level, level.getCurrentDifficultyAt(mission.target()),
                EntitySpawnReason.EVENT, null);
        target.setSkinVariant(CityNpc.MISSION_TARGET_SKIN);
        target.setNoAi(true);
        target.setCustomName(Component.literal(definition.targetName()).withStyle(ChatFormatting.GOLD));
        target.setCustomNameVisible(true);
        target.setPersistenceRequired();
        tagActor(target, player, definition, ROLE_TARGET);
        if (!level.noCollision(target) || !level.addFreshEntity(target)) return null;
        spawnGuards(level, player, definition, mission.target());
        return withActor(mission, target.getUUID());
    }

    static ActiveMission spawnCyberpsycho(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        CyberpsychoEntity psycho = FactionEntities.CYBERPSYCHO.get().create(
                level, EntitySpawnReason.EVENT);
        if (psycho == null) return null;
        psycho.snapTo(mission.target().getX() + 0.5, mission.target().getY(),
                mission.target().getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        psycho.finalizeSpawn(level, level.getCurrentDifficultyAt(mission.target()),
                EntitySpawnReason.EVENT, null);
        FactionSquads.equip(psycho, Faction.MILITECH, level.getRandom());
        psycho.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(WeaponItems.gun(definition.cyberpsychoGun()).get()));
        psycho.configure(definition.cyberpsychoHealth(), definition.cyberware());
        psycho.setGrenadeCount(definition.cyberpsychoGrenades());
        psycho.setHome(mission.target());
        psycho.setCustomName(Component.literal(definition.targetName()).withStyle(ChatFormatting.RED));
        psycho.setCustomNameVisible(true);
        psycho.setPersistenceRequired();
        tagActor(psycho, player, definition, ROLE_TARGET);
        if (!level.noCollision(psycho) || !level.addFreshEntity(psycho)) return null;
        return withActor(mission, psycho.getUUID());
    }

    static ActiveMission installDataObjective(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        if (!level.setBlock(mission.target(), MissionBlocks.DATA_TERMINAL.get().defaultBlockState(), 3)) {
            return null;
        }
        spawnGuards(level, player, definition, nearestStreet(level, mission.target()));
        return mission;
    }

    static ActiveMission issueCargo(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            ActiveMission mission) {
        Item item = item(definition.cargoItem());
        if (item == null || item == Items.AIR) return null;
        ItemStack cargo = new ItemStack(item, definition.cargoCount());
        if (!player.addItem(cargo) && !cargo.isEmpty()) player.drop(cargo, false);
        level.playSound(null, player.blockPosition(), SoundEvents.BUNDLE_INSERT,
                SoundSource.PLAYERS, 0.8F, 1.1F);
        return mission;
    }

    private static BlockPos prepareTargetArea(
            ServerLevel level,
            MissionCatalog.MissionDefinition definition,
            MissionOffer offer) {
        int chunkX = Math.floorDiv(offer.targetX(), 16);
        int chunkZ = Math.floorDiv(offer.targetZ(), 16);
        if (definition.type() != MissionCatalog.MissionType.SHIP_ITEM) {
            NeonCityGenerator.generateNow(level, chunkX, chunkZ, 1);
        }
        BlockPos approximate = new BlockPos(
                offer.targetX(), NeonCityGenerator.CITY_GROUND_Y + 1, offer.targetZ());
        if (definition.type() == MissionCatalog.MissionType.SHIP_ITEM) return approximate;
        if (definition.type() == MissionCatalog.MissionType.STEAL_DATA) {
            BlockPos interior = findInterior(level, approximate);
            return interior != null ? interior : buildDataSafehouse(level, approximate);
        }
        return nearestStreet(level, approximate);
    }

    private static BlockPos nearestStreet(ServerLevel level, BlockPos approximate) {
        BlockPos direct = com.example.cyberdeck.city.CityWorlds.resolveStreetFeet(
                level, approximate.getX(), approximate.getZ(), approximate.getY());
        if (direct != null) return direct;
        for (int radius = 2; radius <= 56; radius += 2) {
            for (int offset = -radius; offset <= radius; offset += 2) {
                int[][] points = {
                        {approximate.getX() + offset, approximate.getZ() - radius},
                        {approximate.getX() + offset, approximate.getZ() + radius},
                        {approximate.getX() - radius, approximate.getZ() + offset},
                        {approximate.getX() + radius, approximate.getZ() + offset}
                };
                for (int[] point : points) {
                    BlockPos found = com.example.cyberdeck.city.CityWorlds.resolveStreetFeet(
                            level, point[0], point[1], approximate.getY());
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static BlockPos findInterior(ServerLevel level, BlockPos approximate) {
        for (int radius = 0; radius <= 48; radius += 2) {
            int min = Math.max(1, radius);
            for (int dx = -radius; dx <= radius; dx += min) {
                for (int dz = -radius; dz <= radius; dz += min) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = approximate.getX() + dx;
                    int z = approximate.getZ() + dz;
                    BlockPos surface = level.getHeightmapPos(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                    int maxY = Math.min(surface.getY() - 2, NeonCityGenerator.CITY_GROUND_Y + 28);
                    for (int y = NeonCityGenerator.CITY_GROUND_Y + 1; y <= maxY; y++) {
                        BlockPos candidate = new BlockPos(x, y, z);
                        if (interiorCandidate(level, candidate)) return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean interiorCandidate(ServerLevel level, BlockPos position) {
        if (!level.isEmptyBlock(position) || !level.isEmptyBlock(position.above())
                || !level.getBlockState(position.below()).blocksMotion()) return false;
        boolean ceiling = false;
        for (int y = 2; y <= 6; y++) {
            if (level.getBlockState(position.above(y)).blocksMotion()) {
                ceiling = true;
                break;
            }
        }
        if (!ceiling) return false;
        int enclosure = 0;
        int access = 0;
        for (BlockPos direction : List.of(
                new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                new BlockPos(0, 0, 1), new BlockPos(0, 0, -1))) {
            BlockPos adjacent = position.offset(direction);
            if (level.getBlockState(adjacent).blocksMotion()) enclosure++;
            if (level.isEmptyBlock(adjacent) && level.isEmptyBlock(adjacent.above())) access++;
        }
        return enclosure >= 1 && access >= 1;
    }

    private static BlockPos buildDataSafehouse(ServerLevel level, BlockPos approximate) {
        BlockPos street = nearestStreet(level, approximate);
        if (street == null) return null;
        BlockPos center = street.offset(5, 0, 5);
        int floorY = center.getY() - 1;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlock(new BlockPos(center.getX() + dx, floorY, center.getZ() + dz),
                        Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos position = new BlockPos(
                            center.getX() + dx, floorY + dy, center.getZ() + dz);
                    boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    boolean entrance = dz == -3 && dx == 0 && dy <= 2;
                    level.setBlock(position, wall && !entrance
                            ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(new BlockPos(center.getX() + dx, floorY + 4, center.getZ() + dz),
                        Blocks.DEEPSLATE_TILES.defaultBlockState(), 3);
            }
        }
        return center;
    }

    private static void spawnGuards(
            ServerLevel level,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            BlockPos home) {
        if (home == null) return;
        RandomSource random = level.getRandom();
        int spawned = 0;
        int deploymentRadius = Math.min(32, definition.objectiveRadius());
        for (int radius = 2; radius <= deploymentRadius
                && spawned < definition.guards(); radius += 2) {
            for (int index = 0; index < 8 && spawned < definition.guards(); index++) {
                double angle = index * Math.PI / 4.0;
                BlockPos probe = home.offset(
                        (int) Math.round(Math.cos(angle) * radius), 0,
                        (int) Math.round(Math.sin(angle) * radius));
                BlockPos position = nearestStreet(level, probe);
                if (position == null
                        || position.distSqr(home) > deploymentRadius * deploymentRadius) continue;
                FactionEnemy guard = FactionEntities.FACTION_ENEMY.get().create(
                        level, EntitySpawnReason.EVENT);
                if (guard == null) continue;
                guard.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                        random.nextFloat() * 360.0F, 0.0F);
                guard.finalizeSpawn(level, level.getCurrentDifficultyAt(position),
                        EntitySpawnReason.EVENT, null);
                guard.setHome(home);
                guard.setPersistenceRequired();
                FactionSquads.equip(guard, Faction.ARASAKA, random);
                tagActor(guard, player, definition, ROLE_GUARD);
                if (level.noCollision(guard) && level.addFreshEntity(guard)) spawned++;
            }
        }
    }

    private static void tagActor(
            Entity entity,
            ServerPlayer player,
            MissionCatalog.MissionDefinition definition,
            String role) {
        CompoundTag data = entity.getPersistentData();
        data.putBoolean(ACTOR_TAG, true);
        data.putString(ACTOR_OWNER, player.getUUID().toString());
        data.putString(ACTOR_DEFINITION, definition.id());
        data.putString(ACTOR_ROLE, role);
    }

    private static ActiveMission withActor(ActiveMission mission, UUID actor) {
        return new ActiveMission(
                mission.definitionId(), mission.type(), mission.title(), mission.briefing(),
                mission.objective(), mission.targetDistrict(), mission.target(), mission.reward(),
                actor.toString(), mission.cargoItem(), mission.cargoCount(), mission.acceptedTick());
    }

    private static void complete(ServerPlayer player, ActiveMission mission) {
        giveEmmies(player, mission.reward());
        cleanup((ServerLevel) player.level(), player, mission);
        clear(player);
        player.sendSystemMessage(Component.literal(
                        "Mission complete: " + mission.title() + ". Paid "
                                + mission.reward() + " emmies.")
                .withStyle(ChatFormatting.GREEN));
        ((ServerLevel) player.level()).playSound(null, player.blockPosition(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.65F, 1.35F);
        forceSync(player);
    }

    private static void fail(ServerPlayer player, ActiveMission mission, String reason) {
        cleanup((ServerLevel) player.level(), player, mission);
        clear(player);
        player.sendSystemMessage(Component.literal("Mission failed: " + reason)
                .withStyle(ChatFormatting.RED));
        forceSync(player);
    }

    private static void cleanup(ServerLevel level, ServerPlayer player, ActiveMission mission) {
        AABB area = new AABB(mission.target()).inflate(48.0, 24.0, 48.0);
        for (CityNpc npc : level.getEntitiesOfClass(CityNpc.class, area,
                entity -> ownedBy(entity, player, mission))) npc.discard();
        for (FactionEnemy enemy : level.getEntitiesOfClass(FactionEnemy.class, area,
                entity -> ownedBy(entity, player, mission))) enemy.discard();
        if (mission.type() == MissionCatalog.MissionType.STEAL_DATA
                && level.getBlockState(mission.target()).is(MissionBlocks.DATA_TERMINAL.get())) {
            level.setBlock(mission.target(), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static boolean ownedBy(Entity entity, ServerPlayer player, ActiveMission mission) {
        CompoundTag data = entity.getPersistentData();
        return data.getBoolean(ACTOR_TAG).orElse(false)
                && player.getUUID().toString().equals(data.getString(ACTOR_OWNER).orElse(""))
                && mission.definitionId().equals(data.getString(ACTOR_DEFINITION).orElse(""));
    }

    private static void syncIfChanged(ServerPlayer player, ActiveMission mission) {
        MissionSyncPacket packet = mission == null
                ? MissionSyncPacket.inactive()
                : MissionSyncPacket.active(
                        mission.type(), mission.title(), mission.clientObjective(player),
                        mission.targetDistrict().ordinal(), mission.target().getX(),
                        mission.target().getZ(), mission.reward());
        int hash = packet.hashCode();
        Integer previous = LAST_SYNC.get(player.getUUID());
        if ((previous == null || previous != hash)
                && NetworkRegistry.hasChannel(player.connection, MissionSyncPacket.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, packet);
            LAST_SYNC.put(player.getUUID(), hash);
        }
    }

    static void save(ServerPlayer player, ActiveMission mission) {
        CompoundTag data = player.getPersistentData();
        data.putBoolean(ACTIVE, true);
        data.putString(DEFINITION, mission.definitionId());
        data.putString(TYPE, mission.type().name());
        data.putString(TITLE, mission.title());
        data.putString(BRIEFING, mission.briefing());
        data.putString(OBJECTIVE, mission.objective());
        data.putInt(DISTRICT, mission.targetDistrict().ordinal());
        data.putInt(TARGET_X, mission.target().getX());
        data.putInt(TARGET_Y, mission.target().getY());
        data.putInt(TARGET_Z, mission.target().getZ());
        data.putInt(REWARD, mission.reward());
        data.putString(ACTOR_UUID, mission.actorUuid());
        data.putString(CARGO_ITEM, mission.cargoItem());
        data.putInt(CARGO_COUNT, mission.cargoCount());
        data.putLong(ACCEPTED_TICK, mission.acceptedTick());
    }

    private static void clear(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        for (String key : List.of(
                ACTIVE, DEFINITION, TYPE, TITLE, BRIEFING, OBJECTIVE, DISTRICT,
                TARGET_X, TARGET_Y, TARGET_Z, REWARD, ACTOR_UUID,
                CARGO_ITEM, CARGO_COUNT, ACCEPTED_TICK)) {
            data.remove(key);
        }
    }

    private static Item item(Identifier id) {
        return BuiltInRegistries.ITEM.getValue(id);
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static void remove(ServerPlayer player, Item item, int requested) {
        int remaining = requested;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            remaining -= taken;
        }
        player.getInventory().setChanged();
    }

    private static void giveEmmies(ServerPlayer player, int amount) {
        com.example.cyberdeck.economy.Emmies.give(player, amount);
    }

    private static boolean isValidFixer(ServerPlayer player, Entity merchant) {
        return merchant != null && merchant.isAlive() && merchant.level() == player.level()
                && player.distanceToSqr(merchant) <= 64.0
                && MerchantTruckLibrary.merchantRole(merchant).orElse(null)
                == MerchantTruckLibrary.MerchantRole.QUEST;
    }
}
