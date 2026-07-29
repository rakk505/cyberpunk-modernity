package com.example.cyberdeck.npc;

import com.example.cyberdeck.weapon.GunType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One server-side civilian alert per trigger pull, independent of pellet count. */
public final class GunshotAlerts {
    private GunshotAlerts() {
    }

    public static void emit(ServerLevel level, LivingEntity shooter, GunType gun) {
        double radius = hearingRadius(gun);
        if (radius <= 0.0) {
            return;
        }
        Vec3 source = shooter.position();
        AABB area = new AABB(source, source).inflate(radius);
        for (CityNpc npc : level.getEntitiesOfClass(CityNpc.class, area,
                npc -> npc.isAlive() && npc.distanceToSqr(source) <= radius * radius)) {
            npc.hearGunshot(level, source);
        }
    }

    public static double hearingRadius(GunType gun) {
        return switch (gun) {
            case MANTIS_BLADE -> 0.0;
            case SHOTGUN, M2038, CARNAGE, SNIPER, GRAD -> 64.0;
            case ASSAULT_RIFLE, AJAX, COPPERHEAD, THREE_FIVE_ONE_SIX -> 48.0;
            default -> 36.0;
        };
    }
}
