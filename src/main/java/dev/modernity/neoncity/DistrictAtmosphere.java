package dev.modernity.neoncity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Local weather rhythms for districts whose climate should not affect the whole world. */
public final class DistrictAtmosphere {
    static final long WINTER_CYCLE_TICKS = 3_600L;
    static final long GENTLE_SNOW_TICKS = 2_400L;

    /** Data-only fog settings shared with the client renderer and server-side GameTests. */
    public enum FogProfile {
        NONE(0.0F, 0.0F, 0.0F, Float.POSITIVE_INFINITY, 0.0F, 0.08F),
        DENSE(0.58F, 0.64F, 0.66F, 42.0F, 0.05F, 0.07F),
        SMOG(0.31F, 0.32F, 0.32F, 92.0F, 0.035F, 0.05F);

        private final float red;
        private final float green;
        private final float blue;
        private final float farPlane;
        private final float fadeIn;
        private final float fadeOut;

        FogProfile(
                float red,
                float green,
                float blue,
                float farPlane,
                float fadeIn,
                float fadeOut) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.farPlane = farPlane;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
        }

        public float red() { return red; }
        public float green() { return green; }
        public float blue() { return blue; }
        public float farPlane() { return farPlane; }
        public float fadeIn() { return fadeIn; }
        public float fadeOut() { return fadeOut; }
    }

    enum WinterWeather {
        GENTLE(14, 8.0, 5.0, 0.008),
        SNOWSTORM(58, 14.0, 9.0, 0.035);

        private final int particleCount;
        private final double horizontalSpread;
        private final double verticalSpread;
        private final double speed;

        WinterWeather(
                int particleCount,
                double horizontalSpread,
                double verticalSpread,
                double speed) {
            this.particleCount = particleCount;
            this.horizontalSpread = horizontalSpread;
            this.verticalSpread = verticalSpread;
            this.speed = speed;
        }

        int particleCount() {
            return particleCount;
        }
    }

    private DistrictAtmosphere() {
    }

    public static FogProfile fogProfile(District district) {
        if (district == District.D_CORP) {
            return FogProfile.DENSE;
        }
        if (district == District.T_CORP) {
            return FogProfile.SMOG;
        }
        return FogProfile.NONE;
    }

    static WinterWeather winterWeather(long gameTime) {
        long cycleTick = Math.floorMod(gameTime, WINTER_CYCLE_TICKS);
        return cycleTick < GENTLE_SNOW_TICKS
                ? WinterWeather.GENTLE
                : WinterWeather.SNOWSTORM;
    }

    static void tickPlayer(ServerLevel level, ServerPlayer player, District district) {
        if (district.isSharedWinter()) {
            sendSnow(level, player, winterWeather(level.getGameTime()));
        } else if (district == District.T_CORP) {
            sendPollution(level, player);
        }
    }

    private static void sendSnow(
            ServerLevel level,
            ServerPlayer player,
            WinterWeather weather) {
        level.sendParticles(
                player,
                ParticleTypes.SNOWFLAKE,
                false,
                false,
                player.getX(),
                player.getY() + (weather == WinterWeather.SNOWSTORM ? 12.0 : 8.0),
                player.getZ(),
                weather.particleCount,
                weather.horizontalSpread,
                weather.verticalSpread,
                weather.horizontalSpread,
                weather.speed);
    }

    private static void sendPollution(ServerLevel level, ServerPlayer player) {
        level.sendParticles(
                player,
                ParticleTypes.ASH,
                false,
                false,
                player.getX(),
                player.getY() + 9.0,
                player.getZ(),
                22,
                11.0,
                7.0,
                11.0,
                0.004);
    }
}
