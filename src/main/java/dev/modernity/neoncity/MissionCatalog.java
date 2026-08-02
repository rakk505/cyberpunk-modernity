package dev.modernity.neoncity;

import com.example.cyberdeck.weapon.GunType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Strict parser for the editable fixer-gig catalog bundled as server data. */
public final class MissionCatalog {
    public static final String RESOURCE = "/data/neoncity/missions/catalog.json";
    public static final int SCHEMA_VERSION = 1;
    private static final Path CONFIGURATION_PATH = Path.of(
            "config", "cyberdeck", "missions.json");
    private static final Set<String> SUPPORTED_CYBERWARE = Set.of(
            "sandevistan", "subdermal_armor", "blood_pump", "optical_camo");
    private static volatile List<MissionDefinition> definitions = loadBundled();

    private MissionCatalog() {
    }

    public enum MissionType {
        ASSASSINATE_TARGET("assassinate_target", "ASSASSINATE TARGET"),
        NEUTRALIZE_CYBERPSYCHO("neutralize_cyberpsycho", "NEUTRALIZE CYBERPSYCHO"),
        STEAL_DATA("steal_data", "STEAL DATA"),
        SHIP_ITEM("ship_item", "SHIP ITEM");

        private final String id;
        private final String displayName;

        MissionType(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        static MissionType parse(String value) {
            for (MissionType type : values()) {
                if (type.id.equals(value)) return type;
            }
            throw new IllegalArgumentException("unknown mission type " + value);
        }
    }

    public record MissionDefinition(
            String id,
            MissionType type,
            String title,
            String briefing,
            String targetName,
            List<District> targetDistricts,
            int rewardMin,
            int rewardMax,
            int guards,
            int objectiveRadius,
            int cyberpsychoHealth,
            GunType cyberpsychoGun,
            int cyberpsychoGrenades,
            List<String> cyberware,
            Identifier cargoItem,
            int cargoCount,
            int streetCred,
            int activationRadius) {
        public MissionDefinition {
            targetDistricts = List.copyOf(targetDistricts);
            cyberware = List.copyOf(cyberware);
        }

        /** Compatibility constructor for tests and old callers created before gig reputation. */
        public MissionDefinition(
                String id,
                MissionType type,
                String title,
                String briefing,
                String targetName,
                List<District> targetDistricts,
                int rewardMin,
                int rewardMax,
                int guards,
                int objectiveRadius,
                int cyberpsychoHealth,
                GunType cyberpsychoGun,
                int cyberpsychoGrenades,
                List<String> cyberware,
                Identifier cargoItem,
                int cargoCount) {
            this(id, type, title, briefing, targetName, targetDistricts,
                    rewardMin, rewardMax, guards, objectiveRadius,
                    cyberpsychoHealth, cyberpsychoGun, cyberpsychoGrenades,
                    cyberware, cargoItem, cargoCount, 10, 64);
        }

        public String objectiveText() {
            return switch (type) {
                case ASSASSINATE_TARGET -> "Eliminate " + targetName;
                case NEUTRALIZE_CYBERPSYCHO -> "Neutralize " + targetName;
                case STEAL_DATA -> "Access " + targetName;
                case SHIP_ITEM -> "Submit " + cargoCount + " " + itemLabel(cargoItem)
                        + " at " + targetName;
            };
        }
    }

    public static List<MissionDefinition> definitions() {
        return definitions;
    }

