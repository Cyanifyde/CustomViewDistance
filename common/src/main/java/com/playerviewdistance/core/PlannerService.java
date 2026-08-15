package com.playerviewdistance.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One coalescing planner worker with replaceable input and output slots. */
public final class PlannerService implements AutoCloseable {
    private final LatestStateMailbox mailbox = new LatestStateMailbox();
    private final ExactUnionPlanner unionPlanner = new ExactUnionPlanner();
    private final SourceMatcher sourceMatcher = new SourceMatcher();
    private final SourceTransitionPlanner transitionPlanner = new SourceTransitionPlanner();
    private final AdaptiveGovernor governor = new AdaptiveGovernor();
    private final ExecutorService executor;
    private final AtomicLong requestGeneration = new AtomicLong();
    private final AtomicReference<Request> latestRequest = new AtomicReference<>();
    private final AtomicReference<PlanResult> latestResult = new AtomicReference<>();
    private final AtomicReference<Runnable> pendingPersistence = new AtomicReference<>();
    private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile boolean closed;

    private long cachedPlayerGeneration = -1;
    private int cachedSimulationDistance = -1;
    private ExactUnionPlanner.UnionResult cachedUnion = new ExactUnionPlanner.UnionResult(List.of(), 0, 0);

    public PlannerService() {
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PVD-Planner");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> lastFailure.set(failure));
            return thread;
        });
    }

    public long upsertPlayer(PlayerSnapshot snapshot) {
        return mailbox.upsert(snapshot);
    }

    public long removePlayer(PlayerKey player) {
        return mailbox.remove(player);
    }

    public int playerCount() {
        return mailbox.size();
    }

    public long playerGeneration() {
        return mailbox.generation();
    }

    public long requestPlan(
            int simulationDistance,
            long appliedRevision,
            List<AppliedSourceSnapshot> appliedSources,
            PlannerMetrics metrics
    ) {
        if (closed) {
            return requestGeneration.get();
        }
        long generation = requestGeneration.incrementAndGet();
        latestRequest.set(new Request(
                generation,
                mailbox.generation(),
                simulationDistance,
                appliedRevision,
                List.copyOf(appliedSources),
                Objects.requireNonNull(metrics, "metrics")
        ));
        schedule();
        return generation;
    }

    public void requestPersistence(Runnable latestWrite) {
        if (!closed) {
            pendingPersistence.set(Objects.requireNonNull(latestWrite, "latestWrite"));
            schedule();
        }
    }

    public PlanResult latestResult() {
        return latestResult.get();
    }

    public Throwable lastFailure() {
        return lastFailure.get();
    }

    public long requestedGeneration() {
        return requestGeneration.get();
    }

    public void recordApplication(long predictedChangedCells, long elapsedNanos) {
        governor.recordApplication(predictedChangedCells, elapsedNanos);
    }

    private void schedule() {
        if (scheduled.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    private void drain() {
        long processedGeneration = -1;
        try {
            while (!closed) {
                Request request = latestRequest.get();
                if (request != null && request.generation() != processedGeneration) {
                    LatestStateMailbox.Snapshot snapshot = mailbox.snapshot();
                    Request newest = latestRequest.get();
                    if (newest == null || newest.generation() != request.generation()) {
                        processedGeneration = request.generation();
                        continue;
                    }
                    PlanResult result = compute(request, snapshot);
                    if (latestRequest.get() == request) {
                        latestResult.set(result);
                    }
                    processedGeneration = request.generation();
                }

                Runnable persistence = pendingPersistence.getAndSet(null);
                if (persistence != null) {
                    persistence.run();
                }

                Request after = latestRequest.get();
                if ((after == null || after.generation() == processedGeneration)
                        && pendingPersistence.get() == null) {
                    break;
                }
            }
        } catch (Throwable failure) {
            lastFailure.set(failure);
            Request failed = latestRequest.get();
            if (failed != null) {
                // Do not hot-loop the same deterministic failure. A subsequent
                // request still schedules a fresh attempt and can recover.
                processedGeneration = failed.generation();
            }
        } finally {
            scheduled.set(false);
            Request request = latestRequest.get();
            if (!closed && ((request != null && request.generation() != processedGeneration)
                    || pendingPersistence.get() != null)) {
                schedule();
            }
        }
    }

    private PlanResult compute(Request request, LatestStateMailbox.Snapshot snapshot) {
        long started = System.nanoTime();
        if (cachedPlayerGeneration != snapshot.generation()
                || cachedSimulationDistance != request.simulationDistance()) {
            cachedUnion = unionPlanner.plan(snapshot.players(), request.simulationDistance());
            cachedPlayerGeneration = snapshot.generation();
            cachedSimulationDistance = request.simulationDistance();
        }
        long remainingBefore = unionPlanner.exactChangedCells(cachedUnion.sources(), request.appliedSources());
        GovernorDecision decision = governor.evaluate(request.metrics(), remainingBefore);
        List<SourceAssignment> assignments = sourceMatcher.match(
                cachedUnion.sources(), request.appliedSources(), snapshot.players());
        SourceTransitionPlanner.TransitionResult transitions = transitionPlanner.plan(
                assignments, request.appliedSources(), request.simulationDistance(), decision);
        long appliedArea = unionPlanner.exactAppliedUnionArea(transitions.projectedSources());
        long remainingAfter = unionPlanner.exactChangedCells(
                cachedUnion.sources(), transitions.projectedSources());
        List<PlayerCoverage> playerCoverage = unionPlanner.achievedCoverage(
                snapshot.players(), transitions.projectedSources(), request.simulationDistance());
        return new PlanResult(
                request.generation(),
                snapshot.generation(),
                request.appliedRevision(),
                cachedUnion.sources(),
                assignments,
                transitions.mutations(),
                playerCoverage,
                cachedUnion.exactUnionArea(),
                appliedArea,
                remainingAfter,
                decision,
                System.nanoTime() - started
        );
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    public void close(Duration timeout) {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(Math.min(1_000, timeout.toMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record Request(
            long generation,
            long playerGeneration,
            int simulationDistance,
            long appliedRevision,
            List<AppliedSourceSnapshot> appliedSources,
            PlannerMetrics metrics
    ) {
    }
}
