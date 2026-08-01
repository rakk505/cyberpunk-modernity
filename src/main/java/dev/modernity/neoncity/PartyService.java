package dev.modernity.neoncity;

import com.example.cyberdeck.economy.Emmies;
import com.example.cyberdeck.player.StreetCredState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Server-authoritative party membership, shared Street Cred, and reward distribution. */
public final class PartyService {
    public static final long DEFAULT_INVITATION_TICKS = 20L * 120L;

    private static final String INVITATION_PARTY = "cyberdeck_party_invitation";
    private static final String INVITATION_LEADER = "cyberdeck_party_invitation_leader";
    private static final String INVITATION_EXPIRES = "cyberdeck_party_invitation_expires";

    private PartyService() {
    }

    /** Creates a solo party whose canonical Street Cred starts at the leader's current value. */
    public static Optional<PartySavedData.PartySnapshot> create(ServerPlayer leader) {
        ServerLevel level = level(leader);
        PartySavedData data = PartySavedData.get(level);
        if (data.partyFor(leader.getUUID()).isPresent()) {
            return Optional.empty();
        }
        UUID partyId;
        do {
            partyId = UUID.randomUUID();
        } while (data.party(partyId).isPresent());
        PartySavedData.PartySnapshot party = data.create(
                partyId, leader.getUUID(), StreetCredState.getStreetCred(leader));
        clearInvitation(leader);
        synchronizeParty(level, data, party);
        return Optional.of(party);
    }

    public static Optional<PartySavedData.PartySnapshot> partyOf(ServerPlayer player) {
        return PartySavedData.get(level(player)).partyFor(player.getUUID());
    }

    /** Stable party identifier for quest ownership; empty means the player is solo. */
    public static Optional<String> partyId(ServerPlayer player) {
        return partyOf(player).map(party -> party.id().toString());
    }

    public static Optional<PartySavedData.PartySnapshot> party(
            ServerLevel context, UUID partyId) {
        return PartySavedData.get(context).party(partyId);
    }

    /** Stores one replaceable, expiring invitation on the target player. */
    public static InviteResult invite(ServerPlayer inviter, ServerPlayer target) {
        return invite(inviter, target, DEFAULT_INVITATION_TICKS);
    }

    public static InviteResult invite(
            ServerPlayer inviter, ServerPlayer target, long durationTicks) {
        if (inviter == target || inviter.getUUID().equals(target.getUUID())) {
            return InviteResult.SELF;
        }
        ServerLevel level = level(inviter);
        if (target.level().getServer() != level.getServer()) {
            return InviteResult.TARGET_UNAVAILABLE;
        }
        PartySavedData data = PartySavedData.get(level);
        PartySavedData.PartySnapshot party = data.partyFor(inviter.getUUID()).orElse(null);
        if (party == null) {
            return InviteResult.INVITER_NOT_IN_PARTY;
        }
        if (!party.leader().equals(inviter.getUUID())) {
            return InviteResult.INVITER_NOT_LEADER;
        }
        if (data.partyFor(target.getUUID()).isPresent()) {
            return InviteResult.TARGET_ALREADY_IN_PARTY;
        }
        long now = currentTick(level);
        long duration = Math.max(1L, durationTicks);
        long expiresAt = now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
        writeInvitation(target, new Invitation(
                party.id(), inviter.getUUID(), expiresAt));
        return InviteResult.SENT;
    }

    /** Accepts the target player's current invitation after revalidating its party and leader. */
    public static AcceptResult accept(ServerPlayer player) {
        Invitation invitation = rawInvitation(player).orElse(null);
        if (invitation == null) {
            return AcceptResult.NO_INVITATION;
        }
        ServerLevel level = level(player);
        if (currentTick(level) >= invitation.expiresAt()) {
            clearInvitation(player);
            return AcceptResult.INVITATION_EXPIRED;
        }
        PartySavedData data = PartySavedData.get(level);
        if (data.partyFor(player.getUUID()).isPresent()) {
            clearInvitation(player);
            return AcceptResult.ALREADY_IN_PARTY;
        }
        PartySavedData.PartySnapshot party = data.party(invitation.partyId()).orElse(null);
        if (party == null) {
            clearInvitation(player);
            return AcceptResult.PARTY_NOT_FOUND;
        }
        if (!party.leader().equals(invitation.leaderId())) {
            clearInvitation(player);
            return AcceptResult.LEADER_CHANGED;
        }
        PartySavedData.PartySnapshot updated = data.addMember(
                party.id(), player.getUUID(), StreetCredState.getStreetCred(player)).orElse(null);
        if (updated == null) {
            clearInvitation(player);
            return AcceptResult.ALREADY_IN_PARTY;
        }
        clearInvitation(player);
        synchronizeParty(level, data, updated);
        return AcceptResult.ACCEPTED;
    }

