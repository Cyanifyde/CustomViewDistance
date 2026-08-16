package com.playerviewdistance.config;

import java.util.Objects;

public final class ConfigData {
    public static final int CURRENT_SCHEMA = 2;

    private final int schemaVersion;
    private final int minViewDistance;
    private final int maxViewDistance;
    private final GovernorProfile governorProfile;
    private final int telemetryIntervalSeconds;

    public ConfigData(int schemaVersion, int minViewDistance, int maxViewDistance,
                      GovernorProfile governorProfile, int telemetryIntervalSeconds) {
        this.governorProfile = Objects.requireNonNull(governorProfile, "governorProfile");
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
        this.schemaVersion = schemaVersion;
        this.minViewDistance = minViewDistance;
        this.maxViewDistance = maxViewDistance;
        this.telemetryIntervalSeconds = telemetryIntervalSeconds;
    }

    public int schemaVersion() { return schemaVersion; }
    public int minViewDistance() { return minViewDistance; }
    public int maxViewDistance() { return maxViewDistance; }
    public GovernorProfile governorProfile() { return governorProfile; }
    public int telemetryIntervalSeconds() { return telemetryIntervalSeconds; }

    public static ConfigData defaults() {
        return new ConfigData(CURRENT_SCHEMA, 2, 32, GovernorProfile.BALANCED, 60);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ConfigData)) return false;
        ConfigData that = (ConfigData) other;
        return schemaVersion == that.schemaVersion && minViewDistance == that.minViewDistance
                && maxViewDistance == that.maxViewDistance
                && telemetryIntervalSeconds == that.telemetryIntervalSeconds
                && governorProfile == that.governorProfile;
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, minViewDistance, maxViewDistance,
                governorProfile, telemetryIntervalSeconds);
    }
}
