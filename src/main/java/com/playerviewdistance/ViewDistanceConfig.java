package com.playerviewdistance;

import com.playerviewdistance.config.ConfigData;
import com.playerviewdistance.config.ConfigRepository;
import java.nio.file.Path;

public final class ViewDistanceConfig {
    private static ConfigRepository repository;

    private ViewDistanceConfig() {
    }

    public static synchronized ConfigRepository.LoadOutcome initialize() {
        if (repository == null) {
            repository = new ConfigRepository(configDirectory());
        }
        ConfigRepository.LoadOutcome outcome = repository.initialize();
        log(outcome);
        return outcome;
    }

    public static ConfigData get() {
        if (repository == null) {
            initialize();
        }
        return repository.current();
    }

    public static synchronized ConfigRepository.LoadOutcome reload() {
        if (repository == null) {
            initialize();
        }
        ConfigRepository.LoadOutcome outcome = repository.reload();
        log(outcome);
        return outcome;
    }

    public static Path configDirectory() {
        return PlatformEnvironment.configDirectory();
    }

    private static void log(ConfigRepository.LoadOutcome outcome) {
        if (outcome.success()) {
            PlatformEnvironment.logger().debug("{}", outcome.message());
        } else {
            PlatformEnvironment.logger().error("{}", outcome.message());
        }
    }
}
