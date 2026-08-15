package com.playerviewdistance.core;

import java.util.List;

public record PlannerMetrics(
        long tickIntervalNanos,
        long averageTickNanos,
        long smoothedTickNanos,
        long[] recentTickNanos,
        boolean frozen,
        boolean sprinting,
        int pendingChunkWork,
        int loadedChunks,
        List<DimensionMetrics> dimensions,
        int playerCount,
        long mobCount,
        long itemCount,
        long entityCount,
        int convergenceAgeTicks
) {
    public PlannerMetrics {
        if (tickIntervalNanos <= 0) {
            throw new IllegalArgumentException("tickIntervalNanos must be positive");
        }
        recentTickNanos = recentTickNanos == null ? new long[0] : recentTickNanos.clone();
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        pendingChunkWork = Math.max(0, pendingChunkWork);
        loadedChunks = Math.max(0, loadedChunks);
        playerCount = Math.max(0, playerCount);
        mobCount = Math.max(0, mobCount);
        itemCount = Math.max(0, itemCount);
        entityCount = Math.max(0, entityCount);
        convergenceAgeTicks = Math.max(0, convergenceAgeTicks);
    }

    @Override
    public long[] recentTickNanos() {
        return recentTickNanos.clone();
    }
}
