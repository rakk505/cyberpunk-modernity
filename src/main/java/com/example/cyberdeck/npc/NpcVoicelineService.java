package com.example.cyberdeck.npc;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.network.NpcVoicelinePacket;
import com.example.cyberdeck.npc.NpcVoicelineCatalog.LocationPool;
import com.example.cyberdeck.npc.NpcVoicelineCatalog.RolePool;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.MissionService;
import dev.modernity.neoncity.NeonCityGenerator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server authority for NPC dialogue triggers, line selection, and spam control. */
@EventBusSubscriber(modid = Cyberdeck.MODID)
public final class NpcVoicelineService {
    public static final int PLAYER_COOLDOWN_TICKS = 60;
    public static final double MAX_INTERACTION_DISTANCE = 6.0;
    private static final int MIN_DISPLAY_TICKS = 70;
    private static final int MAX_DISPLAY_TICKS = 140;
    private static final Map<UUID, PlayerVoiceState> PLAYER_STATES = new HashMap<>();

    private NpcVoicelineService() {
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)
                || !(event.getTarget() instanceof LivingEntity target)
                || MissionService.isMainlineCharacter(target)) {
            return;
        }
        boolean storyActor = MissionService.isStoryMissionActor(target);
        if (!acceptsTrigger(target, storyActor, DialogueTrigger.ATTACK)) {
            return;
        }

        trySpeak(level, player, target, storyActor);
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)
                || !(event.getTarget() instanceof LivingEntity target)
                || MissionService.isMainlineCharacter(target)) {
            return;
        }
        boolean storyActor = MissionService.isStoryMissionActor(target);
        if (!acceptsTrigger(target, storyActor, DialogueTrigger.INTERACT)) {
            return;
        }

        trySpeak(level, player, target, storyActor);
    }

    /** Ambient city NPCs speak on use; combat actors can retain attack barks. */
    public static boolean acceptsTrigger(
            LivingEntity target, boolean storyActor, DialogueTrigger trigger) {
        if (storyActor) {
            return trigger == DialogueTrigger.ATTACK;
        }
        if (target instanceof CityNpc) {
            return trigger == DialogueTrigger.INTERACT;
        }
        return false;
    }

    static boolean trySpeak(
            ServerLevel level, ServerPlayer player, LivingEntity target, boolean storyActor) {
        if (!player.isAlive() || player.isSpectator() || !target.isAlive()
                || target.level() != level
                || player.distanceToSqr(target)
                        > MAX_INTERACTION_DISTANCE * MAX_INTERACTION_DISTANCE
                || !player.hasLineOfSight(target)) {
            return false;
        }

        long now = level.getGameTime();
        PlayerVoiceState previous = PLAYER_STATES.get(player.getUUID());
        if (previous != null && now < previous.nextAllowedTick()) {
            return false;
        }

        LocationPool location = locationFor(level, target);
        RolePool role = roleFor(target, storyActor);
        List<String> lines = NpcVoicelineCatalog.lines(location, role);
        String lastLine = previous == null ? null : previous.lastLine();
        String line = selectLine(lines, lastLine, level.getRandom());
        int duration = displayTicks(line);
        PLAYER_STATES.put(player.getUUID(),
                new PlayerVoiceState(now + PLAYER_COOLDOWN_TICKS, line));
        PacketDistributor.sendToPlayer(player,
                new NpcVoicelinePacket(target.getDisplayName().getString(), line, duration));
        return true;
    }

    static LocationPool locationFor(ServerLevel level, LivingEntity target) {
        if (!NeonCityGenerator.isMegacityWorld(level) || !NeonCityGenerator.isEnabled()) {
            return LocationPool.GENERIC_UNSUPPORTED_DISTRICTS;
        }
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(
                target.getBlockX(), target.getBlockZ());
        return classifyLocation(sample.district(), sample.zone(), sample.roadClass());
    }

    public static LocationPool classifyLocation(
            District district,
            MegacityLayout.Zone zone,
            NeonCityGenerator.RoadClass roadClass) {
        if (zone == MegacityLayout.Zone.BORDER_WALLED
                || roadClass == NeonCityGenerator.RoadClass.BORDER_WALLED) {
            return LocationPool.BORDER_SLUMS;
        }
        if (roadClass == NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD
                || roadClass == NeonCityGenerator.RoadClass.BRIDGE
                || roadClass == NeonCityGenerator.RoadClass.ELEVATED_RAIL
                || roadClass == NeonCityGenerator.RoadClass.HIGHWAY_BUFFER) {
            return LocationPool.GREAT_HIGHWAY;
        }
        if (district == null) {
            return LocationPool.GENERIC_UNSUPPORTED_DISTRICTS;
        }
        return switch (district) {
            case A_CORP -> LocationPool.DISTRICT_A;
            case O_CORP -> LocationPool.DISTRICT_O;
            case P_CORP -> LocationPool.DISTRICT_P;
            case D_CORP -> LocationPool.DISTRICT_D;
            case E_CORP -> LocationPool.DISTRICT_E;
            case G_CORP -> LocationPool.DISTRICT_G;
            case K_CORP -> LocationPool.DISTRICT_K;
            case B_CORP -> LocationPool.DISTRICT_B;
            case M_CORP -> LocationPool.DISTRICT_M;
            case N_CORP -> LocationPool.DISTRICT_N;
            default -> LocationPool.GENERIC_UNSUPPORTED_DISTRICTS;
        };
    }

    static RolePool roleFor(LivingEntity target, boolean storyActor) {
        if (storyActor) {
            return target instanceof CityNpc ? RolePool.EXECS : RolePool.CORPOS;
        }
        if (target instanceof CityNpc npc) {
            return switch (npc.getRole()) {
                case RESIDENT -> RolePool.RESIDENTS;
                case CORPO -> RolePool.CORPOS;
                case EXEC -> RolePool.EXECS;
            };
        }
        return RolePool.CORPOS;
    }

    /** Selects a random line without immediately repeating when the pool has alternatives. */
    public static String selectLine(List<String> lines, String previous, RandomSource random) {
        int selected = random.nextInt(lines.size());
        if (lines.size() > 1 && lines.get(selected).equals(previous)) {
            selected = (selected + 1 + random.nextInt(lines.size() - 1)) % lines.size();
        }
        return lines.get(selected);
    }

    private static int displayTicks(String line) {
        int codePoints = line.codePointCount(0, line.length());
        return Mth.clamp(50 + codePoints * 2, MIN_DISPLAY_TICKS, MAX_DISPLAY_TICKS);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PLAYER_STATES.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PLAYER_STATES.clear();
    }

    static void resetForTests() {
        PLAYER_STATES.clear();
    }

    private record PlayerVoiceState(long nextAllowedTick, String lastLine) {
    }

    public enum DialogueTrigger {
        ATTACK,
        INTERACT
    }
}
