package dev.modernity.neoncity.client;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.District;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Smoothly applies T Corp's pollution haze without changing other districts' sky. */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class ProjectMoonAtmosphereClient {
    private static final float SMOG_FADE_IN = 0.035F;
    private static final float SMOG_FADE_OUT = 0.05F;
    private static int districtOrdinal = -1;
    private static float smogStrength;

    private ProjectMoonAtmosphereClient() {
    }

    public static void setDistrict(int ordinal) {
        districtOrdinal = ordinal >= 0 && ordinal < District.values().length
                ? ordinal
                : -1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post ignoredEvent) {
        float target = districtOrdinal == District.T_CORP.ordinal() ? 1.0F : 0.0F;
        float step = target > smogStrength ? SMOG_FADE_IN : SMOG_FADE_OUT;
        smogStrength = Mth.clamp(
                smogStrength + Math.copySign(step, target - smogStrength),
                Math.min(smogStrength, target),
                Math.max(smogStrength, target));
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (smogStrength <= 0.0F) {
            return;
        }
        event.setRed(Mth.lerp(smogStrength, event.getRed(), 0.31F));
        event.setGreen(Mth.lerp(smogStrength, event.getGreen(), 0.32F));
        event.setBlue(Mth.lerp(smogStrength, event.getBlue(), 0.32F));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (smogStrength <= 0.0F) {
            return;
        }
        float targetFar = Math.min(event.getFarPlaneDistance(), 92.0F);
        event.setFarPlaneDistance(Mth.lerp(
                smogStrength, event.getFarPlaneDistance(), targetFar));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut ignoredEvent) {
        districtOrdinal = -1;
        smogStrength = 0.0F;
    }

    static float smogStrength() {
        return smogStrength;
    }
}
