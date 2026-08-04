package com.example.cyberdeck.client.advertising;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/** Render-thread snapshot for one large advertising display. */
public final class AdDisplayRenderState extends BlockEntityRenderState {
    public Direction facing = Direction.NORTH;
    public Identifier texture = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "textures/ads/frame.png");
    public float u0;
    public float v0;
    public float u1 = 1.0F;
    public float v1 = 1.0F;
}
