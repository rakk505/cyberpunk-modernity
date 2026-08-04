package com.example.cyberdeck.client.advertising;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.advertising.FreestandingAdType;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Render-thread snapshot for one large advertising display. */
public final class AdDisplayRenderState extends BlockEntityRenderState {
    public boolean renderable = true;
    public Direction facing = Direction.NORTH;
    public @Nullable FreestandingAdType freestandingType;
    public Direction.Axis longAxis = Direction.Axis.X;
    public Identifier texture = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "textures/ads/frame.png");
    public float u0;
    public float v0;
    public float u1 = 1.0F;
    public float v1 = 1.0F;
    public int width = 8;
    public int height = 4;
}
