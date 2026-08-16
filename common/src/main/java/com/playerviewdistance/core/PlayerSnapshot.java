package com.playerviewdistance.core;

import java.util.Objects;

public final class PlayerSnapshot {
    private final PlayerKey player;
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int effectiveViewDistance;
    private final int achievedViewDistance;
    private final int simulationDistance;

    public PlayerSnapshot(PlayerKey player, String dimension, int chunkX, int chunkZ,
                          int effectiveViewDistance, int achievedViewDistance, int simulationDistance) {
        this.player = Objects.requireNonNull(player, "player");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (effectiveViewDistance < 2 || effectiveViewDistance > 32) {
            throw new IllegalArgumentException("effectiveViewDistance must be in [2, 32]");
        }

        if (achievedViewDistance < 0 || achievedViewDistance > 32) {
            throw new IllegalArgumentException("achievedViewDistance must be in [0, 32]");
        }
        if (simulationDistance < 2 || simulationDistance > 32) {
            throw new IllegalArgumentException("simulationDistance must be in [2, 32]");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.effectiveViewDistance = effectiveViewDistance;
        this.achievedViewDistance = achievedViewDistance;
        this.simulationDistance = simulationDistance;
    }

    public PlayerKey player() { return player; }
    public String dimension() { return dimension; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int effectiveViewDistance() { return effectiveViewDistance; }
    public int achievedViewDistance() { return achievedViewDistance; }
    public int simulationDistance() { return simulationDistance; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PlayerSnapshot)) return false;
        PlayerSnapshot that = (PlayerSnapshot) other;
        return chunkX == that.chunkX && chunkZ == that.chunkZ
                && effectiveViewDistance == that.effectiveViewDistance
                && achievedViewDistance == that.achievedViewDistance
                && simulationDistance == that.simulationDistance
                && player.equals(that.player) && dimension.equals(that.dimension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(player, dimension, chunkX, chunkZ, effectiveViewDistance,
                achievedViewDistance, simulationDistance);
    }
}
