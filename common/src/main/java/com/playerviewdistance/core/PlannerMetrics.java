package com.playerviewdistance.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public final class PlannerMetrics {
    private final long tickIntervalNanos;
    private final long averageTickNanos;
    private final long smoothedTickNanos;
    private final long[] recentTickNanos;
    private final boolean frozen;
    private final boolean sprinting;
    private final int pendingChunkWork;
    private final int loadedChunks;
    private final List<DimensionMetrics> dimensions;
    private final int playerCount;
    private final long mobCount;
    private final long itemCount;
    private final long entityCount;
    private final int convergenceAgeTicks;

    public PlannerMetrics(long tickIntervalNanos, long averageTickNanos, long smoothedTickNanos,
                          long[] recentTickNanos, boolean frozen, boolean sprinting,
                          int pendingChunkWork, int loadedChunks, List<DimensionMetrics> dimensions,
                          int playerCount, long mobCount, long itemCount, long entityCount,
                          int convergenceAgeTicks) {
        if (tickIntervalNanos <= 0) {
            throw new IllegalArgumentException("tickIntervalNanos must be positive");
        }
        this.tickIntervalNanos = tickIntervalNanos;
        this.averageTickNanos = averageTickNanos;
        this.smoothedTickNanos = smoothedTickNanos;
        this.recentTickNanos = recentTickNanos == null ? new long[0] : recentTickNanos.clone();
        this.frozen = frozen;
        this.sprinting = sprinting;
        this.pendingChunkWork = Math.max(0, pendingChunkWork);
        this.loadedChunks = Math.max(0, loadedChunks);
        this.dimensions = dimensions == null ? Collections.<DimensionMetrics>emptyList()
                : Collections.unmodifiableList(new ArrayList<DimensionMetrics>(dimensions));
        this.playerCount = Math.max(0, playerCount);
        this.mobCount = Math.max(0, mobCount);
        this.itemCount = Math.max(0, itemCount);
        this.entityCount = Math.max(0, entityCount);
        this.convergenceAgeTicks = Math.max(0, convergenceAgeTicks);
    }

    public long[] recentTickNanos() {
        return recentTickNanos.clone();
    }

    public long tickIntervalNanos() { return tickIntervalNanos; }
    public long averageTickNanos() { return averageTickNanos; }
    public long smoothedTickNanos() { return smoothedTickNanos; }
    public boolean frozen() { return frozen; }
    public boolean sprinting() { return sprinting; }
    public int pendingChunkWork() { return pendingChunkWork; }
    public int loadedChunks() { return loadedChunks; }
    public List<DimensionMetrics> dimensions() { return dimensions; }
    public int playerCount() { return playerCount; }
    public long mobCount() { return mobCount; }
    public long itemCount() { return itemCount; }
    public long entityCount() { return entityCount; }
    public int convergenceAgeTicks() { return convergenceAgeTicks; }
}
