package com.playerviewdistance.core;

public record GovernorDecision(
        State state,
        long graphCellBudget,
        long timeBudgetNanos,
        long reserveNanos,
        long p95TickNanos,
        double learnedNanosPerCell,
        double capacityMultiplier,
        double backlogBaseline,
        int backlogGrowthSamples
) {
    public enum State {
        BALANCED,
        THROTTLED,
        PAUSED_PRESSURE,
        PAUSED_FROZEN
    }
}
