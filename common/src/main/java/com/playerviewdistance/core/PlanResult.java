package com.playerviewdistance.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public final class PlanResult {
    private final long generation;
    private final long playerGeneration;
    private final long appliedRevision;
    private final List<LoadSource> desiredSources;
    private final List<SourceAssignment> assignments;
    private final List<SourceMutation> mutations;
    private final List<PlayerCoverage> playerCoverage;
    private final long desiredUnionArea;
    private final long appliedUnionArea;
    private final long remainingChangedCells;
    private final GovernorDecision governor;
    private final long plannerNanos;

    public PlanResult(long generation, long playerGeneration, long appliedRevision,
                      List<LoadSource> desiredSources, List<SourceAssignment> assignments,
                      List<SourceMutation> mutations, List<PlayerCoverage> playerCoverage,
                      long desiredUnionArea, long appliedUnionArea, long remainingChangedCells,
                      GovernorDecision governor, long plannerNanos) {
        this.generation = generation;
        this.playerGeneration = playerGeneration;
        this.appliedRevision = appliedRevision;
        this.desiredSources = immutable(desiredSources);
        this.assignments = immutable(assignments);
        this.mutations = immutable(mutations);
        this.playerCoverage = immutable(playerCoverage);
        this.desiredUnionArea = desiredUnionArea;
        this.appliedUnionArea = appliedUnionArea;
        this.remainingChangedCells = remainingChangedCells;
        this.governor = java.util.Objects.requireNonNull(governor, "governor");
        this.plannerNanos = plannerNanos;
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public long generation() { return generation; }
    public long playerGeneration() { return playerGeneration; }
    public long appliedRevision() { return appliedRevision; }
    public List<LoadSource> desiredSources() { return desiredSources; }
    public List<SourceAssignment> assignments() { return assignments; }
    public List<SourceMutation> mutations() { return mutations; }
    public List<PlayerCoverage> playerCoverage() { return playerCoverage; }
    public long desiredUnionArea() { return desiredUnionArea; }
    public long appliedUnionArea() { return appliedUnionArea; }
    public long remainingChangedCells() { return remainingChangedCells; }
    public GovernorDecision governor() { return governor; }
    public long plannerNanos() { return plannerNanos; }
}
