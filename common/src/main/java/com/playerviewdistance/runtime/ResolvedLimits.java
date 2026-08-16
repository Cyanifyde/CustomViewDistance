package com.playerviewdistance.runtime;

public final class ResolvedLimits {
    private final Integer loadingMaximum;
    private final Integer sendingMaximum;
    private final long generation;

    public ResolvedLimits(Integer loadingMaximum, Integer sendingMaximum, long generation) {
        this.loadingMaximum = loadingMaximum;
        this.sendingMaximum = sendingMaximum;
        this.generation = generation;
    }

    public Integer loadingMaximum() { return loadingMaximum; }
    public Integer sendingMaximum() { return sendingMaximum; }
    public long generation() { return generation; }
}
