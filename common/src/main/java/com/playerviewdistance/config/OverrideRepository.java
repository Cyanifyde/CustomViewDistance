package com.playerviewdistance.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.playerviewdistance.core.PlayerKey;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class OverrideRepository {
    public static final String FILE_NAME = "playerviewdistance-overrides.json";
    private static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public OverrideRepository(Path configDirectory) {
        this.file = configDirectory.toAbsolutePath().normalize().resolve(FILE_NAME);
    }

    public LoadOutcome load() {
        if (!Files.exists(file)) {
            try {
                save(Map.of());
                return new LoadOutcome(true, Map.of(), "Created persistent override store");
            } catch (IOException failure) {
                return new LoadOutcome(false, Map.of(), "Could not create override store: " + failure.getMessage());
            }
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new JsonParseException("root must be an object");
            }
            JsonObject object = root.getAsJsonObject();
            if (!object.has("schemaVersion") || exactInt(object.get("schemaVersion"), "schemaVersion") != SCHEMA) {
                throw new JsonParseException("schemaVersion must be " + SCHEMA);
            }
            JsonElement overridesElement = object.get("overrides");
            if (overridesElement == null || !overridesElement.isJsonObject()) {
                throw new JsonParseException("overrides must be an object");
            }
            Map<PlayerKey, Integer> result = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : overridesElement.getAsJsonObject().entrySet()) {
                UUID uuid = UUID.fromString(entry.getKey());
                int distance = exactInt(entry.getValue(), "override for " + uuid);
                if (distance < 2 || distance > 32) {
                    throw new IllegalArgumentException("override for " + uuid + " must be in [2, 32]");
                }
                result.put(PlayerKey.of(uuid), distance);
            }
            return new LoadOutcome(true, Map.copyOf(result), "Loaded persistent overrides");
        } catch (IOException | JsonParseException | IllegalArgumentException | ArithmeticException failure) {
            return new LoadOutcome(false, Map.of(),
                    "Invalid override store; file left untouched: " + failure.getMessage());
        }
    }

    private static int exactInt(JsonElement element, String name) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(name + " must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new JsonParseException(name + " must be an integer", invalid);
        }
    }

    public void save(Map<PlayerKey, Integer> overrides) throws IOException {
        Map<String, Integer> sorted = new TreeMap<>();
        for (Map.Entry<PlayerKey, Integer> entry : overrides.entrySet()) {
            int value = entry.getValue();
            if (value < 2 || value > 32) {
                throw new IllegalArgumentException("override must be in [2, 32]");
            }
            sorted.put(entry.getKey().toUuid().toString(), value);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", SCHEMA);
        document.put("overrides", sorted);
        AtomicFiles.writeUtf8(file, GSON.toJson(document) + System.lineSeparator());
    }

    public record LoadOutcome(boolean success, Map<PlayerKey, Integer> overrides, String message) {
    }
}
