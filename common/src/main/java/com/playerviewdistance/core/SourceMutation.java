package com.playerviewdistance.core;

import java.util.Objects;

public final class SourceMutation {
    private final Kind kind;
    private final AppliedSourceSnapshot before;
    private final AppliedSourceSnapshot after;
    private final long predictedChangedCells;

    public SourceMutation(Kind kind, AppliedSourceSnapshot before,
                          AppliedSourceSnapshot after, long predictedChangedCells) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (predictedChangedCells < 0) {
            throw new IllegalArgumentException("predictedChangedCells must be non-negative");
        }
        switch (kind) {
            case REMOVE:
                Objects.requireNonNull(before, "before");
                if (after != null) throw new IllegalArgumentException("REMOVE cannot have an after source");
                break;
            case ADD:
                Objects.requireNonNull(after, "after");
                if (before != null) throw new IllegalArgumentException("ADD cannot have a before source");
                break;
            case REASSIGN:
            case RESIZE:
                Objects.requireNonNull(before, "before");
                Objects.requireNonNull(after, "after");
                break;
            default:
                throw new IllegalStateException("Unhandled mutation kind " + kind);
        }
        this.before = before;
        this.after = after;
        this.predictedChangedCells = predictedChangedCells;
    }

    public Kind kind() { return kind; }
    public AppliedSourceSnapshot before() { return before; }
    public AppliedSourceSnapshot after() { return after; }
    public long predictedChangedCells() { return predictedChangedCells; }

    public enum Kind {
        REMOVE,
        REASSIGN,
        ADD,
        RESIZE
    }
}
