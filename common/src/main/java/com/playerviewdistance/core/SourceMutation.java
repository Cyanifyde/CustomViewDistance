package com.playerviewdistance.core;

import java.util.Objects;

/** Immutable, generation-scoped ticket-source mutation prepared by the planner thread. */
public record SourceMutation(
        Kind kind,
        AppliedSourceSnapshot before,
        AppliedSourceSnapshot after,
        long predictedChangedCells
) {
    public SourceMutation {
        Objects.requireNonNull(kind, "kind");
        if (predictedChangedCells < 0) {
            throw new IllegalArgumentException("predictedChangedCells must be non-negative");
        }
        switch (kind) {
            case REMOVE -> {
                Objects.requireNonNull(before, "before");
                if (after != null) throw new IllegalArgumentException("REMOVE cannot have an after source");
            }
            case ADD -> {
                Objects.requireNonNull(after, "after");
                if (before != null) throw new IllegalArgumentException("ADD cannot have a before source");
            }
            case REASSIGN, RESIZE -> {
                Objects.requireNonNull(before, "before");
                Objects.requireNonNull(after, "after");
            }
        }
    }

    public enum Kind {
        REMOVE,
        REASSIGN,
        ADD,
        RESIZE
    }
}
