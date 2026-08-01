package com.example.cyberdeck.client;

import com.example.cyberdeck.network.QuickhackUploadPacket;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Immutable client snapshot of every independently uploading quickhack target. */
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

    public static List<QuickhackUploadPacket.TargetUpload> uploads() {
        return current.uploads();
    }

    public static boolean isUploading() {
        return !current.uploads().isEmpty();
    }

    public static int reservedRam() {
        return Math.max(0, current.reservedRam());
    }

    public static int activeSkillOrdinal(int targetId) {
        QuickhackUploadPacket.TargetUpload upload = uploadForTarget(targetId);
        return upload == null ? -1 : upload.activeSkillOrdinal();
    }

    public static int queuePosition(int targetId, int skillOrdinal) {
        QuickhackUploadPacket.TargetUpload upload = uploadForTarget(targetId);
        if (upload == null) {
            return -1;
        }
        List<Integer> queue = upload.skillOrdinals();
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i) != null && queue.get(i) == skillOrdinal) {
                return i + 1;
            }
        }
        return -1;
    }

    public static float uploadProgress(QuickhackUploadPacket.TargetUpload upload,
                                       double gameTime) {
        long duration = upload.endTick() - upload.startTick();
        if (duration <= 0L || upload.activeSkillOrdinal() < 0) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F,
                (float) ((gameTime - upload.startTick()) / duration)));
    }

    public static QuickhackUploadPacket.@Nullable TargetUpload uploadForTarget(int targetId) {
        for (QuickhackUploadPacket.TargetUpload upload : current.uploads()) {
            if (upload.targetId() == targetId) {
                return upload;
            }
        }
        return null;
    }
}
