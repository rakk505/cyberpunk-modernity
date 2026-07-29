package com.example.cyberdeck.cyberware;

import java.util.List;

/** One named implant and all of its available tier variants. */
public record CyberwareFamily(
        String id,
        String displayName,
        BodySlot slot,
        List<Cyberware> variants) {

    public CyberwareFamily {
        variants = List.copyOf(variants);
    }

    public Cyberware lowestTier() {
        return variants.getFirst();
    }

    public Cyberware highestTier() {
        return variants.getLast();
    }
}
