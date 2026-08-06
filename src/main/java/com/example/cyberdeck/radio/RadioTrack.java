package com.example.cyberdeck.radio;

import java.util.List;

/**
 * The station's playlist, grouped by the situation each track scores.
 *
 * <p>Durations are baked in because the server decides when a track ends and what follows it. The
 * client only plays what it is told, so a client that mutes, lags or reconnects cannot drift the
 * queue out of step with the rest of a party.</p>
 */
public enum RadioTrack {
    IDLE_1("idle", RadioMood.IDLE, 219),
    IDLE_2("idle2", RadioMood.IDLE, 212),
    IDLE_3("idle3t", RadioMood.IDLE, 207),
    IDLE_4("idle4", RadioMood.IDLE, 52),
    DRIVE_1("drive", RadioMood.DRIVE, 83),
    DRIVE_2("drive2", RadioMood.DRIVE, 131),
    DRIVE_3("drive3", RadioMood.DRIVE, 156),
    DRIVE_4("drive4", RadioMood.DRIVE, 142),
    COMBAT_1("combat1", RadioMood.COMBAT, 218),
    COMBAT_2("combat2", RadioMood.COMBAT, 194),
    COMBAT_3("combat3", RadioMood.COMBAT, 50),
    /**
     * District G's own theme. It belongs to the idle rotation, but is only ever chosen inside
     * G Corp, and is the only thing chosen there.
     */
    G_CORP("gcorp", RadioMood.IDLE, 209);

    private final String id;
    private final RadioMood mood;
    private final int durationSeconds;

    RadioTrack(String id, RadioMood mood, int durationSeconds) {
        this.id = id;
        this.mood = mood;
        this.durationSeconds = durationSeconds;
    }

    public String id() {
        return id;
    }

    public RadioMood mood() {
        return mood;
    }

    public int durationTicks() {
        return durationSeconds * 20;
    }

    /** Tracks eligible for a mood, excluding the district theme that has its own trigger. */
    public static List<RadioTrack> rotation(RadioMood mood) {
        return switch (mood) {
            case IDLE -> List.of(IDLE_1, IDLE_2, IDLE_3, IDLE_4);
            case DRIVE -> List.of(DRIVE_1, DRIVE_2, DRIVE_3, DRIVE_4);
            case COMBAT -> List.of(COMBAT_1, COMBAT_2, COMBAT_3);
        };
    }

    public static RadioTrack byOrdinal(int ordinal) {
        RadioTrack[] values = values();
        return ordinal < 0 || ordinal >= values.length ? null : values[ordinal];
    }
}
