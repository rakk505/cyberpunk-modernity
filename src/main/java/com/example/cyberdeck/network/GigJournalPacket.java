package com.example.cyberdeck.network;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.mission.GigJournalClient;
import dev.modernity.neoncity.AmbientGigService;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MissionCatalog;
import dev.modernity.neoncity.MissionService;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Owner-only snapshot of accepted contract history and the current district gig board. */
public record GigJournalPacket(
        List<Contract> contracts,
        List<AvailableGig> availableGigs) implements CustomPacketPayload {
    public static final int MAX_CONTRACTS = 72;
    public static final int MAX_AVAILABLE_GIGS = 5;
    private static final int MAX_ID = 96;
    private static final int MAX_TEXT = 512;

    public static final Type<GigJournalPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "gig_journal"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GigJournalPacket> STREAM_CODEC =
            StreamCodec.ofMember(GigJournalPacket::encode, GigJournalPacket::decode);

    public GigJournalPacket {
        contracts = bounded(contracts, MAX_CONTRACTS);
        availableGigs = bounded(availableGigs, MAX_AVAILABLE_GIGS);
    }

    public static GigJournalPacket snapshot(ServerPlayer player) {
        List<Contract> contracts = new ArrayList<>();
        for (dev.modernity.neoncity.StoryMissionCatalog.StoryMission story
                : MissionService.availableStoryMissions(player)) {
            dev.modernity.neoncity.StoryMissionCatalog.StoryNode first = story.readyNodes(
                    java.util.Set.of()).getFirst();
            int targetX = player.getBlockX();
            int targetZ = player.getBlockZ();
            if (dev.modernity.neoncity.NeonCityGenerator.isMegacityWorld(
                    player.level().getServer().overworld())) {
                dev.modernity.neoncity.MegacityLayout.Node center =
                        dev.modernity.neoncity.NeonCityGenerator.layout().node(
                                story.primaryDistrict());
                targetX = center.x();
                targetZ = center.z();
            }
            contracts.add(new Contract(
                    UUID.nameUUIDFromBytes(("cyberdeck:mainline:" + story.id())
                            .getBytes(StandardCharsets.UTF_8)),
                    MissionService.ContractKind.STORY_MISSION.ordinal(),
                    story.encounter().type().ordinal(),
                    MissionService.JournalStatus.AVAILABLE.ordinal(),
                    story.id(), story.encounter().title(),
                    story.chapter() + " // " + story.encounter().briefing(),
                    "Begin at " + first.location(), story.primaryDistrict().ordinal(),
                    targetX, targetZ, story.encounter().rewardMin(),
                    story.encounter().streetCred(), 0L, player.level().getGameTime()));
        }
        contracts.addAll(MissionService.journalEntries(player).stream()
                .map(entry -> new Contract(
                        entry.instanceId(), entry.kind().ordinal(), entry.type().ordinal(),
                        entry.status().ordinal(), entry.definitionId(), entry.title(),
                        entry.briefing(), entry.objective(), entry.targetDistrict().ordinal(),
                        entry.targetX(), entry.targetZ(), entry.reward(), entry.streetCred(),
                        entry.acceptedTick(), entry.updatedTick()))
                .toList());
        List<AvailableGig> available = availableGigs(AmbientGigService.availableOffers(player));
        return new GigJournalPacket(List.copyOf(contracts), available);
    }

    /** Data-only journal projection shared with fixed-catalog runtime audits. */
    public static List<AvailableGig> availableGigs(
            List<AmbientGigService.DiscoveredGig> discoveredGigs) {
        return discoveredGigs.stream()
                .map(discovered -> from(discovered.offerId(), discovered.offer()))
                .toList();
    }

    private static AvailableGig from(UUID offerId, MissionService.MissionOffer offer) {
        return new AvailableGig(
                offerId, offer.type().ordinal(), offer.definitionId(), offer.title(),
                offer.briefing(), offer.objective(), offer.targetDistrictOrdinal(),
                offer.targetX(), offer.targetZ(), offer.reward(), offer.streetCred());
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(contracts.size());
        for (Contract contract : contracts) contract.encode(buffer);
        buffer.writeVarInt(availableGigs.size());
        for (AvailableGig gig : availableGigs) gig.encode(buffer);
    }

    private static GigJournalPacket decode(RegistryFriendlyByteBuf buffer) {
        int contractCount = boundedCount(buffer, MAX_CONTRACTS, "journal contract");
        List<Contract> contracts = new ArrayList<>(contractCount);
        for (int index = 0; index < contractCount; index++) contracts.add(Contract.decode(buffer));
        int gigCount = boundedCount(buffer, MAX_AVAILABLE_GIGS, "available gig");
        List<AvailableGig> gigs = new ArrayList<>(gigCount);
        for (int index = 0; index < gigCount; index++) gigs.add(AvailableGig.decode(buffer));
        return new GigJournalPacket(contracts, gigs);
    }

    private static int boundedCount(RegistryFriendlyByteBuf buffer, int maximum, String label) {
        int count = buffer.readVarInt();
        if (count < 0 || count > maximum) {
            throw new DecoderException("Invalid " + label + " count: " + count);
        }
        return count;
    }

    private static void validateOrdinal(int ordinal, int length, String label) {
        if (ordinal < 0 || ordinal >= length) {
            throw new DecoderException("Invalid " + label + " ordinal: " + ordinal);
        }
    }

    private static void writeUuid(RegistryFriendlyByteBuf buffer, UUID value) {
        buffer.writeLong(value.getMostSignificantBits());
        buffer.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(RegistryFriendlyByteBuf buffer) {
        return new UUID(buffer.readLong(), buffer.readLong());
    }

    private static <T> List<T> bounded(List<T> values, int maximum) {
        if (values == null || values.isEmpty()) return List.of();
        return List.copyOf(values.subList(0, Math.min(values.size(), maximum)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GigJournalPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> GigJournalClient.receive(packet));
    }

    public record Contract(
            UUID instanceId,
            int kindOrdinal,
            int typeOrdinal,
            int statusOrdinal,
            String definitionId,
            String title,
            String briefing,
            String objective,
            int districtOrdinal,
            int targetX,
            int targetZ,
            int reward,
            int streetCred,
            long acceptedTick,
            long updatedTick) {
        public Contract {
            if (instanceId == null) throw new IllegalArgumentException("Contract ID is required");
            definitionId = safe(definitionId, MAX_ID);
            title = safe(title, MAX_TEXT);
            briefing = safe(briefing, MAX_TEXT);
            objective = safe(objective, MAX_TEXT);
            reward = Math.max(0, reward);
            streetCred = Math.max(0, streetCred);
        }

        private void encode(RegistryFriendlyByteBuf buffer) {
            writeUuid(buffer, instanceId);
            buffer.writeVarInt(kindOrdinal);
            buffer.writeVarInt(typeOrdinal);
            buffer.writeVarInt(statusOrdinal);
            buffer.writeUtf(definitionId, MAX_ID);
            buffer.writeUtf(title, MAX_TEXT);
            buffer.writeUtf(briefing, MAX_TEXT);
            buffer.writeUtf(objective, MAX_TEXT);
            buffer.writeVarInt(districtOrdinal);
            buffer.writeInt(targetX);
            buffer.writeInt(targetZ);
            buffer.writeVarInt(reward);
            buffer.writeVarInt(streetCred);
            buffer.writeLong(acceptedTick);
            buffer.writeLong(updatedTick);
        }

        private static Contract decode(RegistryFriendlyByteBuf buffer) {
            UUID instanceId = readUuid(buffer);
            int kind = buffer.readVarInt();
            int type = buffer.readVarInt();
            int status = buffer.readVarInt();
            validateOrdinal(kind, MissionService.ContractKind.values().length, "contract kind");
            validateOrdinal(type, MissionCatalog.MissionType.values().length, "mission type");
            validateOrdinal(status, MissionService.JournalStatus.values().length, "journal status");
            String definitionId = buffer.readUtf(MAX_ID);
            String title = buffer.readUtf(MAX_TEXT);
            String briefing = buffer.readUtf(MAX_TEXT);
            String objective = buffer.readUtf(MAX_TEXT);
            int district = buffer.readVarInt();
            validateOrdinal(district, District.values().length, "district");
            return new Contract(
                    instanceId, kind, type, status,
                    definitionId, title, briefing, objective, district,
                    buffer.readInt(), buffer.readInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readLong(), buffer.readLong());
        }
    }

    public record AvailableGig(
            UUID offerId,
            int typeOrdinal,
            String definitionId,
            String title,
            String briefing,
            String objective,
            int districtOrdinal,
            int targetX,
            int targetZ,
            int reward,
            int streetCred) {
        public AvailableGig {
            if (offerId == null) throw new IllegalArgumentException("Gig offer ID is required");
            definitionId = safe(definitionId, MAX_ID);
            title = safe(title, MAX_TEXT);
            briefing = safe(briefing, MAX_TEXT);
            objective = safe(objective, MAX_TEXT);
            reward = Math.max(0, reward);
            streetCred = Math.max(0, streetCred);
        }

        private void encode(RegistryFriendlyByteBuf buffer) {
            writeUuid(buffer, offerId);
            buffer.writeVarInt(typeOrdinal);
            buffer.writeUtf(definitionId, MAX_ID);
            buffer.writeUtf(title, MAX_TEXT);
            buffer.writeUtf(briefing, MAX_TEXT);
            buffer.writeUtf(objective, MAX_TEXT);
            buffer.writeVarInt(districtOrdinal);
            buffer.writeInt(targetX);
            buffer.writeInt(targetZ);
            buffer.writeVarInt(reward);
            buffer.writeVarInt(streetCred);
        }

        private static AvailableGig decode(RegistryFriendlyByteBuf buffer) {
            UUID offerId = readUuid(buffer);
            int type = buffer.readVarInt();
            validateOrdinal(type, MissionCatalog.MissionType.values().length, "mission type");
            String definitionId = buffer.readUtf(MAX_ID);
            String title = buffer.readUtf(MAX_TEXT);
            String briefing = buffer.readUtf(MAX_TEXT);
            String objective = buffer.readUtf(MAX_TEXT);
            int district = buffer.readVarInt();
            validateOrdinal(district, District.values().length, "district");
            return new AvailableGig(
                    offerId, type, definitionId, title, briefing, objective, district,
                    buffer.readInt(), buffer.readInt(), buffer.readVarInt(), buffer.readVarInt());
        }
    }

    private static String safe(String value, int maximumLength) {
        if (value == null) return "";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
