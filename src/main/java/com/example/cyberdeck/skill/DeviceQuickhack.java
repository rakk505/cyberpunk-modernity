package com.example.cyberdeck.skill;

import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.vehicle.VehicleQuickhackService;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Quickhacks exposed by scanned vehicles and security turrets. */
public enum DeviceQuickhack {
    CAR_TAKE_CONTROL(100, DeviceKind.CAR, "Take Control", 4, 20, Items.COMPASS,
            "REMOTE VEHICLE OVERRIDE"),
    CAR_SPEED(101, DeviceKind.CAR, "Speed", 3, 0, Items.SUGAR,
            "FORCED ACCELERATION / 6 SEC"),
    CAR_BRAKE(102, DeviceKind.CAR, "Brake", 2, 0, Items.REDSTONE,
            "EMERGENCY MOTION LOCK"),
    CAR_DETONATE(103, DeviceKind.CAR, "Detonate", 6, 0, Items.TNT,
            "CANISTER-STRENGTH EXPLOSION"),

    TURRET_TAKE_CONTROL(110, DeviceKind.TURRET, "Take Control", 5, 30, Items.ENDER_EYE,
            "REMOTE AIM / MANUAL FIRE"),
    TURRET_DETONATE(111, DeviceKind.TURRET, "Detonate", 6, 40, Items.TNT,
            "LETHAL DEVICE OVERLOAD"),
    TURRET_DEACTIVATE(112, DeviceKind.TURRET, "Deactivate", 3, 20, Items.LEVER,
            "DISABLE TARGETING SYSTEM"),
    ;

    private static final List<DeviceQuickhack> CAR_ACTIONS = List.of(
            CAR_TAKE_CONTROL, CAR_SPEED, CAR_BRAKE, CAR_DETONATE);
    private static final List<DeviceQuickhack> TURRET_ACTIONS = List.of(
            TURRET_TAKE_CONTROL, TURRET_DETONATE, TURRET_DEACTIVATE);

    private final int wireId;
    private final DeviceKind kind;
    private final String displayName;
    private final int ramCost;
    private final int uploadTicks;
    private final Item icon;
    private final String summary;

    DeviceQuickhack(int wireId, DeviceKind kind, String displayName, int ramCost,
                    int uploadTicks, Item icon, String summary) {
        this.wireId = wireId;
        this.kind = kind;
        this.displayName = displayName;
        this.ramCost = ramCost;
        this.uploadTicks = uploadTicks;
        this.icon = icon;
        this.summary = summary;
    }

    public int wireId() {
        return wireId;
    }

    public DeviceKind kind() {
        return kind;
    }

    public String displayName() {
        return displayName;
    }

    public int ramCost() {
        return ramCost;
    }

    public int uploadTicks() {
        return uploadTicks;
    }

    public String summary() {
        return summary;
    }

    public ItemStack stack() {
        ItemStack stack = new ItemStack(icon);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(displayName)
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.AQUA))
                .append(Component.literal("  [" + ramCost + " RAM]")
                        .withStyle(style -> style.withItalic(false)
                                .withColor(ChatFormatting.LIGHT_PURPLE))));
        return stack;
    }

    public boolean supports(Entity entity) {
        return switch (kind) {
            case CAR -> VehicleQuickhackService.isCar(entity);
            case TURRET -> entity instanceof KangTaoTurret turret
                    && turret.isAlive() && !turret.isDestroyed();
        };
    }

    public static List<DeviceQuickhack> actionsFor(Entity entity) {
        if (VehicleQuickhackService.isCar(entity)) {
            return CAR_ACTIONS;
        }
        if (entity instanceof KangTaoTurret turret
                && turret.isAlive() && !turret.isDestroyed()) {
            return TURRET_ACTIONS;
        }
        return List.of();
    }

    public static DeviceQuickhack fromSlot(Entity entity, int slot) {
        List<DeviceQuickhack> actions = actionsFor(entity);
        return slot >= 0 && slot < actions.size() ? actions.get(slot) : null;
    }

    public static DeviceQuickhack fromWireId(int wireId) {
        for (DeviceQuickhack action : values()) {
            if (action.wireId == wireId) {
                return action;
            }
        }
        return null;
    }

    public enum DeviceKind {
        CAR,
        TURRET
    }
}
