package com.example.cyberdeck.client;

import com.example.cyberdeck.network.QuickhackUploadPacket;

/**
 * Client-side holder for the caster's current quickhack upload, populated by
 * {@link QuickhackUploadPacket}. The upload marker HUD reads this each frame.
 */
public final class QuickhackUploadClient {
    private static volatile QuickhackUploadPacket current = QuickhackUploadPacket.NONE;

    private QuickhackUploadClient() {
    }

    public static void set(QuickhackUploadPacket packet) {
        current = packet == null ? QuickhackUploadPacket.NONE : packet;
    }

    public static QuickhackUploadPacket get() {
        return current;
    }

    public static boolean isUploading() {
        QuickhackUploadPacket p = current;
        return p.targetId() >= 0 && p.endTick() > p.startTick();
    }
}
