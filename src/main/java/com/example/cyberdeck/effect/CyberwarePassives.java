package com.example.cyberdeck.effect;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareStats;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Aggregates always-on catalog mechanics into stable transient attribute modifiers. */
public final class CyberwarePassives {
    private static final Identifier ID_ARMOR = id("catalog_armor");
    private static final Identifier ID_ARMOR_MULTIPLIER = id("catalog_armor_multiplier");
    private static final Identifier ID_MAX_HEALTH = id("catalog_max_health");
    private static final Identifier ID_MOVEMENT = id("catalog_movement");
    private static final Identifier ID_ATTACK_SPEED = id("catalog_attack_speed");
    private static final Identifier ID_ATTACK_DAMAGE = id("catalog_attack_damage");
    private static final Identifier ID_ATTACK_KNOCKBACK = id("catalog_attack_knockback");
    private static final Identifier ID_REACH = id("catalog_reach");
    private static final Identifier ID_DYNAMIC_MOVEMENT = id("dynamic_movement");
    private static final Identifier ID_DYNAMIC_ARMOR = id("dynamic_armor");
    private static final Identifier ID_DYNAMIC_ATTACK_SPEED = id("dynamic_attack_speed");

    private CyberwarePassives() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "cyberware/" + path);
    }

    public static void reapply(ServerPlayer player) {
        clearStatic(player);

        double armor = 0.0;
        double armorMultiplier = 0.0;
        double maxHealth = 0.0;
        double movement = 0.0;
        double attackSpeed = 0.0;
        double attackDamage = 0.0;
        double knockback = 0.0;
        double reach = 0.0;

        CyberwareStats stats = CyberwareStats.from(CyberwareAttachments.get(player));

        for (Cyberware cyberware : CyberwareAttachments.get(player).allInstalled()) {
            armor += cyberware.value("armor_points");
            armorMultiplier += cyberware.value("armor_multiplier_percent") / 100.0;
            maxHealth += cyberware.value("max_health_percent") / 100.0;
            movement += cyberware.value("movement_speed_percent") / 100.0;
            if (cyberware.hasFlag("gorilla_arms")) {
                knockback = Math.max(knockback, 2.0);
                attackDamage += 0.35;
            }
            if (cyberware.hasFlag("mantis_blades")) {
                attackDamage += 0.75;
                reach = Math.max(reach, 1.5);
            }
            if (cyberware.hasFlag("monowire")) {
                attackDamage += 0.35;
                reach = Math.max(reach, 2.0);
            }
        }
        attackSpeed += stats.meleeAttackSpeedBonus();
        attackDamage += stats.meleeDamageBonus();

        addIfNonZero(player, Attributes.ARMOR, ID_ARMOR, armor,
                AttributeModifier.Operation.ADD_VALUE);
        addIfNonZero(player, Attributes.ARMOR, ID_ARMOR_MULTIPLIER, armorMultiplier,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        addIfNonZero(player, Attributes.MAX_HEALTH, ID_MAX_HEALTH, maxHealth,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        addIfNonZero(player, Attributes.MOVEMENT_SPEED, ID_MOVEMENT, movement,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        addIfNonZero(player, Attributes.ATTACK_SPEED, ID_ATTACK_SPEED, attackSpeed,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        addIfNonZero(player, Attributes.ATTACK_DAMAGE, ID_ATTACK_DAMAGE, attackDamage,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        addIfNonZero(player, Attributes.ATTACK_KNOCKBACK, ID_ATTACK_KNOCKBACK, knockback,
                AttributeModifier.Operation.ADD_VALUE);
        addIfNonZero(player, Attributes.ENTITY_INTERACTION_RANGE, ID_REACH, reach,
                AttributeModifier.Operation.ADD_VALUE);
        player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
    }

    public static void setDynamicMovement(ServerPlayer player, double fraction) {
        setDynamic(player, Attributes.MOVEMENT_SPEED, ID_DYNAMIC_MOVEMENT, fraction,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    public static void setDynamicArmor(ServerPlayer player, double points) {
        setDynamic(player, Attributes.ARMOR, ID_DYNAMIC_ARMOR, points,
                AttributeModifier.Operation.ADD_VALUE);
    }

    public static void setDynamicAttackSpeed(ServerPlayer player, double fraction) {
        setDynamic(player, Attributes.ATTACK_SPEED, ID_DYNAMIC_ATTACK_SPEED, fraction,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    public static void clear(ServerPlayer player) {
        clearStatic(player);
        removeIf(player, Attributes.MOVEMENT_SPEED, ID_DYNAMIC_MOVEMENT);
        removeIf(player, Attributes.ARMOR, ID_DYNAMIC_ARMOR);
        removeIf(player, Attributes.ATTACK_SPEED, ID_DYNAMIC_ATTACK_SPEED);
    }

    private static void clearStatic(ServerPlayer player) {
        removeIf(player, Attributes.ARMOR, ID_ARMOR);
        removeIf(player, Attributes.ARMOR, ID_ARMOR_MULTIPLIER);
        removeIf(player, Attributes.MAX_HEALTH, ID_MAX_HEALTH);
        removeIf(player, Attributes.MOVEMENT_SPEED, ID_MOVEMENT);
        removeIf(player, Attributes.ATTACK_SPEED, ID_ATTACK_SPEED);
        removeIf(player, Attributes.ATTACK_DAMAGE, ID_ATTACK_DAMAGE);
        removeIf(player, Attributes.ATTACK_KNOCKBACK, ID_ATTACK_KNOCKBACK);
        removeIf(player, Attributes.ENTITY_INTERACTION_RANGE, ID_REACH);
    }

    private static void addIfNonZero(ServerPlayer player, Holder<Attribute> attribute,
                                     Identifier id, double amount,
                                     AttributeModifier.Operation operation) {
        if (Math.abs(amount) > 1.0e-8) {
            setDynamic(player, attribute, id, amount, operation);
        }
    }

    private static void setDynamic(ServerPlayer player, Holder<Attribute> attribute,
                                   Identifier id, double amount,
                                   AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        if (Math.abs(amount) > 1.0e-8) {
            instance.addTransientModifier(new AttributeModifier(id, amount, operation));
        }
    }

    private static void removeIf(ServerPlayer player, Holder<Attribute> attribute, Identifier id) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }
}
