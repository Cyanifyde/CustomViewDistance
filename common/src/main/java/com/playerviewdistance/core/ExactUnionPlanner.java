package com.playerviewdistance.core;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;

public final class ExactUnionPlanner {
    private static final int INDEX_BUCKET_SIZE = 32;

    public UnionResult plan(Collection<PlayerSnapshot> players) {
        long started = System.nanoTime();
        List<LoadSource> grouped = groupIdenticalCenters(players);
        if (grouped.isEmpty()) {
            return new UnionResult(Collections.<LoadSource>emptyList(), 0, System.nanoTime() - started);
        }

        grouped.sort(LoadSource.ORDER);
        UnionFind components = partition(grouped);
        Map<Integer, IntArrayList> members = new HashMap<>();
        for (int i = 0; i < grouped.size(); i++) {
            members.computeIfAbsent(components.find(i), ignored -> new IntArrayList()).add(i);
        }

        boolean[] retained = new boolean[grouped.size()];
        long exactArea = 0;
        for (IntArrayList component : members.values()) {
            Long2IntOpenHashMap refCounts = new Long2IntOpenHashMap();
            refCounts.defaultReturnValue(0);
            for (int index : component) {
                addFootprint(refCounts, grouped.get(index));
                retained[index] = true;
            }

            for (int member = component.size() - 1; member >= 0; member--) {
                int index = component.getInt(member);
                LoadSource candidate = grouped.get(index);
                if (isCompletelyShared(refCounts, candidate)) {
                    removeFootprint(refCounts, candidate);
                    retained[index] = false;
                }
            }
            exactArea += refCounts.size();
        }

        List<LoadSource> sources = new ArrayList<>();
        for (int i = 0; i < grouped.size(); i++) {
            if (retained[i]) {
                sources.add(grouped.get(i));
            }
        }
        sources.sort(LoadSource.ORDER);
        return new UnionResult(sources, exactArea, System.nanoTime() - started);
    }

    public long exactUnionArea(Collection<LoadSource> sources) {
        Map<String, Long2IntOpenHashMap> dimensions = new HashMap<>();
        for (LoadSource source : sources) {
            Long2IntOpenHashMap cells = dimensions.computeIfAbsent(source.dimension(), ignored -> new Long2IntOpenHashMap());
            ChunkCells.forEachSquare(source.chunkX(), source.chunkZ(), source.ticketRadius(), packed -> cells.put(packed, 1));
        }
        return dimensions.values().stream().mapToLong(Long2IntOpenHashMap::size).sum();
    }

    public long exactAppliedUnionArea(Collection<AppliedSourceSnapshot> sources) {
        Map<String, Long2IntOpenHashMap> dimensions = new HashMap<>();
        for (AppliedSourceSnapshot source : sources) {
            if (source.ticketRadius() == 0) {
                continue;
            }
            Long2IntOpenHashMap cells = dimensions.computeIfAbsent(source.dimension(), ignored -> new Long2IntOpenHashMap());
            ChunkCells.forEachSquare(source.chunkX(), source.chunkZ(), source.ticketRadius(), packed -> cells.put(packed, 1));
        }
        return dimensions.values().stream().mapToLong(Long2IntOpenHashMap::size).sum();
    }

    public long exactChangedCells(Collection<LoadSource> desired, Collection<AppliedSourceSnapshot> applied) {
        Map<String, Long2IntOpenHashMap> dimensions = new HashMap<>();
        for (LoadSource source : desired) {
            Long2IntOpenHashMap flags = dimensions.computeIfAbsent(source.dimension(), ignored -> new Long2IntOpenHashMap());
            ChunkCells.forEachSquare(source.chunkX(), source.chunkZ(), source.ticketRadius(),
                    packed -> flags.put(packed, flags.get(packed) | 1));
        }
        for (AppliedSourceSnapshot source : applied) {
            if (source.ticketRadius() == 0) {
                continue;
            }
            Long2IntOpenHashMap flags = dimensions.computeIfAbsent(source.dimension(), ignored -> new Long2IntOpenHashMap());
            ChunkCells.forEachSquare(source.chunkX(), source.chunkZ(), source.ticketRadius(),
                    packed -> flags.put(packed, flags.get(packed) | 2));
        }
        long changed = 0;
        for (Long2IntOpenHashMap flags : dimensions.values()) {
            for (int value : flags.values()) {
                if (value == 1 || value == 2) {
                    changed++;
                }
            }
        }
        return changed;
    }

