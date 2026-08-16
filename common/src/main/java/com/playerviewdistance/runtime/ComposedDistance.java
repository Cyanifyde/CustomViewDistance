package com.playerviewdistance.runtime;

public final class ComposedDistance {
    private final int loadingDistance;
    private final int sendingDistance;

    public ComposedDistance(int loadingDistance, int sendingDistance) {
        this.loadingDistance = loadingDistance;
        this.sendingDistance = sendingDistance;
    }

    public int loadingDistance() { return loadingDistance; }
    public int sendingDistance() { return sendingDistance; }
}
