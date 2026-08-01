package dev.modernity.neoncity;

import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.player.StreetCredState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Focused progression invariants kept separate from the four objective end-to-end tests. */
final class MissionFeatureGameTests {
    private MissionFeatureGameTests() {
    }

    static void storyDag(GameTestHelper helper) {
        List<StoryMissionCatalog.StoryMission> definitions = StoryMissionCatalog.definitions();
        helper.assertTrue(definitions.size() >= 5, "story catalog lost its main progression chain");
        List<StoryMissionCatalog.StoryMission> roots = StoryMissionCatalog.available(Set.of(), 0);
        helper.assertTrue(roots.size() == 1
                        && roots.getFirst().id().equals("signal_in_the_static"),
                "story DAG must begin at one deterministic root");
        Set<String> rootComplete = Set.of("signal_in_the_static");
        Set<String> firstBranch = StoryMissionCatalog.available(rootComplete, 100).stream()
                .map(StoryMissionCatalog.StoryMission::id)
                .collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(firstBranch.equals(Set.of("glass_house", "chrome_saint")),
                "story fork did not unlock after its shared prerequisite");
        helper.assertTrue(StoryMissionCatalog.available(
                        Set.of("signal_in_the_static", "glass_house"), 10_000).stream()
                        .noneMatch(value -> value.id().equals("two_keys")),
                "multi-parent mission unlocked with only one completed parent");
        Set<String> bothParents = Set.of(
                "signal_in_the_static", "glass_house", "chrome_saint");
        helper.assertTrue(StoryMissionCatalog.available(bothParents, 419).stream()
                        .noneMatch(value -> value.id().equals("two_keys"))
                        && StoryMissionCatalog.available(bothParents, 420).stream()
                        .anyMatch(value -> value.id().equals("two_keys")),
                "Street Cred gate did not hold exactly below its configured threshold");
        int maximumGigCred = MissionCatalog.definitions().stream()
                .mapToInt(MissionCatalog.MissionDefinition::streetCred).max().orElseThrow();
        helper.assertTrue(definitions.stream().allMatch(
                        value -> value.encounter().streetCred() > maximumGigCred),
                "story missions must award materially more Street Cred than gigs");

        helper.assertTrue(rejected(storyRoot("a", List.of("missing"))),
                "story parser accepted a dangling prerequisite");
        JsonObject cycle = storyRoot("a", List.of("b"));
        cycle.getAsJsonArray("missions").add(storyEntry("b", List.of("a")));
        helper.assertTrue(rejected(cycle), "story parser accepted a dependency cycle");
        helper.succeed();
    }

    static void partyRewards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer online = helper.makeMockServerPlayerInLevel();
        StreetCredState.setStreetCred(online, 0);
        UUID offline = UUID.fromString("c0de0000-0000-0000-0000-000000000001");
        PartyService.ParticipantSnapshot participants = new PartyService.ParticipantSnapshot(
                java.util.Optional.empty(), List.of(online.getUUID(), offline));
        int before = inventoryCount(online);
        PartyService.RewardDistribution distribution = PartyService.splitEmmieReward(
                level, participants, 11);
        helper.assertTrue(distribution.shares().stream()
                        .mapToInt(PartyService.RewardShare::amount).sum() == 11
                        && distribution.shares().stream()
                        .mapToInt(PartyService.RewardShare::amount).max().orElseThrow()
                                - distribution.shares().stream()
                                .mapToInt(PartyService.RewardShare::amount).min().orElseThrow() <= 1,
                "party reward split lost currency or distributed remainder unevenly");
        int onlineShare = distribution.shares().stream()
                .filter(value -> value.playerId().equals(online.getUUID()))
                .findFirst().orElseThrow().amount();
        int offlineShare = distribution.shares().stream()
                .filter(value -> value.playerId().equals(offline))
                .findFirst().orElseThrow().amount();
        helper.assertTrue(inventoryCount(online) == before + onlineShare
                        && PartySavedData.get(level).pendingEmmies(offline) == offlineShare,
                "online/offline reward shares were not delivered through their proper paths");
        PartySavedData.get(level).takePendingEmmies(offline);

