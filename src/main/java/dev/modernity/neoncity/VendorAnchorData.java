package dev.modernity.neoncity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent server authority for generated fixer and merchant locations. */
final class VendorAnchorData extends SavedData {
    private static final int FORMAT_VERSION = 1;

    private static final Codec<StoredAnchor> ANCHOR_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("site_id").forGetter(StoredAnchor::siteId),
                    Codec.STRING.fieldOf("role").forGetter(StoredAnchor::role),
                    Codec.STRING.fieldOf("district").forGetter(StoredAnchor::district),
                    Codec.LONG.fieldOf("site_pos").forGetter(StoredAnchor::sitePos),
                    Codec.LONG.fieldOf("merchant_pos").forGetter(StoredAnchor::merchantPos),
                    Codec.FLOAT.optionalFieldOf("yaw", 0.0F).forGetter(StoredAnchor::yaw),
                    Codec.STRING.optionalFieldOf("entity_uuid", "")
                            .forGetter(StoredAnchor::entityUuid))
                    .apply(instance, StoredAnchor::new));

    private static final Codec<VendorAnchorData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("format_version", FORMAT_VERSION)
                            .forGetter(data -> FORMAT_VERSION),
                    ANCHOR_CODEC.listOf().optionalFieldOf("anchors", List.of())
                            .forGetter(VendorAnchorData::serializedAnchors))
                    .apply(instance, VendorAnchorData::new));

    static final SavedDataType<VendorAnchorData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("cyberdeck", "vendor_anchors_v1"),
            VendorAnchorData::new,
            CODEC);

    record Anchor(
            String siteId,
            MerchantTruckLibrary.MerchantRole role,
            District district,
            BlockPos sitePos,
            BlockPos merchantPos,
            float yaw,
            Optional<UUID> entityUuid) {
        Anchor {
            entityUuid = entityUuid == null ? Optional.empty() : entityUuid;
        }

        boolean fixer() {
            return role == MerchantTruckLibrary.MerchantRole.QUEST;
        }
    }

    private record StoredAnchor(
            String siteId,
            String role,
            String district,
            long sitePos,
            long merchantPos,
            float yaw,
            String entityUuid) {
    }

    private final Map<String, Anchor> anchors = new LinkedHashMap<>();
    private long revision;

    private VendorAnchorData() {
        this(FORMAT_VERSION, List.of());
    }

    private VendorAnchorData(int ignoredVersion, List<StoredAnchor> stored) {
        for (StoredAnchor value : stored) {
            decode(value).ifPresent(anchor -> anchors.putIfAbsent(anchor.siteId(), anchor));
        }
    }

    static VendorAnchorData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    List<Anchor> anchors() {
        return anchors.values().stream()
                .sorted(Comparator.comparing(Anchor::fixer).reversed()
                        .thenComparingInt(anchor -> anchor.district().ordinal())
                        .thenComparingInt(anchor -> anchor.role().ordinal())
                        .thenComparing(Anchor::siteId))
                .toList();
    }

    Optional<Anchor> anchor(String siteId) {
        return Optional.ofNullable(anchors.get(siteId));
    }

    Optional<Anchor> fixer(District district) {
        return role(district, MerchantTruckLibrary.MerchantRole.QUEST);
    }

    Optional<Anchor> role(
            District district, MerchantTruckLibrary.MerchantRole role) {
        return anchors.values().stream()
                .filter(anchor -> anchor.role() == role && anchor.district() == district)
                .min(Comparator.comparing(Anchor::siteId));
    }

    Anchor register(
            String siteId,
            MerchantTruckLibrary.MerchantRole role,
            District district,
            BlockPos sitePos,
            BlockPos merchantPos,
            float yaw,
            UUID entityUuid) {
        Anchor next = new Anchor(
                siteId,
                role,
                district,
                sitePos.immutable(),
                merchantPos.immutable(),
                yaw,
                Optional.ofNullable(entityUuid));
        Anchor previous = anchors.put(siteId, next);
        if (!next.equals(previous)) {
            revision++;
            setDirty();
        }
        return next;
    }

    void bindEntity(String siteId, UUID entityUuid) {
        Anchor current = anchors.get(siteId);
        if (current == null || current.entityUuid().filter(entityUuid::equals).isPresent()) {
            return;
        }
        register(
                current.siteId(),
                current.role(),
                current.district(),
                current.sitePos(),
                current.merchantPos(),
                current.yaw(),
                entityUuid);
    }

    void remove(String siteId) {
        if (anchors.remove(siteId) != null) {
            revision++;
            setDirty();
        }
    }

    long revision() {
        return revision;
    }

    private List<StoredAnchor> serializedAnchors() {
        ArrayList<StoredAnchor> stored = new ArrayList<>();
        for (Anchor anchor : anchors()) {
            stored.add(new StoredAnchor(
                    anchor.siteId(),
                    anchor.role().name(),
                    anchor.district().name(),
                    anchor.sitePos().asLong(),
                    anchor.merchantPos().asLong(),
                    anchor.yaw(),
                    anchor.entityUuid().map(UUID::toString).orElse("")));
        }
        return stored;
    }

    private static Optional<Anchor> decode(StoredAnchor value) {
        try {
            UUID entityUuid = value.entityUuid().isBlank()
                    ? null : UUID.fromString(value.entityUuid());
            return Optional.of(new Anchor(
                    value.siteId(),
                    MerchantTruckLibrary.MerchantRole.valueOf(value.role()),
                    District.valueOf(value.district()),
                    BlockPos.of(value.sitePos()),
                    BlockPos.of(value.merchantPos()),
                    value.yaw(),
                    Optional.ofNullable(entityUuid)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
