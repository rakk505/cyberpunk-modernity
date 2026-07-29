package com.example.cyberdeck.client;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.hud.AmmoHudOverlay;
import com.example.cyberdeck.client.hud.QuickhackScannerOverlay;
import com.example.cyberdeck.client.hud.SmartLockOverlay;
import com.example.cyberdeck.client.gun.GenericGunClientExtension;
import com.example.cyberdeck.client.movement.TacticalPlayerAnimations;
import com.example.cyberdeck.client.render.FactionEnemyRenderer;
import com.example.cyberdeck.client.render.CityNpcRenderer;
import com.example.cyberdeck.client.render.MantisBladesLayer;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.weapon.WeaponEntities;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import com.google.common.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import org.lwjgl.glfw.GLFW;

@Mod(value = Cyberdeck.MODID, dist = Dist.CLIENT)
public final class CyberdeckClient {
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "main"));

    // Bound to TAB by default. Toggles the scanner when a cyberdeck OS is installed.
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
            "key.cyberdeck.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_TAB,
            CATEGORY);

    // Opens the cyberware management screen (default G).
    public static final KeyMapping OPEN_CYBERWARE_KEY = new KeyMapping(
            "key.cyberdeck.open_cyberware",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    // Sandevistan (default T).
    public static final KeyMapping SANDEVISTAN_KEY = new KeyMapping(
            "key.cyberdeck.sandevistan",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_T,
            CATEGORY);

    // Arm Cannon (default V).
    public static final KeyMapping ARM_CANNON_KEY = new KeyMapping(
            "key.cyberdeck.arm_cannon",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    // Thretevac (default P).
    public static final KeyMapping THRETEVAC_KEY = new KeyMapping(
            "key.cyberdeck.thretevac",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            CATEGORY);

    // Optical Camo (default U).
    public static final KeyMapping OPTICAL_CAMO_KEY = new KeyMapping(
            "key.cyberdeck.optical_camo",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            CATEGORY);

    // Reload the held gun (default R).
    public static final KeyMapping RELOAD_KEY = new KeyMapping(
            "key.cyberdeck.reload",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY);

    // Scanner navigation mirrors Cyberpunk's keyboard layout.
    public static final KeyMapping PREVIOUS_QUICKHACK_KEY = new KeyMapping(
            "key.cyberdeck.quickhack_previous",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_1,
            CATEGORY);

    public static final KeyMapping NEXT_QUICKHACK_KEY = new KeyMapping(
            "key.cyberdeck.quickhack_next",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_3,
            CATEGORY);

    public static final KeyMapping QUEUE_QUICKHACK_KEY = new KeyMapping(
            "key.cyberdeck.quickhack_queue",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            CATEGORY);

    // Cyberpunk combat movement: a short directional burst and a momentum-preserving low slide.
    public static final KeyMapping DASH_KEY = new KeyMapping(
            "key.cyberdeck.dash",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            CATEGORY);
    public static final KeyMapping SLIDE_KEY = new KeyMapping(
            "key.cyberdeck.slide",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            CATEGORY);

    public CyberdeckClient(IEventBus modEventBus) {
        modEventBus.addListener(this::registerKeyMappings);
        modEventBus.addListener(this::addLayers);
        modEventBus.addListener(this::registerGuiLayers);
        modEventBus.addListener(this::registerRenderers);
        modEventBus.addListener(this::registerClientExtensions);
        modEventBus.addListener(this::registerRenderStateModifiers);
        modEventBus.addListener(TacticalPlayerAnimations::registerRenderStateModifiers);
    }

    private void registerClientExtensions(RegisterClientExtensionsEvent event) {
        for (GunType gun : GunType.values()) {
            event.registerItem(GenericGunClientExtension.INSTANCE, WeaponItems.gun(gun).get());
        }
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Faction soldiers render as Steve-skinned humanoids; their identity shows through their
        // faction armor.
        event.registerEntityRenderer(FactionEntities.FACTION_ENEMY.get(), FactionEnemyRenderer::new);
        event.registerEntityRenderer(CityNpcEntities.CITY_NPC.get(), CityNpcRenderer::new);
        event.registerEntityRenderer(WeaponEntities.THROWN_GRENADE.get(), ThrownItemRenderer::new);
        event.registerEntityRenderer(WeaponEntities.SMART_BULLET.get(), NoopRenderer::new);
    }

    private void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "ammo_hud"),
                new AmmoHudOverlay());
        event.registerAbove(VanillaGuiLayers.CROSSHAIR,
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "smart_lock"),
                new SmartLockOverlay());
        event.registerAbove(VanillaGuiLayers.EFFECTS,
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "quickhack_scanner"),
                new QuickhackScannerOverlay());
    }

    /** Adds the scanner's orange silhouette to only the entity under the reticle. */
    private void registerRenderStateModifiers(RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(
                new TypeToken<LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?>>() {},
                (entity, state) -> {
                    if (QuickhackScannerClient.isActive()
                            && entity.getId() == QuickhackScannerClient.targetId()) {
                        state.outlineColor = 0xFFFF653C;
                        state.hasRedOverlay = true;
                    }
                });
    }

    private void addLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerModelType skin : event.getSkins()) {
            AvatarRenderer<AbstractClientPlayer> renderer = event.getPlayerRenderer(skin);
            if (renderer != null) {
                renderer.addLayer(new MantisBladesLayer(renderer));
            }
        }
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(TOGGLE_KEY);
        event.register(OPEN_CYBERWARE_KEY);
        event.register(SANDEVISTAN_KEY);
        event.register(ARM_CANNON_KEY);
        event.register(THRETEVAC_KEY);
        event.register(OPTICAL_CAMO_KEY);
        event.register(RELOAD_KEY);
        event.register(PREVIOUS_QUICKHACK_KEY);
        event.register(NEXT_QUICKHACK_KEY);
        event.register(QUEUE_QUICKHACK_KEY);
        event.register(DASH_KEY);
        event.register(SLIDE_KEY);
    }
}
