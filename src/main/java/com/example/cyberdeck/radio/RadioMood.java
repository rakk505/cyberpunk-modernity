package com.example.cyberdeck.radio;

/**
 * What the station is scoring right now, in priority order: a fight outranks a drive, and a drive
 * outranks wandering the city.
 */
public enum RadioMood {
    IDLE,
    DRIVE,
    COMBAT
}
