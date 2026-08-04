package com.example.cyberdeck.advertising;

import java.util.Locale;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.resources.Identifier;

/** Preprocessed MP4 clips available to large advertising displays. */
public enum AdClip {
    META_LOGO("meta_logo", 30, 8),
    META_GLASSES("meta_glasses", 30, 8),
    META_AI("meta_ai", 45, 8),
    META_FUTURE("meta_future", 45, 8);

    public static final int SHEET_COLUMNS = 4;
    public static final int SHEET_ROWS = 4;
    public static final int FRAMES_PER_SHEET = SHEET_COLUMNS * SHEET_ROWS;

    private final String id;
    private final int durationTicks;
    private final int framesPerSecond;
    private final int frameCount;

    AdClip(String id, int durationSeconds, int framesPerSecond) {
        this.id = id;
        this.durationTicks = durationSeconds * 20;
        this.framesPerSecond = framesPerSecond;
        this.frameCount = durationSeconds * framesPerSecond;
    }

    public String id() {
        return id;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public int frameCount() {
        return frameCount;
    }

    public int frameAt(float playbackTicks) {
        int frame = (int) (playbackTicks * framesPerSecond / 20.0F);
        return Math.clamp(frame, 0, frameCount - 1);
    }

    public Identifier sheetTexture(int frame) {
        int sheet = frame / FRAMES_PER_SHEET + 1;
        String path = String.format(Locale.ROOT,
                "textures/ads/%s/sheet_%03d.png", id, sheet);
        return Identifier.fromNamespaceAndPath(Cyberdeck.MODID, path);
    }
}
