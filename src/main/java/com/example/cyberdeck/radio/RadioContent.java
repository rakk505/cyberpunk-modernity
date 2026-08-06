package com.example.cyberdeck.radio;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/** Sound events for the radio playlist. */
public final class RadioContent {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Cyberdeck.MODID);

    private static final Map<RadioTrack, DeferredHolder<SoundEvent, SoundEvent>> TRACKS =
            registerTracks();

    private RadioContent() {
    }

    private static Map<RadioTrack, DeferredHolder<SoundEvent, SoundEvent>> registerTracks() {
        Map<RadioTrack, DeferredHolder<SoundEvent, SoundEvent>> sounds =
                new EnumMap<>(RadioTrack.class);
        for (RadioTrack track : RadioTrack.values()) {
            String name = "radio." + track.id();
            Identifier id = Identifier.fromNamespaceAndPath(Cyberdeck.MODID, name);
            // Music is streamed from the player's own position, so it must not attenuate.
            sounds.put(track, SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(id)));
        }
        return Map.copyOf(sounds);
    }

    public static SoundEvent sound(RadioTrack track) {
        return TRACKS.get(track).get();
    }

    public static void register(IEventBus eventBus) {
        SOUND_EVENTS.register(eventBus);
    }
}
