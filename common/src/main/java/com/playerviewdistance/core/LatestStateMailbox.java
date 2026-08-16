package com.playerviewdistance.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
                return new Snapshot(after, copy);
            }
        }
    }

    public static final class Snapshot {
        private final long generation;
        private final List<PlayerSnapshot> players;

        public Snapshot(long generation, List<PlayerSnapshot> players) {
            this.generation = generation;
            this.players = Collections.unmodifiableList(new ArrayList<PlayerSnapshot>(players));
        }

        public long generation() { return generation; }
        public List<PlayerSnapshot> players() { return players; }
    }
}
