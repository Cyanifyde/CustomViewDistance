package com.playerviewdistance.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigRepository {
    public static final String FILE_NAME = "playerviewdistance.json";
    public static final String LEGACY_FILE_NAME = "customviewdistance.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configDirectory;
    private final Path configFile;
    private final Path legacyFile;
    private volatile ConfigData current = ConfigData.defaults();

    public ConfigRepository(Path configDirectory) {
        this.configDirectory = configDirectory.toAbsolutePath().normalize();
        this.configFile = this.configDirectory.resolve(FILE_NAME);
        this.legacyFile = this.configDirectory.resolve(LEGACY_FILE_NAME);
    }

    public ConfigData current() {
        return current;
    }

    public LoadOutcome initialize() {
        if (Files.exists(configFile)) {
            return loadPrimary(true);
        }
        if (Files.exists(legacyFile)) {
            return migrateLegacy();
        }
        try {
            AtomicFiles.writeUtf8(configFile, GSON.toJson(ConfigData.defaults()) + System.lineSeparator());
            current = ConfigData.defaults();
            return new LoadOutcome(true, false, current, "Created schema 2 configuration");
        } catch (IOException failure) {
            return new LoadOutcome(false, false, current,
                    "Could not create configuration: " + failure.getMessage());
        }
    }

    public LoadOutcome reload() {
        if (!Files.exists(configFile)) {
            return new LoadOutcome(false, false, current,
                    "Configuration does not exist; keeping the last-known-good settings");
        }
        return loadPrimary(false);
    }

    private LoadOutcome loadPrimary(boolean allowUpgrade) {
        try {
            Parsed parsed = parse(configFile);
            if (parsed.upgraded() && allowUpgrade) {
                AtomicFiles.writeUtf8(configFile, GSON.toJson(parsed.config()) + System.lineSeparator());
            }
            current = parsed.config();
            return new LoadOutcome(true, parsed.upgraded(), current,
                    parsed.upgraded() ? "Upgraded configuration to schema 2" : "Loaded schema 2 configuration");
        } catch (IOException | JsonParseException | IllegalArgumentException failure) {
            return new LoadOutcome(false, false, current,
                    "Invalid configuration; file left untouched and last-known-good settings retained: "
                            + failure.getMessage());
        }
    }

    private LoadOutcome migrateLegacy() {
        try {
            Parsed parsed = parse(legacyFile);
            ConfigData migrated = parsed.config();
            AtomicFiles.writeUtf8(configFile, GSON.toJson(migrated) + System.lineSeparator());
            archiveLegacy();
            current = migrated;
            return new LoadOutcome(true, true, current, "Validated and migrated customviewdistance.json");
        } catch (IOException | JsonParseException | IllegalArgumentException failure) {
            return new LoadOutcome(false, false, current,
                    "Legacy configuration is invalid; it was left untouched: " + failure.getMessage());
        }
    }

    private Parsed parse(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new JsonParseException("root must be a JSON object");
            }
            JsonObject object = root.getAsJsonObject();
            int schema = object.has("schemaVersion") ? exactInt(object, "schemaVersion") : 1;
            if (schema == ConfigData.CURRENT_SCHEMA) {
                ConfigData parsed = new ConfigData(
                        ConfigData.CURRENT_SCHEMA,
                        exactInt(object, "minViewDistance"),
                        exactInt(object, "maxViewDistance"),
                        GovernorProfile.valueOf(exactString(object, "governorProfile")),
                        exactInt(object, "telemetryIntervalSeconds")
                );
                return new Parsed(parsed, false);
            }
            if (schema == 0 || schema == 1) {
                int min = optionalInt(object, "minViewDistance", 2);
                int max = optionalInt(object, "maxViewDistance", 32);
                return new Parsed(new ConfigData(
                        ConfigData.CURRENT_SCHEMA,
                        min,
                        max,
                        GovernorProfile.BALANCED,
                        60
                ), true);
            }
            throw new IllegalArgumentException("unsupported schemaVersion " + schema);
        }
    }

    private void archiveLegacy() throws IOException {
        Path archived = configDirectory.resolve(LEGACY_FILE_NAME + ".migrated");
        if (Files.exists(archived)) {
            return;
        }
        try {
            Files.move(legacyFile, archived, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(legacyFile, archived);
        }
    }

    private static int exactInt(JsonObject object, String name) {
        JsonElement element = required(object, name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(name + " must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new JsonParseException(name + " must be an integer", invalid);
        }
    }

    private static int optionalInt(JsonObject object, String name, int fallback) {
        return object.has(name) ? exactInt(object, name) : fallback;
    }

    private static String exactString(JsonObject object, String name) {
        JsonElement element = required(object, name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(name + " must be a string");
        }
        return element.getAsString();
    }

    private static JsonElement required(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            throw new JsonParseException("missing " + name);
        }
        return element;
    }

    public static final class LoadOutcome {
        private final boolean success;
        private final boolean migrated;
        private final ConfigData config;
        private final String message;

        public LoadOutcome(boolean success, boolean migrated, ConfigData config, String message) {
            this.success = success;
            this.migrated = migrated;
            this.config = config;
            this.message = message;
        }

        public boolean success() { return success; }
        public boolean migrated() { return migrated; }
        public ConfigData config() { return config; }
        public String message() { return message; }
    }

    private static final class Parsed {
        private final ConfigData config;
        private final boolean upgraded;

        private Parsed(ConfigData config, boolean upgraded) {
            this.config = config;
            this.upgraded = upgraded;
        }

        private ConfigData config() { return config; }
        private boolean upgraded() { return upgraded; }
    }
}
