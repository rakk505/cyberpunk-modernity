package dev.modernity.neoncity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Displays one title when a player enters a district or crosses into another district. */
final class DistrictEntryNotifier {
    static final int FADE_IN_TICKS = 10;
    static final int STAY_TICKS = 50;
    static final int FADE_OUT_TICKS = 15;

    private final Map<UUID, District> currentDistricts = new HashMap<>();

    /**
     * Records a player's current district and returns a district only for a new entry.
     * A {@code null} district represents wilderness or another dimension and rearms the
     * notification for the next city entry.
     */
    Optional<District> transition(UUID playerId, @Nullable District currentDistrict) {
        if (currentDistrict == null) {
            currentDistricts.remove(playerId);
            return Optional.empty();
        }
        District previous = currentDistricts.put(playerId, currentDistrict);
        return previous == currentDistrict ? Optional.empty() : Optional.of(currentDistrict);
    }

    void updatePlayer(ServerPlayer player, @Nullable District currentDistrict) {
        transition(player.getUUID(), currentDistrict).ifPresent(district -> show(player, district));
    }

    void retainPlayers(Set<UUID> activePlayers) {
        currentDistricts.keySet().retainAll(activePlayers);
    }

    void clear() {
        currentDistricts.clear();
    }

    static @Nullable District inhabitedDistrict(
            District district, MegacityLayout.Zone zone) {
        return switch (zone) {
            case NEST, BACKSTREETS -> district;
            default -> null;
        };
    }

    static Component title(District district) {
        return Component.literal("Now Entering District " + district.code())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static void show(ServerPlayer player, District district) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(
                FADE_IN_TICKS, STAY_TICKS, FADE_OUT_TICKS));
        player.connection.send(new ClientboundSetTitleTextPacket(title(district)));
    }
}
