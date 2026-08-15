package com.playerviewdistance.core;

import java.util.List;

public record PlanResult(
        long generation,
        long playerGeneration,
        long appliedRevision,
        List<LoadSource> desiredSources,
        List<SourceAssignment> assignments,
        List<SourceMutation> mutations,
        List<PlayerCoverage> playerCoverage,
        long desiredUnionArea,
        long appliedUnionArea,
        long remainingChangedCells,
        GovernorDecision governor,
        long plannerNanos
) {
}
