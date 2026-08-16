package com.playerviewdistance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Loader-owned services needed by the Mojang-mapped runtime.
 *
 * <p>The runtime deliberately knows nothing about Fabric, Forge, or NeoForge.
 * Loader entrypoints install this environment before configuration or any
 * Minecraft class owned by PVD is initialized.</p>
 */
public final class PlatformEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger("playerviewdistance");

    private static volatile Path configDirectory;
    private static volatile Predicate<String> modLoaded = ignored -> false;

    private PlatformEnvironment() {
    }

    public static synchronized void install(Path directory, Predicate<String> loadedPredicate) {
        Path normalized = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        Predicate<String> predicate = Objects.requireNonNull(loadedPredicate, "loadedPredicate");
        if (configDirectory != null && !configDirectory.equals(normalized)) {
            throw new IllegalStateException("PVD platform environment is already installed");
        }
        configDirectory = normalized;
        modLoaded = predicate;
    }

    public static Path configDirectory() {
        Path directory = configDirectory;
        if (directory == null) {
            throw new IllegalStateException("PVD platform environment was not installed");
        }
        return directory;
    }

    public static boolean isModLoaded(String modId) {
        return modLoaded.test(Objects.requireNonNull(modId, "modId"));
    }

    public static Logger logger() {
        return LOGGER;
    }
}
