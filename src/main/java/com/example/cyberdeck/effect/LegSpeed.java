package com.example.cyberdeck.effect;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Hyena Legs sprint speed boost. The boost must only apply while the player is actively
 * sprinting, so it is added/removed dynamically each tick rather than as a static passive modifier.
 * This class tracks which players have Hyena Legs installed and whether the boost is currently active.
 */
public final class LegSpeed {
    private static final Set<UUID> HYENA_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> BOOST_ACTIVE = ConcurrentHashMap.newKeySet();

    private LegSpeed() {
    }

    static void markHyena(ServerPlayer player, boolean hasHyena) {
        UUID id = player.getUUID();
        if (hasHyena) {
            HYENA_PLAYERS.add(id);
        } else {
            HYENA_PLAYERS.remove(id);
            setBoost(player, false);
        }
    }

    public static boolean hasHyena(ServerPlayer player) {
        return HYENA_PLAYERS.contains(player.getUUID());
    }

    /** Applies or removes the transient sprint-speed modifier to match the desired state. */
    public static void setBoost(ServerPlayer player, boolean active) {
        UUID id = player.getUUID();
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        if (active) {
            if (BOOST_ACTIVE.add(id)) {
                speed.removeModifier(CyberwarePassives.hyenaSpeedId());
                speed.addTransientModifier(new AttributeModifier(
                        CyberwarePassives.hyenaSpeedId(),
                        CyberwarePassives.hyenaSprintMultiplier(),
                        AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        } else {
            if (BOOST_ACTIVE.remove(id)) {
                speed.removeModifier(CyberwarePassives.hyenaSpeedId());
            }
        }
    }

    public static void forget(UUID id) {
        HYENA_PLAYERS.remove(id);
        BOOST_ACTIVE.remove(id);
    }
}
