package com.playerviewdistance.config;

public final class ViewDistancePolicy {
    private ViewDistancePolicy() {
    }

    public static int effectiveDistance(
            int clientRequest,
            Integer persistentOverride,
            ConfigData config,
            int serverViewDistance
    ) {
        int hardCap = Math.max(2, Math.min(32, serverViewDistance));
        int upper = Math.min(config.maxViewDistance(), hardCap);
        int lower = Math.min(config.minViewDistance(), upper);
        int requested = persistentOverride == null ? clientRequest : persistentOverride;
        return Math.max(lower, Math.min(upper, requested));
    }
}
