package com.playerviewdistance.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class NativePlanResult {
    private final long generation;
    private final long playerGeneration;
    private final Map<PlayerKey, Integer> approvedLoadingDistances;
    private final int desiredSourceCount;
    private final long desiredUnionArea;
    private final long appliedUnionArea;
    private final long remainingChangedCells;
    private final GovernorDecision governor;
    private final long plannerNanos;

    public NativePlanResult(long generation, long playerGeneration,
                            Map<PlayerKey, Integer> approvedLoadingDistances,
                            int desiredSourceCount, long desiredUnionArea, long appliedUnionArea,
                            long remainingChangedCells, GovernorDecision governor, long plannerNanos) {
        this.generation = generation;
        this.playerGeneration = playerGeneration;
        this.approvedLoadingDistances = Collections.unmodifiableMap(
                new HashMap<PlayerKey, Integer>(approvedLoadingDistances));
        this.desiredSourceCount = desiredSourceCount;
        this.desiredUnionArea = desiredUnionArea;
        this.appliedUnionArea = appliedUnionArea;
        this.remainingChangedCells = remainingChangedCells;
        this.governor = governor;
        this.plannerNanos = plannerNanos;
    }

    public long generation() { return generation; }
    public long playerGeneration() { return playerGeneration; }
    public Map<PlayerKey, Integer> approvedLoadingDistances() { return approvedLoadingDistances; }
    public int desiredSourceCount() { return desiredSourceCount; }
    public long desiredUnionArea() { return desiredUnionArea; }
    public long appliedUnionArea() { return appliedUnionArea; }
    public long remainingChangedCells() { return remainingChangedCells; }
    public GovernorDecision governor() { return governor; }
    public long plannerNanos() { return plannerNanos; }
}
