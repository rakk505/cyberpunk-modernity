package com.example.cyberdeck.lifepath;

import org.jspecify.annotations.Nullable;

/** Stable player-selected starter archetypes. */
public enum Lifepath {
    NETRUNNER("netrunner"),
    BRAWLER("brawler"),
    MERC("merc");

    public static final Lifepath[] VALUES = values();

    private final String id;

    Lifepath(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "lifepath.cyberdeck." + id;
    }

    public static @Nullable Lifepath byId(String id) {
        if (id == null) {
            return null;
        }
        for (Lifepath lifepath : VALUES) {
            if (lifepath.id.equals(id)) {
                return lifepath;
            }
        }
        return null;
    }
}
