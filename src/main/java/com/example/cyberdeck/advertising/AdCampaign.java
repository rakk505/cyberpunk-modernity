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
    CLOSED_AI("closed_ai", List.of(AdClip.CLOSED_AI));

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
