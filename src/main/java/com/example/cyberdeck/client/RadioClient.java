package com.example.cyberdeck.client;

import com.example.cyberdeck.radio.RadioContent;
import com.example.cyberdeck.radio.RadioTrack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Plays whatever the station tells it to, and keeps vanilla's own music out of the way.
 *
 * <p>The track is streamed non-positionally at a reduced volume: this is a soundtrack, not a
 * jukebox in the world, and it must sit under gunfire and dialogue rather than competing with
 * them. It still goes through the MUSIC category so the player's own volume slider governs it.</p>
 */
@EventBusSubscriber(modid = com.example.cyberdeck.Cyberdeck.MODID, value = Dist.CLIENT)
public final class RadioClient {
    /** Quiet enough to leave headroom for weapons, voice lines and the city itself. */
    private static final float VOLUME = 0.35F;

    private static SoundInstance playing;
    private static boolean active;

    private RadioClient() {
    }

    public static void accept(int trackOrdinal) {
        Minecraft minecraft = Minecraft.getInstance();
        stopCurrent(minecraft);
        RadioTrack track = RadioTrack.byOrdinal(trackOrdinal);
        if (track == null) {
            active = false;
            return;
        }
        active = true;
        // Vanilla may have started something before the first packet arrived.
        minecraft.getMusicManager().stopPlaying();
        playing = SimpleSoundInstance.forUI(RadioContent.sound(track), 1.0F, VOLUME);
        minecraft.getSoundManager().play(playing);
    }

    /**
     * Vanilla restarts its own music on a timer, so suppressing it once is not enough; it has to
     * be held off for as long as the station is on.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        minecraft.getMusicManager().stopPlaying();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopCurrent(Minecraft.getInstance());
        active = false;
    }

    private static void stopCurrent(Minecraft minecraft) {
        if (playing != null) {
            minecraft.getSoundManager().stop(playing);
            playing = null;
        }
    }

    public static boolean isActive() {
        return active;
    }
}
