package com.example.cyberdeck.defense;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckItems;

/** Registers the deployable Kang Tao turret and explosive canister. */
public final class DefenseContent {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Cyberdeck.MODID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Cyberdeck.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Cyberdeck.MODID);
    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Cyberdeck.MODID);

    public static final DeferredBlock<ExplosiveCanisterBlock> EXPLOSIVE_CANISTER =
            BLOCKS.registerBlock("explosive_canister", ExplosiveCanisterBlock::new,
                    properties -> properties
                            .mapColor(MapColor.COLOR_RED)
                            .strength(1.2F)
                            .sound(SoundType.DECORATED_POT)
                            .noOcclusion());

    public static final DeferredHolder<EntityType<?>, EntityType<KangTaoTurret>> KANG_TAO_TURRET =
            ENTITY_TYPES.register("kang_tao_turret", () -> EntityType.Builder
                    .<KangTaoTurret>of(KangTaoTurret::new, MobCategory.MISC)
                    .sized(1.35F, 2.35F)
                    .eyeHeight(2.0F)
                    .clientTrackingRange(12)
                    .updateInterval(1)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "kang_tao_turret"))));

    public static final DeferredItem<BlockItem> EXPLOSIVE_CANISTER_ITEM =
            ITEMS.registerSimpleBlockItem("explosive_canister", EXPLOSIVE_CANISTER);
    public static final DeferredItem<KangTaoTurretItem> KANG_TAO_TURRET_ITEM =
            ITEMS.registerItem("kang_tao_turret",
                    properties -> new KangTaoTurretItem(properties.stacksTo(16)));

    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> TURRET_ARC_TEST =
            TEST_FUNCTIONS.register("turret_arc", () -> DefenseGameTests::turretArc);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> TURRET_DESTRUCTION_TEST =
            TEST_FUNCTIONS.register("turret_destruction", () -> DefenseGameTests::turretDestruction);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CANISTER_EXPLOSION_TEST =
            TEST_FUNCTIONS.register("canister_explosion", () -> DefenseGameTests::canisterExplosion);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CANISTER_CHAIN_REACTION_TEST =
            TEST_FUNCTIONS.register(
                    "canister_chain_reaction", () -> DefenseGameTests::canisterChainReaction);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> TURRET_PLACEMENT_TEST =
            TEST_FUNCTIONS.register("turret_placement", () -> DefenseGameTests::turretPlacement);

    private DefenseContent() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        TEST_FUNCTIONS.register(modEventBus);
    }

    public static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CyberdeckItems.CYBERDECK_TAB.getKey()) {
            event.accept(KANG_TAO_TURRET_ITEM.get());
            event.accept(EXPLOSIVE_CANISTER_ITEM.get());
        }
    }

    public static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "defense"),
                new TestEnvironmentDefinition.AllOf(List.of()));
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("minecraft", "empty"),
                100,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                false,
                4);

        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "turret_arc"),
                new FunctionGameTestInstance(TURRET_ARC_TEST.getKey(), data));
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "turret_destruction"),
                new FunctionGameTestInstance(TURRET_DESTRUCTION_TEST.getKey(), data));
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "canister_explosion"),
                new FunctionGameTestInstance(CANISTER_EXPLOSION_TEST.getKey(), data));
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "canister_chain_reaction"),
                new FunctionGameTestInstance(CANISTER_CHAIN_REACTION_TEST.getKey(), data));
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "turret_placement"),
                new FunctionGameTestInstance(TURRET_PLACEMENT_TEST.getKey(), data));
    }
}
