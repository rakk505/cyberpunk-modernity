package com.example.cyberdeck.effect;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Applies the passive, always-on effects of installed cyberware via transient attribute modifiers.
 * These are re-applied whenever the loadout changes and re-asserted on login so they never persist
 * incorrectly if a player uninstalls cyberware.
 *
 * <p>All modifiers are keyed by a stable {@link Identifier} so {@link #reapply(ServerPlayer)} can
 * cleanly remove the previous set before adding the current one (idempotent).
 */
public final class CyberwarePassives {
    // Stable modifier ids. Removing + re-adding by these ids keeps things idempotent.
    private static final Identifier ID_MANTIS_DAMAGE = id("mantis_damage");
    private static final Identifier ID_MANTIS_REACH = id("mantis_reach");
    private static final Identifier ID_GORILLA_KNOCKBACK = id("gorilla_knockback");
    private static final Identifier ID_HYENA_SPEED = id("hyena_speed");
    private static final Identifier ID_NANO_ARMOR = id("nano_armor");
    private static final Identifier ID_NANO_TOUGHNESS = id("nano_toughness");

    // A single netherite sword deals 8 attack damage; the player base is 1. Two netherite swords
    // worth of damage => total attack damage of ~16 while Mantis Blades are equipped.
    private static final double MANTIS_BONUS_DAMAGE = 15.0; // base 1 + 15 = 16 (~2x netherite sword)
    // Base entity interaction range is 3.0; +1.5 => 4.5 (1.5x reach).
    private static final double MANTIS_BONUS_REACH = 1.5;
    private static final double GORILLA_BONUS_KNOCKBACK = 2.0;
    private static final double HYENA_SPRINT_SPEED_MULT = 1.0; // +100% => double speed (multiplied base)
    private static final double NANO_BONUS_ARMOR = 20.0; // ~ a full iron set (iron set = 15) + buffer
    private static final double NANO_BONUS_TOUGHNESS = 2.0;

    private CyberwarePassives() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "cyberware/" + path);
    }

    /** Removes all cyberware passive modifiers, then re-adds only those for the current loadout. */
    public static void reapply(ServerPlayer player) {
        clear(player);

        CyberwareData data = CyberwareAttachments.get(player);

        Cyberware arms = data.get(BodySlot.ARMS);
        if (arms == Cyberware.MANTIS_BLADES) {
            addTransient(player, Attributes.ATTACK_DAMAGE, ID_MANTIS_DAMAGE,
                    MANTIS_BONUS_DAMAGE, AttributeModifier.Operation.ADD_VALUE);
            addTransient(player, Attributes.ENTITY_INTERACTION_RANGE, ID_MANTIS_REACH,
                    MANTIS_BONUS_REACH, AttributeModifier.Operation.ADD_VALUE);
        } else if (arms == Cyberware.GORILLA_ARMS) {
            addTransient(player, Attributes.ATTACK_KNOCKBACK, ID_GORILLA_KNOCKBACK,
                    GORILLA_BONUS_KNOCKBACK, AttributeModifier.Operation.ADD_VALUE);
        }

        Cyberware legs = data.get(BodySlot.LEGS);
        if (legs == Cyberware.HYENA_LEGS) {
            // Sprint speed is handled dynamically in the tick handler so it only applies while sprinting.
            // (See CyberwareTickHandler.)
            LegSpeed.markHyena(player, true);
        } else {
            LegSpeed.markHyena(player, false);
        }

        Cyberware integ = data.get(BodySlot.INTEGUMENTARY_SYSTEM);
        if (integ == Cyberware.NANO_PLATING) {
            addTransient(player, Attributes.ARMOR, ID_NANO_ARMOR,
                    NANO_BONUS_ARMOR, AttributeModifier.Operation.ADD_VALUE);
            addTransient(player, Attributes.ARMOR_TOUGHNESS, ID_NANO_TOUGHNESS,
                    NANO_BONUS_TOUGHNESS, AttributeModifier.Operation.ADD_VALUE);
        }
    }

    /** Removes every cyberware-owned attribute modifier from the player. */
    public static void clear(ServerPlayer player) {
        removeIf(player, Attributes.ATTACK_DAMAGE, ID_MANTIS_DAMAGE);
        removeIf(player, Attributes.ENTITY_INTERACTION_RANGE, ID_MANTIS_REACH);
        removeIf(player, Attributes.ATTACK_KNOCKBACK, ID_GORILLA_KNOCKBACK);
        removeIf(player, Attributes.MOVEMENT_SPEED, ID_HYENA_SPEED);
        removeIf(player, Attributes.ARMOR, ID_NANO_ARMOR);
        removeIf(player, Attributes.ARMOR_TOUGHNESS, ID_NANO_TOUGHNESS);
        LegSpeed.markHyena(player, false);
    }

    static Identifier hyenaSpeedId() {
        return ID_HYENA_SPEED;
    }

    static double hyenaSprintMultiplier() {
        return HYENA_SPRINT_SPEED_MULT;
    }

    private static void addTransient(ServerPlayer player, Holder<Attribute> attribute, Identifier id,
                                     double amount, AttributeModifier.Operation op) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        instance.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    private static void removeIf(ServerPlayer player, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
