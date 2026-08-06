package dev.modernity.neoncity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Strict, cycle-checked mainline catalog loaded from editable server data. */
public final class StoryMissionCatalog {
    public static final String RESOURCE = "/data/neoncity/missions/story.json";
    public static final int SCHEMA_VERSION = 3;
    private static final Path CONFIGURATION_PATH = Path.of(
            "config", "cyberdeck", "story_missions.json");
    private static volatile Catalog catalog = loadBundled();

    private StoryMissionCatalog() {
    }

    public enum NodeType {
        TALK("talk"),
        TRAVEL("travel"),
        DELIVER("deliver"),
        INFILTRATE("infiltrate"),
        ASSASSINATE("assassinate"),
        STEAL("steal"),
        KILL_CYBERPSYCHO("kill_cyberpsycho");

        private final String id;

        NodeType(String id) {
            this.id = id;
        }

        static NodeType parse(String value) {
            for (NodeType type : values()) {
                if (type.id.equals(value)) return type;
            }
            throw new IllegalArgumentException("unknown mainline node type " + value);
        }

        public boolean usesBuilding() {
            return this != TALK;
        }
    }

    public record CharacterDefinition(
            String id,
            String name,
            String role,
            String district,
            String visualNote,
            int skinVariant) {
    }

    /** What a mission's defenders actually are, not just how many of them there are. */
    public enum DefenderKind {
        /** Ordinary corporate security: faction loadout, faction weapon, patrols its floor. */
        SOLDIER("soldier"),
        /** Augmented security. Rolls an {@code EnemyCyberware} loadout on top of the base kit. */
        ELITE("elite"),
        /** A full cyberpsycho, boss bar and all, used where two of them replace a whole detail. */
        CYBERPSYCHO("cyberpsycho");

        private final String id;

        DefenderKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static DefenderKind parse(String value) {
            for (DefenderKind kind : values()) {
                if (kind.id.equals(value)) return kind;
            }
            throw new IllegalArgumentException("unknown mainline defender kind " + value);
        }
    }

    /**
     * Weapon and chrome for a story target who fights back. A target without one keeps the classic
     * behaviour of standing still and waiting to be killed.
     */
    public record TargetLoadout(String gun, List<String> cyberware, int health) {
        public TargetLoadout {
            gun = gun == null ? "" : gun;
            cyberware = cyberware == null ? List.of() : List.copyOf(cyberware);
        }

        public boolean armed() {
            return !gun.isBlank() || !cyberware.isEmpty();
        }
    }

    public record StoryNode(
            String id,
            NodeType type,
            String characterId,
            String district,
            String location,
            int floor,
            String dialogue,
            List<String> dependsOn) {
        public StoryNode {
            dependsOn = List.copyOf(dependsOn);
            characterId = characterId == null ? "" : characterId;
            dialogue = dialogue == null ? "" : dialogue;
        }

        public Optional<District> destinationDistrict() {
            String code = district.contains("->")
                    ? district.substring(district.lastIndexOf("->") + 2)
                    : district;
            return District.fromCode(code.trim().toUpperCase(Locale.ROOT));
        }

        public boolean ready(Set<String> completedNodes) {
            return !completedNodes.contains(id) && completedNodes.containsAll(dependsOn);
        }
    }

