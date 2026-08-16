package com.playerviewdistance.core;

import java.util.Arrays;

public final class AdaptiveGovernor {
    private static final long TWO_MILLISECONDS = 2_000_000L;
    private static final double INITIAL_NANOS_PER_CELL = 600.0;
    private static final double MIN_NANOS_PER_CELL = 25.0;
    private static final double MAX_NANOS_PER_CELL = 250_000.0;

    private double learnedNanosPerCell = INITIAL_NANOS_PER_CELL;
    private double backlogBaseline;
    private int previousBacklog;
    private int backlogGrowthSamples;
    private double capacityMultiplier = 1.0;
    private long sampledCells;
    private long sampledNanos;

    public synchronized void recordApplication(long predictedChangedCells, long elapsedNanos) {
        if (predictedChangedCells <= 0 || elapsedNanos <= 0) {
            return;
        }
        sampledCells = saturatingAdd(sampledCells, predictedChangedCells);
        sampledNanos = saturatingAdd(sampledNanos, elapsedNanos);
    }

    public synchronized GovernorDecision evaluate(PlannerMetrics metrics, long remainingChangedCells) {
        boolean learnedThisSample = learnFromSamples();
        long p95 = percentile95(metrics.recentTickNanos());
        long tickInterval = metrics.tickIntervalNanos();
        long reserve = Math.max(TWO_MILLISECONDS, tickInterval / 10L);
        long observed = Math.max(Math.max(metrics.averageTickNanos(), metrics.smoothedTickNanos()), p95);
        long positiveHeadroom = Math.max(0L, tickInterval - reserve - observed);
        long timeBudget = Math.min(tickInterval * 4L / 100L, positiveHeadroom / 4L);

        updateBacklog(metrics.pendingChunkWork());
        if (!learnedThisSample && metrics.pendingChunkWork() <= backlogBaseline
                && learnedNanosPerCell > INITIAL_NANOS_PER_CELL) {

            learnedNanosPerCell = Math.max(
                    INITIAL_NANOS_PER_CELL,
                    learnedNanosPerCell * 0.85
            );
        }
        boolean doubledBaseline = backlogBaseline >= 1.0
                && metrics.pendingChunkWork() >= Math.max(16.0, backlogBaseline * 2.0);
        boolean severeTickPressure = observed >= tickInterval + reserve;

        GovernorDecision.State state;
        if (metrics.frozen()) {
            state = GovernorDecision.State.PAUSED_FROZEN;
            timeBudget = 0;
        } else if (backlogGrowthSamples >= 5 || doubledBaseline || severeTickPressure) {
            state = GovernorDecision.State.PAUSED_PRESSURE;
            timeBudget = 0;
        } else if (backlogGrowthSamples >= 2 || capacityMultiplier < 0.999 || metrics.sprinting()) {
            state = GovernorDecision.State.THROTTLED;
        } else {
            state = GovernorDecision.State.BALANCED;
        }

        double sprintMultiplier = metrics.sprinting() ? 0.5 : 1.0;
        long capacity = timeBudget <= 0
                ? 0
                : (long) Math.floor(timeBudget * capacityMultiplier * sprintMultiplier / learnedNanosPerCell);

        long graphCellBudget = remainingChangedCells <= 0 ? 0 : Math.max(0, capacity);

        return new GovernorDecision(
                state,
                graphCellBudget,
                timeBudget,
                reserve,
                p95,
                learnedNanosPerCell,
                capacityMultiplier,
                backlogBaseline,
                backlogGrowthSamples
        );
    }

    private boolean learnFromSamples() {
        if (sampledCells == 0) {
            return false;
        }
        double observation = Math.max(MIN_NANOS_PER_CELL,
                Math.min(MAX_NANOS_PER_CELL, (double) sampledNanos / sampledCells));
        learnedNanosPerCell = learnedNanosPerCell * 0.8 + observation * 0.2;
        sampledCells = 0;
        sampledNanos = 0;
        return true;
    }

    private void updateBacklog(int backlog) {
        if (backlogBaseline == 0.0) {
            backlogBaseline = Math.max(1, backlog);
        } else if (backlog <= backlogBaseline) {
            backlogBaseline = backlogBaseline * 0.85 + Math.max(1, backlog) * 0.15;
        }

        if (backlog > previousBacklog && backlog > backlogBaseline) {
            backlogGrowthSamples++;
        } else if (backlog <= backlogBaseline) {
            backlogGrowthSamples = 0;
        }

        if (backlogGrowthSamples >= 2) {
            capacityMultiplier = Math.max(0.125, capacityMultiplier * 0.5);
        } else if (backlog <= backlogBaseline) {
            capacityMultiplier = Math.min(1.0, capacityMultiplier + 0.05);
        }
        previousBacklog = backlog;
    }

    private static long percentile95(long[] samples) {
        if (samples.length == 0) {
            return 0;
        }
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(sorted.length * 0.95) - 1);
        return sorted[index];
    }

    private static long saturatingAdd(long first, long second) {
        long result = first + second;
        if (((first ^ result) & (second ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }
}