    /** Leaves the party; a departing leader promotes the lowest sorted remaining UUID. */
    public static LeaveResult leave(ServerPlayer player) {
        ServerLevel level = level(player);
        PartySavedData data = PartySavedData.get(level);
        PartySavedData.MemberRemoval removal = data.removeMember(player.getUUID()).orElse(null);
        if (removal == null) {
            return LeaveResult.NOT_IN_PARTY;
        }
        applyStreetCredFloor(player, removal.previous().streetCred());
        data.clearStreetCredFloor(player.getUUID());
        clearInvitation(player);
        removal.remaining().ifPresent(party -> synchronizeParty(level, data, party));
        AmbientGigService.syncJournal(player);
        return removal.remaining().isPresent() ? LeaveResult.LEFT : LeaveResult.DISBANDED;
    }

    /** Disbands a party when invoked by its current leader. */
    public static DisbandResult disband(ServerPlayer leader) {
        ServerLevel level = level(leader);
        PartySavedData data = PartySavedData.get(level);
        PartySavedData.PartySnapshot party = data.partyFor(leader.getUUID()).orElse(null);
        if (party == null) {
            return DisbandResult.NOT_IN_PARTY;
        }
        if (!party.leader().equals(leader.getUUID())) {
            return DisbandResult.NOT_LEADER;
        }
        PartySavedData.PartySnapshot removed = data.disband(party.id()).orElseThrow();
        for (UUID memberId : removed.members()) {
            ServerPlayer member = level.getServer().getPlayerList().getPlayer(memberId);
            if (member == null) {
                data.queueStreetCredFloor(memberId, removed.streetCred());
            } else {
                applyStreetCredFloor(member, removed.streetCred());
                data.clearStreetCredFloor(memberId);
                AmbientGigService.syncJournal(member);
            }
        }
        return DisbandResult.DISBANDED;
    }

    /** Captures sorted participant UUIDs so later membership changes cannot alter a reward split. */
    public static ParticipantSnapshot participantSnapshot(ServerPlayer player) {
        return participantContext(player);
    }

    public static ParticipantSnapshot participantContext(ServerPlayer player) {
        PartySavedData.PartySnapshot party = partyOf(player).orElse(null);
        return party == null
                ? new ParticipantSnapshot(Optional.empty(), List.of(player.getUUID()))
                : new ParticipantSnapshot(Optional.of(party.id()), party.members());
    }

    public static List<ServerPlayer> onlineMembers(ServerPlayer player) {
        return onlineParticipants(level(player).getServer(), participantSnapshot(player));
    }

    public static List<ServerPlayer> onlineMembers(
            MinecraftServer server, ParticipantSnapshot participants) {
        return onlineParticipants(server, participants.playerIds());
    }

