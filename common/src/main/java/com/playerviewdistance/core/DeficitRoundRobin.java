package com.playerviewdistance.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

public final class DeficitRoundRobin {
    private static final long MIN_QUANTUM = 64;
    private static final long MAX_QUANTUM = 8_192;
    private static final long MAX_ACCUMULATED_DEFICIT = MAX_QUANTUM * 2L;

    private final Map<PlayerKey, Long> deficits = new HashMap<>();
    private int cursor;

    public List<GrowthGrant> allocate(List<GrowthCandidate> candidates, long graphCellBudget) {
        if (candidates.isEmpty() || graphCellBudget <= 0) {
            retainOnly(candidates);
            return Collections.emptyList();
        }

        retainOnly(candidates);
        int count = candidates.size();
        for (GrowthCandidate candidate : candidates) {
            long quantum = Math.max(MIN_QUANTUM, Math.min(MAX_QUANTUM, candidate.remainingUnionArea()));
            deficits.compute(candidate.owner(), (ignored, previous) -> Math.min(
                    MAX_ACCUMULATED_DEFICIT,
                    saturatingAdd(previous == null ? 0L : previous, quantum)));
        }

        int[] radii = new int[count];
        for (int i = 0; i < count; i++) {
            radii[i] = candidates.get(i).currentTicketRadius();
        }

        long remainingBudget = graphCellBudget;
        int unsuccessfulVisits = 0;
        while (remainingBudget > 0 && unsuccessfulVisits < count) {
            int index = Math.floorMod(cursor++, count);
            GrowthCandidate candidate = candidates.get(index);
            if (radii[index] >= candidate.targetTicketRadius()) {
                unsuccessfulVisits++;
                continue;
            }
            int nextRadius = radii[index] + 1;
            long cost = ChunkCells.changedCells(radii[index], nextRadius);
            long deficit = deficits.getOrDefault(candidate.owner(), 0L);
            if (cost > deficit || cost > remainingBudget) {
                unsuccessfulVisits++;
                continue;
            }
            deficits.put(candidate.owner(), deficit - cost);
            remainingBudget -= cost;
            radii[index] = nextRadius;
            unsuccessfulVisits = 0;
        }

        List<GrowthGrant> grants = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            GrowthCandidate candidate = candidates.get(i);
            if (radii[i] != candidate.currentTicketRadius()) {
                grants.add(new GrowthGrant(candidate.owner(), radii[i],
                        ChunkCells.changedCells(candidate.currentTicketRadius(), radii[i])));
            }
        }
        return Collections.unmodifiableList(new ArrayList<GrowthGrant>(grants));
    }

    private void retainOnly(List<GrowthCandidate> candidates) {
        Set<PlayerKey> live = new HashSet<>();
        for (GrowthCandidate candidate : candidates) {
            live.add(candidate.owner());
        }
        deficits.keySet().retainAll(live);
        if (cursor == Integer.MAX_VALUE) {
            cursor = 0;
        }
    }

    private static long saturatingAdd(long first, long second) {
        if (Long.MAX_VALUE - first < second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    public static final class GrowthCandidate {
        private final PlayerKey owner;
        private final int currentTicketRadius;
        private final int targetTicketRadius;
        private final long remainingUnionArea;

        public GrowthCandidate(PlayerKey owner, int currentTicketRadius,
                               int targetTicketRadius, long remainingUnionArea) {
            this.owner = java.util.Objects.requireNonNull(owner, "owner");
            if (currentTicketRadius < 0 || targetTicketRadius < currentTicketRadius) {
                throw new IllegalArgumentException("invalid growth radii");
            }
            this.currentTicketRadius = currentTicketRadius;
            this.targetTicketRadius = targetTicketRadius;
            this.remainingUnionArea = remainingUnionArea;
        }

        public PlayerKey owner() { return owner; }
        public int currentTicketRadius() { return currentTicketRadius; }
        public int targetTicketRadius() { return targetTicketRadius; }
        public long remainingUnionArea() { return remainingUnionArea; }
    }

    public static final class GrowthGrant {
        private final PlayerKey owner;
        private final int targetTicketRadius;
        private final long predictedChangedCells;

        public GrowthGrant(PlayerKey owner, int targetTicketRadius, long predictedChangedCells) {
            this.owner = java.util.Objects.requireNonNull(owner, "owner");
            this.targetTicketRadius = targetTicketRadius;
            this.predictedChangedCells = predictedChangedCells;
        }

        public PlayerKey owner() { return owner; }
        public int targetTicketRadius() { return targetTicketRadius; }
        public long predictedChangedCells() { return predictedChangedCells; }
    }
}
