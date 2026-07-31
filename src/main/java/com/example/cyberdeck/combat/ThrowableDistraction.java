package com.example.cyberdeck.combat;

import com.example.cyberdeck.faction.FactionEnemy;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.List;

/**
 * Makes any throwable the player lobs briefly distract nearby corporation soldiers. When a {@link
 * ThrowableItemProjectile} (cyberdeck grenades and every other vanilla/mod throwable) enters the
 * world, every {@link FactionEnemy} within {@link #DISTRACTION_RADIUS} has its gaze drawn to the
 * item for {@link #DISTRACTION_TICKS} ticks.
 *
 * <p>This is a look-only nudge: it never changes an enemy's combat target or goals, so it stays
 * clear of the faction combat AI. Registered on the {@code NeoForge.EVENT_BUS}.
 */
public final class ThrowableDistraction {
    /** How far (blocks) a landed/spawned throwable is noticed by soldiers. */
    private static final double DISTRACTION_RADIUS = 16.0;
    /** How long (ticks) a soldier's attention is drawn to the throwable. */
    private static final int DISTRACTION_TICKS = 60;

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ThrowableItemProjectile throwable)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Vec3 itemPos = throwable.position();
        Entity thrower = throwable.getOwner();
        AABB area = new AABB(itemPos, itemPos).inflate(DISTRACTION_RADIUS);
        List<FactionEnemy> nearby = level.getEntitiesOfClass(FactionEnemy.class, area,
                e -> e.isAlive() && e != thrower
                        && e.distanceToSqr(itemPos) <= DISTRACTION_RADIUS * DISTRACTION_RADIUS);
        for (FactionEnemy soldier : nearby) {
            soldier.distractTo(itemPos, DISTRACTION_TICKS);
        }
    }
}
