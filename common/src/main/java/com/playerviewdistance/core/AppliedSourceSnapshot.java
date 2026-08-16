package com.playerviewdistance.core;

import java.util.Objects;

public final class AppliedSourceSnapshot {
    private final PlayerKey owner;
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int ticketRadius;

    public AppliedSourceSnapshot(PlayerKey owner, String dimension, int chunkX, int chunkZ, int ticketRadius) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (ticketRadius < 0 || ticketRadius > 34) {
            throw new IllegalArgumentException("ticketRadius must be in [0, 34]");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.ticketRadius = ticketRadius;
    }

    public PlayerKey owner() { return owner; }
    public String dimension() { return dimension; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int ticketRadius() { return ticketRadius; }

    public long area() {
        if (ticketRadius == 0) {
            return 0;
        }
        long diameter = 2L * ticketRadius + 1L;
        return diameter * diameter;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AppliedSourceSnapshot)) return false;
        AppliedSourceSnapshot that = (AppliedSourceSnapshot) other;
        return chunkX == that.chunkX && chunkZ == that.chunkZ && ticketRadius == that.ticketRadius
                && owner.equals(that.owner) && dimension.equals(that.dimension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, dimension, chunkX, chunkZ, ticketRadius);
    }
}