    public record StoryMission(
            MissionCatalog.MissionDefinition encounter,
            List<String> prerequisites,
            int requiredStreetCred,
            String chapter,
            District primaryDistrict,
            List<District> districtsInvolved,
            String initiatorCharacterId,
            String targetCharacterId,
            List<StoryNode> nodes,
            String completionNodeId,
            int requestedFloors,
            List<Integer> enemiesPerFloor,
            String enemyType,
            String enemyFaction,
            double enemyPower,
            DefenderKind defenderKind,
            double eliteFraction,
            boolean defendersRoam,
            TargetLoadout targetLoadout,
            Identifier rewardItem,
            String completionFlag,
            String loreUnlock) {
        public StoryMission {
            prerequisites = List.copyOf(prerequisites);
            districtsInvolved = List.copyOf(districtsInvolved);
            nodes = List.copyOf(nodes);
            enemiesPerFloor = List.copyOf(enemiesPerFloor);
            defenderKind = defenderKind == null ? DefenderKind.SOLDIER : defenderKind;
            initiatorCharacterId = safe(initiatorCharacterId);
            targetCharacterId = safe(targetCharacterId);
            enemyType = safe(enemyType);
            enemyFaction = safe(enemyFaction);
            completionFlag = safe(completionFlag);
            loreUnlock = safe(loreUnlock);
        }

        public String id() {
            return encounter.id();
        }

        public boolean available(Set<String> completed, int streetCred) {
            return streetCred >= requiredStreetCred && completed.containsAll(prerequisites);
        }

        public StoryNode node(String nodeId) {
            return nodes.stream().filter(node -> node.id().equals(nodeId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown node " + nodeId + " in " + id()));
        }

        public List<StoryNode> readyNodes(Set<String> completedNodes) {
            return nodes.stream().filter(node -> node.ready(completedNodes)).toList();
        }

        /**
         * Whether the defender at {@code index} within this mission's detail is an elite. Elites
         * are spread deterministically across the detail rather than rolled per spawn, so a party
         * that redeploys the same contract meets the same fight.
         */
        public boolean isElite(int index) {
            if (defenderKind == DefenderKind.ELITE) return true;
            if (defenderKind != DefenderKind.SOLDIER || eliteFraction <= 0.0) return false;
            int total = Math.max(1, enemiesPerFloor.stream().mapToInt(Integer::intValue).sum());
            int elites = (int) Math.round(eliteFraction * total);
            if (elites <= 0) return false;
            // Spread by stride so the elites are not all stacked on the first floor.
            return Math.floorMod(index * elites, total) < elites;
        }
    }

    private record Catalog(
            List<StoryMission> missions,
            Map<String, CharacterDefinition> characters) {
        private Catalog {
            missions = List.copyOf(missions);
            characters = Map.copyOf(characters);
        }
    }

    public static List<StoryMission> definitions() {
        return catalog.missions();
    }

    public static StoryMission definition(String id) {
        return definitions().stream()
                .filter(definition -> definition.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown story mission " + id));
    }

    public static CharacterDefinition character(String id) {
        CharacterDefinition character = catalog.characters().get(id);
        if (character == null) throw new IllegalArgumentException("unknown story character " + id);
        return character;
    }

    public static Optional<CharacterDefinition> findCharacter(String id) {
        return Optional.ofNullable(catalog.characters().get(id));
    }

    public static List<CharacterDefinition> characters() {
        return catalog.characters().values().stream()
                .sorted(java.util.Comparator.comparing(CharacterDefinition::id)).toList();
    }

    public static List<StoryMission> available(Set<String> completed, int streetCred) {
        return definitions().stream()
                .filter(definition -> !completed.contains(definition.id()))
                .filter(definition -> definition.available(completed, streetCred))
                .toList();
    }

    public static synchronized int reloadConfiguration() {
        try {
            Files.createDirectories(CONFIGURATION_PATH.getParent());
            if (Files.notExists(CONFIGURATION_PATH)) {
                try (InputStream stream = StoryMissionCatalog.class.getResourceAsStream(RESOURCE)) {
                    if (stream == null) throw new IllegalStateException("missing " + RESOURCE);
                    Files.copy(stream, CONFIGURATION_PATH);
                }
            }
            JsonObject configured;
            try (var reader = Files.newBufferedReader(CONFIGURATION_PATH, StandardCharsets.UTF_8)) {
                configured = JsonParser.parseReader(reader).getAsJsonObject();
            }
            // Any config older than the bundled schema is superseded, not just version 1. A
            // campaign redesign that adds fields would otherwise be invisible on every existing
            // server: the config file already exists, so the bundled catalog is never copied and
            // the server keeps running last release's missions while the code expects this one's.
            int configuredVersion = requiredInteger(configured, "schema_version");
            if (configuredVersion < SCHEMA_VERSION) {
                Path backup = CONFIGURATION_PATH.resolveSibling(
                        "story_missions.v" + configuredVersion + ".backup.json");
                if (Files.notExists(backup)) Files.copy(CONFIGURATION_PATH, backup);
                try (InputStream stream = StoryMissionCatalog.class.getResourceAsStream(RESOURCE)) {
                    if (stream == null) throw new IllegalStateException("missing " + RESOURCE);
                    Files.copy(stream, CONFIGURATION_PATH, StandardCopyOption.REPLACE_EXISTING);
                }
                try (var reader = Files.newBufferedReader(
                        CONFIGURATION_PATH, StandardCharsets.UTF_8)) {
                    configured = JsonParser.parseReader(reader).getAsJsonObject();
                }
            }
            Catalog loaded = parseCatalog(configured);
            catalog = loaded;
            return loaded.missions().size();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "invalid story mission configuration " + CONFIGURATION_PATH.toAbsolutePath(),
                    exception);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "could not load story mission configuration "
                            + CONFIGURATION_PATH.toAbsolutePath(), exception);
        }
    }

    public static Path configurationPath() {
        return CONFIGURATION_PATH;
    }

    /** Package-visible validation entry point retained for GameTests. */
    static List<StoryMission> parse(JsonObject root) {
        return parseCatalog(root).missions();
    }

    private static Catalog parseCatalog(JsonObject root) {
        int version = requiredInteger(root, "schema_version");
        if (version != 1 && version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported story mission schema " + version);
        }
        Map<String, CharacterDefinition> characters = version == 1
                ? Map.of() : parseCharacters(requiredArray(root, "characters"));
        List<StoryMission> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : requiredArray(root, "missions")) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("story mission entry is not an object");
            }
            JsonObject value = element.getAsJsonObject();
            MissionCatalog.MissionDefinition encounter = normalizeEncounter(
                    MissionCatalog.parseDefinition(value));
            if (!ids.add(encounter.id())) {
                throw new IllegalArgumentException("duplicate story mission " + encounter.id());
            }
            StoryMission mission = version == 1
                    ? parseLegacyMission(value, encounter)
                    : parseMission(value, encounter, characters);
            result.add(mission);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("story mission DAG is empty");
        validateMissionDag(result);
        return new Catalog(result, characters);
    }

    private static MissionCatalog.MissionDefinition normalizeEncounter(
            MissionCatalog.MissionDefinition encounter) {
        if (encounter.type() != MissionCatalog.MissionType.NEUTRALIZE_CYBERPSYCHO
                || encounter.guards() == 0) {
            return encounter;
        }
        return new MissionCatalog.MissionDefinition(
                encounter.id(), encounter.type(), encounter.title(), encounter.briefing(),
                encounter.targetName(), encounter.targetDistricts(), encounter.rewardMin(),
                encounter.rewardMax(), 0, encounter.objectiveRadius(),
                encounter.cyberpsychoHealth(), encounter.cyberpsychoGun(),
                encounter.cyberpsychoGrenades(), encounter.cyberware(), encounter.cargoItem(),
                encounter.cargoCount(), encounter.streetCred(), encounter.activationRadius());
    }

    private static Map<String, CharacterDefinition> parseCharacters(JsonArray values) {
        Map<String, CharacterDefinition> result = new HashMap<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("story character entry is not an object");
            }
            JsonObject value = element.getAsJsonObject();
            String id = id(value, "id");
            CharacterDefinition character = new CharacterDefinition(
                    id,
                    bounded(text(value, "name"), 1, 64, "character name"),
                    bounded(text(value, "role"), 1, 96, "character role"),
                    bounded(text(value, "district"), 1, 64, "character district"),
                    bounded(text(value, "visual_note"), 1, 256, "visual note"),
                    range(requiredInteger(value, "skin_variant"), 0, 64, "skin variant"));
            if (result.putIfAbsent(id, character) != null) {
                throw new IllegalArgumentException("duplicate story character " + id);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("story character catalog is empty");
        return result;
    }

    private static StoryMission parseMission(
            JsonObject value,
            MissionCatalog.MissionDefinition encounter,
            Map<String, CharacterDefinition> characters) {
        List<String> prerequisites = strings(requiredArray(value, "prerequisites"));
        int requiredStreetCred = optionalInteger(value, "required_street_cred", 0);
        if (requiredStreetCred < 0 || requiredStreetCred > 1_000_000) {
            throw new IllegalArgumentException(
                    "required_street_cred outside 0..1000000 for " + encounter.id());
        }
        District primary = district(text(value, "primary_district"));
        List<District> involved = districts(requiredArray(value, "districts_involved"));
        String initiator = id(value, "initiator_character_id");
        String target = optionalText(value, "target_character_id");
        if (!characters.containsKey(initiator)) {
            throw new IllegalArgumentException(encounter.id() + " has unknown initiator " + initiator);
        }
        if (!target.isBlank() && !characters.containsKey(target)) {
            throw new IllegalArgumentException(encounter.id() + " has unknown target " + target);
        }

        boolean cyberpsycho = encounter.type()
                == MissionCatalog.MissionType.NEUTRALIZE_CYBERPSYCHO;
        JsonObject dag = requiredObject(value, "dag");
        List<StoryNode> nodes = parseNodes(encounter.id(), requiredArray(dag, "nodes"), characters);
        if (cyberpsycho) {
            nodes = nodes.stream().map(node -> new StoryNode(
                    node.id(), node.type(), node.characterId(), node.district(), node.location(),
                    1, node.dialogue(), node.dependsOn())).toList();
        }
        String completionNode = id(dag, "completion_node");
        validateNodeDag(encounter.id(), nodes, completionNode);

        JsonObject scale = requiredObject(value, "scale");
        int configuredFloors = range(
                requiredInteger(scale, "floor_count"), cyberpsycho ? 1 : 2, 5, "floor_count");
        List<Integer> configuredEnemies = integers(requiredArray(scale, "enemies_per_floor"));
        if (configuredEnemies.size() != configuredFloors
                || configuredEnemies.stream().anyMatch(count -> count < 0 || count > 16)) {
            throw new IllegalArgumentException(
                    encounter.id() + " enemies_per_floor must match floor_count and stay in 0..16");
        }
        int configuredEnemyTotal = configuredEnemies.stream().mapToInt(Integer::intValue).sum();
        if (!cyberpsycho && encounter.guards() != configuredEnemyTotal) {
            throw new IllegalArgumentException(
                    encounter.id() + " guards must equal enemies_per_floor total");
        }
        int requestedFloors = cyberpsycho ? 1 : configuredFloors;
        List<Integer> enemies = cyberpsycho ? List.of(0) : configuredEnemies;
        double enemyPower = requiredDouble(scale, "enemy_power");
        if (!Double.isFinite(enemyPower) || enemyPower < 1.0 || enemyPower > 4.0) {
            throw new IllegalArgumentException(encounter.id() + " enemy_power outside 1.0..4.0");
        }
        String defenderKindText = optionalText(scale, "defender_kind");
        DefenderKind defenderKind = defenderKindText.isBlank()
                ? DefenderKind.SOLDIER : DefenderKind.parse(defenderKindText);
        double eliteFraction = optionalDouble(scale, "elite_fraction", 0.0);
        if (!Double.isFinite(eliteFraction) || eliteFraction < 0.0 || eliteFraction > 1.0) {
            throw new IllegalArgumentException(encounter.id() + " elite_fraction outside 0.0..1.0");
        }
        boolean defendersRoam = optionalBoolean(scale, "defenders_roam", false);
        TargetLoadout targetLoadout = parseTargetLoadout(encounter, scale);
        Identifier rewardItem = null;
        String rewardItemText = optionalText(value, "reward_item");
        if (!rewardItemText.isBlank()) rewardItem = Identifier.parse(rewardItemText);
        return new StoryMission(
                encounter,
                prerequisites,
                requiredStreetCred,
                bounded(text(value, "chapter"), 1, 64, "chapter"),
                primary,
                involved,
                initiator,
                target,
                nodes,
                completionNode,
                requestedFloors,
                enemies,
                bounded(text(scale, "enemy_type"), 1, 64, "enemy_type"),
                bounded(text(scale, "enemy_faction"), 1, 32, "enemy_faction"),
                enemyPower,
                defenderKind,
                eliteFraction,
                defendersRoam,
                targetLoadout,
                rewardItem,
                optionalText(value, "completion_flag"),
                optionalText(value, "lore_unlock"));
    }

    private static TargetLoadout parseTargetLoadout(
            MissionCatalog.MissionDefinition encounter, JsonObject scale) {
        if (!scale.has("target_loadout") || scale.get("target_loadout").isJsonNull()) return null;
        JsonObject loadout = requiredObject(scale, "target_loadout");
        String gun = optionalText(loadout, "gun");
        if (!gun.isBlank()) MissionCatalog.gun(gun, encounter.id() + " target_loadout");
        List<String> cyberware = loadout.has("cyberware")
                ? strings(requiredArray(loadout, "cyberware")) : List.of();
        for (String id : cyberware) {
            if (!MissionCatalog.SUPPORTED_CYBERWARE.contains(id)) {
                throw new IllegalArgumentException(
                        encounter.id() + " target_loadout has unsupported cyberware " + id);
            }
        }
        int health = optionalInteger(loadout, "health", 0);
        if (health < 0 || health > 400) {
            throw new IllegalArgumentException(
                    encounter.id() + " target_loadout health outside 0..400");
        }
        return new TargetLoadout(gun, cyberware, health);
    }

    private static StoryMission parseLegacyMission(
            JsonObject value, MissionCatalog.MissionDefinition encounter) {
        List<String> prerequisites = strings(requiredArray(value, "prerequisites"));
        int requiredStreetCred = optionalInteger(value, "required_street_cred", 0);
        District primary = encounter.targetDistricts().getFirst();
        NodeType type = switch (encounter.type()) {
            case ASSASSINATE_TARGET -> NodeType.ASSASSINATE;
            case NEUTRALIZE_CYBERPSYCHO -> NodeType.KILL_CYBERPSYCHO;
            case STEAL_DATA -> NodeType.STEAL;
            case SHIP_ITEM -> NodeType.DELIVER;
        };
        boolean cyberpsycho = encounter.type()
                == MissionCatalog.MissionType.NEUTRALIZE_CYBERPSYCHO;
        StoryNode node = new StoryNode(
                encounter.id() + "_objective", type, "", primary.commandCode(),
                encounter.targetName(), cyberpsycho ? 1 : 2, "", List.of());
        int floors = cyberpsycho ? 1 : 2;
        int lower = encounter.guards() / 2;
        List<Integer> enemies = cyberpsycho
                ? List.of(0) : List.of(lower, encounter.guards() - lower);
        return new StoryMission(
                encounter, prerequisites, requiredStreetCred, "Mainline", primary,
                List.of(primary), "", "", List.of(node), node.id(), floors, enemies,
                "legacy_security", "arasaka", 1.0,
                DefenderKind.SOLDIER, 0.0, false, null, null, "", "");
    }

    private static List<StoryNode> parseNodes(
            String missionId,
            JsonArray values,
            Map<String, CharacterDefinition> characters) {
        List<StoryNode> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            String id = id(value, "node_id");
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate node " + id);
            String character = optionalText(value, "character_id");
            if (!character.isBlank() && !characters.containsKey(character)) {
                throw new IllegalArgumentException(missionId + " node uses unknown character " + character);
            }
            NodeType type = NodeType.parse(text(value, "type"));
            if ((type == NodeType.TALK || type == NodeType.DELIVER
                    || type == NodeType.ASSASSINATE || type == NodeType.KILL_CYBERPSYCHO)
                    && character.isBlank()) {
                throw new IllegalArgumentException(id + " requires a character_id");
            }
            nodes.add(new StoryNode(
                    id,
                    type,
                    character,
                    bounded(text(value, "district"), 1, 16, "node district"),
                    bounded(text(value, "location"), 1, 128, "node location"),
                    range(requiredInteger(value, "floor"), 1, 16, "node floor"),
                    bounded(optionalText(value, "dialogue"), 0, 768, "dialogue"),
                    strings(requiredArray(value, "depends_on"))));
        }
        if (nodes.isEmpty()) throw new IllegalArgumentException(missionId + " has no nodes");
        return List.copyOf(nodes);
    }

    private static void validateMissionDag(List<StoryMission> definitions) {
        Map<String, StoryMission> byId = new HashMap<>();
        for (StoryMission definition : definitions) byId.put(definition.id(), definition);
        boolean hasRoot = false;
        for (StoryMission definition : definitions) {
            hasRoot |= definition.prerequisites().isEmpty();
            Set<String> unique = new HashSet<>();
            for (String prerequisite : definition.prerequisites()) {
                if (!byId.containsKey(prerequisite)) {
                    throw new IllegalArgumentException(
                            definition.id() + " requires missing mission " + prerequisite);
                }
                if (prerequisite.equals(definition.id()) || !unique.add(prerequisite)) {
                    throw new IllegalArgumentException(
                            "invalid prerequisite " + prerequisite + " for " + definition.id());
                }
            }
        }
        if (!hasRoot) throw new IllegalArgumentException("story mission DAG has no root");
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (StoryMission definition : definitions) {
            visitMission(definition.id(), byId, visiting, visited);
        }
    }

    private static void visitMission(
            String id,
            Map<String, StoryMission> byId,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("story mission dependency cycle at " + id);
        }
        for (String prerequisite : byId.get(id).prerequisites()) {
            visitMission(prerequisite, byId, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static void validateNodeDag(
            String missionId, List<StoryNode> nodes, String completionNodeId) {
        Map<String, StoryNode> byId = new HashMap<>();
        for (StoryNode node : nodes) byId.put(node.id(), node);
        if (!byId.containsKey(completionNodeId)) {
            throw new IllegalArgumentException(missionId + " has missing completion node " + completionNodeId);
        }
        boolean hasRoot = false;
        for (StoryNode node : nodes) {
            hasRoot |= node.dependsOn().isEmpty();
            Set<String> unique = new HashSet<>();
            for (String dependency : node.dependsOn()) {
                if (!byId.containsKey(dependency) || dependency.equals(node.id())
                        || !unique.add(dependency)) {
                    throw new IllegalArgumentException(
                            "invalid node dependency " + dependency + " for " + node.id());
                }
            }
        }
        if (!hasRoot) throw new IllegalArgumentException(missionId + " node DAG has no root");
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (StoryNode node : nodes) visitNode(node.id(), byId, visiting, visited);
        Set<String> completionAncestors = new HashSet<>();
        collectAncestors(completionNodeId, byId, completionAncestors);
        if (completionAncestors.size() != nodes.size()) {
            throw new IllegalArgumentException(
                    missionId + " completion node does not depend on every mission node");
        }
    }

    private static void visitNode(
            String id,
            Map<String, StoryNode> byId,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("node dependency cycle at " + id);
        for (String dependency : byId.get(id).dependsOn()) {
            visitNode(dependency, byId, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static void collectAncestors(
            String id, Map<String, StoryNode> byId, Set<String> ancestors) {
        if (!ancestors.add(id)) return;
        for (String dependency : byId.get(id).dependsOn()) {
            collectAncestors(dependency, byId, ancestors);
        }
    }

    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : values) result.add(element.getAsString());
        return List.copyOf(result);
    }

    private static List<Integer> integers(JsonArray values) {
        List<Integer> result = new ArrayList<>();
        for (JsonElement element : values) result.add(element.getAsInt());
        return List.copyOf(result);
    }

    private static List<District> districts(JsonArray values) {
        List<District> result = new ArrayList<>();
        for (JsonElement element : values) {
            District district = district(element.getAsString());
            if (!result.contains(district)) result.add(district);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("district list is empty");
        return List.copyOf(result);
    }

    private static District district(String code) {
        return District.fromCode(code.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("unknown district " + code));
    }

    private static String id(JsonObject value, String key) {
        String id = text(value, key);
        if (!id.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("invalid id " + id + " for " + key);
        }
        return id;
    }

    private static JsonArray requiredArray(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalArgumentException("missing array " + key);
        }
        return value.getAsJsonArray(key);
    }

    private static JsonObject requiredObject(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) {
            throw new IllegalArgumentException("missing object " + key);
        }
        return value.getAsJsonObject(key);
    }

    private static String text(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing text " + key);
        }
        return value.get(key).getAsString();
    }

    private static String optionalText(JsonObject value, String key) {
        if (!value.has(key) || value.get(key).isJsonNull()) return "";
        if (!value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("invalid text " + key);
        }
        return value.get(key).getAsString();
    }

    private static int requiredInteger(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing integer " + key);
        }
        return value.get(key).getAsInt();
    }

    private static int optionalInteger(JsonObject value, String key, int fallback) {
        return value.has(key) ? requiredInteger(value, key) : fallback;
    }

    private static double requiredDouble(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing number " + key);
        }
        return value.get(key).getAsDouble();
    }

    private static double optionalDouble(JsonObject value, String key, double fallback) {
        return value.has(key) ? requiredDouble(value, key) : fallback;
    }

    private static boolean optionalBoolean(JsonObject value, String key, boolean fallback) {
        if (!value.has(key)) return fallback;
        if (!value.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing boolean " + key);
        }
        return value.get(key).getAsBoolean();
    }

    private static int range(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " outside " + minimum + ".." + maximum);
        }
        return value;
    }

    private static String bounded(
            String value, int minimum, int maximum, String label) {
        if (value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(label + " length outside " + minimum + ".." + maximum);
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Catalog loadBundled() {
        try (InputStream stream = StoryMissionCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("missing " + RESOURCE);
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return parseCatalog(root);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid bundled story mission DAG", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("could not load bundled story mission DAG", exception);
        }
    }
}
