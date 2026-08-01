package com.example.cyberdeck.trauma;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.NpcRole;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Server-owned Trauma Team aerodyne and extraction event lifecycle. */
public final class TraumaTeamEvents {
    public enum Phase {
        DESCENDING,
        LANDED,
        BOARDING,
        ASCENDING
    }

    public static final int AERODYNE_WIDTH = 23;
    public static final int AERODYNE_HEIGHT = 9;
    public static final int AERODYNE_LENGTH = 11;
    public static final int AERODYNE_HOVER_CLEARANCE = 3;
    public static final int DEFAULT_DROP_HEIGHT = 24;
    public static final int MIN_RESPONDERS = 4;
    public static final int MAX_RESPONDERS = 5;
    public static final int EXEC_APPROACH_TIMEOUT_TICKS = 20 * 60 * 2 + 20 * 30;
    public static final int EXEC_BOARDING_WAIT_TICKS = 20 * 60 * 2 + 20 * 30;
    public static final int MAX_LANDED_TICKS =
            EXEC_APPROACH_TIMEOUT_TICKS + EXEC_BOARDING_WAIT_TICKS;

    private static final Identifier AERODYNE = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "trauma_team/aerodyne");
    private static final int MOVE_INTERVAL_TICKS = 3;
    private static final double PICKUP_DISTANCE_SQR = 3.5 * 3.5;
    private static final int BLOCK_UPDATE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
    private static final int[][] RESPONDER_OFFSETS = {
            {-8, -8}, {-3, -8}, {3, -8}, {8, -8}, {0, 8}
    };
    private static final GunType[] RESPONDER_LOADOUT = {
            GunType.TECH_UNITY,
            GunType.YUKIMURA,
            GunType.TECH_AJAX,
            GunType.G58_DIAN,
            GunType.TECH_GRAD
    };
    private static final Map<ServerLevel, List<EventState>> ACTIVE = new WeakHashMap<>();

    public static boolean request(ServerLevel level, CityNpc exec, Entity attacker) {
        if (!exec.isAlive() || exec.getRole() != NpcRole.EXEC || hasEvent(level, exec.getUUID())) {
            return false;
        }
        ServerPlayer target = resolveTarget(level, exec, attacker);
        if (target == null) {
            return false;
        }
        BlockPos landingCenter = findLandingCenter(level, exec.blockPosition(), level.getRandom());
        if (landingCenter == null
                || !start(level, exec, target, landingCenter, DEFAULT_DROP_HEIGHT)) {
            return false;
        }
        announceDispatch(exec, target, landingCenter);
        return true;
    }

    /** Administrative dispatch used by /cyberdeck trauma. */
    public static boolean requestForCommand(ServerPlayer target) {
        if (!isCommandTargetEligible(target)) {
            return false;
        }
        ServerLevel level = target.level();
        Vec3 look = target.getLookAngle();
        double horizontalLength = Math.sqrt(look.x * look.x + look.z * look.z);
        double directionX;
        double directionZ;
        if (horizontalLength > 1.0E-4) {
            directionX = look.x / horizontalLength;
            directionZ = look.z / horizontalLength;
        } else {
            double yaw = Math.toRadians(target.getYRot());
            directionX = -Math.sin(yaw);
            directionZ = Math.cos(yaw);
        }
        BlockPos searchOrigin = BlockPos.containing(
                target.getX() + directionX * 18.0,
                target.getY(),
                target.getZ() + directionZ * 18.0);
        BlockPos landingCenter = findLandingCenter(level, searchOrigin, level.getRandom());
        if (landingCenter == null) {
            return false;
        }

        CityNpc exec = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.COMMAND);
        if (exec == null) {
            return false;
        }
        exec.snapTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), 0.0F);
        exec.finalizeSpawn(level, level.getCurrentDifficultyAt(exec.blockPosition()),
                EntitySpawnReason.COMMAND, null);
        exec.setRole(NpcRole.EXEC);
        exec.setSkinVariant(CityNpc.MISSION_TARGET_SKIN);
        exec.setPersistenceRequired();
        if (!level.addFreshEntity(exec)) {
            exec.discard();
            return false;
        }
        if (!start(level, exec, target, landingCenter, DEFAULT_DROP_HEIGHT, true)) {
            exec.discard();
            return false;
        }
        announceDispatch(exec, target, landingCenter);
        return true;
    }

    private static void announceDispatch(CityNpc exec, ServerPlayer target, BlockPos landingCenter) {
        target.sendSystemMessage(
                Component.translatable("message.cyberdeck.trauma_team.inbound"), true);
        Cyberdeck.LOGGER.info("Trauma Team dispatched for Exec {} at {} targeting {}",
                exec.getUUID(), landingCenter, target.getScoreboardName());
    }

    public static boolean isCommandTargetEligible(ServerPlayer target) {
        return target.isAlive() && !target.isSpectator();
    }

    /** Explicit landing hook used by deterministic GameTests; mirrors command eligibility. */
    public static boolean requestAt(ServerLevel level, CityNpc exec, ServerPlayer target,
                                    BlockPos landingCenter, int dropHeight) {
        return start(level, exec, target, landingCenter, Math.max(0, dropHeight), true);
    }

    /** Reduced timing hook used to exercise the full lifecycle in deterministic GameTests. */
    public static boolean requestAt(ServerLevel level, CityNpc exec, ServerPlayer target,
                                    BlockPos landingCenter, int dropHeight,
                                    int approachTimeoutTicks, int boardingWaitTicks) {
        if (approachTimeoutTicks < 1 || boardingWaitTicks < 1) {
            return false;
        }
        return start(level, exec, target, landingCenter, Math.max(0, dropHeight),
                true, approachTimeoutTicks, boardingWaitTicks);
    }

    private static boolean start(ServerLevel level, CityNpc exec, ServerPlayer target,
                                 BlockPos landingCenter, int dropHeight) {
        return start(level, exec, target, landingCenter, dropHeight,
                false, EXEC_APPROACH_TIMEOUT_TICKS, EXEC_BOARDING_WAIT_TICKS);
    }

    private static boolean start(ServerLevel level, CityNpc exec, ServerPlayer target,
                                 BlockPos landingCenter, int dropHeight,
                                 boolean allowCreativeTarget) {
        return start(level, exec, target, landingCenter, dropHeight,
                allowCreativeTarget, EXEC_APPROACH_TIMEOUT_TICKS, EXEC_BOARDING_WAIT_TICKS);
    }

    private static boolean start(ServerLevel level, CityNpc exec, ServerPlayer target,
                                 BlockPos landingCenter, int dropHeight,
                                 boolean allowCreativeTarget,
                                 int approachTimeoutTicks, int boardingWaitTicks) {
        if (!exec.isAlive() || exec.getRole() != NpcRole.EXEC
                || !target.isAlive() || target.isSpectator()
                || (!allowCreativeTarget && target.isCreative())
                || hasEvent(level, exec.getUUID())
                || !hasLandingClearance(level, landingCenter)) {
            return false;
        }
        StructureTemplate template = level.getStructureManager().get(AERODYNE).orElse(null);
        if (template == null || !hasExpectedSize(template.getSize())) {
            Cyberdeck.LOGGER.error("Missing or invalid Trauma Team aerodyne template {}", AERODYNE);
            return false;
        }

        int availableDropHeight = availableDropHeight(level, landingCenter, dropHeight);
        EventState state = new EventState(
                template,
                exec.getUUID(),
                target.getUUID(),
                landingCenter,
                availableDropHeight,
                approachTimeoutTicks,
                boardingWaitTicks);
        if (!state.place(level)) {
            return false;
        }
        exec.beginEvacuation(state.pickupPosition());
        ACTIVE.computeIfAbsent(level, ignored -> new ArrayList<>()).add(state);
        level.playSound(null, landingCenter, SoundEvents.BEACON_ACTIVATE,
                SoundSource.HOSTILE, 1.5F, 0.65F);
        return true;
    }

    private static boolean hasExpectedSize(Vec3i size) {
        return size.getX() == AERODYNE_WIDTH
                && size.getY() == AERODYNE_HEIGHT
                && size.getZ() == AERODYNE_LENGTH;
    }

    public static boolean hasLandingClearance(ServerLevel level, BlockPos center) {
        if (!isSafeFeet(level, center)) {
            return false;
        }
        int minX = center.getX() - AERODYNE_WIDTH / 2;
        int minZ = center.getZ() - AERODYNE_LENGTH / 2;
        int structureY = center.getY() + AERODYNE_HOVER_CLEARANCE;
        return isEmptyVolume(
                level,
                minX,
                center.getY(),
                minZ,
                minX + AERODYNE_WIDTH - 1,
                structureY + AERODYNE_HEIGHT - 1,
                minZ + AERODYNE_LENGTH - 1);
    }

    /** Compatibility overload: approach height no longer affects landing-site acceptance. */
    public static boolean hasLandingClearance(ServerLevel level, BlockPos center, int dropHeight) {
        return hasLandingClearance(level, center);
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
        int allowedDropHeight = 0;
        for (int offsetY = 1; offsetY <= Math.max(0, requestedDropHeight); offsetY++) {
            if (!isEmptyVolume(
                    level,
                    minX,
                    structureTopY + offsetY,
                    minZ,
                    maxX,
                    structureTopY + offsetY,
                    maxZ)) {
                break;
            }
            allowedDropHeight = offsetY;
        }
        return allowedDropHeight;
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

    private static BlockPos findLandingCenter(ServerLevel level, BlockPos origin, RandomSource random) {
        BlockPos direct = resolveLandingAnchor(level, origin.getX(), origin.getZ(), origin.getY());
        if (direct != null && hasLandingClearance(level, direct)) {
            return direct;
        }
        for (int radius : new int[] {12, 18, 24, 32}) {
            double offset = random.nextDouble() * Math.PI * 2.0;
            for (int sample = 0; sample < 16; sample++) {
                double angle = offset + sample * Math.PI * 2.0 / 16.0;
                BlockPos candidate = resolveLandingAnchor(
                        level,
                        origin.getX() + (int) Math.round(Math.cos(angle) * radius),
                        origin.getZ() + (int) Math.round(Math.sin(angle) * radius),
                        origin.getY());
                if (candidate != null && hasLandingClearance(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static BlockPos resolveLandingAnchor(ServerLevel level, int x, int z, int preferredY) {
        BlockPos probe = new BlockPos(x, preferredY, z);
        if (!level.isLoaded(probe)) {
            return null;
        }
        BlockPos candidate = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, probe);
        return isSafeFeet(level, candidate) ? candidate : null;
    }

    private static BlockPos resolveFeet(ServerLevel level, int x, int z, int preferredY) {
        if (CityWorlds.isCity(level)) {
            return CityWorlds.resolveStreetFeet(level, x, z, preferredY);
        }
        BlockPos candidate = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, preferredY, z));
        return isSafeFeet(level, candidate) ? candidate : null;
    }

    private static boolean isSafeFeet(ServerLevel level, BlockPos feet) {
        return level.isLoaded(feet)
                && level.getWorldBorder().isWithinBounds(feet)
                && level.getBlockState(feet.below()).blocksMotion()
                && level.isEmptyBlock(feet)
                && level.isEmptyBlock(feet.above());
    }

    private static ServerPlayer resolveTarget(ServerLevel level, CityNpc exec, Entity attacker) {
        if (attacker instanceof ServerPlayer player
                && player.isAlive() && !player.isCreative() && !player.isSpectator()) {
            return player;
        }
        ServerPlayer nearest = null;
        double nearestDistance = 96.0 * 96.0;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isCreative() || player.isSpectator()) {
                continue;
            }
            double distance = exec.distanceToSqr(player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<EventState> events = ACTIVE.get(level);
        if (events == null) {
            return;
        }
        Iterator<EventState> iterator = events.iterator();
        while (iterator.hasNext()) {
            EventState state = iterator.next();
            if (state.tick(level)) {
                iterator.remove();
            }
        }
        if (events.isEmpty()) {
            ACTIVE.remove(level);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        List<EventState> events = ACTIVE.get(level);
        if (events == null) {
            return;
        }
        UUID entityId = event.getEntity().getUUID();
        for (EventState state : events) {
            state.recordDeath(entityId);
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<EventState> events = ACTIVE.remove(level);
        if (events != null) {
            for (EventState state : events) {
                state.clear(level);
            }
        }
    }

    public static int activeEventCount(ServerLevel level) {
        return ACTIVE.getOrDefault(level, List.of()).size();
    }

    public static Phase phaseFor(ServerLevel level, UUID execId) {
        for (EventState state : ACTIVE.getOrDefault(level, List.of())) {
            if (state.execId.equals(execId)) {
                return state.phase;
            }
        }
        return null;
    }

    public static int responderCount(ServerLevel level, UUID execId) {
        for (EventState state : ACTIVE.getOrDefault(level, List.of())) {
            if (state.execId.equals(execId)) {
                return state.responders.size();
            }
        }
        return 0;
    }

    private static boolean hasEvent(ServerLevel level, UUID execId) {
        return phaseFor(level, execId) != null;
    }

    private static final class EventState {
        private final StructureTemplate template;
        private final UUID execId;
        private final UUID targetId;
        private final BlockPos landingCenter;
        private final int originX;
        private final int originZ;
        private final int landingY;
        private final int startY;
        private final int approachTimeoutTicks;
        private final int boardingWaitTicks;
        private final List<UUID> responders = new ArrayList<>();
        private final Set<UUID> deadResponders = new HashSet<>();
        private final List<BlockPos> placedBlocks = new ArrayList<>();
        private int currentY;
        private int movementTicks;
        private long landedAt = -1L;
        private long boardingStartedAt = -1L;
        private boolean execKilled;
        private Phase phase = Phase.DESCENDING;

        private EventState(StructureTemplate template, UUID execId, UUID targetId,
                           BlockPos landingCenter, int dropHeight,
                           int approachTimeoutTicks, int boardingWaitTicks) {
            this.template = template;
            this.execId = execId;
            this.targetId = targetId;
            this.landingCenter = landingCenter.immutable();
            this.originX = landingCenter.getX() - AERODYNE_WIDTH / 2;
            this.originZ = landingCenter.getZ() - AERODYNE_LENGTH / 2;
            this.landingY = landingCenter.getY() + AERODYNE_HOVER_CLEARANCE;
            this.startY = landingY + dropHeight;
            this.currentY = startY;
            this.approachTimeoutTicks = approachTimeoutTicks;
            this.boardingWaitTicks = boardingWaitTicks;
        }

        private BlockPos origin() {
            return new BlockPos(originX, currentY, originZ);
        }

        private BlockPos pickupPosition() {
            return landingCenter.offset(0, 0, AERODYNE_LENGTH / 2 + 2);
        }

        private boolean tick(ServerLevel level) {
            if (phase != Phase.ASCENDING && execLost(level)) {
                clearExecEvacuation(level);
                beginAscent(level);
                return false;
            }
            return switch (phase) {
                case DESCENDING -> tickDescent(level);
                case LANDED -> tickLanded(level);
                case BOARDING -> tickBoarding(level);
                case ASCENDING -> tickAscent(level);
            };
        }

        private boolean tickDescent(ServerLevel level) {
            if (currentY > landingY && !movementReady()) {
                return false;
            }
            if (currentY > landingY && !moveTo(level, currentY - 1)) {
                clear(level);
                clearExecEvacuation(level);
                return true;
            }
            if (currentY == landingY) {
                phase = Phase.LANDED;
                landedAt = level.getGameTime();
                spawnResponders(level);
                level.playSound(null, landingCenter, SoundEvents.IRON_GOLEM_REPAIR,
                        SoundSource.HOSTILE, 1.4F, 0.55F);
            }
            return false;
        }

        private boolean tickLanded(ServerLevel level) {
            Entity entity = level.getEntity(execId);
            CityNpc exec = entity instanceof CityNpc npc ? npc : null;
            if (exec == null || !exec.isAlive() || respondersEliminated(level)) {
                if (exec != null && exec.isAlive()) {
                    exec.clearEvacuationTarget();
                }
                beginAscent(level);
            } else if (exec.distanceToSqr(Vec3.atCenterOf(pickupPosition()))
                    <= PICKUP_DISTANCE_SQR) {
                phase = Phase.BOARDING;
                boardingStartedAt = level.getGameTime();
                exec.clearEvacuationTarget();
                stopBoardingExec(exec);
            } else if (level.getGameTime() - landedAt >= approachTimeoutTicks) {
                exec.clearEvacuationTarget();
                beginAscent(level);
            }
            return false;
        }

        private boolean tickBoarding(ServerLevel level) {
            Entity entity = level.getEntity(execId);
            CityNpc exec = entity instanceof CityNpc npc ? npc : null;
            if (exec == null || !exec.isAlive() || respondersEliminated(level)) {
                if (exec != null && exec.isAlive()) {
                    exec.clearEvacuationTarget();
                }
                beginAscent(level);
            } else if (level.getGameTime() - boardingStartedAt >= boardingWaitTicks) {
                exec.clearEvacuationTarget();
                exec.discard();
                beginAscent(level);
            } else {
                stopBoardingExec(exec);
            }
            return false;
        }

        private void stopBoardingExec(CityNpc exec) {
            exec.getNavigation().stop();
            Vec3 movement = exec.getDeltaMovement();
            exec.setDeltaMovement(0.0, movement.y, 0.0);
        }

        private boolean respondersEliminated(ServerLevel level) {
            if (responders.isEmpty()) {
                return false;
            }
            for (UUID responderId : responders) {
                Entity responder = level.getEntity(responderId);
                if (responder != null && !responder.isAlive()) {
                    deadResponders.add(responderId);
                }
            }
            return deadResponders.containsAll(responders);
        }

        private boolean execLost(ServerLevel level) {
            Entity exec = level.getEntity(execId);
            return execKilled || exec == null || !exec.isAlive();
        }

        private void recordDeath(UUID entityId) {
            if (execId.equals(entityId)) {
                execKilled = true;
            } else if (responders.contains(entityId)) {
                deadResponders.add(entityId);
            }
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

        private void beginAscent(ServerLevel level) {
            phase = Phase.ASCENDING;
            movementTicks = 0;
            level.playSound(null, landingCenter, SoundEvents.BEACON_DEACTIVATE,
                    SoundSource.HOSTILE, 1.5F, 0.8F);
        }

        private void clearExecEvacuation(ServerLevel level) {
            Entity entity = level.getEntity(execId);
            if (entity instanceof CityNpc exec) {
                exec.clearEvacuationTarget();
            }
        }

        private boolean movementReady() {
            movementTicks++;
            if (movementTicks < MOVE_INTERVAL_TICKS) {
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
                        landingCenter.getX() + 0.5, currentY, landingCenter.getZ() + 0.5,
                        10, 4.0, 0.25, 3.0, 0.03);
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

        private void spawnResponders(ServerLevel level) {
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(targetId);
            if (target == null) {
                return;
            }
            int count = MIN_RESPONDERS + level.getRandom().nextInt(
                    MAX_RESPONDERS - MIN_RESPONDERS + 1);
            for (int index = 0; index < count; index++) {
                int[] offset = RESPONDER_OFFSETS[index];
                BlockPos feet = resolveFeet(
                        level,
                        landingCenter.getX() + offset[0],
                        landingCenter.getZ() + offset[1],
                        landingCenter.getY());
                if (feet == null) {
                    continue;
                }
                FactionEnemy responder = FactionEntities.FACTION_ENEMY.get().create(
                        level, EntitySpawnReason.EVENT);
                if (responder == null) {
                    continue;
                }
                responder.snapTo(feet.getX() + 0.5, feet.getY() + 6.0, feet.getZ() + 0.5,
                        level.getRandom().nextFloat() * 360.0F, 0.0F);
                responder.finalizeSpawn(level, level.getCurrentDifficultyAt(feet),
                        EntitySpawnReason.EVENT, null);
                responder.setHome(feet);
                responder.setFaction(Faction.MILITECH);
                responder.setItemSlot(EquipmentSlot.MAINHAND,
                        new ItemStack(WeaponItems.gun(RESPONDER_LOADOUT[index]).get()));
                responder.setDropChance(EquipmentSlot.MAINHAND, 0.05F);
                responder.addEffect(new MobEffectInstance(
                        MobEffects.SLOW_FALLING, 20 * 8, 0, false, false));
                responder.deployAsTraumaTeam(target);
                if (level.addFreshEntity(responder)) {
                    responders.add(responder.getUUID());
                }
            }
        }
    }
}
