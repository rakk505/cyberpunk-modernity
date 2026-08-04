package com.example.cyberdeck.advertising;

import java.util.Locale;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.resources.Identifier;

/** Preprocessed MP4 clips available to large advertising displays. */
public enum AdClip {
    NEON_SKYLINE("neon_skyline", 30, 8),
    CHROME_COLA("chrome_cola", 36, 8),
    ORBITAL_AIR("orbital_air", 42, 8);

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
