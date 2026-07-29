package com.example.cyberdeck.client;

import com.example.cyberdeck.network.QuickhackUploadPacket;

import java.util.List;

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
        return p.activeSkillOrdinal() >= 0
                && p.targetId() >= 0
                && p.endTick() > p.startTick();
    }

    public static int activeSkillOrdinal() {
        return current.activeSkillOrdinal();
    }

    public static int reservedRam() {
        return Math.max(0, current.reservedRam());
    }

    public static List<Integer> queuedSkillOrdinals() {
        List<Integer> queue = current.skillOrdinals();
        return queue == null ? List.of() : queue;
    }

    public static int queuePosition(int skillOrdinal) {
        List<Integer> queue = queuedSkillOrdinals();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i) != null && queue.get(i) == skillOrdinal) {
                return i + 1;
            }
        }
        return -1;
    }

    public static float uploadProgress(long gameTime) {
        QuickhackUploadPacket packet = current;
        long duration = packet.endTick() - packet.startTick();
        if (duration <= 0L || packet.activeSkillOrdinal() < 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F,
                (gameTime - packet.startTick()) / (float) duration));
    }
}
