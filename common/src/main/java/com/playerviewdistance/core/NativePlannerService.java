package com.playerviewdistance.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class NativePlannerService implements AutoCloseable {
    private final LatestStateMailbox mailbox = new LatestStateMailbox();
    private final ExactUnionPlanner unionPlanner = new ExactUnionPlanner();
    private final DeficitRoundRobin growthScheduler = new DeficitRoundRobin();
    private final AdaptiveGovernor governor = new AdaptiveGovernor();
    private final ExecutorService executor;
    private final AtomicLong requestGeneration = new AtomicLong();
    private final AtomicReference<Request> latestRequest = new AtomicReference<Request>();
    private final AtomicReference<NativePlanResult> latestResult = new AtomicReference<NativePlanResult>();
    private final AtomicReference<Runnable> pendingPersistence = new AtomicReference<Runnable>();
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<Throwable>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Object executorLifecycle = new Object();
    private volatile boolean closed;

    public NativePlannerService() {
        executor = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "PVD-Native-Planner");
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread ignored, Throwable failure) {
                        lastFailure.set(failure);
                    }
                });
                return thread;
            }
        });
    }

    public long upsertPlayer(PlayerSnapshot snapshot) { return mailbox.upsert(snapshot); }
    public long removePlayer(PlayerKey player) { return mailbox.remove(player); }
    public int playerCount() { return mailbox.size(); }
    public long playerGeneration() { return mailbox.generation(); }

    public long requestPlan(PlannerMetrics metrics) {
        if (closed) return requestGeneration.get();
        long generation = requestGeneration.incrementAndGet();
        latestRequest.set(new Request(generation, Objects.requireNonNull(metrics, "metrics")));
        schedule();
        return generation;
    }

    public void requestPersistence(Runnable latestWrite) {
        if (!closed) {
            pendingPersistence.set(Objects.requireNonNull(latestWrite, "latestWrite"));
            schedule();
        }
    }

    public NativePlanResult latestResult() { return latestResult.get(); }
    public Throwable lastFailure() { return lastFailure.get(); }
    public long requestedGeneration() { return requestGeneration.get(); }

    public void recordApplication(long predictedChangedCells, long elapsedNanos) {
        governor.recordApplication(predictedChangedCells, elapsedNanos);
    }

    private void schedule() {
        synchronized (executorLifecycle) {
            if (closed || !scheduled.compareAndSet(false, true)) return;
            executor.execute(new Runnable() {
                @Override
                public void run() { drain(); }
            });
        }
    }

    private void drain() {
        long processed = -1;
        try {
            while (!closed) {
                Request request = latestRequest.get();
                if (request != null && request.generation != processed) {
                    LatestStateMailbox.Snapshot snapshot = mailbox.snapshot();
                    if (latestRequest.get() != request) {
                        processed = request.generation;
                        continue;
                    }
                    NativePlanResult result = compute(request, snapshot);
                    if (latestRequest.get() == request) latestResult.set(result);
                    processed = request.generation;
                }
                Runnable persistence = pendingPersistence.getAndSet(null);
                if (persistence != null) persistence.run();
                Request after = latestRequest.get();
                if ((after == null || after.generation == processed)
                        && pendingPersistence.get() == null) break;
            }
        } catch (Throwable failure) {
            lastFailure.set(failure);
            Request failed = latestRequest.get();
            if (failed != null) processed = failed.generation;
        } finally {
            scheduled.set(false);
            Request request = latestRequest.get();
            if (!closed && ((request != null && request.generation != processed)
                    || pendingPersistence.get() != null)) schedule();
        }
    }

    private NativePlanResult compute(Request request, LatestStateMailbox.Snapshot snapshot) {
        long started = System.nanoTime();
        List<PlayerSnapshot> players = snapshot.players();
        ExactUnionPlanner.UnionResult desired = unionPlanner.plan(players);
        List<AppliedSourceSnapshot> applied = appliedSources(players);
        long appliedArea = unionPlanner.exactAppliedUnionArea(applied);
        long remaining = unionPlanner.exactChangedCells(desired.sources(), applied);
        GovernorDecision decision = governor.evaluate(request.metrics, remaining);

        Map<PlayerKey, Integer> approved = new HashMap<PlayerKey, Integer>();
        List<DeficitRoundRobin.GrowthCandidate> candidates =
                new ArrayList<DeficitRoundRobin.GrowthCandidate>();
        for (PlayerSnapshot player : players) {
            int current = Math.min(player.effectiveViewDistance(),
                    Math.max(2, player.achievedViewDistance()));
            approved.put(player.player(), Integer.valueOf(current));
            int backedCurrent = Math.max(current, player.simulationDistance());
            int backedTarget = Math.max(player.effectiveViewDistance(), player.simulationDistance());
            if (backedTarget > backedCurrent) {
                candidates.add(new DeficitRoundRobin.GrowthCandidate(
                        player.player(),
                        backedCurrent + LoadSource.LOADING_MARGIN,
                        backedTarget + LoadSource.LOADING_MARGIN,
                        Math.max(1L, ChunkCells.changedCells(backedCurrent, backedTarget))));
            } else if (player.effectiveViewDistance() > current) {
                approved.put(player.player(), Integer.valueOf(player.effectiveViewDistance()));
            }
        }

        if (decision.state() == GovernorDecision.State.PAUSED_FROZEN
                || decision.state() == GovernorDecision.State.PAUSED_PRESSURE) {
            growthScheduler.allocate(Collections.<DeficitRoundRobin.GrowthCandidate>emptyList(), 0);
        } else {
            List<DeficitRoundRobin.GrowthGrant> grants = growthScheduler.allocate(
                    candidates, decision.graphCellBudget());
            for (DeficitRoundRobin.GrowthGrant grant : grants) {
                approved.put(grant.owner(), Integer.valueOf(
                        Math.max(2, grant.targetTicketRadius() - LoadSource.LOADING_MARGIN)));
            }
        }

        return new NativePlanResult(
                request.generation,
                snapshot.generation(),
                approved,
                desired.sources().size(),
                desired.exactUnionArea(),
                appliedArea,
                remaining,
                decision,
                System.nanoTime() - started);
    }

    private static List<AppliedSourceSnapshot> appliedSources(List<PlayerSnapshot> players) {
        List<AppliedSourceSnapshot> sources = new ArrayList<AppliedSourceSnapshot>();
        for (PlayerSnapshot player : players) {
            int applied = Math.max(2, player.achievedViewDistance());
            if (applied <= player.simulationDistance()) continue;
            sources.add(new AppliedSourceSnapshot(
                    player.player(), player.dimension(), player.chunkX(), player.chunkZ(),
                    applied + LoadSource.LOADING_MARGIN));
        }
        return sources;
    }

    @Override
    public void close() { close(Duration.ofSeconds(5)); }

    public void close(Duration timeout) {
        synchronized (executorLifecycle) {
            closed = true;
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(Math.min(1_000L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Request {
        private final long generation;
        private final PlannerMetrics metrics;

        private Request(long generation, PlannerMetrics metrics) {
            this.generation = generation;
            this.metrics = metrics;
        }
    }
}
