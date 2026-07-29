package com.example.cyberdeck;

import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSpawns;
import com.example.cyberdeck.network.CyberdeckNetwork;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.ReloadState;
import com.example.cyberdeck.weapon.SmartLockState;
import com.example.cyberdeck.weapon.WeaponComponents;
import com.example.cyberdeck.weapon.WeaponEntities;
import com.example.cyberdeck.weapon.WeaponItems;
import com.mojang.logging.LogUtils;
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

        // Guns, ammo, grenades, ballistic armor and their entities.
        WeaponItems.ITEMS.register(modEventBus);
        AmmoItems.ITEMS.register(modEventBus);
        WeaponComponents.COMPONENTS.register(modEventBus);
        ReloadState.ATTACHMENT_TYPES.register(modEventBus);
        SmartLockState.ATTACHMENT_TYPES.register(modEventBus);
        WeaponEntities.ENTITY_TYPES.register(modEventBus);
        FactionEntities.ENTITY_TYPES.register(modEventBus);

        modEventBus.addListener(CyberdeckNetwork::register);
        modEventBus.addListener(CyberdeckItems::addCreative);
        modEventBus.addListener(CyberwareItems::addToTab);
        modEventBus.addListener(WeaponItems::addToTab);
        modEventBus.addListener(Cyberdeck::registerEntityAttributes);

        NeoForge.EVENT_BUS.register(new ServerEvents());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.effect.CyberwareTickHandler());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.effect.CyberwareCombatHandler());
        NeoForge.EVENT_BUS.register(new FactionSpawns());
        NeoForge.EVENT_BUS.register(new com.example.cyberdeck.city.CityBuilder());
    }

    private static void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(FactionEntities.FACTION_ENEMY.get(), FactionEnemy.createAttributes().build());
    }
}
