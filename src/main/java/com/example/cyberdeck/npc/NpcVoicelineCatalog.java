package com.example.cyberdeck.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict bundled catalog for district- and role-specific ambient NPC dialogue. */
public final class NpcVoicelineCatalog {
    public static final String RESOURCE = "/data/cyberdeck/voicelines/ambient.json";
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_LINE_LENGTH = 512;
    private static final Catalog BUNDLED = loadBundled();

    public enum LocationPool {
        GREAT_HIGHWAY("GREAT_HIGHWAY"),
        DISTRICT_O("DISTRICT_O"),
        DISTRICT_P("DISTRICT_P"),
        DISTRICT_D("DISTRICT_D"),
        DISTRICT_G("DISTRICT_G"),
        DISTRICT_K("DISTRICT_K"),
        DISTRICT_B("DISTRICT_B"),
        DISTRICT_M("DISTRICT_M"),
        BORDER_SLUMS("BORDER_SLUMS"),
        DISTRICT_A("DISTRICT_A"),
        DISTRICT_E("DISTRICT_E"),
        DISTRICT_N("DISTRICT_N"),
        GENERIC_UNSUPPORTED_DISTRICTS("generic_unsupported_districts");

        private final String id;

        LocationPool(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum RolePool {
        RESIDENTS("residents"),
        CORPOS("corpos"),
        EXECS("execs");

        private final String id;

        RolePool(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Catalog(Map<LocationPool, Map<RolePool, List<String>>> pools) {
        public Catalog {
            EnumMap<LocationPool, Map<RolePool, List<String>>> locations =
                    new EnumMap<>(LocationPool.class);
            for (LocationPool location : LocationPool.values()) {
                Map<RolePool, List<String>> rawRoles = pools.get(location);
                if (rawRoles == null) {
                    throw new IllegalArgumentException("missing voiceline location " + location.id());
                }
                EnumMap<RolePool, List<String>> roles = new EnumMap<>(RolePool.class);
                for (RolePool role : RolePool.values()) {
                    List<String> lines = rawRoles.get(role);
                    if (lines == null || lines.isEmpty()) {
                        throw new IllegalArgumentException(
                                "empty voiceline pool " + location.id() + "/" + role.id());
                    }
                    roles.put(role, List.copyOf(lines));
                }
                locations.put(location, Map.copyOf(roles));
            }
            pools = Map.copyOf(locations);
        }

        public List<String> lines(LocationPool location, RolePool role) {
            return pools.get(location).get(role);
        }
    }

    private NpcVoicelineCatalog() {
    }

    public static List<String> lines(LocationPool location, RolePool role) {
        return BUNDLED.lines(location, role);
    }

    static Catalog parse(JsonObject root) {
        if (!root.has("schema_version")
                || root.get("schema_version").getAsInt() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported ambient voiceline schema");
        }
        JsonObject pools = requiredObject(root, "pools");
        Set<String> expectedLocations = new HashSet<>();
        EnumMap<LocationPool, Map<RolePool, List<String>>> parsed =
                new EnumMap<>(LocationPool.class);
        for (LocationPool location : LocationPool.values()) {
            expectedLocations.add(location.id());
            JsonObject roles = requiredObject(pools, location.id());
            Set<String> expectedRoles = new HashSet<>();
            EnumMap<RolePool, List<String>> rolePools = new EnumMap<>(RolePool.class);
            for (RolePool role : RolePool.values()) {
                expectedRoles.add(role.id());
                JsonArray values = requiredArray(roles, role.id());
                if (values.isEmpty()) {
                    throw new IllegalArgumentException(
                            "empty voiceline pool " + location.id() + "/" + role.id());
                }
                List<String> lines = new ArrayList<>(values.size());
                Set<String> unique = new HashSet<>();
                for (JsonElement element : values) {
                    if (!element.isJsonPrimitive()
                            || !element.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException(
                                "non-string voiceline in " + location.id() + "/" + role.id());
                    }
                    String line = element.getAsString().strip();
                    int length = line.codePointCount(0, line.length());
                    if (line.isEmpty() || length > MAX_LINE_LENGTH) {
                        throw new IllegalArgumentException(String.format(
                                Locale.ROOT,
                                "voiceline length %d outside 1..%d in %s/%s",
                                length, MAX_LINE_LENGTH, location.id(), role.id()));
                    }
                    if (!unique.add(line)) {
                        throw new IllegalArgumentException(
                                "duplicate voiceline in " + location.id() + "/" + role.id());
                    }
                    lines.add(line);
                }
                rolePools.put(role, List.copyOf(lines));
            }
            rejectUnknownKeys(roles, expectedRoles, "role in " + location.id());
            parsed.put(location, Map.copyOf(rolePools));
        }
        rejectUnknownKeys(pools, expectedLocations, "voiceline location");
        return new Catalog(parsed);
    }

    private static Catalog loadBundled() {
        try (InputStream stream = NpcVoicelineCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("invalid bundled voiceline catalog " + RESOURCE, exception);
        } catch (Exception exception) {
            throw new IllegalStateException("could not load bundled voiceline catalog " + RESOURCE,
                    exception);
        }
    }

    private static JsonObject requiredObject(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonObject()) {
            throw new IllegalArgumentException("missing object " + key);
        }
        return owner.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject owner, String key) {
        if (!owner.has(key) || !owner.get(key).isJsonArray()) {
            throw new IllegalArgumentException("missing array " + key);
        }
        return owner.getAsJsonArray(key);
    }

    private static void rejectUnknownKeys(
            JsonObject object, Set<String> expected, String description) {
        for (String key : object.keySet()) {
            if (!expected.contains(key)) {
                throw new IllegalArgumentException("unknown " + description + " " + key);
            }
        }
    }
}
