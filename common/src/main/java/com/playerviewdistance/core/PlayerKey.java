package com.playerviewdistance.core;

import java.util.UUID;

public record PlayerKey(long mostSignificantBits, long leastSignificantBits) implements Comparable<PlayerKey> {
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
}
