package com.playerviewdistance.core;

import java.util.Comparator;
import java.util.Objects;

/** A loading-only graph source. Radius is measured in graph cells, including Minecraft's margin. */
public record LoadSource(
        PlayerKey owner,
        String dimension,
        int chunkX,
        int chunkZ,
        int viewRadius,
        int ticketRadius
) {
    public static final int LOADING_MARGIN = 2;

    public static final Comparator<LoadSource> ORDER = Comparator
            .comparing(LoadSource::dimension)
            .thenComparingInt(LoadSource::chunkX)
            .thenComparingInt(LoadSource::chunkZ)
            .thenComparingInt(LoadSource::ticketRadius)
            .thenComparing(LoadSource::owner);

    public LoadSource {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(dimension, "dimension");
        if (viewRadius < 2 || viewRadius > 32) {
            throw new IllegalArgumentException("viewRadius must be in [2, 32]");
        }
        if (ticketRadius != viewRadius + LOADING_MARGIN) {
            throw new IllegalArgumentException("ticketRadius must include the two-cell loading margin");
        }
    }

    public static LoadSource desired(PlayerSnapshot player) {
        return new LoadSource(
                player.player(),
                player.dimension(),
                player.chunkX(),
                player.chunkZ(),
                player.effectiveViewDistance(),
                player.effectiveViewDistance() + LOADING_MARGIN
        );
    }

    public long area() {
        long diameter = 2L * ticketRadius + 1L;
        return diameter * diameter;
    }

    public boolean intersects(LoadSource other) {
        return dimension.equals(other.dimension)
                && Math.abs((long) chunkX - other.chunkX) <= (long) ticketRadius + other.ticketRadius
                && Math.abs((long) chunkZ - other.chunkZ) <= (long) ticketRadius + other.ticketRadius;
    }

    public boolean containsCell(int x, int z) {
        return Math.abs((long) x - chunkX) <= ticketRadius
                && Math.abs((long) z - chunkZ) <= ticketRadius;
    }
}