    public static List<ServerPlayer> onlineParticipants(
            MinecraftServer server, List<UUID> participantIds) {
        ArrayList<ServerPlayer> online = new ArrayList<>();
        for (UUID playerId : sortedParticipants(participantIds)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null) {
                online.add(player);
            }
        }
        return List.copyOf(online);
    }

    public static int sharedStreetCred(ServerPlayer player) {
        return partyOf(player)
                .map(PartySavedData.PartySnapshot::streetCred)
                .orElseGet(() -> StreetCredState.getStreetCred(player));
    }

    /** Awards unsplit Street Cred to the player's current party, or just the player when solo. */
    public static int awardSharedStreetCred(ServerPlayer player, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Street Cred award cannot be negative");
        }
        ServerLevel level = level(player);
        PartySavedData data = PartySavedData.get(level);
        PartySavedData.PartySnapshot party = data.partyFor(player.getUUID()).orElse(null);
        if (party == null) {
            StreetCredState.addStreetCred(player, amount);
            return StreetCredState.getStreetCred(player);
        }
        PartySavedData.PartySnapshot updated = data.addStreetCred(party.id(), amount).orElseThrow();
        synchronizeParty(level, data, updated);
        return updated.streetCred();
    }

    /** Awards a known party by stable UUID, useful when a quest stores its owning party. */
    public static OptionalInt awardPartyStreetCred(
            ServerLevel context, UUID partyId, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Street Cred award cannot be negative");
        }
        PartySavedData data = PartySavedData.get(context);
        PartySavedData.PartySnapshot updated = data.addStreetCred(partyId, amount).orElse(null);
        if (updated == null) {
            return OptionalInt.empty();
        }
        synchronizeParty(context, data, updated);
        return OptionalInt.of(updated.streetCred());
    }

    /**
     * Awards a quest's snapshotted group. A surviving party receives one canonical increment;
     * otherwise every participant receives or queues the full unsplit Street Cred award.
     */
    public static int awardSharedStreetCred(
            ServerLevel context,
            String partyId,
            List<UUID> participantIds,
            int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Street Cred award cannot be negative");
        }
        List<UUID> participants = sortedParticipants(participantIds);
        PartySavedData data = PartySavedData.get(context);
        PartySavedData.PartySnapshot updatedParty = parsePartyId(partyId)
                .flatMap(data::party)
                .flatMap(party -> data.addStreetCred(party.id(), amount))
                .orElse(null);
        int resultingCred = 0;
        if (updatedParty != null) {
            synchronizeParty(context, data, updatedParty);
            resultingCred = updatedParty.streetCred();
        }
        for (UUID participantId : participants) {
            if (updatedParty != null && updatedParty.contains(participantId)) {
                continue;
            }
            ServerPlayer participant = context.getServer().getPlayerList().getPlayer(participantId);
            if (participant == null) {
                data.addPendingStreetCred(participantId, amount);
            } else {
                StreetCredState.addStreetCred(participant, amount);
                resultingCred = Math.max(
                        resultingCred, StreetCredState.getStreetCred(participant));
            }
        }
        return resultingCred;
    }

    /** Splits one conserved emmie total across a fixed participant snapshot. */
    public static RewardDistribution splitEmmieReward(ServerPlayer owner, int totalEmmies) {
        return splitEmmieReward(level(owner), participantSnapshot(owner), totalEmmies);
    }

    public static RewardDistribution splitEmmieReward(
            ServerLevel context,
            List<UUID> participantIds,
            int totalEmmies) {
        return splitEmmieReward(
                context,
                new ParticipantSnapshot(Optional.empty(), participantIds),
                totalEmmies);
    }

    public static RewardDistribution splitEmmieReward(
            ServerLevel context,
            ParticipantSnapshot participants,
            int totalEmmies) {
        if (totalEmmies < 0) {
            throw new IllegalArgumentException("Emmie reward cannot be negative");
        }
        PartySavedData data = PartySavedData.get(context);
        int count = participants.playerIds().size();
        int quotient = totalEmmies / count;
        int remainder = totalEmmies % count;
        ArrayList<RewardShare> shares = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID playerId = participants.playerIds().get(index);
            int amount = quotient + (index < remainder ? 1 : 0);
            ServerPlayer player = context.getServer().getPlayerList().getPlayer(playerId);
            boolean deferred = player == null;
            if (amount > 0) {
                if (deferred) {
                    data.addPendingEmmies(playerId, amount);
                } else {
                    Emmies.give(player, amount);
                }
            }
            shares.add(new RewardShare(playerId, amount, deferred));
        }
        return new RewardDistribution(totalEmmies, shares);
    }

    /** Claims and clears the player's persisted offline emmie rewards. */
    public static int claimPendingRewards(ServerPlayer player) {
        int amount = PartySavedData.get(level(player)).takePendingEmmies(player.getUUID());
        if (amount > 0) {
            Emmies.give(player, amount);
        }
        return amount;
    }

    public static int claimPending(ServerPlayer player) {
        return claimPendingRewards(player);
    }

    public static void queueStoryCompletion(
            ServerLevel context, UUID playerId, String storyId) {
        PartySavedData.get(context).queueStoryCompletion(playerId, storyId);
    }

    public static List<String> claimPendingStoryIds(ServerPlayer player) {
        return PartySavedData.get(level(player)).takeStoryCompletions(player.getUUID());
    }

    public static void registerContract(
            ServerLevel context, UUID instanceId, ParticipantSnapshot participants) {
        PartySavedData.get(context).registerContract(instanceId, participants.playerIds());
    }

    /**
     * Durably stages every contract grant before marking the instance terminal, then delivers
     * staged grants to participants who are currently online. Repeated calls are no-ops.
     */
    public static boolean settleContract(
            ServerLevel context,
            UUID instanceId,
            ParticipantSnapshot participants,
            int totalEmmies,
            int streetCred,
            String storyId) {
        PartySavedData data = PartySavedData.get(context);
        if (!data.settleContract(
                instanceId,
                participants.partyId(),
                participants.playerIds(),
                totalEmmies,
                streetCred,
                storyId)) {
            return false;
        }

        participants.partyId().flatMap(data::party)
                .ifPresent(party -> synchronizeParty(context, data, party));
        for (ServerPlayer participant : onlineMembers(context.getServer(), participants)) {
            synchronizePlayer(participant);
            claimPendingRewards(participant);
            for (String completedStoryId : claimPendingStoryIds(participant)) {
                MissionPlayerData.completeStory(participant, completedStoryId);
            }
        }
        return true;
    }

    /** Atomically records a completed contract; false means it was already processed. */
    public static boolean markContractCompleted(ServerLevel context, UUID instanceId) {
        if (instanceId == null) {
            throw new IllegalArgumentException("Contract instance id is required");
        }
        return PartySavedData.get(context).markContractCompleted(instanceId);
    }

    public static boolean isContractCompleted(ServerLevel context, UUID instanceId) {
        return instanceId != null && PartySavedData.get(context).isContractCompleted(instanceId);
    }

    public static boolean isContractTerminal(ServerLevel context, UUID instanceId) {
        return instanceId != null && PartySavedData.get(context).isContractTerminal(instanceId);
    }

    public static boolean requiresContractClear(
            ServerLevel context, UUID instanceId, UUID playerId) {
        return instanceId != null && playerId != null
                && PartySavedData.get(context).requiresContractClear(instanceId, playerId);
    }

    public static void acknowledgeContractClear(
            ServerLevel context, UUID instanceId, UUID playerId) {
        PartySavedData.get(context).acknowledgeContractClear(instanceId, playerId);
    }

    public static void acknowledgeMissingContracts(ServerPlayer player) {
        PartySavedData.get(level(player)).acknowledgeMissingContracts(player.getUUID());
    }

    /** Applies canonical party progression and any deferred rewards after player login. */
    public static int onPlayerLogin(ServerPlayer player) {
        synchronizePlayer(player);
        invitation(player);
        int claimed = claimPendingRewards(player);
        if (claimed > 0) {
            player.sendSystemMessage(Component.literal(
                            "Party rewards delivered: " + claimed + " emmies.")
                    .withStyle(ChatFormatting.GREEN));
        }
        return claimed;
    }

    public static void synchronizePlayer(ServerPlayer player) {
        PartySavedData data = PartySavedData.get(level(player));
        PartySavedData.PartySnapshot party = data.partyFor(player.getUUID()).orElse(null);
        if (party != null) {
            int deferred = data.takePendingStreetCred(player.getUUID());
            PartySavedData.PartySnapshot updated = data.addStreetCred(
                    party.id(), deferred).orElse(party);
            synchronizeParty(level(player), data, updated);
            data.clearStreetCredFloor(player.getUUID());
            return;
        }
        int floor = data.takeStreetCredFloor(player.getUUID());
        applyStreetCredFloor(player, floor);
        StreetCredState.addStreetCred(player, data.takePendingStreetCred(player.getUUID()));
    }

    /** Returns a valid invitation, eagerly deleting malformed or expired data. */
    public static Optional<Invitation> invitation(ServerPlayer player) {
        Optional<Invitation> invitation = rawInvitation(player);
        if (invitation.isPresent()
                && currentTick(level(player)) >= invitation.get().expiresAt()) {
            clearInvitation(player);
            return Optional.empty();
        }
        return invitation;
    }

    public static boolean declineInvitation(ServerPlayer player) {
        boolean present = rawInvitation(player).isPresent();
        clearInvitation(player);
        return present;
    }

    /** Registers the user-level {@code /party} command tree. */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("party")
                .requires(CommandSourceStack::isPlayer)
                .executes(context -> info(context.getSource()))
                .then(Commands.literal("info")
                        .executes(context -> info(context.getSource())))
                .then(Commands.literal("create")
                        .executes(context -> createCommand(context.getSource())))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> inviteCommand(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("accept")
                        .executes(context -> acceptCommand(context.getSource())))
                .then(Commands.literal("decline")
                        .executes(context -> declineCommand(context.getSource())))
                .then(Commands.literal("leave")
                        .executes(context -> leaveCommand(context.getSource())))
                .then(Commands.literal("disband")
                        .executes(context -> disbandCommand(context.getSource()))));
    }

    private static int info(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PartySavedData.PartySnapshot party = partyOf(player).orElse(null);
        if (party == null) {
            source.sendSuccess(() -> Component.literal("You are not in a party."), false);
            return 1;
        }
        MinecraftServer server = level(player).getServer();
        String members = party.members().stream()
                .map(playerId -> playerName(server, playerId))
                .reduce((first, second) -> first + ", " + second)
                .orElse("");
        source.sendSuccess(() -> Component.literal(
                "Party " + shortId(party.id())
                        + " | Leader: " + playerName(server, party.leader())
                        + " | Street Cred: " + party.streetCred()
                        + " | Members: " + members), false);
        return 1;
    }

    private static int createCommand(CommandSourceStack source) throws CommandSyntaxException {
        if (create(source.getPlayerOrException()).isEmpty()) {
            source.sendFailure(Component.literal("You are already in a party."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Party created."), false);
        return 1;
    }

    private static int inviteCommand(CommandSourceStack source, ServerPlayer target)
            throws CommandSyntaxException {
        ServerPlayer inviter = source.getPlayerOrException();
        InviteResult result = invite(inviter, target);
        if (result != InviteResult.SENT) {
            source.sendFailure(Component.literal(inviteFailure(result)));
            return 0;
        }
        target.sendSystemMessage(Component.literal(
                        inviter.getGameProfile().name()
                                + " invited you to a party. Use /party accept or /party decline.")
                .withStyle(ChatFormatting.AQUA));
        source.sendSuccess(() -> Component.literal(
                "Invited " + target.getGameProfile().name() + "."), false);
        return 1;
    }

    private static int acceptCommand(CommandSourceStack source) throws CommandSyntaxException {
        AcceptResult result = accept(source.getPlayerOrException());
        if (result != AcceptResult.ACCEPTED) {
            source.sendFailure(Component.literal(acceptFailure(result)));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Party invitation accepted."), false);
        return 1;
    }

    private static int declineCommand(CommandSourceStack source) throws CommandSyntaxException {
        if (!declineInvitation(source.getPlayerOrException())) {
            source.sendFailure(Component.literal("You have no party invitation."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Party invitation declined."), false);
        return 1;
    }

    private static int leaveCommand(CommandSourceStack source) throws CommandSyntaxException {
        LeaveResult result = leave(source.getPlayerOrException());
        if (result == LeaveResult.NOT_IN_PARTY) {
            source.sendFailure(Component.literal("You are not in a party."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(result == LeaveResult.DISBANDED
                ? "Party disbanded." : "You left the party."), false);
        return 1;
    }

    private static int disbandCommand(CommandSourceStack source) throws CommandSyntaxException {
        DisbandResult result = disband(source.getPlayerOrException());
        if (result != DisbandResult.DISBANDED) {
            source.sendFailure(Component.literal(result == DisbandResult.NOT_LEADER
                    ? "Only the party leader can disband it." : "You are not in a party."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Party disbanded."), false);
        return 1;
    }

    private static String inviteFailure(InviteResult result) {
        return switch (result) {
            case SELF -> "You cannot invite yourself.";
            case INVITER_NOT_IN_PARTY -> "Create a party before inviting players.";
            case INVITER_NOT_LEADER -> "Only the party leader can invite players.";
            case TARGET_ALREADY_IN_PARTY -> "That player is already in a party.";
            case TARGET_UNAVAILABLE -> "That player is unavailable.";
            case SENT -> "Invitation sent.";
        };
    }

    private static String acceptFailure(AcceptResult result) {
        return switch (result) {
            case NO_INVITATION -> "You have no party invitation.";
            case INVITATION_EXPIRED -> "That party invitation expired.";
            case ALREADY_IN_PARTY -> "You are already in a party.";
            case PARTY_NOT_FOUND -> "That party no longer exists.";
            case LEADER_CHANGED -> "That invitation is no longer valid.";
            case ACCEPTED -> "Invitation accepted.";
        };
    }

    private static void synchronizeParty(
            ServerLevel context,
            PartySavedData data,
            PartySavedData.PartySnapshot party) {
        MinecraftServer server = context.getServer();
        for (UUID memberId : party.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member == null) {
                data.queueStreetCredFloor(memberId, party.streetCred());
            } else {
                StreetCredState.setStreetCred(member, party.streetCred());
                data.clearStreetCredFloor(memberId);
                AmbientGigService.syncJournal(member);
            }
        }
    }

    private static void applyStreetCredFloor(ServerPlayer player, int floor) {
        if (floor > StreetCredState.getStreetCred(player)) {
            StreetCredState.setStreetCred(player, floor);
        }
    }

    private static Optional<Invitation> rawInvitation(ServerPlayer player) {
        CompoundTag persisted = player.getPersistentData()
                .getCompound(Player.PERSISTED_NBT_TAG).orElse(null);
        if (persisted == null) {
            return Optional.empty();
        }
        try {
            String partyId = persisted.getString(INVITATION_PARTY).orElse("");
            String leaderId = persisted.getString(INVITATION_LEADER).orElse("");
            long expiresAt = persisted.getLong(INVITATION_EXPIRES).orElse(0L);
            if (partyId.isBlank() || leaderId.isBlank() || expiresAt <= 0L) {
                clearInvitation(player);
                return Optional.empty();
            }
            return Optional.of(new Invitation(
                    UUID.fromString(partyId), UUID.fromString(leaderId), expiresAt));
        } catch (IllegalArgumentException exception) {
            clearInvitation(player);
            return Optional.empty();
        }
    }

    private static void writeInvitation(ServerPlayer player, Invitation invitation) {
        CompoundTag root = player.getPersistentData();
        CompoundTag persisted = root.getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        persisted.putString(INVITATION_PARTY, invitation.partyId().toString());
        persisted.putString(INVITATION_LEADER, invitation.leaderId().toString());
        persisted.putLong(INVITATION_EXPIRES, invitation.expiresAt());
        root.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private static void clearInvitation(ServerPlayer player) {
        player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).ifPresent(persisted -> {
            persisted.remove(INVITATION_PARTY);
            persisted.remove(INVITATION_LEADER);
            persisted.remove(INVITATION_EXPIRES);
        });
    }

    private static List<UUID> sortedParticipants(List<UUID> participantIds) {
        if (participantIds == null || participantIds.isEmpty()) {
            throw new IllegalArgumentException("A participant snapshot cannot be empty");
        }
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(participantIds);
        if (unique.contains(null)) {
            throw new IllegalArgumentException("Participant UUIDs cannot be null");
        }
        ArrayList<UUID> sorted = new ArrayList<>(unique);
        sorted.sort(UUID::compareTo);
        return List.copyOf(sorted);
    }

    private static Optional<UUID> parsePartyId(String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(partyId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static long currentTick(ServerLevel context) {
        return context.getServer().overworld().getGameTime();
    }

    private static ServerLevel level(ServerPlayer player) {
        return (ServerLevel) player.level();
    }

    private static String playerName(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player == null ? shortId(playerId) : player.getGameProfile().name();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    public record Invitation(UUID partyId, UUID leaderId, long expiresAt) {
    }

    /** Immutable, UUID-sorted participant set captured when a mission or gig starts. */
    public static final class ParticipantSnapshot extends AbstractList<UUID> {
        private final Optional<UUID> partyId;
        private final List<UUID> playerIds;

        public ParticipantSnapshot(Optional<UUID> partyId, List<UUID> playerIds) {
            this.partyId = partyId == null ? Optional.empty() : partyId;
            this.playerIds = sortedParticipants(playerIds);
        }

        public Optional<UUID> partyId() {
            return partyId;
        }

        public List<UUID> playerIds() {
            return playerIds;
        }

        @Override
        public UUID get(int index) {
            return playerIds.get(index);
        }

        @Override
        public int size() {
            return playerIds.size();
        }
    }

    public record RewardShare(UUID playerId, int amount, boolean deferred) {
    }

    public record RewardDistribution(int totalEmmies, List<RewardShare> shares) {
        public RewardDistribution {
            shares = List.copyOf(shares);
        }
    }

    public enum InviteResult {
        SENT,
        SELF,
        INVITER_NOT_IN_PARTY,
        INVITER_NOT_LEADER,
        TARGET_ALREADY_IN_PARTY,
        TARGET_UNAVAILABLE
    }

    public enum AcceptResult {
        ACCEPTED,
        NO_INVITATION,
        INVITATION_EXPIRED,
        ALREADY_IN_PARTY,
        PARTY_NOT_FOUND,
        LEADER_CHANGED
    }

    public enum LeaveResult {
        LEFT,
        DISBANDED,
        NOT_IN_PARTY
    }

    public enum DisbandResult {
        DISBANDED,
        NOT_IN_PARTY,
        NOT_LEADER
    }
}
