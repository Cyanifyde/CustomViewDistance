package com.playerviewdistance.core;

/** Maximum per-player view radius fully backed by the projected loading union. */
public record PlayerCoverage(PlayerKey player, int achievedViewDistance) {
    public PlayerCoverage {
        if (achievedViewDistance < 0 || achievedViewDistance > 32) {
            throw new IllegalArgumentException("achievedViewDistance must be in [0, 32]");
        }
    }
}
