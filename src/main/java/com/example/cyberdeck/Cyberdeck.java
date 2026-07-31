package com.example.cyberdeck;

import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.city.CityActorJoinCompatibility;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.CyberpsychoEntity;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSpawns;
import com.example.cyberdeck.healing.HealingState;
import com.example.cyberdeck.healing.HealingSystem;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.network.CyberdeckNetwork;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.CityNpcSpawns;
import com.example.cyberdeck.player.StreetCredState;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.ReloadState;
import com.example.cyberdeck.weapon.SmartLockState;
import com.example.cyberdeck.weapon.WeaponComponents;
import com.example.cyberdeck.weapon.WeaponEntities;
import com.example.cyberdeck.weapon.WeaponItems;
import com.mojang.logging.LogUtils;
import dev.modernity.neoncity.ProjectMoonCityModule;
import dev.modernity.neoncity.MissionBlocks;
import dev.modernity.neoncity.QuicktimeBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import org.slf4j.Logger;

@Mod(Cyberdeck.MODID)
public class Cyberdeck {
    public static final String MODID = "cyberdeck";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Cyberdeck(IEventBus modEventBus, ModContainer modContainer) {
        CyberdeckItems.ITEMS.register(modEventBus);
        QuickhackItems.ITEMS.register(modEventBus);
        CyberdeckItems.CREATIVE_MODE_TABS.register(modEventBus);
        CyberwareItems.CYBERWARE_ITEMS.register(modEventBus);
        CyberwareAttachments.ATTACHMENT_TYPES.register(modEventBus);
        RamAttachments.ATTACHMENT_TYPES.register(modEventBus);
        QuickhackAttachments.ATTACHMENT_TYPES.register(modEventBus);
        TacticalMovement.ATTACHMENT_TYPES.register(modEventBus);
        HealingState.ATTACHMENT_TYPES.register(modEventBus);
        StreetCredState.ATTACHMENT_TYPES.register(modEventBus);

        // Guns, ammo, grenades, ballistic armor and their entities.
        WeaponItems.ITEMS.register(modEventBus);
        AmmoItems.ITEMS.register(modEventBus);
        WeaponComponents.COMPONENTS.register(modEventBus);
        ReloadState.ATTACHMENT_TYPES.register(modEventBus);
        SmartLockState.ATTACHMENT_TYPES.register(modEventBus);
        WeaponEntities.ENTITY_TYPES.register(modEventBus);
        FactionEntities.ENTITY_TYPES.register(modEventBus);
        CityNpcEntities.ENTITY_TYPES.register(modEventBus);
        CyberdeckGameTests.bootstrap(modEventBus);
        QuicktimeBlocks.register(modEventBus);
        MissionBlocks.register(modEventBus);
        ProjectMoonCityModule.bootstrap(modEventBus);

        modEventBus.addListener(CyberdeckNetwork::register);
        modEventBus.addListener(CyberdeckItems::addCreative);
        modEventBus.addListener(CyberwareItems::addToTab);
        modEventBus.addListener(WeaponItems::addToTab);
        modEventBus.addListener(Cyberdeck::registerEntityAttributes);

        NeoForge.EVENT_BUS.register(new ServerEvents());
        NeoForge.EVENT_BUS.addListener(CyberdeckCommands::register);
        NeoForge.EVENT_BUS.register(new TacticalMovement());
        NeoForge.EVENT_BUS.addListener(HealingSystem::onPlayerTick);
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.effect.CyberwareTickHandler());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.effect.CyberwareCombatHandler());
        NeoForge.EVENT_BUS.register(new FactionSpawns());
        NeoForge.EVENT_BUS.register(new CityNpcSpawns());
        NeoForge.EVENT_BUS.register(new CityActorJoinCompatibility());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.city.CityBuilder());
    }

    private static void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(FactionEntities.FACTION_ENEMY.get(), FactionEnemy.createAttributes().build());
        event.put(FactionEntities.CYBERPSYCHO.get(), CyberpsychoEntity.createAttributes().build());
        event.put(CityNpcEntities.CITY_NPC.get(), CityNpc.createAttributes().build());
    }
}
