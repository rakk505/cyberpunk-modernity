package com.example.cyberdeck.ram;

import com.example.cyberdeck.Cyberdeck;

import com.mojang.serialization.Codec;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Player RAM: a regenerating resource consumed by Cyberdeck quickhacks.
 *
 * <p>Players start with {@link #MAX_RAM} and regenerate 1 RAM per second up to that cap. The value
 * is persisted, kept across death, and synced to the owning client so the HUD and screen can display
 * it without extra packets.
 */
public final class RamAttachments {
    public static final int BASE_MAX_RAM = 12;
    /** Compatibility constant; callers that have a player should use {@link #max(Player)}. */
    public static final int MAX_RAM = BASE_MAX_RAM;

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<Integer>> RAM =
            ATTACHMENT_TYPES.register("ram", () -> AttachmentType
                    .builder(() -> BASE_MAX_RAM)
                    .serialize(Codec.INT.fieldOf("ram"))
                    .sync(ByteBufCodecs.VAR_INT)
                    .copyOnDeath()
                    .build());

    private RamAttachments() {
    }

    /** Current RAM for a player, clamped to [0, MAX_RAM]. */
    public static int get(Player player) {
        return Math.max(0, Math.min(max(player), player.getData(RAM.get())));
    }

    public static int max(Player player) {
        double bonus = 0.0;
        for (com.example.cyberdeck.cyberware.Cyberware cyberware
                : com.example.cyberdeck.cyberware.CyberwareAttachments.get(player).allInstalled()) {
            bonus += cyberware.value("max_ram");
        }
        return Math.max(1, BASE_MAX_RAM + (int) Math.round(bonus));
    }

    /** Overwrites the player's RAM, clamped to the valid range. */
    public static void set(Player player, int value) {
        player.setData(RAM.get(), Math.max(0, Math.min(max(player), value)));
    }

    /** {@return true if the player has at least {@code cost} RAM available}. */
    public static boolean canAfford(Player player, int cost) {
        return get(player) >= cost;
    }

    /**
     * Attempts to spend {@code cost} RAM. Returns {@code true} and deducts if affordable, otherwise
     * leaves RAM unchanged and returns {@code false}.
     */
    public static boolean spend(Player player, int cost) {
        int current = get(player);
        if (current < cost) {
            return false;
        }
        set(player, current - cost);
        return true;
    }
}
