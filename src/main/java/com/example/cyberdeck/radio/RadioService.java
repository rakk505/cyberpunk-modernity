package com.example.cyberdeck.radio;

import com.example.cyberdeck.network.RadioTrackPacket;

import dev.modernity.neoncity.District;
import dev.modernity.neoncity.NeonCityGenerator;
import dev.modernity.neoncity.PartySavedData;
import dev.modernity.neoncity.PartyService;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-authoritative station. Decides what everybody is listening to and when it changes.
 *
 * <p>A party listens together, which is the whole point of the feature, so the queue cannot be a
 * client-side shuffle. One station is kept per party (or per solo listener), the leader owns the
 * switch, and every member is sent the same track at the same moment. Because the server holds the
 * clock, someone joining mid-song, reconnecting, or lagging still ends up on the same track as the
 * rest of the party rather than quietly desynchronising.</p>
 */
public final class RadioService {
    /** How often the station re-evaluates. Music does not need a twenty-hertz decision loop. */
    private static final int EVALUATE_INTERVAL_TICKS = 20;
    /** How close a hostile must be, and be targeting someone, before the fight music starts. */
    private static final double COMBAT_RADIUS = 24.0;
    /** Silence after combat ends, so a brief lull does not whiplash between tracks. */
    private static final int COMBAT_HANGOVER_TICKS = 20 * 8;

    /** Listeners who asked for the radio, whether or not they are currently in a party. */
    private static final Set<UUID> ENABLED = new HashSet<>();
    /** One station per party id, or per player id when solo. */
    private static final Map<String, Station> STATIONS = new HashMap<>();
    private static int tickCounter;

    private RadioService() {
    }

    private static final class Station {
        private RadioTrack track;
        private RadioMood mood;
        private long trackEndTick;
        private long combatUntilTick;
    }

    public static boolean isEnabled(ServerPlayer player) {
        return ENABLED.contains(player.getUUID());
    }

    /**
     * Turns the station on or off. In a party only the leader may do so, and the change reaches
     * every member, because a party that hears different music is worse than no music at all.
     */
    public static ToggleResult toggle(ServerPlayer player, boolean on) {
        Optional<PartySavedData.PartySnapshot> party = PartyService.partyOf(player);
        if (party.isPresent() && !party.get().leader().equals(player.getUUID())) {
            return ToggleResult.NOT_PARTY_LEADER;
        }
        List<ServerPlayer> audience = audience(player);
        for (ServerPlayer listener : audience) {
            if (on) {
                ENABLED.add(listener.getUUID());
            } else {
                ENABLED.remove(listener.getUUID());
            }
        }
        if (!on) {
            STATIONS.remove(stationKey(player));
            for (ServerPlayer listener : audience) {
                send(listener, null);
            }
        }
        return audience.size() > 1
                ? ToggleResult.CHANGED_FOR_PARTY
                : ToggleResult.CHANGED;
    }

    public enum ToggleResult {
        CHANGED,
        CHANGED_FOR_PARTY,
        NOT_PARTY_LEADER
    }

