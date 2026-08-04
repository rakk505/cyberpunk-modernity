package com.example.cyberdeck.advertising;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.resources.Identifier;

/** Silent logo cards used exclusively by the four-sided small street display. */
public enum LogoAd {
    META("meta"),
    CLOSEDAI("closedai"),
    MISANTHROPIC("misanthropic");

    private final String id;
    private final Identifier texture;

    LogoAd(String id) {
        this.id = id;
        this.texture = Identifier.fromNamespaceAndPath(
                Cyberdeck.MODID, "textures/ad_logos/" + id + ".png");
    }

    public String id() {
        return id;
    }

    public Identifier texture() {
        return texture;
    }
}
