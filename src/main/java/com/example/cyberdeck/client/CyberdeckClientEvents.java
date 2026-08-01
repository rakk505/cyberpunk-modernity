package com.example.cyberdeck.client;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.hud.MinimapClientState;
import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.client.mission.MissionTrackerClient;
import com.example.cyberdeck.client.mission.GigJournalClient;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.healing.HealingConsumable;
import com.example.cyberdeck.healing.HealingState;
import com.example.cyberdeck.network.ActivateSkillPacket;
import com.example.cyberdeck.network.CyberwareActionPacket;
import com.example.cyberdeck.network.ToggleInterfacePacket;
import com.example.cyberdeck.network.UseHealingConsumablePacket;
import com.example.cyberdeck.movement.TacticalAction;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.movement.TacticalMovementPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
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
            if (!QuickhackScannerClient.isQuickhacking() || mc.player == null) {
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
        CityMapNavigationClient.tick(mc);
        if (mc.player == null) {
            QuickhackScannerClient.reset();
            return;
        }
        HealingConsumableClient.migrateLegacyUseBinding(mc);

        while (CyberdeckClient.OPEN_CITY_MAP_KEY.consumeClick()) {
            if (mc.gui.screen() == null) {
                CityMapNavigationClient.requestOpen();
            }
        }

        while (CyberdeckClient.OPEN_JOURNAL_KEY.consumeClick()) {
            if (mc.gui.screen() == null) {
                com.example.cyberdeck.client.screen.JournalScreen.open();
            }
        }

        // Open the cyberware screen regardless of the currently installed operating system.
        while (CyberdeckClient.OPEN_CYBERWARE_KEY.consumeClick()) {
            if (mc.gui.screen() == null) {
                mc.setScreenAndShow(new com.example.cyberdeck.client.screen.CyberwareScreen());
            }
        }

        // Minimap and merchant-marker visibility toggles persist for the client session.
        while (CyberdeckClient.TOGGLE_MINIMAP_KEY.consumeClick()) {
            MinimapClientState.toggleMinimap();
        }
        while (CyberdeckClient.TOGGLE_MERCHANTS_KEY.consumeClick()) {
            MinimapClientState.toggleMerchantMarkers();
        }

        QuickhackScannerClient.tick(mc);
        boolean physicalHealingClick = HealingConsumableClient.pollPhysicalUseKey(mc);

        // The remaining inputs are only meaningful when a screen is not open.
        if (mc.gui.screen() != null) {
            resetJumpTracking();
            return;
        }

        // The owner-synced cyberware attachment keeps TAB available when no cyberdeck OS is installed.
        while (CyberdeckClient.TOGGLE_KEY.consumeClick()) {
            var cyberware = CyberwareAttachments.get(mc.player);
            if (CyberwareEffects.canQuickhack(cyberware) || CyberwareEffects.canScan(cyberware)) {
                ClientPacketDistributor.sendToServer(new ToggleInterfacePacket());
            }
        }

        while (CyberdeckClient.PREVIOUS_QUICKHACK_KEY.consumeClick()) {
            if (QuickhackScannerClient.isQuickhacking()) {
                QuickhackScannerClient.cycle(mc.player, -1);
            }
        }
        while (CyberdeckClient.NEXT_QUICKHACK_KEY.consumeClick()) {
            if (QuickhackScannerClient.isQuickhacking()) {
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

        while (CyberdeckClient.SELECT_HEALING_KEY.consumeClick()) {
            HealingConsumableClient.cycle();
        }
        boolean mappedHealingClick = false;
        while (CyberdeckClient.USE_HEALING_KEY.consumeClick()) {
            mappedHealingClick = true;
        }
        if (mappedHealingClick || physicalHealingClick) {
            HealingConsumable selected = HealingConsumableClient.selected();
            long gameTick = mc.level.getGameTime();
            HealingState state = HealingState.get(mc.player);
            if (mc.player.isAlive()
                    && !mc.player.isSpectator()
                    && HealingConsumableClient.cooldownRemaining(
                            state, selected, gameTick) == 0L) {
                HealingConsumableClient.predictUse(selected, gameTick);
                ClientPacketDistributor.sendToServer(new UseHealingConsumablePacket(selected));
            }
        }

        // Manual reload: only meaningful while holding a gun. The server validates reserve ammo and
        // whether a reload is already in progress or the magazine is full.
        while (CyberdeckClient.RELOAD_KEY.consumeClick()) {
            if (mc.player.getMainHandItem().getItem() instanceof com.example.cyberdeck.weapon.GunItem) {
                ClientPacketDistributor.sendToServer(new com.example.cyberdeck.network.ReloadPacket());
            }
        }

        // Stealth takedown: F is reserved only by full quickhacking, not by the read-only scanner.
        while (CyberdeckClient.STEALTH_TAKEDOWN_KEY.consumeClick()) {
            if (QuickhackScannerClient.isQuickhacking()) {
                continue;
            }
            com.example.cyberdeck.faction.FactionEnemy takedownTarget =
                    com.example.cyberdeck.faction.CrouchCombat.findStealthTakedownTarget(mc.player);
            if (takedownTarget != null) {
                ClientPacketDistributor.sendToServer(
                        new com.example.cyberdeck.network.StealthTakedownPacket(takedownTarget.getId()));
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
        if (!QuickhackScannerClient.isQuickhacking()) {
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
                || layer.equals(VanillaGuiLayers.TAB_LIST)
                || isScannerSuppressedLayer(layer)) {
            event.setCanceled(true);
        }
    }

    private static boolean isScannerSuppressedLayer(Identifier layer) {
        return layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "city_minimap"))
                || layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "mission_tracker"))
                || layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "wanted_hud"))
                || layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "ammo_hud"))
                || layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "healing_hud"))
                || layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "smart_lock"))
                || layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "detection_meter"))
                || layer.equals(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "stealth_takedown"));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        quickhackUseLatched = false;
        QuickhackScannerClient.reset();
        QuickhackUploadClient.set(com.example.cyberdeck.network.QuickhackUploadPacket.NONE);
        HealingConsumableClient.reset();
        CityMapNavigationClient.reset();
        MissionTrackerClient.reset();
        GigJournalClient.reset();
    }

    private static boolean queueSelectedQuickhack(Minecraft minecraft) {
        if (!QuickhackScannerClient.isQuickhacking()) {
            return false;
        }
        LivingEntity target = QuickhackScannerClient.actionTarget(minecraft.level);
        if (target == null) {
            return false;
        }
        ClientPacketDistributor.sendToServer(new ActivateSkillPacket(
                QuickhackScannerClient.selectedSkillOrdinal(), target.getId()));
        return true;
    }
}
