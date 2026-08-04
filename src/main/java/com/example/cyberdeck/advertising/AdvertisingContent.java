package com.example.cyberdeck.advertising;

import java.util.EnumMap;
import java.util.Map;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckItems;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registry owner for animated facade displays and generated street-ad structures. */
public final class AdvertisingContent {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Cyberdeck.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Cyberdeck.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cyberdeck.MODID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Cyberdeck.MODID);

    public static final DeferredBlock<AdDisplayBlock> AD_DISPLAY_ANCHOR =
            BLOCKS.registerBlock("large_ad_display_anchor", AdDisplayBlock::new,
                    AdvertisingContent::displayProperties);
    public static final DeferredBlock<AdPanelBlock> AD_DISPLAY_PANEL =
            BLOCKS.registerBlock("large_ad_display_panel", AdPanelBlock::new,
                    AdvertisingContent::displayProperties);
    public static final DeferredBlock<FreestandingAdBlock> FREESTANDING_AD_CONTROLLER =
            BLOCKS.registerBlock("freestanding_ad_controller", FreestandingAdBlock::new,
                    AdvertisingContent::freestandingProperties);
    public static final DeferredBlock<Block> FREESTANDING_AD_FRAME =
            BLOCKS.registerBlock("freestanding_ad_frame", Block::new,
                    AdvertisingContent::freestandingProperties);
    public static final DeferredItem<LargeAdDisplayItem> LARGE_AD_DISPLAY =
            ITEMS.registerItem("large_ad_display",
                    properties -> new LargeAdDisplayItem(properties.stacksTo(4)));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AdDisplayBlockEntity>>
            AD_DISPLAY_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "large_ad_display",
                    () -> new BlockEntityType<>(
                            AdDisplayBlockEntity::new,
                            AD_DISPLAY_ANCHOR.get(), FREESTANDING_AD_CONTROLLER.get()));

    private static final Map<AdClip, DeferredHolder<SoundEvent, SoundEvent>> CLIP_SOUNDS =
            registerClipSounds();

    private AdvertisingContent() {
    }

    private static BlockBehaviour.Properties displayProperties(
            BlockBehaviour.Properties properties) {
        return properties
                .mapColor(MapColor.COLOR_CYAN)
                .strength(-1.0F, 3_600_000.0F)
                .sound(SoundType.METAL)
                .lightLevel(state -> 15)
                .noCollision()
                .noOcclusion()
                .noLootTable()
                .pushReaction(PushReaction.BLOCK);
    }

    private static BlockBehaviour.Properties freestandingProperties(
            BlockBehaviour.Properties properties) {
        return properties
                .mapColor(MapColor.COLOR_BLACK)
                .strength(-1.0F, 3_600_000.0F)
                .sound(SoundType.METAL)
                .lightLevel(state -> 8)
                .noLootTable()
                .pushReaction(PushReaction.BLOCK);
    }

    private static Map<AdClip, DeferredHolder<SoundEvent, SoundEvent>> registerClipSounds() {
        Map<AdClip, DeferredHolder<SoundEvent, SoundEvent>> sounds =
                new EnumMap<>(AdClip.class);
        for (AdClip clip : AdClip.values()) {
            String name = "ad." + clip.id();
            Identifier id = Identifier.fromNamespaceAndPath(Cyberdeck.MODID, name);
            sounds.put(clip, SOUND_EVENTS.register(name,
                    () -> SoundEvent.createFixedRangeEvent(id, 64.0F)));
        }
        return Map.copyOf(sounds);
    }

    public static SoundEvent sound(AdClip clip) {
        return CLIP_SOUNDS.get(clip).get();
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
        BLOCK_ENTITY_TYPES.register(eventBus);
        SOUND_EVENTS.register(eventBus);
    }

    public static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CyberdeckItems.CYBERDECK_TAB.getKey()) {
            event.accept(LARGE_AD_DISPLAY.get());
        }
    }
}
