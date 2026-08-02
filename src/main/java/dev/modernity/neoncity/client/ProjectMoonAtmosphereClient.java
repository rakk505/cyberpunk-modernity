package dev.modernity.neoncity.client;

import com.example.cyberdeck.Cyberdeck;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.DistrictAtmosphere;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Smoothly applies district-local fog without changing the rest of the city's sky. */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class ProjectMoonAtmosphereClient {
    private static int districtOrdinal = -1;
    private static float smogStrength;
    private static float denseFogStrength;

    private ProjectMoonAtmosphereClient() {
    }

    public static void setDistrict(int ordinal) {
        districtOrdinal = ordinal >= 0 && ordinal < District.values().length
                ? ordinal
                : -1;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post ignoredEvent) {
        DistrictAtmosphere.FogProfile target = districtOrdinal >= 0
                ? DistrictAtmosphere.fogProfile(District.values()[districtOrdinal])
                : DistrictAtmosphere.FogProfile.NONE;
        smogStrength = approach(
                smogStrength,
                target == DistrictAtmosphere.FogProfile.SMOG ? 1.0F : 0.0F,
                DistrictAtmosphere.FogProfile.SMOG);
        denseFogStrength = approach(
                denseFogStrength,
                target == DistrictAtmosphere.FogProfile.DENSE ? 1.0F : 0.0F,
                DistrictAtmosphere.FogProfile.DENSE);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (smogStrength <= 0.0F && denseFogStrength <= 0.0F) {
            return;
        }
        event.setRed(blendColor(
                event.getRed(),
                DistrictAtmosphere.FogProfile.SMOG.red(),
                DistrictAtmosphere.FogProfile.DENSE.red()));
        event.setGreen(blendColor(
                event.getGreen(),
                DistrictAtmosphere.FogProfile.SMOG.green(),
                DistrictAtmosphere.FogProfile.DENSE.green()));
        event.setBlue(blendColor(
                event.getBlue(),
                DistrictAtmosphere.FogProfile.SMOG.blue(),
                DistrictAtmosphere.FogProfile.DENSE.blue()));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (smogStrength <= 0.0F && denseFogStrength <= 0.0F) {
            return;
        }
        float farPlane = blendFarPlane(
                event.getFarPlaneDistance(),
                DistrictAtmosphere.FogProfile.SMOG,
                smogStrength);
        farPlane = blendFarPlane(
                farPlane,
                DistrictAtmosphere.FogProfile.DENSE,
                denseFogStrength);
        event.setFarPlaneDistance(farPlane);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut ignoredEvent) {
        districtOrdinal = -1;
        smogStrength = 0.0F;
        denseFogStrength = 0.0F;
    }

    private static float approach(
            float current,
            float target,
            DistrictAtmosphere.FogProfile profile) {
        float step = target > current ? profile.fadeIn() : profile.fadeOut();
        return Mth.clamp(
                current + Math.copySign(step, target - current),
                Math.min(current, target),
                Math.max(current, target));
    }

    private static float blendColor(float base, float smog, float dense) {
        return Mth.lerp(denseFogStrength, Mth.lerp(smogStrength, base, smog), dense);
    }

    private static float blendFarPlane(
            float current,
            DistrictAtmosphere.FogProfile profile,
            float strength) {
        return Mth.lerp(strength, current, Math.min(current, profile.farPlane()));
    }

    static float smogStrength() {
        return smogStrength;
    }

    static float denseFogStrength() {
        return denseFogStrength;
    }
}
