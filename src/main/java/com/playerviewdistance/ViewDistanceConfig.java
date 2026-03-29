package com.playerviewdistance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ViewDistanceConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "playerviewdistance.json";
    private static final String LEGACY_FILE_NAME = "customviewdistance.json";

    private static ViewDistanceConfig INSTANCE = new ViewDistanceConfig();

    public int minViewDistance = 2;
    public int maxViewDistance = 32;
    public int moveCheckIntervalTicks = 5;
    public int rdPollIntervalTicks = 20;
    public int instantInnerRadius = 2;
    public int workerThreadCount = 1;

    public static ViewDistanceConfig get() {
        return INSTANCE;
    }

    public static void load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve(FILE_NAME);
        Path legacyConfigFile = configDir.resolve(LEGACY_FILE_NAME);

        if (!Files.exists(configFile) && Files.exists(legacyConfigFile)) {
            try {
                Files.move(legacyConfigFile, configFile);
            } catch (IOException e) {
                PlayerViewDistanceMod.LOGGER.warn("Failed to migrate legacy config {}, using it in place", legacyConfigFile, e);
                configFile = legacyConfigFile;
            }
        }

        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                ViewDistanceConfig loaded = GSON.fromJson(reader, ViewDistanceConfig.class);
                if (loaded != null) {
                    loaded.validate();
                    INSTANCE = loaded;
                    PlayerViewDistanceMod.LOGGER.info("Config loaded from {}", configFile);
                    return;
                }
            } catch (IOException | com.google.gson.JsonSyntaxException e) {
                PlayerViewDistanceMod.LOGGER.error("Failed to load config, using defaults", e);
            }
        }

        // Write default config
        INSTANCE = new ViewDistanceConfig();
        save();
    }

    public static void save() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path configFile = configDir.resolve(FILE_NAME);
        try (Writer writer = Files.newBufferedWriter(configFile)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            PlayerViewDistanceMod.LOGGER.error("Failed to save config", e);
        }
    }

    public static void reload() {
        load();
    }

    private void validate() {
        minViewDistance = Math.max(2, Math.min(32, minViewDistance));
        maxViewDistance = Math.max(minViewDistance, Math.min(32, maxViewDistance));
        moveCheckIntervalTicks = Math.max(1, moveCheckIntervalTicks);
        rdPollIntervalTicks = Math.max(20, rdPollIntervalTicks);
        instantInnerRadius = Math.max(0, Math.min(maxViewDistance, instantInnerRadius));
        workerThreadCount = Math.max(1, Math.min(16, workerThreadCount));
    }
}
