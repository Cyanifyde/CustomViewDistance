package com.playerviewdistance.core;

import it.unimi.dsi.fastutil.longs.LongConsumer;

public final class ChunkCells {
    private ChunkCells() {
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }

    public static void forEachSquare(int centerX, int centerZ, int radius, LongConsumer consumer) {
        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                consumer.accept(pack(x, z));
            }
        }
    }

    public static long squareArea(int radius) {
        if (radius <= 0) {
            return 0;
        }
        long diameter = 2L * radius + 1L;
        return diameter * diameter;
    }

    public static long changedCells(int oldRadius, int newRadius) {
        return Math.abs(squareArea(newRadius) - squareArea(oldRadius));
    }
}
