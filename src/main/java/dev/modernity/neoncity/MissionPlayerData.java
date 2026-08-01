package dev.modernity.neoncity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Death-safe player journal storage shared by active contracts and story progression. */
final class MissionPlayerData {
    private static final String COMPLETED_STORY = "cyberdeck_story_completed";

    private MissionPlayerData() {
    }

    static CompoundTag persisted(Player player) {
        CompoundTag root = player.getPersistentData();
        CompoundTag existing = root.getCompound(Player.PERSISTED_NBT_TAG).orElse(null);
        if (existing != null) return existing;
        CompoundTag created = new CompoundTag();
        root.put(Player.PERSISTED_NBT_TAG, created);
        return created;
    }

    static Set<String> completedStory(ServerPlayer player) {
        Set<String> completed = new HashSet<>();
        for (Tag entry : persisted(player).getListOrEmpty(COMPLETED_STORY)) {
            entry.asString().ifPresent(completed::add);
        }
        return Set.copyOf(completed);
    }

    static boolean completeStory(ServerPlayer player, String missionId) {
        Set<String> completed = new HashSet<>(completedStory(player));
        if (!completed.add(missionId)) return false;
        ListTag values = new ListTag();
        completed.stream().sorted().map(StringTag::valueOf).forEach(values::add);
        persisted(player).put(COMPLETED_STORY, values);
        return true;
    }

    static void copyOnClone(Player original, Player replacement) {
        CompoundTag source = original.getPersistentData()
                .getCompound(Player.PERSISTED_NBT_TAG).orElse(null);
        if (source != null) {
            replacement.getPersistentData().put(Player.PERSISTED_NBT_TAG, source.copy());
        }
    }

    static void migrateLegacyKeys(ServerPlayer player, List<String> keys) {
        CompoundTag root = player.getPersistentData();
        CompoundTag persisted = persisted(player);
        for (String key : keys) {
            Tag value = root.remove(key);
            if (value != null && !persisted.contains(key)) persisted.put(key, value);
        }
    }
}
