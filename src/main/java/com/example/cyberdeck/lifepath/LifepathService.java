package com.example.cyberdeck.lifepath;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareInstaller;
import com.example.cyberdeck.network.OpenLifepathPacket;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server authority for opening and permanently claiming one starter lifepath. */
public final class LifepathService {
    private static final String BASIC_OPTICS = "basic_kiroshi_optics_t2";
    private static final String[] STARTING_LEGS = {
            "fortified_ankles_t1",
            "jenkins_tendons_t2",
            "leeroy_ligament_system_t2",
            "lynx_paws_t2",
            "reinforced_tendons_t2"
    };

    private LifepathService() {
    }

    /** Opens the picker only while this player still has an unclaimed choice. */
    public static boolean openSelection(ServerPlayer player) {
        LifepathState state = LifepathState.get(player);
        if (state.selected()) {
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.lifepath.already_selected",
                    Component.translatable(state.lifepath().translationKey())), true);
            return false;
        }
        if (player.connection == null
                || !player.connection.hasChannel(OpenLifepathPacket.TYPE)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, OpenLifepathPacket.INSTANCE);
        return true;
    }

    /**
     * Resolves the requested archetype entirely on the server and grants it at most once.
     * Duplicate submissions for the already-selected archetype are idempotent.
     */
    public static boolean select(ServerPlayer player, String requestedId) {
        Lifepath requested = Lifepath.byId(requestedId);
        if (requested == null) {
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.lifepath.invalid"), true);
            return false;
        }

        LifepathState current = LifepathState.get(player);
        if (current.selected()) {
            if (current.lifepath() == requested) {
                return true;
            }
            player.sendSystemMessage(Component.translatable(
                    "message.cyberdeck.lifepath.already_selected",
                    Component.translatable(current.lifepath().translationKey())), true);
            return false;
        }

        String startingLeg = requested == Lifepath.NETRUNNER
                ? "jenkins_tendons_t2"
                : STARTING_LEGS[player.getRandom().nextInt(STARTING_LEGS.length)];
        StarterLoadout loadout = loadout(requested, startingLeg);
        if (!CyberwareInstaller.installGrantedLoadout(player, loadout.cyberware())) {
            return false;
        }

        for (ItemStack stack : loadout.items()) {
            giveOrDrop(player, stack);
        }
        LifepathState.set(player, new LifepathState(requested.id(), startingLeg));
        player.sendSystemMessage(Component.translatable(
                "message.cyberdeck.lifepath.selected",
                Component.translatable(requested.translationKey())), false);
        return true;
    }

    public static boolean isStartingLeg(String cyberwareId) {
        for (String candidate : STARTING_LEGS) {
            if (candidate.equals(cyberwareId)) {
                return true;
            }
        }
        return false;
    }

    private static StarterLoadout loadout(Lifepath lifepath, String startingLeg) {
        List<Cyberware> cyberware = new ArrayList<>();
        List<ItemStack> items = new ArrayList<>();
        cyberware.add(required(BASIC_OPTICS));
        switch (lifepath) {
            case NETRUNNER -> {
                cyberware.add(required("jenkins_tendons_t2"));
                cyberware.add(required("smart_link_t1"));
                cyberware.add(required("paraline_mk_1_5_t1"));
                items.add(new ItemStack(WeaponItems.gun(GunType.YUKIMURA).get()));
                items.add(new ItemStack(AmmoItems.item(AmmoType.HANDGUN).get(), 250));
            }
            case BRAWLER -> {
                cyberware.add(required(startingLeg));
                cyberware.add(required("nano_plating_t2"));
                cyberware.add(required("gorilla_arms_t2"));
                items.add(new ItemStack(WeaponItems.gun(GunType.TECH_SHOTGUN).get()));
                items.add(new ItemStack(AmmoItems.item(AmmoType.SHOTGUN).get(), 200));
            }
            case MERC -> {
                cyberware.add(required(startingLeg));
                cyberware.add(required("mantis_blades_t2"));
                items.add(new ItemStack(WeaponItems.gun(GunType.ASSAULT_RIFLE).get()));
                items.add(new ItemStack(AmmoItems.item(AmmoType.HEAVY).get(), 300));
            }
        }
        return new StarterLoadout(List.copyOf(cyberware), List.copyOf(items));
    }

    private static Cyberware required(String id) {
        Cyberware cyberware = Cyberware.byId(id);
        if (cyberware == null) {
            throw new IllegalStateException("Missing lifepath cyberware variant " + id);
        }
        return cyberware;
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!player.addItem(stack) && !stack.isEmpty()) {
            ItemEntity dropped = player.drop(stack, false);
            if (dropped != null) {
                dropped.setTarget(player.getUUID());
            }
        }
    }

    private record StarterLoadout(List<Cyberware> cyberware, List<ItemStack> items) {
    }
}
