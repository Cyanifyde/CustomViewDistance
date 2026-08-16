package com.playerviewdistance.core;

public final class SourceAssignment {
    private final LoadSource desired;
    private final AppliedSourceSnapshot matched;
    private final int initialTicketRadius;
    private final boolean replacement;

    public SourceAssignment(LoadSource desired, AppliedSourceSnapshot matched,
                            int initialTicketRadius, boolean replacement) {
        this.desired = java.util.Objects.requireNonNull(desired, "desired");
        this.matched = matched;
        this.initialTicketRadius = initialTicketRadius;
        this.replacement = replacement;
    }

    public LoadSource desired() { return desired; }
    public AppliedSourceSnapshot matched() { return matched; }
    public int initialTicketRadius() { return initialTicketRadius; }
    public boolean replacement() { return replacement; }
}