        PartyService.awardSharedStreetCred(level, "", participants, 37);
        helper.assertTrue(StreetCredState.getStreetCred(online) == 37,
                "party participant did not receive the full unsplit Street Cred award");
        PartySavedData.get(level).takePendingStreetCred(offline);
        UUID contract = UUID.randomUUID();
        helper.assertTrue(PartyService.markContractCompleted(level, contract)
                        && !PartyService.markContractCompleted(level, contract)
                        && PartyService.isContractCompleted(level, contract),
                "completed-contract ledger did not reject duplicate completion");

        UUID deferredPlayer = UUID.randomUUID();
        PartyService.ParticipantSnapshot settlementParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(online.getUUID(), deferredPlayer));
        UUID settlement = UUID.randomUUID();
        int emmieBeforeSettlement = inventoryCount(online);
        int credBeforeSettlement = StreetCredState.getStreetCred(online);
        PartyService.registerContract(level, settlement, settlementParticipants);
        helper.assertTrue(PartyService.settleContract(
                        level, settlement, settlementParticipants, 13, 9,
                        "atomic_settlement_test"),
                "contract settlement was not recorded");
        int onlineSettlementShare = inventoryCount(online) - emmieBeforeSettlement;
        int deferredSettlementShare = PartySavedData.get(level).pendingEmmies(deferredPlayer);
        helper.assertTrue(onlineSettlementShare + deferredSettlementShare == 13
                        && Math.abs(onlineSettlementShare - deferredSettlementShare) <= 1,
                "atomic settlement did not conserve its split reward");
        helper.assertTrue(StreetCredState.getStreetCred(online) == credBeforeSettlement + 9
                        && PartySavedData.get(level).takePendingStreetCred(deferredPlayer) == 9,
                "atomic settlement did not grant full Street Cred to each solo participant");
        helper.assertTrue(MissionPlayerData.completedStory(online)
                                .contains("atomic_settlement_test")
                        && PartySavedData.get(level).takeStoryCompletions(deferredPlayer)
                                .equals(List.of("atomic_settlement_test")),
                "atomic settlement did not preserve online and deferred story progression");
        helper.assertTrue(!PartyService.settleContract(
                        level, settlement, settlementParticipants, 13, 9,
                        "atomic_settlement_test")
                        && inventoryCount(online) - emmieBeforeSettlement
                                == onlineSettlementShare,
                "duplicate settlement paid the same contract twice");
        helper.assertTrue(PartyService.requiresContractClear(
                                level, settlement, online.getUUID())
                        && PartyService.requiresContractClear(
                                level, settlement, deferredPlayer),
                "terminal contract did not retain per-participant clear acknowledgements");
        PartyService.acknowledgeContractClear(level, settlement, online.getUUID());
        helper.assertTrue(!PartyService.requiresContractClear(
                                level, settlement, online.getUUID())
                        && PartyService.requiresContractClear(
                                level, settlement, deferredPlayer),
                "one participant acknowledgement cleared another participant's tombstone");
        PartyService.acknowledgeContractClear(level, settlement, deferredPlayer);
        PartySavedData.get(level).takePendingEmmies(deferredPlayer);
        helper.succeed();
    }

    private static boolean rejected(JsonObject root) {
        try {
            StoryMissionCatalog.parse(root);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static JsonObject storyRoot(String id, List<String> prerequisites) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", StoryMissionCatalog.SCHEMA_VERSION);
        JsonArray missions = new JsonArray();
        missions.add(storyEntry(id, prerequisites));
        root.add("missions", missions);
        return root;
    }

    private static JsonObject storyEntry(String id, List<String> prerequisites) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("type", "assassinate_target");
        value.addProperty("title", "Test " + id);
        value.addProperty("briefing", "Test story mission " + id);
        value.addProperty("target_name", "Target " + id);
        JsonArray districts = new JsonArray();
        districts.add("A");
        value.add("target_districts", districts);
        JsonObject reward = new JsonObject();
        reward.addProperty("min", 1);
        reward.addProperty("max", 1);
        value.add("reward_emmies", reward);
        value.addProperty("street_cred", 1);
        value.addProperty("activation_radius", 32);
        value.addProperty("guards", 1);
        value.addProperty("objective_radius", 16);
        JsonArray parents = new JsonArray();
        prerequisites.forEach(parents::add);
        value.add("prerequisites", parents);
        value.addProperty("required_street_cred", 0);
        return value;
    }

    private static int inventoryCount(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(CyberdeckItems.EMMIES.get())) count += stack.getCount();
        }
        return count;
    }
}
