package dev.modernity.neoncity;

import com.example.cyberdeck.network.ClearCityWaypointPacket;
import com.example.cyberdeck.network.OpenMerchantQuestPacket;
import com.example.cyberdeck.network.SetCityWaypointPacket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server authority for the lightweight fixer delivery loop. */
public final class MerchantQuestService {
    private static final String ACTIVE = "cyberdeck_delivery_active";
    private static final String TARGET_DISTRICT = "cyberdeck_delivery_target_district";
    private static final String TARGET_X = "cyberdeck_delivery_target_x";
    private static final String TARGET_Z = "cyberdeck_delivery_target_z";
    private static final String REWARD = "cyberdeck_delivery_reward";
    private static final String CARGO = "cyberdeck_delivery_cargo";
    private static final String LOCAL = "cyberdeck_delivery_local";
    private static final int OFFER_COUNT = 5;
    private static final int LOCAL_DELIVERY_RADIUS = 96;
    private static final long QUEST_SALT = 0x4649584552515545L;
    private static final List<String> CARGOES = List.of(
            "3 Copper Ingots",
            "8 Redstone Dust",
            "12 Iron Nuggets",
            "4 Slop Packs",
            "A Sealed Optics Case",
            "A Data Shard",
            "2 Honey Bottles",
            "6 Heavy Ammo Rounds");

    private MerchantQuestService() {
    }

    public record QuestOffer(
            int targetDistrictOrdinal,
            int targetX,
            int targetZ,
            int reward,
            String cargo,
            boolean local) {
    }

    record ActiveQuest(
            District targetDistrict,
            int targetX,
            int targetZ,
            int reward,
            String cargo,
            boolean local) {
    }

    public static void open(ServerPlayer player, Entity merchant) {
        if (!isValidFixer(player, merchant)) {
            return;
        }
        District source = MerchantTruckLibrary.merchantDistrict(merchant).orElse(null);
        BlockPos anchor = MerchantTruckLibrary.merchantAnchor(merchant).orElse(null);
        if (source == null || anchor == null) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        PacketDistributor.sendToPlayer(player, new OpenMerchantQuestPacket(
                merchant.getId(), source.ordinal(), offers(level, anchor, source)));
    }

    public static boolean accept(ServerPlayer player, int merchantEntityId, int offerIndex) {
        ServerLevel level = (ServerLevel) player.level();
        Entity merchant = level.getEntity(merchantEntityId);
        if (!isValidFixer(player, merchant)) {
            return false;
        }
        District source = MerchantTruckLibrary.merchantDistrict(merchant).orElse(null);
        BlockPos anchor = MerchantTruckLibrary.merchantAnchor(merchant).orElse(null);
        if (source == null || anchor == null) {
            return false;
        }
        List<QuestOffer> available = offers(level, anchor, source);
        if (offerIndex < 0 || offerIndex >= available.size()) {
            return false;
        }
        QuestOffer offer = available.get(offerIndex);
        CompoundTag data = player.getPersistentData();
        data.putBoolean(ACTIVE, true);
        data.putInt(TARGET_DISTRICT, offer.targetDistrictOrdinal());
        data.putInt(TARGET_X, offer.targetX());
        data.putInt(TARGET_Z, offer.targetZ());
        data.putInt(REWARD, offer.reward());
        data.putString(CARGO, offer.cargo());
        data.putBoolean(LOCAL, offer.local());
        PacketDistributor.sendToPlayer(
                player, new SetCityWaypointPacket(offer.targetX(), offer.targetZ()));
        District target = District.values()[offer.targetDistrictOrdinal()];
        player.sendSystemMessage(Component.literal(
                        "Delivery accepted: " + offer.cargo() + " to District " + target.code()
                                + " for " + offer.reward() + " emeralds.")
                .withStyle(ChatFormatting.AQUA));
        return true;
    }

    static List<QuestOffer> offers(ServerLevel level, BlockPos anchor, District source) {
        return offers(NeonCityGenerator.layout(), level.getSeed(), anchor, source);
    }

