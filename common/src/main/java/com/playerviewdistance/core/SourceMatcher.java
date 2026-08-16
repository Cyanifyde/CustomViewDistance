package com.playerviewdistance.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public final class SourceMatcher {
    public List<SourceAssignment> match(
            List<LoadSource> desired,
            List<AppliedSourceSnapshot> applied,
            List<PlayerSnapshot> players
    ) {
        boolean[] used = new boolean[applied.size()];
        Map<PlayerKey, PlayerSnapshot> byPlayer = new HashMap<>();
        for (PlayerSnapshot player : players) {
            byPlayer.put(player.player(), player);
        }

        int[] matches = new int[desired.size()];
        Arrays.fill(matches, -1);
        for (int index = 0; index < desired.size(); index++) {
            int match = findExact(desired.get(index), applied, used);
            if (match >= 0) {
                matches[index] = match;
                used[match] = true;
            }
        }
        for (int index = 0; index < desired.size(); index++) {
            if (matches[index] >= 0) {
                continue;
            }
            int match = findOwner(desired.get(index), applied, used);
            if (match >= 0) {
                matches[index] = match;
                used[match] = true;
            }
        }
        for (int index = 0; index < desired.size(); index++) {
            if (matches[index] >= 0) {
                continue;
            }
            int match = findOverlapping(desired.get(index), applied, used);
            if (match >= 0) {
                matches[index] = match;
                used[match] = true;
            }
        }

        List<SourceAssignment> assignments = new ArrayList<>(desired.size());
        for (int index = 0; index < desired.size(); index++) {
            LoadSource source = desired.get(index);
            int match = matches[index];
            AppliedSourceSnapshot existing = match < 0 ? null : applied.get(match);
            PlayerSnapshot owner = byPlayer.get(source.owner());
            int carried = owner == null ? 2 : Math.max(2, owner.achievedViewDistance());
            int initial = existing == null
                    ? Math.min(source.ticketRadius(), carried + LoadSource.LOADING_MARGIN)
                    : Math.min(source.ticketRadius(), existing.ticketRadius());
            boolean replacement = existing != null && (!existing.dimension().equals(source.dimension())
                    || existing.chunkX() != source.chunkX()
                    || existing.chunkZ() != source.chunkZ());
            assignments.add(new SourceAssignment(source, existing, initial, replacement));
        }
        return Collections.unmodifiableList(new ArrayList<SourceAssignment>(assignments));
    }

    private static int findExact(LoadSource desired, List<AppliedSourceSnapshot> applied, boolean[] used) {
        int best = -1;
        int bestRadiusDelta = Integer.MAX_VALUE;
        for (int i = 0; i < applied.size(); i++) {
            AppliedSourceSnapshot source = applied.get(i);
            if (!used[i]
                    && source.dimension().equals(desired.dimension())
                    && source.chunkX() == desired.chunkX()
                    && source.chunkZ() == desired.chunkZ()) {
                int delta = Math.abs(source.ticketRadius() - desired.ticketRadius());
                if (delta < bestRadiusDelta) {
                    best = i;
                    bestRadiusDelta = delta;
                }
            }
        }
        return best;
    }

    private static int findOwner(LoadSource desired, List<AppliedSourceSnapshot> applied, boolean[] used) {
        for (int i = 0; i < applied.size(); i++) {
            if (!used[i] && applied.get(i).owner().equals(desired.owner())) {
                return i;
            }
        }
        return -1;
    }

    private static int findOverlapping(LoadSource desired, List<AppliedSourceSnapshot> applied, boolean[] used) {
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < applied.size(); i++) {
            AppliedSourceSnapshot source = applied.get(i);
            if (used[i] || !source.dimension().equals(desired.dimension())) {
                continue;
            }
            long dx = Math.abs((long) source.chunkX() - desired.chunkX());
            long dz = Math.abs((long) source.chunkZ() - desired.chunkZ());
            if (dx <= source.ticketRadius() + desired.ticketRadius()
                    && dz <= source.ticketRadius() + desired.ticketRadius()) {
                long distance = dx + dz;
                if (distance < bestDistance) {
                    best = i;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }
}
