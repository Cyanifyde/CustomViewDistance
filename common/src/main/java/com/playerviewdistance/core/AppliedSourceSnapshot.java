package com.playerviewdistance.core;

import java.util.Objects;

public record AppliedSourceSnapshot(
        PlayerKey owner,
        String dimension,
        int chunkX,
        int chunkZ,
        int ticketRadius
) {
    public AppliedSourceSnapshot {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(dimension, "dimension");
        if (ticketRadius < 0 || ticketRadius > 34) {
            throw new IllegalArgumentException("ticketRadius must be in [0, 34]");
        }
    }

    public long area() {
        if (ticketRadius == 0) {
            return 0;
        }
        long diameter = 2L * ticketRadius + 1L;
        return diameter * diameter;
    }
}
