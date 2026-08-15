package com.playerviewdistance.core;

import java.util.Objects;

public record PlayerSnapshot(
        PlayerKey player,
        String dimension,
        int chunkX,
        int chunkZ,
        int effectiveViewDistance,
        int achievedViewDistance
) {
    public PlayerSnapshot {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(dimension, "dimension");
        if (effectiveViewDistance < 2 || effectiveViewDistance > 32) {
            throw new IllegalArgumentException("effectiveViewDistance must be in [2, 32]");
        }
        if (achievedViewDistance < 0 || achievedViewDistance > effectiveViewDistance) {
            throw new IllegalArgumentException("achievedViewDistance must be in [0, effectiveViewDistance]");
        }
    }
}
