package com.playerviewdistance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pre-computed circular chunk offset templates and cardinal move deltas.
 * All arrays are computed once at startup and immutable thereafter.
 */
public final class CircleTemplate {

    public static final int MIN_RD = 2;
    public static final int MAX_RD = 32;

    // circleOffsets[rd] = flat array [dx0, dz0, dx1, dz1, ...] sorted inside-out
    private static final int[][] circleOffsets = new int[MAX_RD + 1][];

    // chunkCounts[rd] = number of chunks in a circle of that radius
    private static final int[] chunkCounts = new int[MAX_RD + 1];

    // Cardinal direction indices: 0=+X, 1=-X, 2=+Z, 3=-Z
    public static final int DIR_POS_X = 0;
    public static final int DIR_NEG_X = 1;
    public static final int DIR_POS_Z = 2;
    public static final int DIR_NEG_Z = 3;

    // enteringOffsets[dir][rd] = flat [dx0, dz0, ...] for chunks entering on a 1-chunk move
    private static final int[][][] enteringOffsets = new int[4][MAX_RD + 1][];
    // leavingOffsets[dir][rd] = flat [dx0, dz0, ...] for chunks leaving on a 1-chunk move
    private static final int[][][] leavingOffsets = new int[4][MAX_RD + 1][];

    private CircleTemplate() {}

    public static void init() {
        for (int r = MIN_RD; r <= MAX_RD; r++) {
            computeCircle(r);
            computeMoveDeltas(r);
        }
    }

    private static void computeCircle(int r) {
        int r2 = r * r;
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r2) {
                    offsets.add(new int[]{dx, dz});
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        int[] flat = new int[offsets.size() * 2];
        for (int i = 0; i < offsets.size(); i++) {
            flat[i * 2] = offsets.get(i)[0];
            flat[i * 2 + 1] = offsets.get(i)[1];
        }
        circleOffsets[r] = flat;
        chunkCounts[r] = offsets.size();
    }

    private static void computeMoveDeltas(int r) {
        int r2 = r * r;

        // +X move: old center (0,0), new center (1,0)
        List<int[]> enterPosX = new ArrayList<>();
        List<int[]> leavePosX = new ArrayList<>();
        for (int dx = -r; dx <= r + 1; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean inOld = dx * dx + dz * dz <= r2;
                boolean inNew = (dx - 1) * (dx - 1) + dz * dz <= r2;
                if (inNew && !inOld) enterPosX.add(new int[]{dx, dz});
                if (inOld && !inNew) leavePosX.add(new int[]{dx, dz});
            }
        }

        // Sort entering inside-out relative to new center (1,0)
        enterPosX.sort(Comparator.comparingInt(o -> (o[0] - 1) * (o[0] - 1) + o[1] * o[1]));

        enteringOffsets[DIR_POS_X][r] = flatten(enterPosX);
        leavingOffsets[DIR_POS_X][r] = flatten(leavePosX);

        // -X: mirror of +X
        enteringOffsets[DIR_NEG_X][r] = mirror(enteringOffsets[DIR_POS_X][r], true, false);
        leavingOffsets[DIR_NEG_X][r] = mirror(leavingOffsets[DIR_POS_X][r], true, false);

        // +Z move: old center (0,0), new center (0,1)
        List<int[]> enterPosZ = new ArrayList<>();
        List<int[]> leavePosZ = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r + 1; dz++) {
                boolean inOld = dx * dx + dz * dz <= r2;
                boolean inNew = dx * dx + (dz - 1) * (dz - 1) <= r2;
                if (inNew && !inOld) enterPosZ.add(new int[]{dx, dz});
                if (inOld && !inNew) leavePosZ.add(new int[]{dx, dz});
            }
        }

        enterPosZ.sort(Comparator.comparingInt(o -> o[0] * o[0] + (o[1] - 1) * (o[1] - 1)));

        enteringOffsets[DIR_POS_Z][r] = flatten(enterPosZ);
        leavingOffsets[DIR_POS_Z][r] = flatten(leavePosZ);

        // -Z: mirror of +Z
        enteringOffsets[DIR_NEG_Z][r] = mirror(enteringOffsets[DIR_POS_Z][r], false, true);
        leavingOffsets[DIR_NEG_Z][r] = mirror(leavingOffsets[DIR_POS_Z][r], false, true);
    }

    private static int[] flatten(List<int[]> list) {
        int[] flat = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            flat[i * 2] = list.get(i)[0];
            flat[i * 2 + 1] = list.get(i)[1];
        }
        return flat;
    }

    private static int[] mirror(int[] offsets, boolean flipX, boolean flipZ) {
        int[] result = new int[offsets.length];
        for (int i = 0; i < offsets.length; i += 2) {
            result[i] = flipX ? -offsets[i] : offsets[i];
            result[i + 1] = flipZ ? -offsets[i + 1] : offsets[i + 1];
        }
        return result;
    }

    /** Get pre-computed circle offsets for the given render distance, sorted inside-out. */
    public static int[] getCircle(int rd) {
        rd = clampRd(rd);
        return circleOffsets[rd];
    }

    /** Get the number of chunks in a circle of the given radius. */
    public static int getChunkCount(int rd) {
        rd = clampRd(rd);
        return chunkCounts[rd];
    }

    /** Get entering offsets for a 1-chunk cardinal move. Offsets are relative to the OLD center. */
    public static int[] getEntering(int direction, int rd) {
        rd = clampRd(rd);
        return enteringOffsets[direction][rd];
    }

    /** Get leaving offsets for a 1-chunk cardinal move. Offsets are relative to the OLD center. */
    public static int[] getLeaving(int direction, int rd) {
        rd = clampRd(rd);
        return leavingOffsets[direction][rd];
    }

    /**
     * Determine the cardinal direction for a 1-chunk move, or -1 if not cardinal.
     */
    public static int cardinalDirection(int dxChunk, int dzChunk) {
        if (dxChunk == 1 && dzChunk == 0) return DIR_POS_X;
        if (dxChunk == -1 && dzChunk == 0) return DIR_NEG_X;
        if (dxChunk == 0 && dzChunk == 1) return DIR_POS_Z;
        if (dxChunk == 0 && dzChunk == -1) return DIR_NEG_Z;
        return -1;
    }

    private static int clampRd(int rd) {
        return Math.max(MIN_RD, Math.min(MAX_RD, rd));
    }
}
