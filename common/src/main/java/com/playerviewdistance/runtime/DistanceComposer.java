package com.playerviewdistance.runtime;

public final class DistanceComposer {
    private DistanceComposer() {
    }

    public static ComposedDistance compose(int pvdCeiling,
                                           Integer externalLoadingLimit,
                                           Integer externalSendingLimit) {
        int ceiling = clamp(pvdCeiling);
        int loading = Math.min(ceiling, limitOrCeiling(externalLoadingLimit, ceiling));
        int sending = Math.min(loading, limitOrCeiling(externalSendingLimit, ceiling));
        return new ComposedDistance(loading, sending);
    }

    private static int limitOrCeiling(Integer value, int ceiling) {
        return value == null ? ceiling : clamp(value.intValue());
    }

    private static int clamp(int value) {
        return Math.max(2, Math.min(32, value));
    }
}
