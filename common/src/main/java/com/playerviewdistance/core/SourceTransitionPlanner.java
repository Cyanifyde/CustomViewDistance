package com.playerviewdistance.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public final class SourceTransitionPlanner {
    private final DeficitRoundRobin growthScheduler = new DeficitRoundRobin();

    public TransitionResult plan(
            List<SourceAssignment> assignments,
            List<AppliedSourceSnapshot> applied,
            List<PlayerSnapshot> players,
            GovernorDecision governor
    ) {
        Map<PlayerKey, Integer> simulationDistances = new HashMap<>();
        for (PlayerSnapshot player : players) {
            simulationDistances.put(player.player(), player.simulationDistance());
        }
        boolean[] claimed = new boolean[applied.size()];
        Map<PlayerKey, AppliedSourceSnapshot> working = new LinkedHashMap<>();
        List<SourceMutation> mutations = new ArrayList<>();

        for (SourceAssignment assignment : assignments) {
            LoadSource target = assignment.desired();
            int exactIndex = findExact(target, applied, claimed);
            if (exactIndex < 0) {
                continue;
            }
            AppliedSourceSnapshot exact = applied.get(exactIndex);
            if (exact.ticketRadius() > target.ticketRadius()) {
                continue;
            }
            claimed[exactIndex] = true;
            AppliedSourceSnapshot retained = withOwner(exact, target.owner());
            working.put(target.owner(), retained);
            if (!exact.owner().equals(target.owner())) {
                mutations.add(new SourceMutation(SourceMutation.Kind.REASSIGN, exact, retained, 0));
            }
        }

        for (int index = 0; index < applied.size(); index++) {
            if (!claimed[index]) {
                AppliedSourceSnapshot source = applied.get(index);
                mutations.add(new SourceMutation(
                        SourceMutation.Kind.REMOVE, source, null, source.area()));
            }
        }

        long remainingBudget = governor.graphCellBudget();
        boolean paused = governor.state() == GovernorDecision.State.PAUSED_FROZEN
                || governor.state() == GovernorDecision.State.PAUSED_PRESSURE;
        List<SourceAssignment> missing = new ArrayList<>();
        for (SourceAssignment assignment : assignments) {
            if (!working.containsKey(assignment.desired().owner())) {
                missing.add(assignment);
            }
        }
        missing.sort(Comparator.comparing(SourceAssignment::replacement).reversed()
                .thenComparing(SourceAssignment::desired, LoadSource.ORDER));

        if (!paused) {
            for (SourceAssignment assignment : missing) {
                int simulationDistance = simulationDistances.getOrDefault(
                        assignment.desired().owner(), 2);
                int minimumUsefulRadius = simulationDistance + LoadSource.LOADING_MARGIN + 1;
                int preferred = Math.max(assignment.initialTicketRadius(), minimumUsefulRadius);
                int radius = largestAffordableRadius(
                        preferred,
                        assignment.desired().ticketRadius(),
                        minimumUsefulRadius,
                        remainingBudget
                );
                if (radius == 0) {
                    continue;
                }
                long cost = ChunkCells.squareArea(radius);
                LoadSource target = assignment.desired();
                AppliedSourceSnapshot created = new AppliedSourceSnapshot(
                        target.owner(), target.dimension(), target.chunkX(), target.chunkZ(), radius);
                mutations.add(new SourceMutation(SourceMutation.Kind.ADD, null, created, cost));
                working.put(created.owner(), created);
                remainingBudget -= cost;
            }

            Map<String, LongOpenHashSet> covered = coverage(working.values());
            List<DeficitRoundRobin.GrowthCandidate> candidates = new ArrayList<>();
            for (SourceAssignment assignment : assignments) {
                LoadSource target = assignment.desired();
                AppliedSourceSnapshot existing = working.get(target.owner());
                if (existing != null && existing.ticketRadius() < target.ticketRadius()) {
                    candidates.add(new DeficitRoundRobin.GrowthCandidate(
                            target.owner(),
                            existing.ticketRadius(),
                            target.ticketRadius(),
                            Math.max(1, uncoveredArea(target, covered))
                    ));
                }
            }
            for (DeficitRoundRobin.GrowthGrant grant : growthScheduler.allocate(candidates, remainingBudget)) {
                AppliedSourceSnapshot old = working.get(grant.owner());
                if (old == null || grant.targetTicketRadius() <= old.ticketRadius()) {
                    continue;
                }
                AppliedSourceSnapshot grown = withRadius(old, grant.targetTicketRadius());
                mutations.add(new SourceMutation(
                        SourceMutation.Kind.RESIZE, old, grown, grant.predictedChangedCells()));
                working.put(grown.owner(), grown);
            }
        } else {
            growthScheduler.allocate(Collections.<DeficitRoundRobin.GrowthCandidate>emptyList(), 0);
        }

        List<AppliedSourceSnapshot> projected = new ArrayList<>(working.values());
        projected.sort(APPLIED_ORDER);
        return new TransitionResult(mutations, projected);
    }

    private static int findExact(
            LoadSource desired,
            List<AppliedSourceSnapshot> applied,
            boolean[] claimed
    ) {
        int owned = -1;
        int fallback = -1;
        for (int index = 0; index < applied.size(); index++) {
            AppliedSourceSnapshot source = applied.get(index);
            if (claimed[index]
                    || !source.dimension().equals(desired.dimension())
                    || source.chunkX() != desired.chunkX()
                    || source.chunkZ() != desired.chunkZ()) {
                continue;
            }
            if (source.owner().equals(desired.owner())) {
                owned = index;
                break;
            }
            if (fallback < 0) {
                fallback = index;
            }
        }
        return owned >= 0 ? owned : fallback;
    }

    private static int largestAffordableRadius(int preferred, int maximum, int minimum, long budget) {
        int radius = Math.min(preferred, maximum);
        while (radius >= minimum && ChunkCells.squareArea(radius) > budget) {
            radius--;
        }
        return radius < minimum ? 0 : radius;
    }

    private static Map<String, LongOpenHashSet> coverage(
            Iterable<AppliedSourceSnapshot> applied
    ) {
        Map<String, LongOpenHashSet> covered = new HashMap<>();
        for (AppliedSourceSnapshot source : applied) {
            LongOpenHashSet dimension = covered.computeIfAbsent(
                    source.dimension(), ignored -> new LongOpenHashSet());
            ChunkCells.forEachSquare(
                    source.chunkX(), source.chunkZ(), source.ticketRadius(), dimension::add);
        }
        return covered;
    }

    private static long uncoveredArea(LoadSource desired, Map<String, LongOpenHashSet> covered) {
        LongOpenHashSet dimension = covered.get(desired.dimension());
        if (dimension == null) {
            return desired.area();
        }
        long[] count = {0};
        ChunkCells.forEachSquare(desired.chunkX(), desired.chunkZ(), desired.ticketRadius(), packed -> {
            if (!dimension.contains(packed)) {
                count[0]++;
            }
        });
        return count[0];
    }

    private static AppliedSourceSnapshot withOwner(AppliedSourceSnapshot source, PlayerKey owner) {
        return new AppliedSourceSnapshot(
                owner, source.dimension(), source.chunkX(), source.chunkZ(), source.ticketRadius());
    }

    private static AppliedSourceSnapshot withRadius(AppliedSourceSnapshot source, int radius) {
        return new AppliedSourceSnapshot(
                source.owner(), source.dimension(), source.chunkX(), source.chunkZ(), radius);
    }

    private static final Comparator<AppliedSourceSnapshot> APPLIED_ORDER = Comparator
            .comparing(AppliedSourceSnapshot::dimension)
            .thenComparingInt(AppliedSourceSnapshot::chunkX)
            .thenComparingInt(AppliedSourceSnapshot::chunkZ)
            .thenComparing(AppliedSourceSnapshot::owner);

    public static final class TransitionResult {
        private final List<SourceMutation> mutations;
        private final List<AppliedSourceSnapshot> projectedSources;

        public TransitionResult(List<SourceMutation> mutations,
                                List<AppliedSourceSnapshot> projectedSources) {
            this.mutations = Collections.unmodifiableList(new ArrayList<SourceMutation>(mutations));
            this.projectedSources = Collections.unmodifiableList(
                    new ArrayList<AppliedSourceSnapshot>(projectedSources));
        }

        public List<SourceMutation> mutations() { return mutations; }
        public List<AppliedSourceSnapshot> projectedSources() { return projectedSources; }
    }
}
