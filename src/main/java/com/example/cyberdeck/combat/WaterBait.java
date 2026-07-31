package com.example.cyberdeck.combat;

import com.example.cyberdeck.faction.FactionEnemy;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.List;

/**
 * Baits nearby {@link FactionEnemy} soldiers toward the ground-impact point of a player-thrown
 * splash potion of water. The still, empty water bottle draws corporate patrols to investigate the
 * splash: every faction enemy within {@link #BAIT_RADIUS} of the impact is redirected to walk to
 * that spot, and any that are already hostile lock their attack focus onto the location.
 *
 * <p>The hook is {@link ProjectileImpactEvent} on the {@link net.neoforged.neoforge.common.NeoForge}
 * game bus, filtered to {@link AbstractThrownPotion} projectiles whose {@code POTION_CONTENTS}
 * component {@code is(Potions.WATER)} (i.e. a water splash/lingering potion with no mob effects) and
 * whose owner is a {@link Player}. All work is server-side only.
 */
public final class WaterBait {
    /** Enemies within this many blocks of the splash impact are baited toward it. */
    private static final double BAIT_RADIUS = 10.0;

    /** Pathfinding speed multiplier used when sending enemies to investigate the splash. */
    private static final double INVESTIGATE_SPEED = 1.1;

    private WaterBait() {}

    /**
     * Handles a projectile impact. Called from
     * {@link com.example.cyberdeck.ServerEvents#onProjectileImpact(ProjectileImpactEvent)}.
     */
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile instanceof AbstractThrownPotion potion)) {
            return;
        }
        if (!(potion.level() instanceof ServerLevel level)) {
            return;
        }
        // Only player-thrown potions bait enemies; ignore potions splashed by mobs/dispensers.
        if (!(potion.getOwner() instanceof Player)) {
            return;
        }
        ItemStack stack = potion.getItem();
        PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        if (!contents.is(Potions.WATER)) {
            return;
        }

        // Ground-impact location: where the bottle actually hit (block face or entity).
        HitResult hit = event.getRayTraceResult();
        Vec3 impact = hit.getLocation();

        AABB area = new AABB(impact, impact).inflate(BAIT_RADIUS);
        List<FactionEnemy> enemies = level.getEntitiesOfClass(FactionEnemy.class, area,
                e -> e.isAlive() && e.distanceToSqr(impact) <= BAIT_RADIUS * BAIT_RADIUS);

        for (FactionEnemy enemy : enemies) {
            // Walk over to investigate the splash's ground-impact point.
            enemy.getNavigation().moveTo(impact.x, impact.y, impact.z, INVESTIGATE_SPEED);
        }
    }
}
