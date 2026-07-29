package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.Cyberdeck;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A concrete, tiered cyberware variant loaded from the checked-in wiki catalog.
 *
 * <p>The old implementation was an enum with fifteen hand-written implants. Loading immutable
 * definitions from the generated catalog lets registration, tooltips, the ripperdoc screen and the
 * effect engine share all 121 families and 1,025 quality variants without duplicating data.</p>
 */
public final class Cyberware {
    private static final String CATALOG_RESOURCE = "/data/cyberdeck/cyberware/catalog.json";
    private static final Catalog CATALOG = loadCatalog();

    public static final Cyberware[] VALUES = CATALOG.values.toArray(Cyberware[]::new);

    // Compatibility handles used by the existing combat/render systems. Each points at the highest
    // catalog tier in that family; family-aware checks cover every lower tier as well.
    public static final Cyberware MILITECH_APOGEE = highest("militech_apogee");
    public static final Cyberware MILITECH_FALCON = highest("militech_falcon");
    public static final Cyberware DYNALAR_SANDEVISTAN = highest("dynalar_sandevistan");
    public static final Cyberware ZETATECH_SANDEVISTAN = highest("zetatech_sandevistan");
    public static final Cyberware QIANT_WARP_DANCER = highest("qiant_warp_dancer");
    public static final Cyberware CYBERDECK_OS = highest("arasaka_mk_1_5");
    public static final Cyberware GORILLA_ARMS = highest("gorilla_arms");
    public static final Cyberware MANTIS_BLADES = highest("mantis_blades");
    public static final Cyberware ARM_CANNON = highest("projectile_launch_system");
    public static final Cyberware SMART_LINK = highest("smart_link");
    public static final Cyberware FROG_LEGS = highest("reinforced_tendons");
    public static final Cyberware HYENA_LEGS = highest("jenkins_tendons");
    public static final Cyberware THRETEVAC = highest("threat_evac");
    public static final Cyberware NANO_PLATING = highest("nano_plating");
    public static final Cyberware OPTICAL_CAMO = highest("optical_camo");

    static {
        // Saved-data and command aliases from the pre-catalog implementation.
        alias("sandevistan", MILITECH_APOGEE);
        alias("militech_apogee", MILITECH_APOGEE);
        alias("militech_falcon", MILITECH_FALCON);
        alias("dynalar_sandevistan", DYNALAR_SANDEVISTAN);
        alias("zetatech_sandevistan", ZETATECH_SANDEVISTAN);
        alias("qiant_warp_dancer", QIANT_WARP_DANCER);
        alias("cyberdeck_os", CYBERDECK_OS);
        alias("gorilla_arms", GORILLA_ARMS);
        alias("mantis_blades", MANTIS_BLADES);
        alias("arm_cannon", ARM_CANNON);
        alias("smart_link", SMART_LINK);
        alias("frog_legs", FROG_LEGS);
        alias("hyena_legs", HYENA_LEGS);
        alias("thretevac", THRETEVAC);
        alias("nano_plating", NANO_PLATING);
        alias("optical_camo", OPTICAL_CAMO);
    }

    private final String id;
    private final String familyId;
    private final String displayName;
    private final CyberwareTier tier;
    private final BodySlot slot;
    private final int capacity;
    private final double armor;
    private final String effect;
    private final Set<String> flags;
    private final Map<String, Double> values;

    private Cyberware(String id, String familyId, String displayName, CyberwareTier tier,
                      BodySlot slot, int capacity, double armor, String effect,
                      Set<String> flags, Map<String, Double> values) {
        this.id = id;
        this.familyId = familyId;
        this.displayName = displayName;
        this.tier = tier;
        this.slot = slot;
        this.capacity = capacity;
        this.armor = armor;
        this.effect = effect;
        this.flags = Set.copyOf(flags);
        this.values = Map.copyOf(values);
    }

    public String id() {
        return id;
    }

    public String familyId() {
        return familyId;
    }

    public String displayName() {
        return displayName;
    }

    public String fullDisplayName() {
        return displayName + " [" + tier.displayName() + "]";
    }

    public CyberwareTier tier() {
        return tier;
    }

    public BodySlot slot() {
        return slot;
    }

    public int capacity() {
        return capacity;
    }

    /** Raw Cyberpunk 2077 armor value for display; mechanics use the normalized armor_points key. */
    public double armor() {
        return armor;
    }

