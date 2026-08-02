package com.example.cyberdeck.faction;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent one-roll ledger for non-ambient reinforcement groups. */
public final class ReinforcementSavedData extends SavedData {
    private static final int MAX_RESOLVED_GROUPS = 8_192;
    private static final Codec<ReinforcementSavedData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(UUIDUtil.CODEC.listOf().optionalFieldOf("resolved", List.of())
                            .forGetter(ReinforcementSavedData::serializedGroups))
                    .apply(instance, ReinforcementSavedData::new));

    public static final SavedDataType<ReinforcementSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "reinforcement_rolls_v1"),
            ReinforcementSavedData::new,
            CODEC);

    private final LinkedHashSet<UUID> resolvedGroups = new LinkedHashSet<>();

    public ReinforcementSavedData() {
    }

    private ReinforcementSavedData(List<UUID> resolvedGroups) {
        int firstRetained = Math.max(0, resolvedGroups.size() - MAX_RESOLVED_GROUPS);
        for (int index = firstRetained; index < resolvedGroups.size(); index++) {
            this.resolvedGroups.add(resolvedGroups.get(index));
        }
    }

    public static ReinforcementSavedData get(ServerLevel context) {
        return context.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Returns true only for the first resolution of this group across save/load cycles. */
    public boolean resolve(UUID groupId) {
        if (!resolvedGroups.add(groupId)) {
            return false;
        }
        while (resolvedGroups.size() > MAX_RESOLVED_GROUPS) {
            resolvedGroups.remove(resolvedGroups.getFirst());
        }
        setDirty();
        return true;
    }

    private List<UUID> serializedGroups() {
        return List.copyOf(resolvedGroups);
    }
}
