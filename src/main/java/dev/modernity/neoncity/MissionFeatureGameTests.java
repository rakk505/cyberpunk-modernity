package dev.modernity.neoncity;

import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.player.StreetCredState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

/** Focused progression invariants kept separate from the four objective end-to-end tests. */
final class MissionFeatureGameTests {
    private MissionFeatureGameTests() {
    }

    static void storyDag(GameTestHelper helper) {
        List<StoryMissionCatalog.StoryMission> definitions = StoryMissionCatalog.definitions();
        helper.assertTrue(definitions.size() == 5, "story catalog lost its five-mission mainline");
        List<StoryMissionCatalog.StoryMission> roots = StoryMissionCatalog.available(Set.of(), 0);
        helper.assertTrue(roots.size() == 1
                        && roots.getFirst().id().equals("m01_deliver_datashards"),
                "story DAG must begin at one deterministic root");
        List<String> expectedOrder = List.of(
                "m01_deliver_datashards",
                "m02_assassinate_g_exec",
                "m03_steal_weights",
                "m04_assassinate_fixer",
                "m05_kill_cyberpsycho");
        java.util.HashSet<String> completed = new java.util.HashSet<>();
        for (String expected : expectedOrder) {
            List<StoryMissionCatalog.StoryMission> available =
                    StoryMissionCatalog.available(Set.copyOf(completed), 0);
            helper.assertTrue(available.size() == 1 && available.getFirst().id().equals(expected),
                    "completing one mainline mission did not unlock exactly its successor");
            completed.add(expected);
        }
        helper.assertTrue(StoryMissionCatalog.available(Set.copyOf(completed), 0).isEmpty(),
                "completed mainline still exposed an available mission");
        StoryMissionCatalog.StoryMission oFortress =
                StoryMissionCatalog.definition("m03_steal_weights");
        helper.assertTrue(oFortress.requestedFloors() == 5
                        && oFortress.enemiesPerFloor().equals(List.of(4, 5, 5, 4, 2))
                        && definitions.stream().allMatch(value -> value.requiredStreetCred() == 0),
                "mainline floor scale or always-available unlock policy regressed");
        helper.assertTrue(StoryMissionCatalog.characters().size() == 7
                        && StoryMissionCatalog.character("fog_mother").skinVariant() == 1,
                "mainline character/skin catalog is incomplete");
        for (int floors = 3; floors <= 5; floors++) {
            MissionBuildingPlanner.Site tower = MainlineBuildingGenerator.createSite(
                    District.O_CORP, "tower_test_" + floors,
                    helper.absolutePos(new net.minecraft.core.BlockPos(floors * 20, 3, 0)),
                    floors, 1000L + floors);
            helper.assertTrue(tower.floorYs().size() == floors
                            && tower.floorMasks().stream().allMatch(mask -> mask.cells().size() == 100)
                            && tower.stairs().size() == floors - 1
                            && tower.target().getY() == tower.floorYs().getLast(),
                    "purpose-built mainline tower lost authored floor topology");
        }

        MainlineQuestData progress = MainlineQuestData.get(helper.getLevel());
        UUID progressId = UUID.randomUUID();
        MissionService.ContractContext progressContext = new MissionService.ContractContext(
                MissionService.ContractKind.STORY_MISSION, 15, progressId,
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(UUID.randomUUID())),
                false, false);
        helper.assertTrue(MainlineQuestService.ensureProgress(
                        helper.getLevel(), progressContext, "m01_deliver_datashards")
                        && !MainlineQuestService.ensureProgress(
                                helper.getLevel(), progressContext, "m01_deliver_datashards"),
                "mainline save recovery was not idempotent");
        StoryMissionCatalog.StoryNode first = MainlineQuestService.currentNode(
                helper.getLevel(), progressContext).orElseThrow();
        helper.assertTrue(first.id().equals("m01_talk_jerry")
                        && progress.completeNode(progressId, first.id())
                        && !progress.completeNode(progressId, first.id())
                        && MainlineQuestService.currentNode(helper.getLevel(), progressContext)
                                .map(StoryMissionCatalog.StoryNode::id)
                                .filter("m01_travel_highway"::equals).isPresent(),
                "mainline node progress was not atomic and idempotent");
        progress.removeProgress(progressId);

