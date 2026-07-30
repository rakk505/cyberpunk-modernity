package dev.modernity.neoncity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Local weather rhythms for districts whose climate should not affect the whole world. */
final class DistrictAtmosphere {
    static final long WINTER_CYCLE_TICKS = 3_600L;
    static final long GENTLE_SNOW_TICKS = 2_400L;

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

    static WinterWeather winterWeather(long gameTime) {
        long cycleTick = Math.floorMod(gameTime, WINTER_CYCLE_TICKS);
        return cycleTick < GENTLE_SNOW_TICKS
                ? WinterWeather.GENTLE
                : WinterWeather.SNOWSTORM;
    }

    static void tickPlayer(ServerLevel level, ServerPlayer player, District district) {
        if (district == District.Y_CORP) {
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
