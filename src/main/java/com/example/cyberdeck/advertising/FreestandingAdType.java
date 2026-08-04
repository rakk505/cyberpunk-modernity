package com.example.cyberdeck.advertising;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.Direction;

/** Exact physical and rendered contracts for the two generated street-ad structures. */
public enum FreestandingAdType {
    MEDIUM("medium", 6, 8, false),
    SMALL("small", 2, 4, true);

    public static final int BODY_WIDTH = 2;

    private final String id;
    private final int faceLength;
    private final int height;
    private final boolean logoOnly;

    FreestandingAdType(String id, int faceLength, int height, boolean logoOnly) {
        this.id = id;
        this.faceLength = faceLength;
        this.height = height;
        this.logoOnly = logoOnly;
    }

    public String id() {
        return id;
    }

    public int faceLength() {
        return faceLength;
    }

    public int height() {
        return height;
    }

    public boolean logoOnly() {
        return logoOnly;
    }

    public boolean audioEnabled() {
        return !logoOnly;
    }

    public int sizeX(Direction.Axis longAxis) {
        requireHorizontal(longAxis);
        return this == MEDIUM && longAxis == Direction.Axis.X ? faceLength : BODY_WIDTH;
    }

    public int sizeZ(Direction.Axis longAxis) {
        requireHorizontal(longAxis);
        return this == MEDIUM && longAxis == Direction.Axis.Z ? faceLength : BODY_WIDTH;
    }

    public List<Direction> displayFaces(Direction.Axis longAxis) {
        requireHorizontal(longAxis);
        if (this == SMALL) {
            return List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
        }
        return longAxis == Direction.Axis.X
                ? List.of(Direction.NORTH, Direction.SOUTH)
                : List.of(Direction.EAST, Direction.WEST);
    }

    public static Optional<FreestandingAdType> byId(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        for (FreestandingAdType type : values()) {
            if (type.id.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private static void requireHorizontal(Direction.Axis axis) {
        if (axis == Direction.Axis.Y) {
            throw new IllegalArgumentException("Freestanding ads require a horizontal long axis");
        }
    }
}