        MissionBuildingPlanner.Site reserved = MainlineBuildingGenerator.createSite(
                District.G_CORP, "reservation_test",
                new net.minecraft.core.BlockPos(20_000, 73, 20_000), 3, 2001L);
        MissionBuildingPlanner.Site overlapping = MainlineBuildingGenerator.createSite(
                District.G_CORP, "overlap_test",
                new net.minecraft.core.BlockPos(20_008, 73, 20_008), 3, 2002L);
        MissionBuildingPlanner.Site separate = MainlineBuildingGenerator.createSite(
                District.G_CORP, "separate_test",
                new net.minecraft.core.BlockPos(20_080, 73, 20_080), 3, 2003L);
        progress.putSite("__gametest_reservation", reserved);
        helper.assertTrue(progress.conflicts(overlapping, null)
                        && !progress.conflicts(overlapping, "__gametest_reservation")
                        && !progress.conflicts(separate, null),
                "permanent mainline reservation did not exclude overlapping gig sites");

        helper.assertTrue(rejected(storyRoot("a", List.of("missing"))),
                "story parser accepted a dangling prerequisite");
        JsonObject cycle = storyRoot("a", List.of("b"));
        cycle.getAsJsonArray("missions").add(storyEntry("b", List.of("a")));
        helper.assertTrue(rejected(cycle), "story parser accepted a dependency cycle");
        helper.succeed();
    }

    static void partyRewards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer online = makeUniquePlayer(helper, "rewards");
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
        disconnect(online);
        helper.succeed();
    }

    static ServerPlayer makeUniquePlayer(GameTestHelper helper, String prefix) {
        UUID playerId = UUID.randomUUID();
        String name = (prefix + "-" + playerId.toString().substring(0, 7));
        if (name.length() > 16) name = name.substring(0, 16);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(playerId, name), false);
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                cookie.gameProfile(),
                cookie.clientInformation()) {
            @Override
            public GameType gameMode() {
                return GameType.SURVIVAL;
            }
        };
        GameType.SURVIVAL.updatePlayerAbilities(player.getAbilities());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    static void disconnect(ServerPlayer player) {
        if (player.connection != null) {
            player.connection.disconnect(Component.literal("GameTest complete"));
        }
    }

    static void gigBoardLifecycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MegacityLayout layout = MegacityLayout.create(0xC1A0B04DL);
        UUID ownerId = UUID.randomUUID();
        AmbientGigData.OwnerKey owner = new AmbientGigData.OwnerKey(false, ownerId);
        UUID partyMember = UUID.randomUUID();
        AmbientGigData.OwnerKey firstPartyOwner = AmbientGigService.ownerForMembers(
                List.of(ownerId, partyMember));
        AmbientGigData.OwnerKey recreatedPartyOwner = AmbientGigService.ownerForMembers(
                List.of(partyMember, ownerId));
        helper.assertTrue(firstPartyOwner.equals(recreatedPartyOwner)
                        && firstPartyOwner.party()
                        && AmbientGigService.ownerForMembers(List.of(ownerId)).equals(owner),
                "gig board ownership changed with party identity/order or failed solo normalization");
        PartySavedData partyData = PartySavedData.get(level);
        UUID firstPartyId = UUID.randomUUID();
        PartySavedData.PartySnapshot firstSoloParty = partyData.create(firstPartyId, ownerId, 0);
        AmbientGigData.OwnerKey firstSoloPartyOwner = AmbientGigService.ownerForMembers(
                firstSoloParty.members());
        partyData.disband(firstPartyId).orElseThrow();
        UUID recreatedPartyId = UUID.randomUUID();
        PartySavedData.PartySnapshot recreatedSoloParty =
                partyData.create(recreatedPartyId, ownerId, 0);
        AmbientGigData.OwnerKey recreatedSoloPartyOwner = AmbientGigService.ownerForMembers(
                recreatedSoloParty.members());
        partyData.disband(recreatedPartyId).orElseThrow();
        helper.assertTrue(!firstPartyId.equals(recreatedPartyId)
                        && firstSoloPartyOwner.equals(owner)
                        && recreatedSoloPartyOwner.equals(owner),
                "recreating a one-member party minted a new gig board owner");
        List<AmbientGigData.StoredOffer> first = AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.A_CORP, 0L);
        List<AmbientGigData.StoredOffer> repeated = AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.A_CORP, 0L);
        List<AmbientGigData.StoredOffer> refreshed = AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.A_CORP, 1L);
        helper.assertTrue(first.size() == AmbientGigService.OFFERS_PER_DISTRICT
                        && first.equals(repeated)
                        && first.stream().map(AmbientGigData.StoredOffer::id).distinct().count()
                                == AmbientGigService.OFFERS_PER_DISTRICT,
                "district board was not a stable set of five unique offers");
        helper.assertTrue(first.stream().allMatch(offer -> {
                    MegacityLayout.Location location = layout.locateDistrict(
                            offer.targetX(), offer.targetZ());
                    return location.insideCity() && location.district() == District.A_CORP;
                }),
                "generated gig escaped its owning district");
        helper.assertTrue(!first.stream().map(AmbientGigData.StoredOffer::id).toList()
                        .equals(refreshed.stream().map(AmbientGigData.StoredOffer::id).toList()),
                "eligible board refresh reused the previous offer identities");

        AmbientGigData data = AmbientGigData.get(level);
        data.replace(owner, District.A_CORP, 0L, first);
        data.replace(owner, District.B_CORP, 0L, AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.B_CORP, 0L));
        helper.assertTrue(!data.pool(owner, District.A_CORP).orElseThrow().refreshEligible(),
                "new district board began refresh-eligible");
        AmbientGigData.StoredOffer accepted = first.getFirst();
        helper.assertTrue(data.removeOffer(owner, District.A_CORP, accepted.id())
                        && !data.removeOffer(owner, District.A_CORP, accepted.id())
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().size()
                                == AmbientGigService.OFFERS_PER_DISTRICT - 1,
                "stable offer acceptance was not idempotent");
        List<UUID> remainingIds = data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                .map(AmbientGigData.StoredOffer::id).toList();
        helper.assertTrue(!AmbientGigService.ensureBoard(level, owner, District.A_CORP)
                        && data.pool(owner, District.A_CORP).orElseThrow().generation() == 0L
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                                .map(AmbientGigData.StoredOffer::id).toList().equals(remainingIds),
                "re-entering before an external completion replenished accepted gigs");

        PartyService.ParticipantSnapshot solo = new PartyService.ParticipantSnapshot(
                java.util.Optional.empty(), List.of(ownerId));
        data.setLastDistrict(ownerId, District.A_CORP);
        UUID refreshCompletion = UUID.randomUUID();
        helper.assertTrue(AmbientGigService.recordCompletion(
                        level, refreshCompletion, solo, District.B_CORP),
                "first external completion was rejected");
        helper.assertTrue(data.pool(owner, District.A_CORP).orElseThrow().refreshPending()
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshEligible(),
                "external completion was not held pending while an offline owner remained inside");
        data.setLastDistrict(ownerId, null);
        AmbientGigService.promotePendingRefreshes(level, owner, solo.playerIds());
        helper.assertTrue(data.pool(owner, District.A_CORP).orElseThrow().refreshEligible()
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshPending()
                        && !data.pool(owner, District.B_CORP).orElseThrow().refreshEligible(),
                "completion did not arm only boards outside the completed district");
        helper.assertTrue(AmbientGigService.ensureBoard(level, owner, District.A_CORP)
                        && data.pool(owner, District.A_CORP).orElseThrow().generation() == 1L
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().size()
                                == AmbientGigService.OFFERS_PER_DISTRICT
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                                .map(AmbientGigData.StoredOffer::id)
                                .noneMatch(remainingIds::contains)
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshEligible(),
                "qualified re-entry did not advance and restore the district board");
        List<UUID> refreshedIds = data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                .map(AmbientGigData.StoredOffer::id).toList();
        helper.assertTrue(!AmbientGigService.recordCompletion(
                                level, refreshCompletion, solo, District.B_CORP)
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshEligible()
                        && !AmbientGigService.ensureBoard(level, owner, District.A_CORP)
                        && data.pool(owner, District.A_CORP).orElseThrow().generation() == 1L
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                                .map(AmbientGigData.StoredOffer::id).toList().equals(refreshedIds),
                "duplicate completion refreshed a later board generation");
        var ops = level.registryAccess().createSerializationContext(
                com.mojang.serialization.JsonOps.INSTANCE);
        com.google.gson.JsonElement encodedBoards = AmbientGigData.TYPE.codec()
                .encodeStart(ops, data)
                .getOrThrow(message -> helper.assertionException(
                        net.minecraft.network.chat.Component.literal(
                                "gig boards must encode: " + message)));
        AmbientGigData decodedBoards = AmbientGigData.TYPE.codec()
                .parse(ops, encodedBoards)
                .getOrThrow(message -> helper.assertionException(
                        net.minecraft.network.chat.Component.literal(
                                "gig boards must decode: " + message)));
        helper.assertTrue(!decodedBoards.claimCompletion(refreshCompletion),
                "completion idempotency was not preserved by the saved-data codec");

        UUID churnLeader = UUID.randomUUID();
        UUID churnMember = UUID.randomUUID();
        UUID churnPartyId = UUID.randomUUID();
        partyData.create(churnPartyId, churnLeader, 0);
        PartySavedData.PartySnapshot churnParty = partyData.addMember(
                churnPartyId, churnMember, 0).orElseThrow();
        PartyService.ParticipantSnapshot churnParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.of(churnPartyId), churnParty.members());
        AmbientGigData.OwnerKey oldGroupOwner = AmbientGigService.ownerForMembers(
                churnParty.members());
        AmbientGigData.OwnerKey churnLeaderOwner = AmbientGigService.ownerForMembers(
                List.of(churnLeader));
        AmbientGigData.OwnerKey churnMemberOwner = AmbientGigService.ownerForMembers(
                List.of(churnMember));
        data.replace(oldGroupOwner, District.A_CORP, 0L, List.of());
        partyData.disband(churnPartyId).orElseThrow();
        data.replace(churnLeaderOwner, District.A_CORP, 0L, List.of());
        data.replace(churnMemberOwner, District.A_CORP, 0L, List.of());
        UUID churnCompletion = UUID.randomUUID();
        helper.assertTrue(AmbientGigService.recordCompletion(
                                level, churnCompletion, churnParticipants, District.B_CORP)
                        && data.pool(churnLeaderOwner, District.A_CORP)
                                .orElseThrow().refreshEligible()
                        && data.pool(churnMemberOwner, District.A_CORP)
                                .orElseThrow().refreshEligible()
                        && !data.pool(oldGroupOwner, District.A_CORP)
                                .orElseThrow().refreshEligible(),
                "completion after disband refreshed the orphaned group instead of current owners");

        UUID secondParticipant = UUID.randomUUID();
        PartyService.ParticipantSnapshot journalParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(ownerId, secondParticipant));
        UUID contractId = UUID.randomUUID();
        MissionService.ContractContext context = new MissionService.ContractContext(
                MissionService.ContractKind.GIG, 17, contractId,
                journalParticipants, false, false);
        MissionService.ActiveMission mission = new MissionService.ActiveMission(
                accepted.definitionId(), MissionCatalog.definition(accepted.definitionId()).type(),
                "Journal Test", "Persistent briefing", "Complete the test",
                District.A_CORP,
                new net.minecraft.core.BlockPos(
                        accepted.targetX(), NeonCityGenerator.CITY_GROUND_Y + 1,
                        accepted.targetZ()),
                accepted.reward(), "", "", 0, 100L);
        MissionJournalData journal = MissionJournalData.get(level);
        journal.accept(journalParticipants, context, mission, 100L);
        journal.status(contractId, MissionService.JournalStatus.COMPLETED, 200L);
        journal.accept(journalParticipants, context, mission, 300L);
        helper.assertTrue(journal.entries(ownerId).getFirst().status()
                                == MissionService.JournalStatus.COMPLETED
                        && journal.entries(secondParticipant).getFirst().status()
                                == MissionService.JournalStatus.COMPLETED,
                "offline journal status was lost or overwritten by active reconciliation");
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
        root.addProperty("schema_version", 1);
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