    public List<PlayerCoverage> achievedCoverage(
            Collection<PlayerSnapshot> players,
            Collection<AppliedSourceSnapshot> applied
    ) {
        Map<String, LongOpenHashSet> dimensions = new HashMap<>();
        for (AppliedSourceSnapshot source : applied) {
            if (source.ticketRadius() == 0) {
                continue;
            }
            LongOpenHashSet cells = dimensions.computeIfAbsent(
                    source.dimension(), ignored -> new LongOpenHashSet());
            ChunkCells.forEachSquare(
                    source.chunkX(), source.chunkZ(), source.ticketRadius(), cells::add);
        }

        List<PlayerCoverage> coverage = new ArrayList<>(players.size());
        for (PlayerSnapshot player : players) {
            int floor = Math.min(player.effectiveViewDistance(), player.simulationDistance());
            int low = floor;
            int high = player.effectiveViewDistance();
            LongOpenHashSet cells = dimensions.get(player.dimension());
            while (low < high) {
                int candidate = (low + high + 1) >>> 1;
                if (cells != null && squareCovered(cells, player, candidate + LoadSource.LOADING_MARGIN)) {
                    low = candidate;
                } else {
                    high = candidate - 1;
                }
            }
            coverage.add(new PlayerCoverage(player.player(), low));
        }
        coverage.sort(Comparator.comparing(PlayerCoverage::player));
        return Collections.unmodifiableList(new ArrayList<PlayerCoverage>(coverage));
    }

