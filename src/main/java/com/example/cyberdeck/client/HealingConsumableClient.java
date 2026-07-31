package com.example.cyberdeck.client;

import com.example.cyberdeck.healing.HealingConsumable;
import com.example.cyberdeck.healing.HealingState;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

import org.lwjgl.glfw.GLFW;

import java.util.Arrays;

/** Client-local quick-slot selection and immediate cooldown feedback. */
public final class HealingConsumableClient {
    private static HealingConsumable selected = HealingConsumable.BOUNCE_BACK;
    private static final long[] predictedReadyTicks =
            new long[HealingConsumable.VALUES.length];
    private static boolean physicalUseWasDown;
    private static boolean bindingMigrationChecked;

    private HealingConsumableClient() {
    }

    public static HealingConsumable selected() {
        return selected;
    }

    public static void cycle() {
        int next = (selected.ordinal() + 1) % HealingConsumable.VALUES.length;
        selected = HealingConsumable.VALUES[next];
    }

    public static void migrateLegacyUseBinding(Minecraft minecraft) {
        if (bindingMigrationChecked) {
            return;
        }
        bindingMigrationChecked = true;
        if ("key.keyboard.apostrophe".equals(CyberdeckClient.USE_HEALING_KEY.saveString())) {
            CyberdeckClient.USE_HEALING_KEY.setKey(
                    InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_X));
            KeyMapping.resetMapping();
            minecraft.options.save();
        }
    }

    /** Keeps physical X working even when an older options file saved a legacy binding. */
    public static boolean pollPhysicalUseKey(Minecraft minecraft) {
        boolean down = InputConstants.isKeyDown(minecraft.getWindow(), GLFW.GLFW_KEY_X);
        boolean clicked = down && !physicalUseWasDown;
        physicalUseWasDown = down;
        return clicked;
    }

    public static long cooldownRemaining(
            HealingState serverState, HealingConsumable consumable, long gameTick) {
        long predicted = Math.max(0L, predictedReadyTicks[consumable.ordinal()] - gameTick);
        return Math.max(serverState.cooldownRemaining(consumable, gameTick), predicted);
    }

    public static void predictUse(HealingConsumable consumable, long gameTick) {
        predictedReadyTicks[consumable.ordinal()] = gameTick + consumable.cooldownTicks();
    }

    public static void reset() {
        selected = HealingConsumable.BOUNCE_BACK;
        Arrays.fill(predictedReadyTicks, 0L);
        physicalUseWasDown = false;
    }
}