    static List<QuestOffer> offers(
            MegacityLayout layout,
            long worldSeed,
            BlockPos anchor,
            District source) {
        List<QuestOffer> result = new ArrayList<>(OFFER_COUNT);
        long baseHash = MegacityLayout.mix(
                worldSeed ^ layout.seed() ^ QUEST_SALT,
                anchor.getX(),
                anchor.getZ());
        int districtRotation = Math.floorMod((int) (baseHash ^ (baseHash >>> 32)),
                District.values().length - 1);
        for (int index = 0; index < OFFER_COUNT; index++) {
            long hash = MegacityLayout.mix(baseHash, index, source.ordinal());
            int targetOrdinal;
            boolean local = index == 0;
            if (local) {
                targetOrdinal = source.ordinal();
            } else {
                int offset = 1 + Math.floorMod(
                        districtRotation + (index - 1) * 7,
                        District.values().length - 1);
                targetOrdinal = Math.floorMod(source.ordinal() + offset, District.values().length);
            }
            District target = District.values()[targetOrdinal];
            MegacityLayout.Node node = layout.node(target);
            int offsetX = -176 + Math.floorMod((int) hash, 353);
            int offsetZ = -176 + Math.floorMod((int) (hash >>> 32), 353);
            int targetX = node.x() + offsetX;
            int targetZ = node.z() + offsetZ;
            String cargo = CARGOES.get(Math.floorMod((int) Long.rotateRight(hash, 19),
                    CARGOES.size()));
            int reward = 18 + Math.floorMod((int) Long.rotateRight(hash, 37), 25);
            result.add(new QuestOffer(
                    targetOrdinal, targetX, targetZ, reward, cargo, local));
        }
        return List.copyOf(result);
    }

    static Optional<ActiveQuest> activeQuest(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ACTIVE).orElse(false)) {
            return Optional.empty();
        }
        int targetOrdinal = data.getInt(TARGET_DISTRICT).orElse(-1);
        if (targetOrdinal < 0 || targetOrdinal >= District.values().length) {
            clear(player, false);
            return Optional.empty();
        }
        return Optional.of(new ActiveQuest(
                District.values()[targetOrdinal],
                data.getInt(TARGET_X).orElse(0),
                data.getInt(TARGET_Z).orElse(0),
                Math.max(1, data.getInt(REWARD).orElse(1)),
                data.getString(CARGO).orElse("Cargo"),
                data.getBoolean(LOCAL).orElse(false)));
    }

    static void tickPlayer(ServerPlayer player, MegacityLayout.Location location) {
        Optional<ActiveQuest> active = activeQuest(player);
        if (active.isEmpty() || !location.insideCity()) {
            return;
        }
        ActiveQuest quest = active.get();
        if (location.district() != quest.targetDistrict()) {
            return;
        }
        if (quest.local()) {
            double distanceSquared = player.distanceToSqr(
                    quest.targetX() + 0.5, player.getY(), quest.targetZ() + 0.5);
            if (distanceSquared > LOCAL_DELIVERY_RADIUS * LOCAL_DELIVERY_RADIUS) {
                return;
            }
        }
        complete(player, quest);
    }

    private static void complete(ServerPlayer player, ActiveQuest quest) {
        ItemStack payment = new ItemStack(Items.EMERALD, quest.reward());
        if (!player.addItem(payment)) {
            player.drop(payment, false);
        }
        clear(player, true);
        player.sendSystemMessage(Component.literal(
                        "Delivery complete. Paid " + quest.reward() + " emeralds.")
                .withStyle(ChatFormatting.GREEN));
        ((ServerLevel) player.level()).playSound(
                null,
                player.blockPosition(),
                SoundEvents.PLAYER_LEVELUP,
                SoundSource.PLAYERS,
                0.65F,
                1.35F);
    }

    private static void clear(ServerPlayer player, boolean notifyClient) {
        CompoundTag data = player.getPersistentData();
        data.remove(ACTIVE);
        data.remove(TARGET_DISTRICT);
        data.remove(TARGET_X);
        data.remove(TARGET_Z);
        data.remove(REWARD);
        data.remove(CARGO);
        data.remove(LOCAL);
        if (notifyClient) {
            PacketDistributor.sendToPlayer(player, ClearCityWaypointPacket.INSTANCE);
        }
    }

    private static boolean isValidFixer(ServerPlayer player, Entity merchant) {
        return merchant != null
                && merchant.isAlive()
                && merchant.level() == player.level()
                && player.distanceToSqr(merchant) <= 64.0
                && MerchantTruckLibrary.merchantRole(merchant)
                        .orElse(null) == MerchantTruckLibrary.MerchantRole.QUEST;
    }
}
