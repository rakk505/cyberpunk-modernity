package com.example.cyberdeck.city;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.npc.CityNpc;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Keeps Cyberdeck's managed city actors compatible with city generators that suppress ambient
 * mob joins. Neon City 2.0 cancels every {@code Mob} join inside its generated bounds, including
 * civilians and faction squads explicitly created by Cyberdeck. This lowest-priority listener
 * restores only Cyberdeck-managed actor families and leaves every unrelated cancellation untouched.
 */
public final class CityActorJoinCompatibility {
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.isCanceled()
                || !isManagedCityActor(event.getEntity())
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        restoreManagedCityActor(event,
                CityWorlds.kind(level) == CityWorlds.Kind.NEON_MEGACITY);
    }

    /** Purely parameterized compatibility seam used by the regression suite. */
    public static void restoreManagedCityActor(EntityJoinLevelEvent event, boolean supportedCity) {
        if (event.isCanceled() && supportedCity && isManagedCityActor(event.getEntity())) {
            event.setCanceled(false);
            Cyberdeck.LOGGER.debug("Restored canceled city actor join for {}",
                    event.getEntity().getType());
        }
    }

    public static boolean isManagedCityActor(Entity entity) {
        return entity instanceof CityNpc
                || entity instanceof FactionEnemy
                || entity instanceof KangTaoTurret;
    }
}
