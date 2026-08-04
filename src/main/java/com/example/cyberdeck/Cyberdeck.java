package com.example.cyberdeck;

import com.example.cyberdeck.advertising.AdvertisingContent;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.defense.DefenseContent;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.city.CityActorJoinCompatibility;
import com.example.cyberdeck.city.CityLootBlocks;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.CyberpsychoEntity;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSpawns;
import com.example.cyberdeck.healing.HealingState;
import com.example.cyberdeck.healing.HealingSystem;
import com.example.cyberdeck.economy.MoneyShardComponents;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.network.CyberdeckNetwork;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.CityNpcSpawns;
import com.example.cyberdeck.lifepath.LifepathEvents;
import com.example.cyberdeck.lifepath.LifepathGameTests;
import com.example.cyberdeck.lifepath.LifepathState;
import com.example.cyberdeck.player.StreetCredState;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.ReloadState;
import com.example.cyberdeck.weapon.SmartLockState;
import com.example.cyberdeck.weapon.WeaponComponents;
import com.example.cyberdeck.weapon.WeaponEntities;
import com.example.cyberdeck.weapon.WeaponItems;
import com.example.cyberdeck.weapon.WeaponSounds;
import com.example.cyberdeck.wanted.WantedState;
import com.example.cyberdeck.wanted.WantedGameTests;
import com.example.cyberdeck.wanted.WantedSystem;
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
        WantedState.ATTACHMENT_TYPES.register(modEventBus);
        LifepathState.ATTACHMENT_TYPES.register(modEventBus);
        DefenseContent.register(modEventBus);
        AdvertisingContent.register(modEventBus);

        // Guns, ammo, grenades, ballistic armor and their entities.
        WeaponItems.ITEMS.register(modEventBus);
        WeaponSounds.SOUND_EVENTS.register(modEventBus);
        AmmoItems.ITEMS.register(modEventBus);
        WeaponComponents.COMPONENTS.register(modEventBus);
        MoneyShardComponents.COMPONENTS.register(modEventBus);
        ReloadState.ATTACHMENT_TYPES.register(modEventBus);
        SmartLockState.ATTACHMENT_TYPES.register(modEventBus);
        WeaponEntities.ENTITY_TYPES.register(modEventBus);
        FactionEntities.ENTITY_TYPES.register(modEventBus);
        CityNpcEntities.ENTITY_TYPES.register(modEventBus);
        CyberdeckGameTests.bootstrap(modEventBus);
        WantedGameTests.bootstrap(modEventBus);
        LifepathGameTests.bootstrap(modEventBus);
        QuicktimeBlocks.register(modEventBus);
        MissionBlocks.register(modEventBus);
        ProjectMoonCityModule.bootstrap(modEventBus);
        CityLootBlocks.register(modEventBus);

        modEventBus.addListener(CyberdeckNetwork::register);
        modEventBus.addListener(CyberdeckItems::addCreative);
        modEventBus.addListener(CyberwareItems::addToTab);
        modEventBus.addListener(WeaponItems::addToTab);
        modEventBus.addListener(DefenseContent::addToTab);
        modEventBus.addListener(AdvertisingContent::addToTab);
        modEventBus.addListener(DefenseContent::registerTests);
        modEventBus.addListener(Cyberdeck::registerEntityAttributes);

        NeoForge.EVENT_BUS.register(new ServerEvents());
        NeoForge.EVENT_BUS.addListener(CyberdeckCommands::register);
        NeoForge.EVENT_BUS.register(new TacticalMovement());
        NeoForge.EVENT_BUS.addListener(HealingSystem::onPlayerTick);
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.effect.CyberwareTickHandler());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.effect.CyberwareCombatHandler());
        NeoForge.EVENT_BUS.register(new FactionSpawns());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.combat.ThrowableDistraction());
        NeoForge.EVENT_BUS.register(new CityNpcSpawns());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.trauma.TraumaTeamEvents());
        NeoForge.EVENT_BUS.register(new WantedSystem());
        NeoForge.EVENT_BUS.register(new LifepathEvents());
        NeoForge.EVENT_BUS.register(new CityActorJoinCompatibility());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.city.CityBuilder());
    }

    private static void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(FactionEntities.FACTION_ENEMY.get(), FactionEnemy.createAttributes().build());
        event.put(FactionEntities.CYBERPSYCHO.get(), CyberpsychoEntity.createAttributes().build());
        event.put(CityNpcEntities.CITY_NPC.get(), CityNpc.createAttributes().build());
        event.put(DefenseContent.KANG_TAO_TURRET.get(), KangTaoTurret.createAttributes().build());
    }
}
