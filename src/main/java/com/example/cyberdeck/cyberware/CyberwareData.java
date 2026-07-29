package com.example.cyberdeck.cyberware;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-player storage of installed cyberware, one optional {@link Cyberware} per {@link BodySlot}.
 *
 * <p>Stored as a NeoForge attachment. Persisted via {@link #CODEC} and synced to the owning client
 * via {@link #STREAM_CODEC}. The wire/disk form is a map of body-slot name -> cyberware id, which
 * is forward/backward tolerant: unknown ids are dropped on load rather than crashing.
 */
public final class CyberwareData {
    public static final Codec<CyberwareData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .fieldOf("installed")
                    .forGetter(CyberwareData::asIdMap))
            .apply(instance, CyberwareData::fromIdMap));

    public static final MapCodec<CyberwareData> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .fieldOf("installed")
                    .forGetter(CyberwareData::asIdMap))
            .apply(instance, CyberwareData::fromIdMap));

    public static final StreamCodec<RegistryFriendlyByteBuf, CyberwareData> STREAM_CODEC =
            ByteBufCodecs.<net.minecraft.network.RegistryFriendlyByteBuf, String, String, HashMap<String, String>>map(
                            HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8)
                    .map(CyberwareData::fromIdMap, CyberwareData::asHashMap);

    private final EnumMap<BodySlot, Cyberware> installed = new EnumMap<>(BodySlot.class);

    public CyberwareData() {
    }

    private static CyberwareData fromIdMap(Map<String, String> map) {
        CyberwareData data = new CyberwareData();
        for (Map.Entry<String, String> e : map.entrySet()) {
            BodySlot slot = slotByName(e.getKey());
            Cyberware cw = Cyberware.byId(e.getValue());
            // Drop stale/mismatched entries defensively so a bad save can never crash the game.
            if (slot != null && cw != null && cw.slot() == slot) {
                data.installed.put(slot, cw);
            }
        }
        return data;
    }

    private Map<String, String> asIdMap() {
        return asHashMap();
    }

    private HashMap<String, String> asHashMap() {
        HashMap<String, String> map = new HashMap<>();
        installed.forEach((slot, cw) -> map.put(slot.name(), cw.id()));
        return map;
    }

    private static BodySlot slotByName(String name) {
        try {
            return BodySlot.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The cyberware installed in the given slot, or {@code null} if the slot is empty. */
    public Cyberware get(BodySlot slot) {
        return installed.get(slot);
    }

    public boolean has(Cyberware cyberware) {
        return cyberware != null && installed.get(cyberware.slot()) == cyberware;
    }

    /** Installs cyberware into its slot, replacing whatever mutually-exclusive option was there. */
    public void install(Cyberware cyberware) {
        if (cyberware != null) {
            installed.put(cyberware.slot(), cyberware);
        }
    }

    /** Clears the given slot. */
    public void remove(BodySlot slot) {
        installed.remove(slot);
    }

    public CyberwareData copy() {
        CyberwareData c = new CyberwareData();
        c.installed.putAll(this.installed);
        return c;
    }
}
