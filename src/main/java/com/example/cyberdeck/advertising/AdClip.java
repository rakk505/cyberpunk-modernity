package com.example.cyberdeck.advertising;

import java.util.Locale;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.resources.Identifier;

/** Preprocessed animated clips available to large advertising displays. */
public enum AdClip {
    NEON_SKYLINE("neon_skyline", 30, 8, true),
    CHROME_COLA("chrome_cola", 36, 8, true),
    ORBITAL_AIR("orbital_air", 42, 8, true),
    MISANTHROPIC("misanthropic", 30, 8, false),
    CLOSED_AI("closed_ai", 30, 8, false),
    META_LOGO("meta_logo", 30, 8, false),
    META_GLASSES("meta_glasses", 30, 8, false),
    META_AI("meta_ai", 45, 8, false),
    META_FUTURE("meta_future", 45, 8, false),
    VATER("vater", 30, 8, false),
    GOJO("gojo", 30, 8, false),
    HORIZON("horizon", 30, 8, false),
    META_LOGO_2("meta_logo_2", 30, 8, false),
    PETROCHEM("petrochem", 30, 8, false),
    ERI("eri", 30, 8, false),
    HAMBURGER("hamburger", 30, 8, false),
    SODA("soda", 30, 8, false);

    public static final int SHEET_COLUMNS = 4;
    public static final int SHEET_ROWS = 4;
    public static final int FRAMES_PER_SHEET = SHEET_COLUMNS * SHEET_ROWS;

    private final String id;
    private final int durationTicks;
    private final int framesPerSecond;
    private final int frameCount;
    private final boolean audioEnabled;

    AdClip(String id, int durationSeconds, int framesPerSecond, boolean audioEnabled) {
        this.id = id;
        this.durationTicks = durationSeconds * 20;
        this.framesPerSecond = framesPerSecond;
        this.frameCount = durationSeconds * framesPerSecond;
        this.audioEnabled = audioEnabled;
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

    public int framesPerSecond() {
        return framesPerSecond;
    }

    public boolean audioEnabled() {
        return audioEnabled;
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
