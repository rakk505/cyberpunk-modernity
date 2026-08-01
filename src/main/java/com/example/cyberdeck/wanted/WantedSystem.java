package com.example.cyberdeck.wanted;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Server-owned wanted pursuit, Excision deployment, and black Aerodyne lifecycle. */
public final class WantedSystem {
    public enum AerodynePhase {
        DESCENDING,
        LANDED,
        ASCENDING
    }

    public static final int GROUND_WAVE_SIZE = 3;
    public static final int AERODYNE_WAVE_SIZE = 4;
    public static final int AERODYNE_WIDTH = 23;
    public static final int AERODYNE_HEIGHT = 9;
    public static final int AERODYNE_LENGTH = 11;
    public static final int AERODYNE_HOVER_CLEARANCE = 2;
    public static final int DISTRICT_ESCAPE_DISTANCE = 55;
    public static final double DISTRICT_EDGE_SCORE = 1.08;

    private static final Identifier EXCISION_AERODYNE = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "excision/aerodyne");
    private static final int SYSTEM_TICK_INTERVAL = 10;
    private static final int GROUND_WAVE_RETRY_TICKS = 20 * 8;
    private static final int AERODYNE_RETRY_TICKS = 20 * 5;
    private static final int AERODYNE_WAVE_INTERVAL_TICKS = 20 * 20;
    private static final int DEFAULT_AERODYNE_DROP_HEIGHT = 12;
    private static final int AERODYNE_MOVE_INTERVAL_TICKS = 2;
    private static final int AERODYNE_HOLD_TICKS = 20 * 4;
    private static final int MIN_AGENT_DISTANCE = 28;
    private static final int MAX_AGENT_DISTANCE = 44;
    private static final int MAX_ACTIVE_EXCISION = 12;
    private static final double EXCISION_TRACK_RADIUS = 192.0;
    private static final int CYBERWARE_EFFECT_TICKS = 20 * 60 * 60;
    private static final double EXCISION_HEALTH = 32.0;
    private static final int BLOCK_UPDATE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
    private static final GunType[] EXCISION_LOADOUT = {
            GunType.AJAX,
            GunType.COPPERHEAD,
            GunType.SARATOGA,
            GunType.YUKIMURA
    };

    private static final Map<ServerLevel, Map<UUID, PursuitRuntime>> PURSUITS =
            new WeakHashMap<>();
    private static final Map<ServerLevel, List<AerodyneState>> AERODYNES =
            new WeakHashMap<>();

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer victim) {
            clearPursuit(level, victim, false);
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) {
            return;
        }
        if (event.getEntity() instanceof CityNpc) {
            recordNpcKill(level, killer);
        } else if (event.getEntity() instanceof FactionEnemy enemy
                && enemy.isExcisionTarget(killer.getUUID())) {
            recordExcisionKill(level, killer);
        }
    }

    private static void recordNpcKill(ServerLevel level, ServerPlayer killer) {
        WantedState previous = WantedState.get(killer);
        int districtOrdinal = districtOrdinalAt(level, killer.getBlockX(), killer.getBlockZ());
        WantedState updated = previous.recordNpcKill(districtOrdinal);
        WantedState.set(killer, updated);
        if (previous.stars() == 0 && updated.stars() == 1) {
            PursuitRuntime runtime = pursuit(level, killer.getUUID());
            runtime.nextGroundWaveTick = level.getGameTime();
            killer.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.wanted.started"), true);
            level.playSound(null, killer.blockPosition(), SoundEvents.PILLAGER_CELEBRATE,
                    SoundSource.HOSTILE, 1.0F, 0.7F);
        }
    }

    private static void recordExcisionKill(ServerLevel level, ServerPlayer killer) {
        WantedState previous = WantedState.get(killer);
        WantedState updated = previous.recordExcisionKill();
        WantedState.set(killer, updated);
        if (previous.stars() < 3 && updated.stars() == 3) {
            PursuitRuntime runtime = pursuit(level, killer.getUUID());
            runtime.nextAerodyneTick = level.getGameTime();
            killer.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.wanted.escalated"), true);
            level.playSound(null, killer.blockPosition(), SoundEvents.BEACON_ACTIVATE,
                    SoundSource.HOSTILE, 1.5F, 0.55F);
        }
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        tickAerodynes(level);
        if (level.getGameTime() % SYSTEM_TICK_INTERVAL != 0) {
            return;
        }

        Map<UUID, PursuitRuntime> runtimes = PURSUITS.computeIfAbsent(
                level, ignored -> new java.util.HashMap<>());
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            online.add(player.getUUID());
            WantedState state = WantedState.get(player);
            if (!state.active()) {
                runtimes.remove(player.getUUID());
                continue;
            }
            if (!player.isAlive() || player.isSpectator() || escapedTargetDistrict(level, player, state)) {
                clearPursuit(level, player, true);
                continue;
            }
            PursuitRuntime runtime = runtimes.computeIfAbsent(
                    player.getUUID(), ignored -> new PursuitRuntime());
            if (state.stars() >= 3) {
                tickThreeStarPursuit(level, player, runtime);
            } else {
                tickOneStarPursuit(level, player, runtime);
            }
        }
        runtimes.keySet().removeIf(playerId -> !online.contains(playerId));
        if (runtimes.isEmpty()) {
            PURSUITS.remove(level);
        }
    }

    private static void tickOneStarPursuit(
            ServerLevel level, ServerPlayer player, PursuitRuntime runtime) {
        long now = level.getGameTime();
        if (now < runtime.nextGroundWaveTick) {
            return;
        }
        int active = activeExcisionCount(level, player);
        if (active >= GROUND_WAVE_SIZE) {
            runtime.nextGroundWaveTick = now + GROUND_WAVE_RETRY_TICKS;
            return;
        }
        int spawned = spawnGroundWave(level, player, GROUND_WAVE_SIZE - active);
        runtime.nextGroundWaveTick = now + (spawned > 0
                ? GROUND_WAVE_RETRY_TICKS : AERODYNE_RETRY_TICKS);
    }

    private static void tickThreeStarPursuit(
            ServerLevel level, ServerPlayer player, PursuitRuntime runtime) {
        long now = level.getGameTime();
        if (now < runtime.nextAerodyneTick || hasActiveAerodyne(level, player.getUUID())
                || activeExcisionCount(level, player) >= MAX_ACTIVE_EXCISION) {
            return;
        }
        BlockPos landing = findAerodyneLanding(level, player, level.getRandom());
        if (landing != null && startAerodyne(
                level, player, landing, DEFAULT_AERODYNE_DROP_HEIGHT)) {
            runtime.nextAerodyneTick = now + AERODYNE_WAVE_INTERVAL_TICKS;
        } else {
            runtime.nextAerodyneTick = now + AERODYNE_RETRY_TICKS;
        }
    }

    private static boolean escapedTargetDistrict(
            ServerLevel level, ServerPlayer player, WantedState state) {
        if (CityWorlds.kind(level) != CityWorlds.Kind.NEON_MEGACITY
                || state.districtOrdinal() < 0
                || state.districtOrdinal() >= District.values().length) {
            return true;
        }
        return isBeyondDistrictEdge(
                NeonCityGenerator.layout(),
                District.values()[state.districtOrdinal()],
                player.getBlockX(),
                player.getBlockZ(),
                DISTRICT_ESCAPE_DISTANCE);
    }

    public static boolean isBeyondDistrictEdge(
            MegacityLayout layout, District district, int worldX, int worldZ,
            int requiredDistance) {
        MegacityLayout.Node node = layout.node(district);
        if (layout.normalizedDistanceTo(node, worldX, worldZ) <= DISTRICT_EDGE_SCORE) {
            return false;
        }
        double towardCenterX = node.x() - worldX;
        double towardCenterZ = node.z() - worldZ;
        double distance = Math.hypot(towardCenterX, towardCenterZ);
        if (distance <= requiredDistance || distance < 1.0E-4) {
            return false;
        }
        double scale = requiredDistance / distance;
        int inwardX = (int) Math.round(worldX + towardCenterX * scale);
        int inwardZ = (int) Math.round(worldZ + towardCenterZ * scale);
        return layout.normalizedDistanceTo(node, inwardX, inwardZ) > DISTRICT_EDGE_SCORE;
    }

    public static boolean isOutsideView(Vec3 lookDirection, Vec3 offsetToSpawn) {
        Vec3 look = new Vec3(lookDirection.x, 0.0, lookDirection.z);
        Vec3 offset = new Vec3(offsetToSpawn.x, 0.0, offsetToSpawn.z);
        if (look.lengthSqr() < 1.0E-6 || offset.lengthSqr() < 1.0E-6) {
            return false;
        }
        return look.normalize().dot(offset.normalize()) <= 0.0;
    }

    private static int districtOrdinalAt(ServerLevel level, int worldX, int worldZ) {
        if (CityWorlds.kind(level) != CityWorlds.Kind.NEON_MEGACITY
                || !NeonCityGenerator.isInsideCity(level, worldX, worldZ)) {
            return -1;
        }
        return NeonCityGenerator.districtAt(worldX, worldZ).ordinal();
    }

    private static PursuitRuntime pursuit(ServerLevel level, UUID playerId) {
        return PURSUITS.computeIfAbsent(level, ignored -> new java.util.HashMap<>())
                .computeIfAbsent(playerId, ignored -> new PursuitRuntime());
    }

    private static int spawnGroundWave(ServerLevel level, ServerPlayer target, int requested) {
        List<BlockPos> positions = findOutOfViewPositions(level, target, requested, level.getRandom());
        int spawned = 0;
        for (BlockPos position : positions) {
            if (spawnExcisionAgent(level, position, target, spawned, false) != null) {
                spawned++;
            }
        }
        return spawned;
    }

    private static List<BlockPos> findOutOfViewPositions(
            ServerLevel level, ServerPlayer player, int requested, RandomSource random) {
        List<BlockPos> positions = new ArrayList<>(requested);
        Vec3 look = player.getLookAngle();
        double behind = Math.atan2(-look.z, -look.x);
        for (int attempt = 0; attempt < 64 && positions.size() < requested; attempt++) {
            double angle = behind + (random.nextDouble() - 0.5) * Math.PI * 0.9;
            int distance = MIN_AGENT_DISTANCE
                    + random.nextInt(MAX_AGENT_DISTANCE - MIN_AGENT_DISTANCE + 1);
            int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos feet = resolveSurfaceFeet(level, x, z, player.getBlockY());
            if (feet == null || !isOutsideView(
                    look, Vec3.atCenterOf(feet).subtract(player.getEyePosition()))) {
                continue;
            }
            boolean separated = positions.stream().allMatch(position -> position.distSqr(feet) >= 9.0);
            if (separated) {
                positions.add(feet);
            }
        }
        return positions;
    }

    private static FactionEnemy spawnExcisionAgent(
            ServerLevel level, BlockPos feet, ServerPlayer target, int loadoutIndex,
            boolean airborne) {
        FactionEnemy agent = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        if (agent == null) {
            return null;
        }
        double spawnY = feet.getY() + (airborne ? 6.0 : 0.0);
        agent.snapTo(feet.getX() + 0.5, spawnY, feet.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(agent)) {
            agent.discard();
            return null;
        }
        agent.finalizeSpawn(level, level.getCurrentDifficultyAt(feet),
                EntitySpawnReason.EVENT, null);
        configureExcisionAgent(agent, target, feet, loadoutIndex);
        if (airborne) {
            agent.addEffect(new MobEffectInstance(
                    MobEffects.SLOW_FALLING, 20 * 10, 0, false, false));
        }
        if (!level.addFreshEntity(agent)) {
            agent.discard();
            return null;
        }
        // Target assignment must happen after the join lifecycle. Join compatibility and vanilla
        // mob initialization may normalize a target set while the entity is still detached.
        agent.deployAsExcision(target);
        return agent;
    }

    private static void configureExcisionAgent(
            FactionEnemy agent, ServerPlayer target, BlockPos home, int loadoutIndex) {
        agent.setFaction(Faction.MILITECH);
        agent.setHome(home);
        agent.setCustomName(Component.literal("Excision Agent"));
        agent.setCustomNameVisible(false);
        agent.getAttribute(Attributes.MAX_HEALTH).setBaseValue(EXCISION_HEALTH);
        agent.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.30);
        agent.getAttribute(Attributes.ARMOR).setBaseValue(7.0);
        agent.getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(2.0);
        agent.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(72.0);
        agent.setHealth((float) EXCISION_HEALTH);
        GunType primary = EXCISION_LOADOUT[Math.floorMod(loadoutIndex, EXCISION_LOADOUT.length)];
        agent.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(WeaponItems.gun(primary).get()));
        agent.setItemSlot(EquipmentSlot.OFFHAND,
                new ItemStack(WeaponItems.gun(GunType.UNITY).get()));
        agent.setDropChance(EquipmentSlot.MAINHAND, 0.05F);
        agent.setDropChance(EquipmentSlot.OFFHAND, 0.03F);
        agent.addEffect(new MobEffectInstance(
                MobEffects.SPEED, CYBERWARE_EFFECT_TICKS, 0, false, false));
        agent.addEffect(new MobEffectInstance(
                MobEffects.RESISTANCE, CYBERWARE_EFFECT_TICKS, 0, false, false));
    }

    public static FactionEnemy spawnExcisionAgentForTest(
            ServerLevel level, BlockPos feet, ServerPlayer target) {
        return spawnExcisionAgent(level, feet, target, 0, false);
    }

    private static int activeExcisionCount(ServerLevel level, ServerPlayer player) {
        AABB area = player.getBoundingBox().inflate(
                EXCISION_TRACK_RADIUS, 96.0, EXCISION_TRACK_RADIUS);
        return level.getEntitiesOfClass(
                FactionEnemy.class, area,
                enemy -> enemy.isAlive() && enemy.isExcisionTarget(player.getUUID()))
                .size();
    }

    private static BlockPos findAerodyneLanding(
            ServerLevel level, ServerPlayer player, RandomSource random) {
        for (int radius : new int[] {18, 24, 30, 36}) {
            double start = random.nextDouble() * Math.PI * 2.0;
            for (int sample = 0; sample < 16; sample++) {
                double angle = start + sample * Math.PI * 2.0 / 16.0;
                int x = player.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
                int z = player.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
                BlockPos candidate = resolveSurfaceFeet(level, x, z, player.getBlockY());
                if (candidate != null && hasAerodyneClearance(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static boolean hasAerodyneClearance(ServerLevel level, BlockPos center) {
        if (!isSafeFeet(level, center)) {
            return false;
        }
        int minX = center.getX() - AERODYNE_WIDTH / 2;
        int minZ = center.getZ() - AERODYNE_LENGTH / 2;
        int topY = center.getY() + AERODYNE_HOVER_CLEARANCE + AERODYNE_HEIGHT - 1;
        return isEmptyVolume(
                level,
                minX,
                center.getY(),
                minZ,
                minX + AERODYNE_WIDTH - 1,
                topY,
                minZ + AERODYNE_LENGTH - 1);
    }

    private static boolean startAerodyne(
            ServerLevel level, ServerPlayer target, BlockPos landingCenter, int requestedDropHeight) {
        if (!hasAerodyneClearance(level, landingCenter)) {
            return false;
        }
        StructureTemplate template = level.getStructureManager().get(EXCISION_AERODYNE).orElse(null);
        if (template == null || !hasExpectedAerodyneSize(template.getSize())) {
            Cyberdeck.LOGGER.error("Missing or invalid Excision aerodyne template {}", EXCISION_AERODYNE);
            return false;
        }
        int availableDropHeight = availableDropHeight(
                level, landingCenter, Math.max(0, requestedDropHeight));
        AerodyneState state = new AerodyneState(
                template, target.getUUID(), landingCenter, availableDropHeight);
        if (!state.place(level)) {
            return false;
        }
        AERODYNES.computeIfAbsent(level, ignored -> new ArrayList<>()).add(state);
        level.playSound(null, landingCenter, SoundEvents.BEACON_ACTIVATE,
                SoundSource.HOSTILE, 1.6F, 0.45F);
        return true;
    }

    public static boolean requestAerodyneAtForTest(
            ServerLevel level, ServerPlayer target, BlockPos landingCenter, int dropHeight) {
        return startAerodyne(level, target, landingCenter, dropHeight);
    }

    public static AerodynePhase aerodynePhaseFor(ServerLevel level, UUID playerId) {
        for (AerodyneState state : AERODYNES.getOrDefault(level, List.of())) {
            if (state.targetId.equals(playerId)) {
                return state.phase;
            }
        }
        return null;
    }

    public static int aerodyneAgentCount(ServerLevel level, UUID playerId) {
        for (AerodyneState state : AERODYNES.getOrDefault(level, List.of())) {
            if (state.targetId.equals(playerId)) {
                return state.deployedAgents.size();
            }
        }
        return 0;
    }

    private static boolean hasActiveAerodyne(ServerLevel level, UUID playerId) {
        return aerodynePhaseFor(level, playerId) != null;
    }

    private static boolean hasExpectedAerodyneSize(Vec3i size) {
        return size.getX() == AERODYNE_WIDTH
                && size.getY() == AERODYNE_HEIGHT
                && size.getZ() == AERODYNE_LENGTH;
    }

    private static int availableDropHeight(
            ServerLevel level, BlockPos center, int requestedDropHeight) {
        int minX = center.getX() - AERODYNE_WIDTH / 2;
        int minZ = center.getZ() - AERODYNE_LENGTH / 2;
        int maxX = minX + AERODYNE_WIDTH - 1;
        int maxZ = minZ + AERODYNE_LENGTH - 1;
        int structureTopY = center.getY()
                + AERODYNE_HOVER_CLEARANCE
                + AERODYNE_HEIGHT - 1;
        int allowed = 0;
        for (int offsetY = 1; offsetY <= requestedDropHeight; offsetY++) {
            if (!isEmptyVolume(level, minX, structureTopY + offsetY, minZ,
                    maxX, structureTopY + offsetY, maxZ)) {
                break;
            }
            allowed = offsetY;
        }
        return allowed;
    }

    private static BlockPos resolveSurfaceFeet(
            ServerLevel level, int x, int z, int preferredY) {
        BlockPos street = CityWorlds.isCity(level)
                ? CityWorlds.resolveStreetFeet(level, x, z, preferredY)
                : null;
        if (street != null) {
            return street;
        }
        BlockPos probe = new BlockPos(x, preferredY, z);
        if (!level.isLoaded(probe)) {
            return null;
        }
        BlockPos candidate = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
        return isSafeFeet(level, candidate) ? candidate : null;
    }

    private static boolean isSafeFeet(ServerLevel level, BlockPos feet) {
        return level.isLoaded(feet)
                && level.getWorldBorder().isWithinBounds(feet)
                && level.getBlockState(feet.below()).blocksMotion()
                && level.isEmptyBlock(feet)
                && level.isEmptyBlock(feet.above());
    }

    private static boolean isEmptyVolume(
            ServerLevel level,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    if (!level.isLoaded(cursor)
                            || !level.getWorldBorder().isWithinBounds(cursor)
                            || !level.isEmptyBlock(cursor)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void tickAerodynes(ServerLevel level) {
        List<AerodyneState> states = AERODYNES.get(level);
        if (states == null) {
            return;
        }
        Iterator<AerodyneState> iterator = states.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().tick(level)) {
                iterator.remove();
            }
        }
        if (states.isEmpty()) {
            AERODYNES.remove(level);
        }
    }

    private static void clearPursuit(ServerLevel level, ServerPlayer player, boolean announce) {
        WantedState previous = WantedState.get(player);
        WantedState.set(player, WantedState.NONE);
        Map<UUID, PursuitRuntime> runtimes = PURSUITS.get(level);
        if (runtimes != null) {
            runtimes.remove(player.getUUID());
        }
        AABB area = player.getBoundingBox().inflate(
                EXCISION_TRACK_RADIUS, 96.0, EXCISION_TRACK_RADIUS);
        for (FactionEnemy enemy : level.getEntitiesOfClass(
                FactionEnemy.class, area, candidate -> candidate.isExcisionTarget(player.getUUID()))) {
            enemy.discard();
        }
        List<AerodyneState> airships = AERODYNES.get(level);
        if (airships != null) {
            airships.removeIf(state -> {
                if (!state.targetId.equals(player.getUUID())) {
                    return false;
                }
                state.clear(level);
                return true;
            });
            if (airships.isEmpty()) {
                AERODYNES.remove(level);
            }
        }
        if (announce && previous.active()) {
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.wanted.cleared"), true);
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_DEACTIVATE,
                    SoundSource.PLAYERS, 1.0F, 1.2F);
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<AerodyneState> airships = AERODYNES.remove(level);
        if (airships != null) {
            for (AerodyneState airship : airships) {
                airship.clear(level);
            }
        }
        PURSUITS.remove(level);
    }

    private static final class PursuitRuntime {
        private long nextGroundWaveTick;
        private long nextAerodyneTick;
    }

    private static final class AerodyneState {
        private final StructureTemplate template;
        private final UUID targetId;
        private final BlockPos landingCenter;
        private final int originX;
        private final int originZ;
        private final int landingY;
        private final int startY;
        private final List<BlockPos> placedBlocks = new ArrayList<>();
        private final List<UUID> deployedAgents = new ArrayList<>();
        private int currentY;
        private int movementTicks;
        private int landedTicks;
        private AerodynePhase phase = AerodynePhase.DESCENDING;

        private AerodyneState(
                StructureTemplate template, UUID targetId, BlockPos landingCenter,
                int dropHeight) {
            this.template = template;
            this.targetId = targetId;
            this.landingCenter = landingCenter.immutable();
            this.originX = landingCenter.getX() - AERODYNE_WIDTH / 2;
            this.originZ = landingCenter.getZ() - AERODYNE_LENGTH / 2;
            this.landingY = landingCenter.getY() + AERODYNE_HOVER_CLEARANCE;
            this.startY = landingY + dropHeight;
            this.currentY = startY;
        }

        private BlockPos origin() {
            return new BlockPos(originX, currentY, originZ);
        }

        private boolean tick(ServerLevel level) {
            return switch (phase) {
                case DESCENDING -> tickDescent(level);
                case LANDED -> tickLanded(level);
                case ASCENDING -> tickAscent(level);
            };
        }

        private boolean tickDescent(ServerLevel level) {
            if (currentY > landingY && !movementReady()) {
                return false;
            }
            if (currentY > landingY && !moveTo(level, currentY - 1)) {
                clear(level);
                return true;
            }
            if (currentY == landingY) {
                phase = AerodynePhase.LANDED;
                deployAgents(level);
                level.playSound(null, landingCenter, SoundEvents.IRON_GOLEM_REPAIR,
                        SoundSource.HOSTILE, 1.5F, 0.45F);
            }
            return false;
        }

        private boolean tickLanded(ServerLevel level) {
            landedTicks++;
            if (landedTicks >= AERODYNE_HOLD_TICKS
                    || level.getServer().getPlayerList().getPlayer(targetId) == null) {
                phase = AerodynePhase.ASCENDING;
                movementTicks = 0;
                level.playSound(null, landingCenter, SoundEvents.BEACON_DEACTIVATE,
                        SoundSource.HOSTILE, 1.4F, 0.75F);
            }
            return false;
        }

        private boolean tickAscent(ServerLevel level) {
            if (!movementReady()) {
                return false;
            }
            if (currentY >= startY) {
                clear(level);
                return true;
            }
            if (!moveTo(level, currentY + 1)) {
                clear(level);
                return true;
            }
            return false;
        }

        private boolean movementReady() {
            movementTicks++;
            if (movementTicks < AERODYNE_MOVE_INTERVAL_TICKS) {
                return false;
            }
            movementTicks = 0;
            return true;
        }

        private boolean moveTo(ServerLevel level, int nextY) {
            clear(level);
            currentY = nextY;
            boolean placed = place(level);
            if (placed) {
                level.sendParticles(ParticleTypes.CLOUD,
                        landingCenter.getX() + 0.5,
                        currentY,
                        landingCenter.getZ() + 0.5,
                        12, 4.5, 0.3, 3.5, 0.04);
            }
            return placed;
        }

        private boolean place(ServerLevel level) {
            if (!isEmptyVolume(
                    level,
                    originX,
                    currentY,
                    originZ,
                    originX + AERODYNE_WIDTH - 1,
                    currentY + AERODYNE_HEIGHT - 1,
                    originZ + AERODYNE_LENGTH - 1)) {
                return false;
            }
            StructurePlaceSettings settings = settings();
            boolean placed = template.placeInWorld(
                    level, origin(), origin(), settings, level.getRandom(), BLOCK_UPDATE_FLAGS);
            placedBlocks.clear();
            if (placed) {
                BoundingBox bounds = template.getBoundingBox(settings, origin());
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                        for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                            cursor.set(x, y, z);
                            if (!level.isEmptyBlock(cursor)) {
                                placedBlocks.add(cursor.immutable());
                            }
                        }
                    }
                }
            }
            return placed;
        }

        private void clear(ServerLevel level) {
            for (BlockPos block : placedBlocks) {
                level.setBlock(block, Blocks.AIR.defaultBlockState(), BLOCK_UPDATE_FLAGS);
            }
            placedBlocks.clear();
        }

        private StructurePlaceSettings settings() {
            return new StructurePlaceSettings()
                    .setMirror(Mirror.NONE)
                    .setRotation(Rotation.NONE)
                    .setIgnoreEntities(true)
                    .setKnownShape(true)
                    .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
        }

        private void deployAgents(ServerLevel level) {
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetId);
            if (target == null) {
                return;
            }
            List<BlockPos> positions = findDeploymentPositions(level);
            for (int index = 0; index < AERODYNE_WAVE_SIZE; index++) {
                BlockPos feet = index < positions.size() ? positions.get(index) : landingCenter;
                FactionEnemy agent = spawnExcisionAgent(level, feet, target, index, true);
                if (agent != null) {
                    deployedAgents.add(agent.getUUID());
                }
            }
        }

        private List<BlockPos> findDeploymentPositions(ServerLevel level) {
            List<BlockPos> positions = new ArrayList<>(AERODYNE_WAVE_SIZE);
            int preferredZ = landingCenter.getZ() + AERODYNE_LENGTH / 2 + 3;
            for (int offsetX : new int[] {-6, -2, 2, 6}) {
                BlockPos feet = resolveSurfaceFeet(
                        level, landingCenter.getX() + offsetX, preferredZ, landingCenter.getY());
                if (feet != null) {
                    positions.add(feet);
                }
            }
            for (int radius = 8; positions.size() < AERODYNE_WAVE_SIZE && radius <= 14; radius += 2) {
                for (int sample = 0; sample < 12 && positions.size() < AERODYNE_WAVE_SIZE; sample++) {
                    double angle = sample * Math.PI * 2.0 / 12.0;
                    int x = landingCenter.getX() + (int) Math.round(Math.cos(angle) * radius);
                    int z = landingCenter.getZ() + (int) Math.round(Math.sin(angle) * radius);
                    BlockPos feet = resolveSurfaceFeet(level, x, z, landingCenter.getY());
                    if (feet != null && positions.stream().noneMatch(feet::equals)) {
                        positions.add(feet);
                    }
                }
            }
            return positions;
        }
    }
}