    public static MissionDefinition definition(String id) {
        return definitions.stream()
                .filter(definition -> definition.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown mission " + id));
    }

    /**
     * Loads the editable server configuration, creating it from the bundled catalog on first run.
     * Parsing completes before the live immutable snapshot is replaced, so a rejected reload leaves
     * the previous catalog active.
     */
    public static synchronized int reloadConfiguration() {
        try {
            Files.createDirectories(CONFIGURATION_PATH.getParent());
            if (Files.notExists(CONFIGURATION_PATH)) {
                try (InputStream stream = MissionCatalog.class.getResourceAsStream(RESOURCE)) {
                    if (stream == null) {
                        throw new IllegalStateException("missing mission catalog " + RESOURCE);
                    }
                    Files.copy(stream, CONFIGURATION_PATH);
                }
            }
            List<MissionDefinition> loaded;
            try (var reader = Files.newBufferedReader(CONFIGURATION_PATH, StandardCharsets.UTF_8)) {
                loaded = parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
            definitions = loaded;
            return loaded.size();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "invalid mission configuration " + CONFIGURATION_PATH.toAbsolutePath(),
                    exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "could not load mission configuration " + CONFIGURATION_PATH.toAbsolutePath(),
                    exception);
        }
    }

    public static Path configurationPath() {
        return CONFIGURATION_PATH;
    }

    static List<MissionDefinition> parse(JsonObject root) {
        int version = integer(root, "schema_version");
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported mission schema " + version);
        }
        JsonArray missions = array(root, "missions");
        List<MissionDefinition> definitions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        EnumSet<MissionType> types = EnumSet.noneOf(MissionType.class);
        for (JsonElement element : missions) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("mission entry is not an object");
            }
            MissionDefinition definition = parseDefinition(element.getAsJsonObject());
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("duplicate mission id " + definition.id());
            }
            types.add(definition.type());
            definitions.add(definition);
        }
        if (definitions.isEmpty() || !types.containsAll(EnumSet.allOf(MissionType.class))) {
            throw new IllegalArgumentException("catalog must define all four mission types");
        }
        return List.copyOf(definitions);
    }

    static MissionDefinition parseDefinition(JsonObject value) {
        String id = text(value, "id");
        if (!id.matches("[a-z0-9_]{1,48}")) {
            throw new IllegalArgumentException("invalid mission id " + id);
        }
        MissionType type = MissionType.parse(text(value, "type"));
        String title = bounded(text(value, "title"), 1, 64, "title");
        String briefing = bounded(text(value, "briefing"), 1, 256, "briefing");
        String targetName = bounded(text(value, "target_name"), 1, 64, "target_name");
        List<District> targetDistricts = districts(array(value, "target_districts"));
        JsonObject reward = value.has("reward_emmies")
                ? object(value, "reward_emmies") : object(value, "reward_emeralds");
        int rewardMin = range(integer(reward, "min"), 1, 10_000, "reward min");
        int rewardMax = range(integer(reward, "max"), rewardMin, 10_000, "reward max");
        int guards = range(integer(value, "guards"), 0, 24, "guards");
        int objectiveRadius = range(integer(value, "objective_radius"), 8, 128,
                "objective_radius");
        int streetCred = value.has("street_cred")
                ? range(integer(value, "street_cred"), 1, 10_000, "street_cred") : 10;
        int activationRadius = value.has("activation_radius")
                ? range(integer(value, "activation_radius"), 16, 160, "activation_radius") : 64;

        int health = 0;
        GunType gun = null;
        int grenades = 0;
        List<String> cyberware = List.of();
        Identifier cargoItem = null;
        int cargoCount = 0;
        if (type == MissionType.NEUTRALIZE_CYBERPSYCHO) {
            JsonObject psycho = object(value, "cyberpsycho");
            health = com.example.cyberdeck.faction.CyberpsychoEntity.balancedHealth(
                    range(integer(psycho, "health"), 40, 1024, "cyberpsycho health"));
            gun = parseGun(text(psycho, "gun"));
            grenades = range(integer(psycho, "grenades"), 0, 16,
                    "cyberpsycho grenades");
            List<String> installed = new ArrayList<>();
            for (JsonElement item : array(psycho, "cyberware")) {
                String cyberwareId = bounded(item.getAsString(), 1, 48, "cyberware id");
                if (!SUPPORTED_CYBERWARE.contains(cyberwareId)) {
                    throw new IllegalArgumentException(
                            "unsupported cyberpsycho cyberware " + cyberwareId);
                }
                installed.add(cyberwareId);
            }
            cyberware = List.copyOf(installed);
            if (cyberware.isEmpty()) {
                throw new IllegalArgumentException("cyberpsycho needs installed cyberware");
            }
        } else if (type == MissionType.SHIP_ITEM) {
            JsonObject cargo = object(value, "cargo");
            cargoItem = Identifier.parse(text(cargo, "item"));
            cargoCount = range(integer(cargo, "count"), 1, 64, "cargo count");
        }
        return new MissionDefinition(
                id, type, title, briefing, targetName, targetDistricts,
                rewardMin, rewardMax, guards, objectiveRadius,
                health, gun, grenades, cyberware, cargoItem, cargoCount,
                streetCred, activationRadius);
    }

    private static List<District> districts(JsonArray values) {
        EnumSet<District> districts = EnumSet.noneOf(District.class);
        for (JsonElement element : values) {
            String value = element.getAsString().toUpperCase(Locale.ROOT);
            if (value.equals("*")) {
                districts.addAll(EnumSet.allOf(District.class));
                continue;
            }
            District.fromCode(value).ifPresent(districts::add);
        }
        if (districts.isEmpty()) {
            throw new IllegalArgumentException("mission has no valid target districts");
        }
        return List.copyOf(districts);
    }

    private static GunType parseGun(String id) {
        for (GunType gun : GunType.values()) {
            if (gun.id().equals(id) && gun != GunType.MANTIS_BLADE) return gun;
        }
        throw new IllegalArgumentException("invalid cyberpsycho gun " + id);
    }

    private static List<MissionDefinition> loadBundled() {
        try (InputStream stream = MissionCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("missing mission catalog " + RESOURCE);
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return parse(root);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid mission catalog", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("could not load mission catalog", exception);
        }
    }

    private static JsonObject object(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) {
            throw new IllegalArgumentException("missing object " + key);
        }
        return value.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalArgumentException("missing array " + key);
        }
        return value.getAsJsonArray(key);
    }

    private static String text(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing text " + key);
        }
        return value.get(key).getAsString();
    }

    private static int integer(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing integer " + key);
        }
        return value.get(key).getAsInt();
    }

    private static int range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " outside " + min + ".." + max);
        }
        return value;
    }

    private static String bounded(String value, int min, int max, String name) {
        if (value.length() < min || value.length() > max) {
            throw new IllegalArgumentException(name + " length outside " + min + ".." + max);
        }
        return value;
    }

    private static String itemLabel(Identifier item) {
        String name = item.getPath().replace('_', ' ');
        return name.endsWith("s") ? name : name + "s";
    }
}
