package com.playerviewdistance.core;

import java.util.UUID;

public final class PlayerKey implements Comparable<PlayerKey> {
    private final long mostSignificantBits;
    private final long leastSignificantBits;

    public PlayerKey(long mostSignificantBits, long leastSignificantBits) {
        this.mostSignificantBits = mostSignificantBits;
        this.leastSignificantBits = leastSignificantBits;
    }

    public long mostSignificantBits() {
        return mostSignificantBits;
    }

    public long leastSignificantBits() {
        return leastSignificantBits;
    }

    public static PlayerKey of(UUID uuid) {
        return new PlayerKey(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    public UUID toUuid() {
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    @Override
    public int compareTo(PlayerKey other) {
        int most = Long.compareUnsigned(mostSignificantBits, other.mostSignificantBits);
        return most != 0 ? most : Long.compareUnsigned(leastSignificantBits, other.leastSignificantBits);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PlayerKey)) return false;
        PlayerKey that = (PlayerKey) other;
        return mostSignificantBits == that.mostSignificantBits
                && leastSignificantBits == that.leastSignificantBits;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(mostSignificantBits);
        return 31 * result + Long.hashCode(leastSignificantBits);
    }

    @Override
    public String toString() {
        return toUuid().toString();
    }
}
