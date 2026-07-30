package com.example.cyberdeck.client.map;

/** Immutable world-to-screen projection used by both city-map renderers. */
public record CityMapViewport(
        int x,
        int y,
        int width,
        int height,
        double centerX,
        double centerZ,
        double worldSpanX,
        double worldSpanZ) {
    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public int screenX(double worldX) {
        return (int) Math.round(x + width * 0.5
                + (worldX - centerX) * width / worldSpanX);
    }

    public int screenY(double worldZ) {
        return (int) Math.round(y + height * 0.5
                + (worldZ - centerZ) * height / worldSpanZ);
    }

    public double worldX(double screenX) {
        return centerX + (screenX - (x + width * 0.5)) * worldSpanX / width;
    }

    public double worldZ(double screenY) {
        return centerZ + (screenY - (y + height * 0.5)) * worldSpanZ / height;
    }

    public boolean contains(double screenX, double screenY) {
        return screenX >= x && screenX < right() && screenY >= y && screenY < bottom();
    }
}
