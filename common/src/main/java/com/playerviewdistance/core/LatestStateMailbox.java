package com.playerviewdistance.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded by the number of live players: every UUID has exactly one replaceable state. */
public final class LatestStateMailbox {
    private final ConcurrentHashMap<PlayerKey, PlayerSnapshot> players = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public long upsert(PlayerSnapshot snapshot) {
        PlayerSnapshot previous = players.put(snapshot.player(), snapshot);
        if (snapshot.equals(previous)) {
            return generation.get();
        }
        return generation.incrementAndGet();
    }

    public long remove(PlayerKey player) {
        if (players.remove(player) != null) {
            return generation.incrementAndGet();
        }
        return generation.get();
    }

    public long clear() {
        if (!players.isEmpty()) {
            players.clear();
            return generation.incrementAndGet();
        }
        return generation.get();
    }

    public long generation() {
        return generation.get();
    }

    public int size() {
        return players.size();
    }

    public Snapshot snapshot() {
        while (true) {
            long before = generation.get();
            List<PlayerSnapshot> copy = new ArrayList<>(players.values());
            long after = generation.get();
            if (before == after) {
                copy.sort((left, right) -> left.player().compareTo(right.player()));
                return new Snapshot(after, List.copyOf(copy));
            }
        }
    }

    public record Snapshot(long generation, List<PlayerSnapshot> players) {
    }
}
