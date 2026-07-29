package com.example.cyberdeck.client;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.network.ActivateSkillPacket;
import com.example.cyberdeck.network.CyberwareActionPacket;
import com.example.cyberdeck.network.ToggleInterfacePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Handles client input for the cyberdeck: sending the toggle packet on the key press,
 * and sending a skill-activation packet when the player uses a skill slot while looking at an entity.
 */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class CyberdeckClientEvents {

    private CyberdeckClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
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

    // Intercept the "use item" (right click) input while the interface is active and an entity is targeted.
    @SubscribeEvent
    public static void onUseInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (!isWearingCyberdeck(mc.player) || !holdingSkillBlock(mc)) {
            return;
        }
        LivingEntity target = getTargetedEntity(mc);
        if (target == null) {
            return;
        }
        int slot = mc.player.getInventory().getSelectedSlot();
        ClientPacketDistributor.sendToServer(new ActivateSkillPacket(slot, target.getId()));
        // Prevent the vanilla place/use action from also firing.
        event.setCanceled(true);
        event.setSwingHand(true);
    }

    private static boolean isWearingCyberdeck(Player player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).is(CyberdeckItems.CYBERDECK.get());
    }

    // Heuristic: the interface is active client-side when the selected hotbar item is one of our
    // custom quickhack items (plain items, so they can never be placed as blocks on the ground).
    private static boolean holdingSkillBlock(Minecraft mc) {
        return mc.player != null
                && com.example.cyberdeck.QuickhackItems.isQuickhackItem(mc.player.getMainHandItem().getItem());
    }

    // Maximum ranged targeting distance for skills.
    private static final double MAX_TARGET_RANGE = 48.0;

    /**
     * Ranged, line-of-sight targeting: casts a ray from the player's eyes along their look vector
     * up to {@link #MAX_TARGET_RANGE} blocks and returns the first living entity under the crosshair,
     * provided no solid block obstructs the line of sight.
     */
    private static LivingEntity getTargetedEntity(Minecraft mc) {
        Player player = mc.player;
        if (player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f).normalize();
        Vec3 reachEnd = eye.add(look.scale(MAX_TARGET_RANGE));

        // Find the first blocking terrain hit so entities behind walls cannot be targeted.
        BlockHitResult blockHit = player.level().clip(new ClipContext(
                eye, reachEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double reach = MAX_TARGET_RANGE;
        Vec3 losEnd = reachEnd;
        if (blockHit.getType() != HitResult.Type.MISS) {
            losEnd = blockHit.getLocation();
            reach = eye.distanceTo(losEnd);
        }

        AABB searchBox = player.getBoundingBox()
                .expandTowards(look.scale(reach))
                .inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, losEnd, searchBox,
                e -> e instanceof LivingEntity && e != player && !e.isSpectator() && e.isPickable(),
                reach * reach);

        if (entityHit != null && entityHit.getEntity() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }
}