    public String effect() {
        return effect;
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    public Set<String> flags() {
        return flags;
    }

    public double value(String key) {
        return values.getOrDefault(key, 0.0);
    }

    public Map<String, Double> values() {
        return values;
    }

    public boolean isSandevistan() {
        return hasFlag("sandevistan");
    }

    public boolean sameFamily(Cyberware other) {
        return other != null && familyId.equals(other.familyId);
    }

    public static Cyberware byId(String id) {
        return id == null ? null : CATALOG.byId.get(id);
    }

    /** Every concrete tier variant available for a slot, in wiki declaration order. */
    public static List<Cyberware> forSlot(BodySlot slot) {
        return CATALOG.bySlot.getOrDefault(slot, List.of());
    }

    /** Named families for a slot; the UI uses this to avoid a flat thousand-row catalog. */
    public static List<CyberwareFamily> familiesForSlot(BodySlot slot) {
        return CATALOG.familiesBySlot.getOrDefault(slot, List.of());
    }

    public static CyberwareFamily family(String id) {
        return CATALOG.families.get(id);
    }

    public static Cyberware highest(String familyId) {
        CyberwareFamily family = family(familyId);
        if (family == null || family.variants().isEmpty()) {
            throw new IllegalStateException("Missing cyberware family " + familyId);
        }
        return family.highestTier();
    }

    private static void alias(String id, Cyberware target) {
        CATALOG.byId.put(id, target);
    }

    private static Catalog loadCatalog() {
        InputStream stream = Cyberware.class.getResourceAsStream(CATALOG_RESOURCE);
        if (stream == null) {
            throw new ExceptionInInitializerError("Missing " + CATALOG_RESOURCE);
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<Cyberware> values = new ArrayList<>();
            Map<String, Cyberware> byId = new HashMap<>();
            Map<BodySlot, List<Cyberware>> bySlotMutable = new EnumMap<>(BodySlot.class);
            Map<String, List<Cyberware>> familyVariants = new LinkedHashMap<>();

            for (JsonElement element : root.getAsJsonArray("variants")) {
                JsonObject variant = element.getAsJsonObject();
                JsonObject mechanics = variant.getAsJsonObject("mechanics");
                Set<String> flags = new LinkedHashSet<>();
                JsonArray flagsJson = mechanics.getAsJsonArray("flags");
                for (JsonElement flag : flagsJson) {
                    flags.add(flag.getAsString());
                }
                Map<String, Double> mechanicsValues = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry
                        : mechanics.getAsJsonObject("values").entrySet()) {
                    mechanicsValues.put(entry.getKey(), entry.getValue().getAsDouble());
                }

                BodySlot slot = BodySlot.valueOf(variant.get("slot").getAsString());
                Cyberware cyberware = new Cyberware(
                        variant.get("id").getAsString(),
                        variant.get("family").getAsString(),
                        variant.get("name").getAsString(),
                        CyberwareTier.byId(variant.get("tier").getAsString()),
                        slot,
                        variant.get("capacity").getAsInt(),
                        variant.get("armor").getAsDouble(),
                        variant.get("effect").getAsString(),
                        flags,
                        mechanicsValues);
                values.add(cyberware);
                if (byId.put(cyberware.id, cyberware) != null) {
                    throw new IllegalStateException("Duplicate cyberware id " + cyberware.id);
                }
                bySlotMutable.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(cyberware);
                familyVariants.computeIfAbsent(cyberware.familyId, ignored -> new ArrayList<>())
                        .add(cyberware);
            }

            Map<String, CyberwareFamily> families = new LinkedHashMap<>();
            Map<BodySlot, List<CyberwareFamily>> familiesBySlotMutable =
                    new EnumMap<>(BodySlot.class);
            for (Map.Entry<String, List<Cyberware>> entry : familyVariants.entrySet()) {
                List<Cyberware> variants = entry.getValue();
                Cyberware first = variants.getFirst();
                CyberwareFamily family = new CyberwareFamily(
                        entry.getKey(), first.displayName, first.slot, variants);
                families.put(entry.getKey(), family);
                familiesBySlotMutable.computeIfAbsent(first.slot, ignored -> new ArrayList<>())
                        .add(family);
            }

            Map<BodySlot, List<Cyberware>> bySlot = new EnumMap<>(BodySlot.class);
            Map<BodySlot, List<CyberwareFamily>> familiesBySlot = new EnumMap<>(BodySlot.class);
            for (BodySlot slot : BodySlot.VALUES) {
                bySlot.put(slot, List.copyOf(bySlotMutable.getOrDefault(slot, List.of())));
                familiesBySlot.put(slot,
                        List.copyOf(familiesBySlotMutable.getOrDefault(slot, List.of())));
            }
            Cyberdeck.LOGGER.info("Loaded {} cyberware families / {} tier variants from wiki catalog",
                    families.size(), values.size());
            return new Catalog(
                    List.copyOf(values),
                    byId,
                    Collections.unmodifiableMap(bySlot),
                    Collections.unmodifiableMap(families),
                    Collections.unmodifiableMap(familiesBySlot));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record Catalog(
            List<Cyberware> values,
            Map<String, Cyberware> byId,
            Map<BodySlot, List<Cyberware>> bySlot,
            Map<String, CyberwareFamily> families,
            Map<BodySlot, List<CyberwareFamily>> familiesBySlot) {
    }

    @Override
    public String toString() {
        return id;
    }
}
