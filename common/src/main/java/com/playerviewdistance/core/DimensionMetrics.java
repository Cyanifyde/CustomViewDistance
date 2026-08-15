package com.playerviewdistance.core;

import java.util.Objects;

public record DimensionMetrics(String dimension, int pendingChunkWork, int loadedChunks) {
    public DimensionMetrics {
        Objects.requireNonNull(dimension, "dimension");
        pendingChunkWork = Math.max(0, pendingChunkWork);
        loadedChunks = Math.max(0, loadedChunks);
    }
}
