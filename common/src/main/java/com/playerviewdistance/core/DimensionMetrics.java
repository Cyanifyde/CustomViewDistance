package com.playerviewdistance.core;

import java.util.Objects;

public final class DimensionMetrics {
    private final String dimension;
    private final int pendingChunkWork;
    private final int loadedChunks;

    public DimensionMetrics(String dimension, int pendingChunkWork, int loadedChunks) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.pendingChunkWork = Math.max(0, pendingChunkWork);
        this.loadedChunks = Math.max(0, loadedChunks);
    }

    public String dimension() { return dimension; }
    public int pendingChunkWork() { return pendingChunkWork; }
    public int loadedChunks() { return loadedChunks; }
}