    private static boolean squareCovered(LongOpenHashSet cells, PlayerSnapshot player, int radius) {
        for (int x = player.chunkX() - radius; x <= player.chunkX() + radius; x++) {
            for (int z = player.chunkZ() - radius; z <= player.chunkZ() + radius; z++) {
                if (!cells.contains(ChunkCells.pack(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<LoadSource> groupIdenticalCenters(Collection<PlayerSnapshot> players) {
        Map<Center, LoadSource> grouped = new HashMap<>();
        for (PlayerSnapshot player : players) {
            if (player.effectiveViewDistance() <= player.simulationDistance()) {
                continue;
            }
            LoadSource candidate = LoadSource.desired(player);
            Center center = new Center(candidate.dimension(), candidate.chunkX(), candidate.chunkZ());
            grouped.merge(center, candidate, ExactUnionPlanner::preferSource);
        }
        return new ArrayList<>(grouped.values());
    }

    private static LoadSource preferSource(LoadSource first, LoadSource second) {
        if (first.ticketRadius() != second.ticketRadius()) {
            return first.ticketRadius() > second.ticketRadius() ? first : second;
        }
        return first.owner().compareTo(second.owner()) <= 0 ? first : second;
    }

    private static UnionFind partition(List<LoadSource> sources) {
        UnionFind unionFind = new UnionFind(sources.size());
        Map<String, Long2ObjectOpenHashMap<IntArrayList>> byDimension = new HashMap<>();
        for (int index = 0; index < sources.size(); index++) {
            LoadSource source = sources.get(index);
            Long2ObjectOpenHashMap<IntArrayList> buckets = byDimension.computeIfAbsent(
                    source.dimension(), ignored -> new Long2ObjectOpenHashMap<>());
            int minBucketX = Math.floorDiv(source.chunkX() - source.ticketRadius(), INDEX_BUCKET_SIZE);
            int maxBucketX = Math.floorDiv(source.chunkX() + source.ticketRadius(), INDEX_BUCKET_SIZE);
            int minBucketZ = Math.floorDiv(source.chunkZ() - source.ticketRadius(), INDEX_BUCKET_SIZE);
            int maxBucketZ = Math.floorDiv(source.chunkZ() + source.ticketRadius(), INDEX_BUCKET_SIZE);
            IntOpenHashSet candidates = new IntOpenHashSet();
            for (int bucketX = minBucketX; bucketX <= maxBucketX; bucketX++) {
                for (int bucketZ = minBucketZ; bucketZ <= maxBucketZ; bucketZ++) {
                    long key = ChunkCells.pack(bucketX, bucketZ);
                    IntArrayList occupants = buckets.get(key);
                    if (occupants != null) {
                        candidates.addAll(occupants);
                    }
                }
            }
            for (int candidate : candidates) {
                if (source.intersects(sources.get(candidate))) {
                    unionFind.union(index, candidate);
                }
            }
            for (int bucketX = minBucketX; bucketX <= maxBucketX; bucketX++) {
                for (int bucketZ = minBucketZ; bucketZ <= maxBucketZ; bucketZ++) {
                    buckets.computeIfAbsent(ChunkCells.pack(bucketX, bucketZ), ignored -> new IntArrayList()).add(index);
                }
            }
        }
        return unionFind;
    }

    private static void addFootprint(Long2IntOpenHashMap refs, LoadSource source) {
        ChunkCells.forEachSquare(source.chunkX(), source.chunkZ(), source.ticketRadius(),
                packed -> refs.put(packed, refs.get(packed) + 1));
    }

    private static void removeFootprint(Long2IntOpenHashMap refs, LoadSource source) {
        ChunkCells.forEachSquare(source.chunkX(), source.chunkZ(), source.ticketRadius(), packed -> {
            int next = refs.get(packed) - 1;
            if (next == 0) {
                refs.remove(packed);
            } else {
                refs.put(packed, next);
            }
        });
    }

    private static boolean isCompletelyShared(Long2IntOpenHashMap refs, LoadSource source) {
        for (int x = source.chunkX() - source.ticketRadius(); x <= source.chunkX() + source.ticketRadius(); x++) {
            for (int z = source.chunkZ() - source.ticketRadius(); z <= source.chunkZ() + source.ticketRadius(); z++) {
                if (refs.get(ChunkCells.pack(x, z)) <= 1) {
                    return false;
                }
            }
        }
        return true;
    }

    public static final class UnionResult {
        private final List<LoadSource> sources;
        private final long exactUnionArea;
        private final long plannerNanos;

        public UnionResult(List<LoadSource> sources, long exactUnionArea, long plannerNanos) {
            this.sources = Collections.unmodifiableList(new ArrayList<LoadSource>(sources));
            this.exactUnionArea = exactUnionArea;
            this.plannerNanos = plannerNanos;
        }

        public List<LoadSource> sources() { return sources; }
        public long exactUnionArea() { return exactUnionArea; }
        public long plannerNanos() { return plannerNanos; }
    }

    private static final class Center {
        private final String dimension;
        private final int chunkX;
        private final int chunkZ;

        private Center(String dimension, int chunkX, int chunkZ) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Center)) return false;
            Center that = (Center) other;
            return chunkX == that.chunkX && chunkZ == that.chunkZ
                    && dimension.equals(that.dimension);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimension, chunkX, chunkZ);
        }
    }

    private static final class UnionFind {
        private final int[] parent;
        private final byte[] rank;

        private UnionFind(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        private int find(int value) {
            int root = value;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[value] != value) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }
            return root;
        }

        private void union(int first, int second) {
            int a = find(first);
            int b = find(second);
            if (a == b) {
                return;
            }
            if (rank[a] < rank[b]) {
                parent[a] = b;
            } else if (rank[a] > rank[b]) {
                parent[b] = a;
            } else {
                parent[b] = a;
                rank[a]++;
            }
        }
    }
}