    /** Drives every active station. Called once per server tick from the city module. */
    public static void tick(ServerLevel level) {
        // Counted here rather than tested against getGameTime: the previous version was called
        // from inside another tick window and the two clocks never lined up, so the station
        // silently never evaluated and nothing ever played.
        if (ENABLED.isEmpty()) {
            return;
        }
        if (++tickCounter % EVALUATE_INTERVAL_TICKS != 0) {
            return;
        }
        long now = level.getGameTime();
        Set<String> live = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (!ENABLED.contains(player.getUUID())) {
                continue;
            }
            String key = stationKey(player);
            if (!live.add(key)) {
                continue;
            }
            List<ServerPlayer> audience = audience(player);
            Station station = STATIONS.computeIfAbsent(key, ignored -> new Station());
            RadioMood mood = evaluateMood(audience, station, now);
            boolean moodChanged = station.mood != mood;
            if (moodChanged || station.track == null || now >= station.trackEndTick) {
                advance(station, mood, audience, now);
            }
        }
        STATIONS.keySet().retainAll(live);
    }

    /**
     * The strongest situation anybody in the audience is in. A party shares one mood, so a member
     * under fire puts the whole party on the combat track.
     */
    private static RadioMood evaluateMood(
            List<ServerPlayer> audience, Station station, long now) {
        for (ServerPlayer listener : audience) {
            if (inCombat(listener)) {
                station.combatUntilTick = now + COMBAT_HANGOVER_TICKS;
                return RadioMood.COMBAT;
            }
        }
        if (now < station.combatUntilTick) {
            return RadioMood.COMBAT;
        }
        for (ServerPlayer listener : audience) {
            if (listener.isPassenger()) {
                return RadioMood.DRIVE;
            }
        }
        return RadioMood.IDLE;
    }

    private static boolean inCombat(ServerPlayer player) {
        AABB area = player.getBoundingBox().inflate(COMBAT_RADIUS);
        return !player.level().getEntitiesOfClass(Mob.class, area,
                mob -> mob instanceof Enemy && mob.isAlive() && mob.getTarget() != null)
                .isEmpty();
    }

    /** Picks the next track and broadcasts it to the whole audience at once. */
    private static void advance(
            Station station, RadioMood mood, List<ServerPlayer> audience, long now) {
        RadioTrack next = choose(mood, station.track, audience);
        station.track = next;
        station.mood = mood;
        station.trackEndTick = now + next.durationTicks();
        for (ServerPlayer listener : audience) {
            send(listener, next);
        }
    }

    /**
     * Random, but never the track that just played. Inside G Corp the district theme replaces the
     * idle rotation entirely, which is what makes arriving there feel like arriving somewhere.
     */
    private static RadioTrack choose(
            RadioMood mood, RadioTrack previous, List<ServerPlayer> audience) {
        if (mood == RadioMood.IDLE && anyoneInGCorp(audience)) {
            return RadioTrack.G_CORP;
        }
        List<RadioTrack> options = new ArrayList<>(RadioTrack.rotation(mood));
        // Only exclude the previous track when something else is available, so a one-track mood
        // still plays rather than falling silent.
        if (options.size() > 1) {
            options.remove(previous);
        }
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private static boolean anyoneInGCorp(List<ServerPlayer> audience) {
        for (ServerPlayer listener : audience) {
            if (!NeonCityGenerator.isMegacityWorld(listener.level())) {
                continue;
            }
            var location = NeonCityGenerator.effectiveLocationAt(
                    listener.getBlockX(), listener.getBlockZ());
            if (location.insideCity() && location.district() == District.G_CORP) {
                return true;
            }
        }
        return false;
    }

    /** Everyone who should hear this player's station: their party, or just them. */
    private static List<ServerPlayer> audience(ServerPlayer player) {
        Optional<PartySavedData.PartySnapshot> party = PartyService.partyOf(player);
        if (party.isEmpty()) {
            return List.of(player);
        }
        List<ServerPlayer> members = new ArrayList<>();
        for (UUID memberId : party.get().members()) {
            ServerPlayer member = player.level().getServer().getPlayerList().getPlayer(memberId);
            if (member != null) {
                members.add(member);
            }
        }
        return members.isEmpty() ? List.of(player) : members;
    }

    private static String stationKey(ServerPlayer player) {
        return PartyService.partyId(player).orElseGet(() -> player.getUUID().toString());
    }

    private static void send(ServerPlayer player, RadioTrack track) {
        if (player.connection == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new RadioTrackPacket(
                track == null ? -1 : track.ordinal()));
    }

    /** A disconnecting listener keeps their preference but stops holding a station open. */
    public static void forget(UUID playerId) {
        ENABLED.remove(playerId);
    }

    public static void clearAll() {
        ENABLED.clear();
        STATIONS.clear();
    }
}
