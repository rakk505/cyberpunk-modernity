package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persisted, fixed-seed catalog of mission-ready Arnis buildings for procedural gigs. */
final class GigSiteData extends SavedData {
    static final int CANDIDATES_PER_DISTRICT = 8;
    static final int MIN_FIXED_SITES_PER_DISTRICT = 5;
    private static final int CATALOG_SEARCH_RADIUS_CHUNKS = 24;
    static final int FORMAT_VERSION = 1;
    private static final long CATALOG_SALT = 0x4749475349544553L;
    private static final String FIXED_SITE_RESOURCE =
            "/data/neoncity/missions/gig_sites_50520260801.dat";

    private static final Codec<StoredDistrict> DISTRICT_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("district").forGetter(StoredDistrict::district),
                    CompoundTag.CODEC.listOf().optionalFieldOf("sites", List.of())
                            .forGetter(StoredDistrict::sites))
                    .apply(instance, StoredDistrict::new));

    private static final Codec<GigSiteData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.INT.optionalFieldOf("format_version", FORMAT_VERSION)
                            .forGetter(GigSiteData::formatVersion),
                    Codec.LONG.optionalFieldOf("content_seed", Long.MIN_VALUE)
                            .forGetter(GigSiteData::contentSeed),
                    Codec.LONG.optionalFieldOf("layout_seed", Long.MIN_VALUE)
                            .forGetter(GigSiteData::layoutSeed),
                    Codec.STRING.optionalFieldOf("generator", "")
                            .forGetter(GigSiteData::generatorFingerprint),
                    DISTRICT_CODEC.listOf().optionalFieldOf("districts", List.of())
                            .forGetter(GigSiteData::serializedDistricts))
                    .apply(instance, GigSiteData::new));

    static final SavedDataType<GigSiteData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "gig_site_catalog_v1"),
            GigSiteData::new,
            CODEC);

    private record StoredDistrict(String district, List<CompoundTag> sites) {
        private StoredDistrict {
            sites = sites == null ? List.of() : sites.stream()
                    .map(site -> site == null ? new CompoundTag() : site.copy())
                    .toList();
        }
    }

    private final Map<District, List<CompoundTag>> sites = new EnumMap<>(District.class);
    private static volatile Map<District, List<CompoundTag>> fixedSites;
    private int formatVersion;
    private long contentSeed;
    private long layoutSeed;
    private String generatorFingerprint;

    private GigSiteData() {
        this(FORMAT_VERSION, Long.MIN_VALUE, Long.MIN_VALUE, "", List.of());
    }

    private GigSiteData(
            int formatVersion,
            long contentSeed,
            long layoutSeed,
            String generatorFingerprint,
            List<StoredDistrict> districts) {
        this.formatVersion = formatVersion;
        this.contentSeed = contentSeed;
        this.layoutSeed = layoutSeed;
        this.generatorFingerprint = generatorFingerprint == null ? "" : generatorFingerprint;
        for (StoredDistrict stored : districts) {
            try {
                District district = District.valueOf(stored.district());
                List<CompoundTag> previous = sites.putIfAbsent(
                        district, stored.sites().stream().map(CompoundTag::copy).toList());
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate gig-site district " + district.commandCode());
                }
            } catch (IllegalArgumentException obsoleteDistrict) {
                if (java.util.Arrays.stream(District.values())
                        .anyMatch(district -> district.name().equals(stored.district()))) {
                    throw obsoleteDistrict;
                }
                // One obsolete district must not invalidate the remaining fixed-seed catalog.
            }
        }
    }

    static GigSiteData get(ServerLevel context) {
        GigSiteData data = context.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
        data.validateSeed();
        return data;
    }

    /** Hydrates bundled fixed-seed descriptors without loading or generating their chunks. */
    static int restoreFixedCatalog(ServerLevel context) {
        GigSiteData data = get(context);
        int restored = 0;
        for (Map.Entry<District, List<CompoundTag>> entry : fixedSites().entrySet()) {
            List<CompoundTag> replacement = entry.getValue().stream()
                    .map(CompoundTag::copy).toList();
            if (!replacement.equals(data.sites.get(entry.getKey()))) {
                data.sites.put(entry.getKey(), replacement);
                data.setDirty();
            }
            restored += replacement.size();
        }
        return restored;
    }

    record ScanResult(
            List<MissionBuildingPlanner.Site> sites,
            int regions,
            int buildings,
            long readyBuildings,
            Map<String, Long> structuralDecisions,
            Map<String, Integer> filterRejections,
            boolean reused) {
        ScanResult {
            sites = List.copyOf(sites);
            structuralDecisions = Map.copyOf(structuralDecisions);
            filterRejections = Map.copyOf(filterRejections);
        }
    }

    record ArtifactResult(Path path, int districts, int sites, List<String> deficient) {
        ArtifactResult {
            path = path.toAbsolutePath().normalize();
            deficient = List.copyOf(deficient);
        }
    }

    synchronized ScanResult ensureCandidates(
            ServerLevel context, District district) {
        ServerLevel level = context.getServer().overworld();
        List<MissionBuildingPlanner.Site> persisted = candidates(district);
        if (!persisted.isEmpty()) {
            return new ScanResult(
                    persisted, 0, 0, persisted.size(), Map.of(), Map.of(), true);
        }

        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        BlockPos origin = new BlockPos(
                node.x(), NeonCityGenerator.CITY_GROUND_Y + 1, node.z());
        long selectionSalt = CATALOG_SALT ^ NeonCityGenerator.contentSeed()
                ^ Long.rotateLeft(NeonCityGenerator.layout().seed(), 17)
                ^ district.ordinal();
        Map<String, MissionBuildingPlanner.Site> verified = new LinkedHashMap<>();
        int[] rejected = new int[5];
        ArnisBuildingAtlas.Compilation compilation = ArnisBuildingAtlas.compileGigCatalog(
                level, district, origin, CATALOG_SEARCH_RADIUS_CHUNKS, selectionSalt, 1, 5,
                CANDIDATES_PER_DISTRICT, raw -> {
            if (raw.district() != district) {
                rejected[0]++;
                return false;
            }
            if (raw.entrance().position().getY() != NeonCityGenerator.CITY_GROUND_Y + 1) {
                rejected[1]++;
                return false;
            }
            if (raw.floorYs().getFirst() != NeonCityGenerator.CITY_GROUND_Y + 1) {
                rejected[2]++;
                return false;
            }
            if (MainlineQuestService.conflictsReservedSite(level, raw, null)) {
                rejected[3]++;
                return false;
            }
            MissionBuildingPlanner.Site candidate =
                    MissionBuildingPlanner.withoutMissionInteriorPlan(raw);
            if (verified.values().stream().anyMatch(existing ->
                    footprintsConflict(existing, candidate))) {
                rejected[4]++;
                return false;
            }
            verified.putIfAbsent(candidate.id(), candidate);
            return true;
        });
        List<CompoundTag> encoded = verified.values().stream()
                .map(MissionBuildingPlanner.Site::save)
                .map(CompoundTag::copy)
                .toList();
        sites.put(district, encoded);
        setDirty();
        Cyberdeck.LOGGER.info(
                "[GigSites] parsed and persisted {} buildings for District {}; rejected "
                        + "district={}, door_y={}, floor_y={}, mainline={}, overlap={}",
                encoded.size(), district.commandCode(), rejected[0], rejected[1], rejected[2],
                rejected[3], rejected[4]);
        Map<String, Long> decisions = compilation.scans().stream()
                .flatMap(scan -> scan.buildings().stream())
                .collect(java.util.stream.Collectors.groupingBy(
                        MissionBuildingPlanner.BuildingLabel::decision,
                        TreeMap::new,
                        java.util.stream.Collectors.counting()));
        long ready = compilation.scans().stream()
                .flatMap(scan -> scan.buildings().stream())
                .filter(MissionBuildingPlanner.BuildingLabel::missionReady)
                .count();
        return new ScanResult(
                List.copyOf(verified.values()), compilation.scans().size(),
                compilation.buildingCount(), ready, decisions,
                Map.of(
                        "wrong_district", rejected[0],
                        "wrong_door_y", rejected[1],
                        "wrong_floor_y", rejected[2],
                        "mainline", rejected[3],
                        "overlap", rejected[4]),
                false);
    }

    /** Explicit operator-only rebuild; runtime offer reads never invoke the atlas scanner. */
    synchronized ScanResult rebuildCandidates(ServerLevel context, District district) {
        List<CompoundTag> previous = sites.getOrDefault(district, List.of()).stream()
                .map(CompoundTag::copy)
                .toList();
        sites.remove(district);
        setDirty();
        try {
            ScanResult rebuilt = ensureCandidates(context, district);
            if (rebuilt.sites().size() < MIN_FIXED_SITES_PER_DISTRICT) {
                throw new IllegalStateException(
                        "scan retained only " + rebuilt.sites().size() + " sites for District "
                                + district.commandCode());
            }
            return rebuilt;
        } catch (RuntimeException failed) {
            if (previous.isEmpty()) {
                sites.remove(district);
            } else {
                sites.put(district, previous);
            }
            setDirty();
            throw failed;
        }
    }

    synchronized List<MissionBuildingPlanner.Site> candidates(District district) {
        return sites.getOrDefault(district, List.of()).stream()
                .map(CompoundTag::copy)
                .map(MissionBuildingPlanner.Site::load)
                .flatMap(Optional::stream)
                .filter(site -> site.district() == district)
                .limit(CANDIDATES_PER_DISTRICT)
                .toList();
    }

    static Map<District, List<MissionBuildingPlanner.Site>> fixedCatalog() {
        Map<District, List<MissionBuildingPlanner.Site>> decoded = new EnumMap<>(District.class);
        fixedSites().forEach((district, encoded) -> decoded.put(district, encoded.stream()
                .map(CompoundTag::copy)
                .map(MissionBuildingPlanner.Site::load)
                .flatMap(Optional::stream)
                .filter(site -> site.district() == district)
                .toList()));
        return Map.copyOf(decoded);
    }

    synchronized boolean remove(District district, String siteId) {
        List<CompoundTag> current = sites.get(district);
        if (current == null) return false;
        List<CompoundTag> retained = current.stream()
                .filter(tag -> MissionBuildingPlanner.Site.load(tag)
                        .map(site -> !site.id().equals(siteId)).orElse(false))
                .map(CompoundTag::copy)
                .toList();
        if (retained.size() == current.size()) return false;
        sites.put(district, retained);
        setDirty();
        return true;
    }

    private void validateSeed() {
        long expectedContent = NeonCityGenerator.contentSeed();
        long expectedLayout = NeonCityGenerator.layout().seed();
        String expectedGenerator = NeonCityGenerator.GENERATOR_FINGERPRINT;
        if (formatVersion == FORMAT_VERSION
                && contentSeed == expectedContent && layoutSeed == expectedLayout
                && generatorFingerprint.equals(expectedGenerator)) return;
        sites.clear();
        formatVersion = FORMAT_VERSION;
        contentSeed = expectedContent;
        layoutSeed = expectedLayout;
        generatorFingerprint = expectedGenerator;
        setDirty();
    }

    private long contentSeed() {
        return contentSeed;
    }

    private int formatVersion() {
        return formatVersion;
    }

    private long layoutSeed() {
        return layoutSeed;
    }

    private String generatorFingerprint() {
        return generatorFingerprint;
    }

    private static boolean footprintsConflict(
            MissionBuildingPlanner.Site first, MissionBuildingPlanner.Site second) {
        return MainlineQuestData.buildingConflicts(first, second);
    }

    static ArtifactResult exportShard(
            ServerLevel context, String artifactName, List<District> assigned) throws IOException {
        if (assigned == null || assigned.isEmpty()) {
            throw new IOException("gig-site shard has no assigned districts");
        }
        EnumSet<District> unique = EnumSet.noneOf(District.class);
        for (District district : assigned) {
            if (district == null || !unique.add(district)) {
                throw new IOException("gig-site shard contains a duplicate district assignment");
            }
        }
        GigSiteData source = get(context);
        GigSiteData shard = new GigSiteData(
                FORMAT_VERSION, NeonCityGenerator.contentSeed(),
                NeonCityGenerator.layout().seed(), NeonCityGenerator.GENERATOR_FINGERPRINT,
                List.of());
        ArrayList<String> deficient = new ArrayList<>();
        int total = 0;
        for (District district : unique) {
            List<MissionBuildingPlanner.Site> decoded = source.candidates(district).stream()
                    .sorted(java.util.Comparator.comparing(MissionBuildingPlanner.Site::id))
                    .toList();
            if (decoded.size() < MIN_FIXED_SITES_PER_DISTRICT) {
                deficient.add(district.commandCode() + "=" + decoded.size());
            }
            shard.sites.put(district, decoded.stream()
                    .map(MissionBuildingPlanner.Site::save)
                    .map(CompoundTag::copy)
                    .toList());
            total += decoded.size();
        }
        validateEntries(shard, unique, false);
        Path output = artifactPath(context, artifactName);
        writeCatalog(shard, output);
        return new ArtifactResult(output, unique.size(), total, deficient);
    }

    static ArtifactResult mergeShards(
            ServerLevel context, String outputName, List<String> shardNames) throws IOException {
        if (shardNames == null || shardNames.isEmpty()) {
            throw new IOException("no gig-site shard artifacts were supplied");
        }
        List<String> orderedNames = shardNames.stream().map(GigSiteData::safeArtifactName)
                .distinct().sorted().toList();
        if (orderedNames.size() != shardNames.size()) {
            throw new IOException("duplicate gig-site shard artifact name");
        }
        GigSiteData merged = new GigSiteData(
                FORMAT_VERSION, NeonCityGenerator.contentSeed(),
                NeonCityGenerator.layout().seed(), NeonCityGenerator.GENERATOR_FINGERPRINT,
                List.of());
        for (String name : orderedNames) {
            GigSiteData shard = readCatalog(artifactPath(context, name));
            validateMetadata(shard);
            for (Map.Entry<District, List<CompoundTag>> entry : shard.sites.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                if (merged.sites.putIfAbsent(entry.getKey(), entry.getValue().stream()
                                .map(CompoundTag::copy).toList()) != null) {
                    throw new IOException("District " + entry.getKey().commandCode()
                            + " occurs in more than one shard");
                }
            }
        }
        EnumSet<District> expected = EnumSet.allOf(District.class);
        if (!merged.sites.keySet().equals(expected)) {
            EnumSet<District> missing = EnumSet.copyOf(expected);
            missing.removeAll(merged.sites.keySet());
            throw new IOException("merged gig-site catalog is missing districts " + missing);
        }
        validateEntries(merged, expected, true);
        int total = merged.sites.values().stream().mapToInt(List::size).sum();
        Path output = artifactPath(context, outputName);
        writeCatalog(merged, output);
        return new ArtifactResult(output, merged.sites.size(), total, List.of());
    }

    private static Map<District, List<MissionBuildingPlanner.Site>> validateEntries(
            GigSiteData catalog, Set<District> expectedDistricts, boolean requireMinimum)
            throws IOException {
        validateMetadata(catalog);
        if (!catalog.sites.keySet().equals(expectedDistricts)) {
            throw new IOException("artifact district set does not match its assignment");
        }
        Map<District, List<MissionBuildingPlanner.Site>> decoded = new EnumMap<>(District.class);
        HashSet<String> siteIds = new HashSet<>();
        List<MissionBuildingPlanner.Site> mainline = MainlineQuestData.fixedSites().values().stream()
                .toList();
        for (District district : expectedDistricts.stream().sorted().toList()) {
            List<CompoundTag> encoded = catalog.sites.getOrDefault(district, List.of());
            if (encoded.size() > CANDIDATES_PER_DISTRICT
                    || requireMinimum && encoded.size() < MIN_FIXED_SITES_PER_DISTRICT) {
                throw new IOException("District " + district.commandCode() + " has "
                        + encoded.size() + " sites; expected "
                        + (requireMinimum ? MIN_FIXED_SITES_PER_DISTRICT + ".." : "0..")
                        + CANDIDATES_PER_DISTRICT);
            }
            ArrayList<MissionBuildingPlanner.Site> districtSites = new ArrayList<>();
            for (CompoundTag tag : encoded) {
                MissionBuildingPlanner.Site decodedSite = MissionBuildingPlanner.Site.load(tag.copy())
                        .orElseThrow(() -> new IOException(
                                "invalid site descriptor in District " + district.commandCode()));
                MissionBuildingPlanner.Site site =
                        MissionBuildingPlanner.withoutMissionInteriorPlan(decodedSite);
                if (site.district() != district) {
                    throw new IOException("site " + site.id() + " belongs to District "
                            + site.district().commandCode() + " but was declared as "
                            + district.commandCode());
                }
                if (!validStructuralDescriptor(site)
                        || !belongsToDeclaredDistrict(site)) {
                    throw new IOException("gig site " + site.id()
                            + " has invalid topology or lies outside District "
                            + district.commandCode());
                }
                if (!siteIds.add(site.id())) {
                    throw new IOException("duplicate gig site ID " + site.id());
                }
                if (mainline.stream().anyMatch(reserved -> footprintsConflict(reserved, site))) {
                    throw new IOException("gig site " + site.id()
                            + " overlaps a mainline-reserved footprint");
                }
                districtSites.add(site);
            }
            districtSites.sort(java.util.Comparator.comparing(MissionBuildingPlanner.Site::id));
            decoded.put(district, List.copyOf(districtSites));
            catalog.sites.put(district, districtSites.stream()
                    .map(MissionBuildingPlanner.Site::save)
                    .map(CompoundTag::copy)
                    .toList());
        }
        return Map.copyOf(decoded);
    }

    private static boolean validStructuralDescriptor(MissionBuildingPlanner.Site site) {
        if (site.floorYs().isEmpty()
                || site.floorMasks().size() != site.floorYs().size()
                || site.patrolRoutes().size() != site.floorYs().size()
                || site.stairs().size() != site.floorYs().size() - 1
                || site.floorYs().getFirst() != NeonCityGenerator.CITY_GROUND_Y + 1
                || site.entrance().position().getY() != site.floorYs().getFirst()
                || !site.floorYs().contains(site.target().getY())
                || (site.floorYs().size() >= 2
                        && site.target().getY() < site.floorYs().get(1))) {
            return false;
        }
        Set<Integer> floors = new HashSet<>(site.floorYs());
        if (floors.size() != site.floorYs().size()) return false;
        Set<Integer> masks = new HashSet<>();
        for (MissionBuildingPlanner.FloorMask mask : site.floorMasks()) {
            if (!floors.contains(mask.floorY())
                    || !masks.add(mask.floorY())
                    || mask.cells().isEmpty()
                    || mask.cells().stream().anyMatch(cell -> cell.getY() != mask.floorY())) {
                return false;
            }
        }
        Set<Integer> routes = new HashSet<>();
        for (MissionBuildingPlanner.PatrolRoute route : site.patrolRoutes()) {
            if (!floors.contains(route.floorY()) || !routes.add(route.floorY())) return false;
        }
        return masks.equals(floors) && routes.equals(floors);
    }

    static boolean belongsToDeclaredDistrict(MissionBuildingPlanner.Site site) {
        if (site == null) return false;
        MegacityLayout layout = NeonCityGenerator.fixedLayout();
        return belongsToDistrict(layout, site.district(), site.entrance().position())
                && belongsToDistrict(
                        layout, site.district(), MissionBuildingPlanner.navigationTarget(site))
                && belongsToDistrict(layout, site.district(), site.target());
    }

    private static boolean belongsToDistrict(
            MegacityLayout layout, District district, BlockPos position) {
        MegacityLayout.Location location = layout.locateDistrict(
                position.getX(), position.getZ());
        return location.insideCity() && location.district() == district;
    }

    private static void validateMetadata(GigSiteData catalog) throws IOException {
        if (catalog.formatVersion != FORMAT_VERSION
                || catalog.contentSeed != NeonCityGenerator.contentSeed()
                || catalog.layoutSeed != NeonCityGenerator.layout().seed()
                || !catalog.generatorFingerprint.equals(
                        NeonCityGenerator.GENERATOR_FINGERPRINT)) {
            throw new IOException("gig-site artifact metadata mismatch: format="
                    + catalog.formatVersion + ", content_seed=" + catalog.contentSeed
                    + ", layout_seed=" + catalog.layoutSeed + ", generator="
                    + catalog.generatorFingerprint);
        }
    }

    private static GigSiteData readCatalog(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("missing shard " + path);
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.defaultQuota());
            return CODEC.parse(NbtOps.INSTANCE, root.getCompoundOrEmpty("data"))
                    .getOrThrow(IllegalStateException::new);
        } catch (RuntimeException malformed) {
            throw new IOException("invalid gig-site artifact " + path, malformed);
        }
    }

    private static void writeCatalog(GigSiteData catalog, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        Tag encoded = CODEC.encodeStart(NbtOps.INSTANCE, catalog)
                .getOrThrow(IllegalStateException::new);
        if (!(encoded instanceof CompoundTag data)) {
            throw new IOException("gig-site catalog codec did not produce a compound tag");
        }
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        try {
            NbtIo.writeCompressed(root, temporary);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path artifactPath(ServerLevel context, String artifactName) {
        String safeName = safeArtifactName(artifactName);
        Path directory = context.getServer().getServerDirectory()
                .resolve("gig-site-shards").toAbsolutePath().normalize();
        return directory.resolve(safeName + ".dat").normalize();
    }

    private static String safeArtifactName(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid artifact name " + name);
        }
        return name;
    }

    private List<StoredDistrict> serializedDistricts() {
        ArrayList<StoredDistrict> stored = new ArrayList<>();
        sites.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> stored.add(new StoredDistrict(
                        entry.getKey().name(), entry.getValue())));
        return List.copyOf(stored);
    }

    private static Map<District, List<CompoundTag>> fixedSites() {
        Map<District, List<CompoundTag>> loaded = fixedSites;
        if (loaded != null) return loaded;
        synchronized (GigSiteData.class) {
            if (fixedSites == null) fixedSites = loadFixedSites();
            return fixedSites;
        }
    }

    private static Map<District, List<CompoundTag>> loadFixedSites() {
        try (InputStream stream = GigSiteData.class.getResourceAsStream(FIXED_SITE_RESOURCE)) {
            if (stream == null) throw new IOException("missing " + FIXED_SITE_RESOURCE);
            CompoundTag root = NbtIo.readCompressed(stream, NbtAccounter.defaultQuota());
            GigSiteData catalog = CODEC.parse(
                            NbtOps.INSTANCE, root.getCompoundOrEmpty("data"))
                    .getOrThrow(IllegalStateException::new);
            Map<District, List<MissionBuildingPlanner.Site>> decoded = validateEntries(
                    catalog, EnumSet.allOf(District.class), true);
            Map<District, List<CompoundTag>> loaded = new EnumMap<>(District.class);
            decoded.forEach((district, sites) -> loaded.put(district, sites.stream()
                        .map(MissionBuildingPlanner.Site::save)
                        .map(CompoundTag::copy)
                        .toList()));
            int siteCount = loaded.values().stream().mapToInt(List::size).sum();
            Cyberdeck.LOGGER.info(
                    "[GigSites] loaded {} pre-analyzed sites across {} districts for seed {}",
                    siteCount, loaded.size(), NeonCityGenerator.contentSeed());
            return java.util.Collections.unmodifiableMap(loaded);
        } catch (IOException | RuntimeException exception) {
            Cyberdeck.LOGGER.error(
                    "[GigSites] fixed catalog {} could not be loaded; district boards remain "
                            + "empty until an administrator rebuilds it",
                    FIXED_SITE_RESOURCE, exception);
            return Map.of();
        }
    }
}
