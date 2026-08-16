package com.playerviewdistance.core;

public final class PlayerCoverage {
    private final PlayerKey player;
    private final int achievedViewDistance;

    public PlayerCoverage(PlayerKey player, int achievedViewDistance) {
        this.player = java.util.Objects.requireNonNull(player, "player");
        if (achievedViewDistance < 0 || achievedViewDistance > 32) {
            throw new IllegalArgumentException("achievedViewDistance must be in [0, 32]");
        }
        this.achievedViewDistance = achievedViewDistance;
    }

    public PlayerKey player() { return player; }
    public int achievedViewDistance() { return achievedViewDistance; }
}
