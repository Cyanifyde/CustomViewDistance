package com.playerviewdistance.compat;

/** Volatile, connection-local ceiling used by the packet-layer safety net. */
public interface ConnectionDistanceCeiling {
    int playerviewdistance$getViewDistanceCeiling();

    void playerviewdistance$setViewDistanceCeiling(int distance);
}
