package com.example.cyberdeck.cyberware;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persisted, owner-synced installed cyberware and optional socket unlocks. */
public final class CyberwareData {
    private static final String UNLOCK_PREFIX = "@unlock:";

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
            ByteBufCodecs.<RegistryFriendlyByteBuf, String, String, HashMap<String, String>>map(
                            HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8)
                    .map(CyberwareData::fromIdMap, CyberwareData::asHashMap);

    private final EnumMap<BodySlot, Cyberware[]> installed = new EnumMap<>(BodySlot.class);
    private final EnumSet<SlotUnlock> unlocks = EnumSet.noneOf(SlotUnlock.class);

    public CyberwareData() {
        for (BodySlot slot : BodySlot.VALUES) {
            installed.put(slot, new Cyberware[slot.maximumSockets()]);
        }
    }

    private static CyberwareData fromIdMap(Map<String, String> map) {
        CyberwareData data = new CyberwareData();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(UNLOCK_PREFIX)) {
                try {
                    data.unlocks.add(SlotUnlock.valueOf(key.substring(UNLOCK_PREFIX.length())));
                } catch (IllegalArgumentException ignored) {
                    // Forward-compatible: an unlock removed by a future version is ignored.
                }
                continue;
            }

            int separator = key.lastIndexOf(':');
            String slotName = separator >= 0 ? key.substring(0, separator) : key;
            int socket = 0; // Legacy saves used only the body-slot name.
            if (separator >= 0) {
                try {
                    socket = Integer.parseInt(key.substring(separator + 1));
                } catch (NumberFormatException ignored) {
                    continue;
                }
            }
            BodySlot slot = slotByName(slotName);
            Cyberware cyberware = Cyberware.byId(entry.getValue());
            if (slot != null && cyberware != null && cyberware.slot() == slot
                    && socket >= 0 && socket < slot.maximumSockets()) {
                data.installed.get(slot)[socket] = cyberware;
            }
        }
        return data;
    }

    private Map<String, String> asIdMap() {
        return asHashMap();
    }

    private HashMap<String, String> asHashMap() {
        HashMap<String, String> map = new HashMap<>();
        for (BodySlot slot : BodySlot.VALUES) {
            Cyberware[] sockets = installed.get(slot);
            for (int index = 0; index < sockets.length; index++) {
                if (sockets[index] != null) {
                    map.put(slot.name() + ":" + index, sockets[index].id());
                }
            }
        }
        for (SlotUnlock unlock : unlocks) {
            map.put(UNLOCK_PREFIX + unlock.name(), "1");
        }
        return map;
    }

    private static BodySlot slotByName(String name) {
        try {
            return BodySlot.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** First occupied socket in the system, retained for single-socket compatibility call sites. */
    public Cyberware get(BodySlot slot) {
        for (Cyberware cyberware : installed.get(slot)) {
            if (cyberware != null) {
                return cyberware;
            }
        }
        return null;
    }

    public Cyberware get(BodySlot slot, int socket) {
        if (socket < 0 || socket >= slot.maximumSockets()) {
            return null;
        }
        return installed.get(slot)[socket];
    }

    /** Fixed-size snapshot whose indices correspond exactly to the sockets on the body screen. */
    public List<Cyberware> sockets(BodySlot slot) {
        return Collections.unmodifiableList(Arrays.asList(installed.get(slot).clone()));
    }

    public List<Cyberware> allInstalled() {
        List<Cyberware> result = new ArrayList<>();
        for (BodySlot slot : BodySlot.VALUES) {
            for (Cyberware cyberware : installed.get(slot)) {
                if (cyberware != null) {
                    result.add(cyberware);
                }
            }
        }
        return List.copyOf(result);
    }

    /** Family-aware for compatibility constants: a Tier 1 Smart Link still counts as Smart Link. */
    public boolean has(Cyberware cyberware) {
        return cyberware != null && hasFamily(cyberware.familyId());
    }

    public boolean hasExact(Cyberware cyberware) {
        if (cyberware == null) {
            return false;
        }
        for (Cyberware installedCyberware : installed.get(cyberware.slot())) {
            if (installedCyberware == cyberware) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFamily(String familyId) {
        if (familyId == null) {
            return false;
        }
        for (BodySlot slot : BodySlot.VALUES) {
            for (Cyberware cyberware : installed.get(slot)) {
                if (cyberware != null && cyberware.familyId().equals(familyId)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Cyberware findFamily(String familyId) {
        if (familyId == null) {
            return null;
        }
        for (Cyberware cyberware : allInstalled()) {
            if (cyberware.familyId().equals(familyId)) {
                return cyberware;
            }
        }
        return null;
    }

    public Cyberware findFlag(String flag) {
        for (Cyberware cyberware : allInstalled()) {
            if (cyberware.hasFlag(flag)) {
                return cyberware;
            }
        }
        return null;
    }

    public void install(Cyberware cyberware, int socket) {
        if (cyberware != null && socket >= 0 && socket < cyberware.slot().maximumSockets()) {
            installed.get(cyberware.slot())[socket] = cyberware;
        }
    }

    /** Legacy helper installs into the first empty physical socket, then socket zero. */
    public void install(Cyberware cyberware) {
        if (cyberware == null) {
            return;
        }
        int socket = firstEmptySocket(cyberware.slot(), cyberware.slot().maximumSockets());
        install(cyberware, socket < 0 ? 0 : socket);
    }

    public Cyberware remove(BodySlot slot, int socket) {
        Cyberware previous = get(slot, socket);
        if (previous != null) {
            installed.get(slot)[socket] = null;
        }
        return previous;
    }

    /** Legacy helper clears the first occupied socket. */
    public void remove(BodySlot slot) {
        for (int index = 0; index < slot.maximumSockets(); index++) {
            if (remove(slot, index) != null) {
                return;
            }
        }
    }

    public int firstEmptySocket(BodySlot slot, int unlockedSockets) {
        int limit = Math.min(unlockedSockets, slot.maximumSockets());
        for (int index = 0; index < limit; index++) {
            if (installed.get(slot)[index] == null) {
                return index;
            }
        }
        return -1;
    }

    public boolean unlock(SlotUnlock unlock) {
        return unlock != null && unlocks.add(unlock);
    }

    public boolean isUnlocked(SlotUnlock unlock) {
        return unlock != null && unlocks.contains(unlock);
    }

    public int unlockedSockets(BodySlot slot) {
        int result = slot.baseSockets();
        for (int socket = slot.baseSockets(); socket < slot.maximumSockets(); socket++) {
            SlotUnlock unlock = slot.unlockForSocket(socket);
            if (unlock != null && unlocks.contains(unlock)) {
                result++;
            }
        }
        return result;
    }

    public int installedCount() {
        return allInstalled().size();
    }

    public int capacityUsed() {
        int used = 0;
        for (Cyberware cyberware : allInstalled()) {
            used += cyberware.capacity();
        }
        return used;
    }

    public CyberwareData copy() {
        CyberwareData copy = new CyberwareData();
        for (BodySlot slot : BodySlot.VALUES) {
            System.arraycopy(installed.get(slot), 0, copy.installed.get(slot), 0,
                    slot.maximumSockets());
        }
        copy.unlocks.addAll(unlocks);
        return copy;
    }
}
