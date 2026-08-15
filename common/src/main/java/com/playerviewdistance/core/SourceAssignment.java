package com.playerviewdistance.core;

public record SourceAssignment(
        LoadSource desired,
        AppliedSourceSnapshot matched,
        int initialTicketRadius,
        boolean replacement
) {
}
