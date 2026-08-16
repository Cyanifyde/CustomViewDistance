package com.playerviewdistance.core;

import java.util.Comparator;
import java.util.Objects;

public final class LoadSource {
    public static final int LOADING_MARGIN = 2;

    public static final Comparator<LoadSource> ORDER = Comparator
            .comparing(LoadSource::dimension)
            .thenComparingInt(LoadSource::chunkX)
            .thenComparingInt(LoadSource::chunkZ)
            .thenComparingInt(LoadSource::ticketRadius)
            .thenComparing(LoadSource::owner);

    private final PlayerKey owner;
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int viewRadius;
    private final int ticketRadius;

    public LoadSource(PlayerKey owner, String dimension, int chunkX, int chunkZ,
                      int viewRadius, int ticketRadius) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (viewRadius < 2 || viewRadius > 32) {
            throw new IllegalArgumentException("viewRadius must be in [2, 32]");
        }
        if (ticketRadius != viewRadius + LOADING_MARGIN) {
            throw new IllegalArgumentException("ticketRadius must include the two-cell loading margin");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.viewRadius = viewRadius;
        this.ticketRadius = ticketRadius;
    }

    public PlayerKey owner() { return owner; }
    public String dimension() { return dimension; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int viewRadius() { return viewRadius; }
    public int ticketRadius() { return ticketRadius; }

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

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LoadSource)) return false;
        LoadSource that = (LoadSource) other;
        return chunkX == that.chunkX && chunkZ == that.chunkZ
                && viewRadius == that.viewRadius && ticketRadius == that.ticketRadius
                && owner.equals(that.owner) && dimension.equals(that.dimension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, dimension, chunkX, chunkZ, viewRadius, ticketRadius);
    }
}
