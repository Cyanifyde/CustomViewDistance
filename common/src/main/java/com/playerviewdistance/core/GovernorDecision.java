package com.playerviewdistance.core;

public final class GovernorDecision {
    private final State state;
    private final long graphCellBudget;
    private final long timeBudgetNanos;
    private final long reserveNanos;
    private final long p95TickNanos;
    private final double learnedNanosPerCell;
    private final double capacityMultiplier;
    private final double backlogBaseline;
    private final int backlogGrowthSamples;

    public GovernorDecision(State state, long graphCellBudget, long timeBudgetNanos,
                            long reserveNanos, long p95TickNanos, double learnedNanosPerCell,
                            double capacityMultiplier, double backlogBaseline, int backlogGrowthSamples) {
        this.state = state;
        this.graphCellBudget = graphCellBudget;
        this.timeBudgetNanos = timeBudgetNanos;
        this.reserveNanos = reserveNanos;
        this.p95TickNanos = p95TickNanos;
        this.learnedNanosPerCell = learnedNanosPerCell;
        this.capacityMultiplier = capacityMultiplier;
        this.backlogBaseline = backlogBaseline;
        this.backlogGrowthSamples = backlogGrowthSamples;
    }

    public State state() { return state; }
    public long graphCellBudget() { return graphCellBudget; }
    public long timeBudgetNanos() { return timeBudgetNanos; }
    public long reserveNanos() { return reserveNanos; }
    public long p95TickNanos() { return p95TickNanos; }
    public double learnedNanosPerCell() { return learnedNanosPerCell; }
    public double capacityMultiplier() { return capacityMultiplier; }
    public double backlogBaseline() { return backlogBaseline; }
    public int backlogGrowthSamples() { return backlogGrowthSamples; }

    public enum State {
        BALANCED,
        THROTTLED,
        PAUSED_PRESSURE,
        PAUSED_FROZEN
    }
}
