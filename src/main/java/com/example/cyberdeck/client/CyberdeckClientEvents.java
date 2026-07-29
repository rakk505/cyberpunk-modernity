package com.example.cyberdeck.client;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.network.ActivateSkillPacket;
import com.example.cyberdeck.network.CyberwareActionPacket;
import com.example.cyberdeck.network.ToggleInterfacePacket;
import com.example.cyberdeck.movement.TacticalAction;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.movement.TacticalMovementPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Handles client input for the cyberdeck: sending the toggle packet on the key press,
 * and sending a skill-activation packet when the player uses a skill slot while looking at an entity.
 */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class CyberdeckClientEvents {
    private static boolean quickhackUseLatched;

    private CyberdeckClientEvents() {
    }

    /** Queue on F before vanilla handles its swap-offhand mapping. */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        QuickhackScannerClient.tick(mc);
        if (!mc.options.keyUse.isDown()) {
            quickhackUseLatched = false;
        }
        while (CyberdeckClient.QUEUE_QUICKHACK_KEY.consumeClick()) {
            if (!QuickhackScannerClient.isActive() || mc.player == null) {
                continue;
            }
            if (mc.options.keySwapOffhand.same(CyberdeckClient.QUEUE_QUICKHACK_KEY)) {
                while (mc.options.keySwapOffhand.consumeClick()) {
                    // Drain the conflicting vanilla click while the scanner owns F.
                }
            }
            queueSelectedQuickhack(mc);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            QuickhackScannerClient.reset();
            return;
        }

        // Open the cyberware screen (works regardless of the helmet - it's body augmentation).
        while (CyberdeckClient.OPEN_CYBERWARE_KEY.consumeClick()) {
            if (mc.gui.screen() == null) {
                mc.setScreenAndShow(new com.example.cyberdeck.client.screen.CyberwareScreen());
            }
        }

        // The remaining inputs are only meaningful when a screen is not open.
        if (mc.gui.screen() != null) {
            resetJumpTracking();
            return;
        }

        // Fire the toggle only when the player is wearing the helmet, to avoid hijacking TAB otherwise.
        while (CyberdeckClient.TOGGLE_KEY.consumeClick()) {
            if (isWearingCyberdeck(mc.player)) {
                ClientPacketDistributor.sendToServer(new ToggleInterfacePacket());
            }
        }

        while (CyberdeckClient.PREVIOUS_QUICKHACK_KEY.consumeClick()) {
            if (QuickhackScannerClient.isActive()) {
                QuickhackScannerClient.cycle(mc.player, -1);
            }
        }
        while (CyberdeckClient.NEXT_QUICKHACK_KEY.consumeClick()) {
            if (QuickhackScannerClient.isActive()) {
                QuickhackScannerClient.cycle(mc.player, 1);
            }
        }

        while (CyberdeckClient.SANDEVISTAN_KEY.consumeClick()) {
            ClientPacketDistributor.sendToServer(new CyberwareActionPacket(CyberwareActionPacket.Action.SANDEVISTAN));
        }
        while (CyberdeckClient.ARM_CANNON_KEY.consumeClick()) {
            ClientPacketDistributor.sendToServer(new CyberwareActionPacket(CyberwareActionPacket.Action.ARM_CANNON));
        }
        while (CyberdeckClient.THRETEVAC_KEY.consumeClick()) {
            ClientPacketDistributor.sendToServer(new CyberwareActionPacket(CyberwareActionPacket.Action.THRETEVAC));
        }
        while (CyberdeckClient.OPTICAL_CAMO_KEY.consumeClick()) {
            ClientPacketDistributor.sendToServer(new CyberwareActionPacket(CyberwareActionPacket.Action.OPTICAL_CAMO));
        }

        // Manual reload: only meaningful while holding a gun. The server validates reserve ammo and
        // whether a reload is already in progress or the magazine is full.
        while (CyberdeckClient.RELOAD_KEY.consumeClick()) {
            if (mc.player.getMainHandItem().getItem() instanceof com.example.cyberdeck.weapon.GunItem) {
                ClientPacketDistributor.sendToServer(new com.example.cyberdeck.network.ReloadPacket());
            }
        }

        float forward = (mc.options.keyUp.isDown() ? 1.0F : 0.0F)
                - (mc.options.keyDown.isDown() ? 1.0F : 0.0F);
        float strafe = (mc.options.keyRight.isDown() ? 1.0F : 0.0F)
                - (mc.options.keyLeft.isDown() ? 1.0F : 0.0F);
        while (CyberdeckClient.DASH_KEY.consumeClick()) {
            ClientPacketDistributor.sendToServer(
                    new TacticalMovementPacket(TacticalAction.DASH, forward, strafe));
        }
        while (CyberdeckClient.SLIDE_KEY.consumeClick()) {
            ClientPacketDistributor.sendToServer(
                    new TacticalMovementPacket(TacticalAction.SLIDE, forward, strafe));
        }

        handleDoubleJump(mc);
    }

    // --- Frog Legs double-jump detection ---
    // A double jump fires when the jump key is pressed a second time while airborne, shortly after
    // the first jump, and only once per airborne period.
    private static boolean jumpKeyWasDown = false;
    private static boolean usedDoubleJump = false;
    private static boolean wasOnGround = true;

    private static void handleDoubleJump(Minecraft mc) {
        Player player = mc.player;
        if (player == null) {
            return;
        }
        if (TacticalMovement.get(player).action() != TacticalAction.NONE) {
            jumpKeyWasDown = mc.options.keyJump.isDown();
            usedDoubleJump = true;
            wasOnGround = player.onGround();
            return;
        }
        boolean onGround = player.onGround();
        if (onGround) {
            usedDoubleJump = false;
        }
        boolean jumpDown = mc.options.keyJump.isDown();
        // Rising edge of the jump key while airborne (and after having left the ground) => double jump.
        if (jumpDown && !jumpKeyWasDown && !onGround && !wasOnGround && !usedDoubleJump) {
            usedDoubleJump = true;
            ClientPacketDistributor.sendToServer(new CyberwareActionPacket(CyberwareActionPacket.Action.DOUBLE_JUMP));
        }
        jumpKeyWasDown = jumpDown;
        wasOnGround = onGround;
    }

    private static void resetJumpTracking() {
        jumpKeyWasDown = false;
    }

    // RMB is a second queue control and can be cancelled cleanly by NeoForge.
    @SubscribeEvent
    public static void onUseInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!QuickhackScannerClient.isActive()) {
            return;
        }
        if (!quickhackUseLatched) {
            quickhackUseLatched = true;
            queueSelectedQuickhack(mc);
        }
        // Prevent the vanilla place/use action from also firing.
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** Removes vanilla HUD pieces replaced by the scanner composition. */
    @SubscribeEvent
    public static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (!QuickhackScannerClient.isActive()) {
            return;
        }
        var layer = event.getName();
        if (layer.equals(VanillaGuiLayers.CROSSHAIR)
                || layer.equals(VanillaGuiLayers.HOTBAR)
                || layer.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)
                || layer.equals(VanillaGuiLayers.PLAYER_HEALTH)
                || layer.equals(VanillaGuiLayers.ARMOR_LEVEL)
                || layer.equals(VanillaGuiLayers.FOOD_LEVEL)
                || layer.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR_BACKGROUND)
                || layer.equals(VanillaGuiLayers.EXPERIENCE_LEVEL)
                || layer.equals(VanillaGuiLayers.CONTEXTUAL_INFO_BAR)
                || layer.equals(VanillaGuiLayers.TAB_LIST)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        quickhackUseLatched = false;
        QuickhackScannerClient.reset();
        QuickhackUploadClient.set(com.example.cyberdeck.network.QuickhackUploadPacket.NONE);
    }

    private static boolean isWearingCyberdeck(Player player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).is(CyberdeckItems.CYBERDECK.get());
    }

    private static boolean queueSelectedQuickhack(Minecraft minecraft) {
        LivingEntity target = QuickhackScannerClient.actionTarget(minecraft.level);
        if (target == null) {
            return false;
        }
        ClientPacketDistributor.sendToServer(new ActivateSkillPacket(
                QuickhackScannerClient.selectedSkillOrdinal(), target.getId()));
        return true;
    }
}
