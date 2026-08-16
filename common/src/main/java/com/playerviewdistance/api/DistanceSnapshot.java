package com.playerviewdistance.api;

import java.util.Objects;
import java.util.UUID;

public final class DistanceSnapshot {
    private final UUID playerId;
    private final int requestedCeiling;
    private final Integer externalLoadingLimit;
    private final Integer externalSendingLimit;
    private final int desiredLoadingDistance;
    private final int desiredSendingDistance;
    private final int appliedLoadingDistance;
    private final int appliedSendingDistance;
    private final DistanceBackend backend;
    private final long generation;
    private final String governorState;

    public DistanceSnapshot(UUID playerId, int requestedCeiling,
                            Integer externalLoadingLimit, Integer externalSendingLimit,
                            int desiredLoadingDistance, int desiredSendingDistance,
                            int appliedLoadingDistance, int appliedSendingDistance,
                            DistanceBackend backend, long generation, String governorState) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.requestedCeiling = requireDistance(requestedCeiling, "requestedCeiling");
        this.externalLoadingLimit = optionalDistance(externalLoadingLimit, "externalLoadingLimit");
        this.externalSendingLimit = optionalDistance(externalSendingLimit, "externalSendingLimit");
        this.desiredLoadingDistance = requireDistance(desiredLoadingDistance, "desiredLoadingDistance");
        this.desiredSendingDistance = requireDistance(desiredSendingDistance, "desiredSendingDistance");
        this.appliedLoadingDistance = requireDistance(appliedLoadingDistance, "appliedLoadingDistance");
        this.appliedSendingDistance = requireDistance(appliedSendingDistance, "appliedSendingDistance");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.generation = generation;
        this.governorState = Objects.requireNonNull(governorState, "governorState");
    }

    private static int requireDistance(int value, String name) {
        if (value < 2 || value > 32) {
            throw new IllegalArgumentException(name + " must be in [2, 32]");
        }
        return value;
    }

    private static Integer optionalDistance(Integer value, String name) {
        if (value != null) requireDistance(value.intValue(), name);
        return value;
    }

    public UUID playerId() { return playerId; }
    public int requestedCeiling() { return requestedCeiling; }
    public Integer externalLoadingLimit() { return externalLoadingLimit; }
    public Integer externalSendingLimit() { return externalSendingLimit; }
    public int desiredLoadingDistance() { return desiredLoadingDistance; }
    public int desiredSendingDistance() { return desiredSendingDistance; }
    public int appliedLoadingDistance() { return appliedLoadingDistance; }
    public int appliedSendingDistance() { return appliedSendingDistance; }
    public DistanceBackend backend() { return backend; }
    public long generation() { return generation; }
    public String governorState() { return governorState; }
}
