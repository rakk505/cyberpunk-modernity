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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, cycle-checked main-story mission DAG loaded from editable server data. */
public final class StoryMissionCatalog {
    public static final String RESOURCE = "/data/neoncity/missions/story.json";
    public static final int SCHEMA_VERSION = 1;
    private static final Path CONFIGURATION_PATH = Path.of(
            "config", "cyberdeck", "story_missions.json");
    private static volatile List<StoryMission> definitions = loadBundled();

    private StoryMissionCatalog() {
    }

    public record StoryMission(
            MissionCatalog.MissionDefinition encounter,
            List<String> prerequisites,
            int requiredStreetCred) {
        public StoryMission {
            prerequisites = List.copyOf(prerequisites);
        }

        public String id() {
            return encounter.id();
        }

        public boolean available(Set<String> completed, int streetCred) {
            return streetCred >= requiredStreetCred && completed.containsAll(prerequisites);
        }
    }

    public static List<StoryMission> definitions() {
        return definitions;
    }

    public static StoryMission definition(String id) {
        return definitions.stream()
                .filter(definition -> definition.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown story mission " + id));
    }

    public static List<StoryMission> available(Set<String> completed, int streetCred) {
        return definitions.stream()
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
            List<StoryMission> loaded;
            try (var reader = Files.newBufferedReader(CONFIGURATION_PATH, StandardCharsets.UTF_8)) {
                loaded = parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
            definitions = loaded;
            return loaded.size();
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

    static List<StoryMission> parse(JsonObject root) {
        if (!root.has("schema_version")
                || root.get("schema_version").getAsInt() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported story mission schema");
        }
        JsonArray values = requiredArray(root, "missions");
        List<StoryMission> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("story mission entry is not an object");
            }
            JsonObject value = element.getAsJsonObject();
            MissionCatalog.MissionDefinition encounter = MissionCatalog.parseDefinition(value);
            if (!ids.add(encounter.id())) {
                throw new IllegalArgumentException("duplicate story mission " + encounter.id());
            }
            List<String> prerequisites = new ArrayList<>();
            for (JsonElement prerequisite : requiredArray(value, "prerequisites")) {
                prerequisites.add(prerequisite.getAsString());
            }
            int requiredStreetCred = value.has("required_street_cred")
                    ? value.get("required_street_cred").getAsInt() : 0;
            if (requiredStreetCred < 0 || requiredStreetCred > 1_000_000) {
                throw new IllegalArgumentException(
                        "required_street_cred outside 0..1000000 for " + encounter.id());
            }
            result.add(new StoryMission(encounter, prerequisites, requiredStreetCred));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("story mission DAG is empty");
        validateDag(result);
        return List.copyOf(result);
    }

    private static void validateDag(List<StoryMission> definitions) {
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
            visit(definition.id(), byId, visiting, visited);
        }
    }

    private static void visit(
            String id,
            Map<String, StoryMission> byId,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("story mission dependency cycle at " + id);
        }
        for (String prerequisite : byId.get(id).prerequisites()) {
            visit(prerequisite, byId, visiting, visited);
        }
        visiting.remove(id);
        visited.add(id);
    }

    private static JsonArray requiredArray(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) {
            throw new IllegalArgumentException("missing array " + key);
        }
        return value.getAsJsonArray(key);
    }

    private static List<StoryMission> loadBundled() {
        try (InputStream stream = StoryMissionCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("missing " + RESOURCE);
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            return parse(root);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid bundled story mission DAG", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("could not load bundled story mission DAG", exception);
        }
    }
}
