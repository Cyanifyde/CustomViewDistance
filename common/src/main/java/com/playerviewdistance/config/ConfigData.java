package com.playerviewdistance.config;

import java.util.Objects;

public record ConfigData(
        int schemaVersion,
        int minViewDistance,
        int maxViewDistance,
        GovernorProfile governorProfile,
        int telemetryIntervalSeconds
) {
    public static final int CURRENT_SCHEMA = 2;

    public ConfigData {
        Objects.requireNonNull(governorProfile, "governorProfile");
        if (schemaVersion != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("schemaVersion must be " + CURRENT_SCHEMA);
        }
        if (minViewDistance < 2 || minViewDistance > 32) {
            throw new IllegalArgumentException("minViewDistance must be in [2, 32]");
        }
        if (maxViewDistance < minViewDistance || maxViewDistance > 32) {
            throw new IllegalArgumentException("maxViewDistance must be in [minViewDistance, 32]");
        }
        if (telemetryIntervalSeconds < 5 || telemetryIntervalSeconds > 3600) {
            throw new IllegalArgumentException("telemetryIntervalSeconds must be in [5, 3600]");
        }
    }

    public static ConfigData defaults() {
        return new ConfigData(CURRENT_SCHEMA, 2, 32, GovernorProfile.BALANCED, 60);
    }
}
