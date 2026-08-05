package com.example.cyberdeck.advertising;

import java.util.List;
import java.util.Optional;

import dev.modernity.neoncity.District;
import dev.modernity.neoncity.NeonCityGenerator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** District-scoped clip playlists persisted by large advertising displays. */
public enum AdCampaign {
    GENERAL("general", List.of(
            AdClip.NEON_SKYLINE,
            AdClip.CHROME_COLA,
            AdClip.ORBITAL_AIR,
            AdClip.MISANTHROPIC,
            AdClip.META_LOGO,
            AdClip.META_GLASSES,
            AdClip.META_AI,
            AdClip.META_FUTURE)),
    META("meta", List.of(
            AdClip.META_LOGO,
            AdClip.META_GLASSES,
            AdClip.META_AI,
            AdClip.META_FUTURE)),
    CLOSED_AI("closed_ai", List.of(AdClip.CLOSED_AI)),
    /** Wide megascreens on the facades that face an inter-district highway. */
    HIGHWAY("highway", List.of(
            AdClip.VATER,
            AdClip.GOJO,
            AdClip.HORIZON,
            AdClip.META_LOGO_2,
            AdClip.ERI,
            AdClip.HAMBURGER,
            AdClip.SODA)),
    /**
     * Vertical roadside screens. Their clips come from 9:16 sources, so they belong on narrow
     * slices of a building rather than being letterboxed onto a wide facade.
     */
    HIGHWAY_TALL("highway_tall", List.of(AdClip.PETROCHEM)),
    /** District S runs its own retro Soviet advertising, the way M runs Meta and O runs ClosedAI. */
    S_CORP("s_corp", List.of(AdClip.SOVIET_MEAT, AdClip.SOVIET_PROPAGANDA));

    private final String id;
    private final List<AdClip> clips;

    AdCampaign(String id, List<AdClip> clips) {
        this.id = id;
        this.clips = clips;
    }

    public String id() {
        return id;
    }

    public List<AdClip> clips() {
        return clips;
    }

    public AdClip clipAt(int index) {
        return clips.get(Math.floorMod(index, clips.size()));
    }

    /** Shape of display this campaign's clips are meant to fill. */
    public AdClip.Orientation orientation() {
        return this == HIGHWAY_TALL
                ? AdClip.Orientation.PORTRAIT
                : AdClip.Orientation.LANDSCAPE;
    }

    public static Optional<AdCampaign> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (AdCampaign campaign : values()) {
            if (campaign.id.equals(id)) {
                return Optional.of(campaign);
            }
        }
        return Optional.empty();
    }

    public static AdCampaign forDistrict(District district) {
        if (district == District.M_CORP) {
            return META;
        }
        if (district == District.O_CORP) {
            return CLOSED_AI;
        }
        if (district == District.S_CORP) {
            return S_CORP;
        }
        return GENERAL;
    }

    public static AdCampaign forLevel(ServerLevel level, BlockPos position) {
        if (!NeonCityGenerator.isMegacityWorld(level)) {
            return GENERAL;
        }
        var location = NeonCityGenerator.effectiveLocationAt(
                position.getX(), position.getZ());
        return location.insideCity() ? forDistrict(location.district()) : GENERAL;
    }
}
