package com.playerviewdistance;

import com.playerviewdistance.config.ConfigData;
import com.playerviewdistance.config.ConfigRepository;
import com.playerviewdistance.config.OverrideRepository;
import com.playerviewdistance.config.ViewDistancePolicy;
import com.playerviewdistance.core.AppliedSourceSnapshot;
import com.playerviewdistance.core.ChunkCells;
import com.playerviewdistance.core.DimensionMetrics;
import com.playerviewdistance.core.GovernorDecision;
import com.playerviewdistance.core.LoadSource;
import com.playerviewdistance.core.PlanResult;
import com.playerviewdistance.core.PlannerMetrics;
import com.playerviewdistance.core.PlannerService;
import com.playerviewdistance.core.PlayerKey;
import com.playerviewdistance.core.PlayerCoverage;
import com.playerviewdistance.core.PlayerSnapshot;
import com.playerviewdistance.core.SourceMutation;
import com.playerviewdistance.mixin.ChunkMapInvoker;
import com.playerviewdistance.mixin.ServerChunkCacheAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PerPlayerChunkLoader {
    /*
     * TicketType is a value-based record in current Minecraft.  Using the same
     * timeout/flags as PLAYER_LOADING would therefore make a supposedly private
     * ticket equal to vanilla's ticket and allow removeTicketWithRadius to remove
     * the wrong entry.  The high private marker bit is ignored by Minecraft's
     * documented flag predicates while making the value unambiguously distinct.
     */
    private static final int PVD_PRIVATE_FLAG = 1 << 30;
    private static final TicketType PVD_LOADING = new TicketType(
            TicketType.NO_TIMEOUT,
            TicketType.FLAG_LOADING | PVD_PRIVATE_FLAG
    );
    private static final Map<PlayerKey, CapturedPlayer> players = new LinkedHashMap<>();
    private static final Map<PlayerKey, AppliedSource> appliedSources = new LinkedHashMap<>();
    private static final Map<PlayerKey, Integer> operatorOverrides = new HashMap<>();

    private static MinecraftServer server;
    private static PlannerService planner;
    private static OverrideRepository overrideRepository;
    private static int serverViewDistance = 32;
    private static int simulationDistance = 2;
    private static boolean planDirty;
    private static long requestedGeneration;
    private static long appliedGeneration;
    private static long appliedRevision;
    private static long movementBudgetRemaining;
    private static long convergenceAgeTicks;
    private static long explicitTicketMutations;
    private static long pvdServerThreadNanos;
    private static long lastPvdServerThreadNanos;
    private static long lastTelemetryNanos;
    private static long entityCount;
    private static long mobCount;
    private static long itemCount;
    private static long feedbackCells;
    private static long feedbackTicketNanos;
    private static long feedbackGraphNanos;
    private static long runtimeTicks;
    private static Throwable reportedPlannerFailure;
    private static Method c2mePendingLoadsMethod;
    private static Method vmpGetWatcherMethod;
    private static Method vmpMovePlayerMethod;
    private static PlanResult currentPlan;
    private static PlannerMetrics latestMetrics;

    private PerPlayerChunkLoader() {
    }

    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        players.clear();
        appliedSources.clear();
        operatorOverrides.clear();
        entityCount = 0;
        mobCount = 0;
        itemCount = 0;
        explicitTicketMutations = 0;
        pvdServerThreadNanos = 0;
        lastPvdServerThreadNanos = 0;
        convergenceAgeTicks = 0;
        requestedGeneration = 0;
        appliedGeneration = 0;
        appliedRevision = 0;
        movementBudgetRemaining = 0;
        currentPlan = null;
        latestMetrics = null;
        reportedPlannerFailure = null;
        c2mePendingLoadsMethod = null;
        vmpGetWatcherMethod = null;
        vmpMovePlayerMethod = null;
        feedbackCells = 0;
        feedbackTicketNanos = 0;
        feedbackGraphNanos = 0;
        runtimeTicks = 0;
        serverViewDistance = minecraftServer.getPlayerList().getViewDistance();
        simulationDistance = minecraftServer.getPlayerList().getSimulationDistance();

        verifyPrivateTicketType();
        rescanEntityCounts(minecraftServer);
        planner = new PlannerService();
        overrideRepository = new OverrideRepository(ViewDistanceConfig.configDirectory());
        OverrideRepository.LoadOutcome overrides = overrideRepository.load();
        if (overrides.success()) {
            operatorOverrides.putAll(overrides.overrides());
            PlayerViewDistanceMod.LOGGER.info("{} ({} entries)", overrides.message(), operatorOverrides.size());
        } else {
            PlayerViewDistanceMod.LOGGER.error("{}", overrides.message());
        }

        boolean c2me = FabricLoader.getInstance().isModLoaded("c2me")
                || FabricLoader.getInstance().isModLoaded("c2me-notickvd");
        boolean c2meNoTick = FabricLoader.getInstance().isModLoaded("c2me-notickvd");
        boolean vmp = FabricLoader.getInstance().isModLoaded("vmp");
        if (c2me) {
            PlayerViewDistanceMod.LOGGER.info(
                    "C2ME detected: the simulation-distance player-loading floor remains authoritative; "
                            + "PVD adds loading-only coverage beyond it");
        }
        if (c2meNoTick) {
            initializeC2meNoTickAdapter(minecraftServer);
        }
        if (vmp) {
            initializeVmpAdapter(minecraftServer);
            PlayerViewDistanceMod.LOGGER.info(
                    "VMP adapter active: its area watcher consumes PVD's per-player tracking distance");
        }
        planDirty = true;
        lastTelemetryNanos = System.nanoTime();
        PlayerViewDistanceMod.LOGGER.info(
                "PVD runtime started (server cap={}, simulation floor={}, planner threads=1)",
                serverViewDistance, simulationDistance);
    }

    public static void shutdown(MinecraftServer minecraftServer) {
        if (server == null || minecraftServer != server) {
            return;
        }
        long started = System.nanoTime();
        removeAllPrivateSources();
        saveOverridesSynchronously();
        if (planner != null) {
            planner.close(Duration.ofSeconds(5));
            planner = null;
        }
        players.clear();
        appliedSources.clear();
        operatorOverrides.clear();
        c2mePendingLoadsMethod = null;
        vmpGetWatcherMethod = null;
        vmpMovePlayerMethod = null;
        server = null;
        pvdServerThreadNanos += System.nanoTime() - started;
        PlayerViewDistanceMod.LOGGER.info("PVD runtime stopped; all private loading tickets were removed");
    }

    public static int getEffectiveViewDistance(ServerPlayer player) {
        PlayerKey key = PlayerKey.of(player.getUUID());
        Integer override = operatorOverrides.get(key);
        return ViewDistancePolicy.effectiveDistance(
                player.requestedViewDistance(), override, ViewDistanceConfig.get(), serverViewDistance);
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (server == null) {
            return;
        }
        capturePlayer(player);
        // VMP adds the player to its area map after Fabric's JOIN callback.
        // Its add hook already reads our mixed-in per-player distance, while an
        // eager move here would try to update an entry that does not exist yet.
        if (vmpGetWatcherMethod == null) {
            refreshChunkTracking(player);
        }
        planDirty = true;
    }

    public static void onPlayerLeave(ServerPlayer player) {
        PlayerKey key = PlayerKey.of(player.getUUID());
        retireOwnedSource(key, true);
        players.remove(key);
        if (planner != null) {
            planner.removePlayer(key);
        }
        invalidateOutstandingPlan();
    }

    /** Called from ChunkMap.move at HEAD, before vanilla updates its tracking view. */
    public static void onPlayerMoved(ServerPlayer player) {
        if (server != null) {
            capturePlayer(player);
        }
    }

    /** Called after ServerPlayer has stored a new ClientInformation instance. */
    public static void onClientOptionsChanged(ServerPlayer player) {
        // updateOptions is also called from the ServerPlayer constructor before
        // PrepareSpawnTask assigns the packet listener. JOIN performs the first
        // safe refresh; subsequent option packets refresh immediately here.
        if (server == null || player.connection == null) {
            return;
        }
        capturePlayer(player);
        refreshChunkTracking(player);
    }

    public static void onServerViewDistanceChanging(int newCap) {
        int clamped = clampVanillaDistance(newCap);
        if (serverViewDistance == clamped) {
            return;
        }
        serverViewDistance = clamped;
        if (server != null) {
            removeAllPrivateSources();
            invalidateOutstandingPlan();
            recaptureAllPlayers();
        }
    }

    public static void onServerViewDistanceChanged() {
        if (server != null) {
            refreshAllChunkTracking();
        }
    }

    public static void onSimulationDistanceChanged(int newSimulationDistance) {
        int clamped = clampVanillaDistance(newSimulationDistance);
        if (simulationDistance == clamped) {
            return;
        }
        simulationDistance = clamped;
        if (server != null) {
            removeAllPrivateSources();
            invalidateOutstandingPlan();
            recaptureAllPlayers();
        }
    }

    public static void onConfigReloaded(ConfigRepository.LoadOutcome outcome) {
        if (!outcome.success() || server == null) {
            return;
        }
        removeAllPrivateSources();
        invalidateOutstandingPlan();
        recaptureAllPlayers();
        refreshAllChunkTracking();
    }

    public static void setOperatorOverride(UUID uuid, int distance) {
        if (distance < 2 || distance > 32) {
            throw new IllegalArgumentException("distance must be in [2, 32]");
        }
        PlayerKey key = PlayerKey.of(uuid);
        operatorOverrides.put(key, distance);
        persistOverrides();
        onOverrideChanged(key);
    }

    public static void removeOperatorOverride(UUID uuid) {
        PlayerKey key = PlayerKey.of(uuid);
        if (operatorOverrides.remove(key) != null) {
            persistOverrides();
            onOverrideChanged(key);
        }
    }

    public static boolean hasOverride(UUID uuid) {
        return operatorOverrides.containsKey(PlayerKey.of(uuid));
    }

    public static int getOverride(UUID uuid) {
        return operatorOverrides.getOrDefault(PlayerKey.of(uuid), -1);
    }

    public static void onEntityLoad(Entity entity, ServerLevel level) {
        entityCount++;
        if (entity instanceof Mob) {
            mobCount++;
        }
        if (entity instanceof ItemEntity) {
            itemCount++;
        }
    }

    public static void onEntityUnload(Entity entity, ServerLevel level) {
        entityCount = Math.max(0, entityCount - 1);
        if (entity instanceof Mob) {
            mobCount = Math.max(0, mobCount - 1);
        }
        if (entity instanceof ItemEntity) {
            itemCount = Math.max(0, itemCount - 1);
        }
    }

    /** Receives the measured server-thread ticket-graph phase from the required mixin. */
    public static void onChunkGraphTiming(long elapsedNanos) {
        if (server != null && feedbackCells > 0 && elapsedNanos > 0) {
            feedbackGraphNanos = saturatingLongAdd(feedbackGraphNanos, elapsedNanos);
        }
    }

    private static void rescanEntityCounts(MinecraftServer minecraftServer) {
        entityCount = 0;
        mobCount = 0;
        itemCount = 0;
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                entityCount++;
                if (entity instanceof Mob) {
                    mobCount++;
                }
                if (entity instanceof ItemEntity) {
                    itemCount++;
                }
            }
        }
    }

    public static void onServerTick(MinecraftServer minecraftServer) {
        if (server == null || minecraftServer != server || planner == null) {
            return;
        }
        long started = System.nanoTime();
        runtimeTicks++;
        latestMetrics = captureMetrics(minecraftServer);
        reportGraphFeedback(latestMetrics);

        int liveSimulationDistance = minecraftServer.getPlayerList().getSimulationDistance();
        if (liveSimulationDistance != simulationDistance) {
            onSimulationDistanceChanged(liveSimulationDistance);
        }
        int liveViewDistance = minecraftServer.getPlayerList().getViewDistance();
        if (liveViewDistance != serverViewDistance) {
            onServerViewDistanceChanging(liveViewDistance);
        }

        consumeLatestPlan();
        boolean noOutstandingRequest = requestedGeneration == appliedGeneration;
        boolean convergenceNeeded = currentPlan == null || currentPlan.remainingChangedCells() > 0;
        boolean governorHeartbeat = runtimeTicks % 20L == 0L;
        if (planDirty || requestedGeneration == 0
                || (noOutstandingRequest && (convergenceNeeded || governorHeartbeat))) {
            requestedGeneration = planner.requestPlan(
                    simulationDistance,
                    appliedRevision,
                    appliedSnapshots(),
                    latestMetrics
            );
            planDirty = false;
        }

        if (currentPlan != null && currentPlan.remainingChangedCells() == 0) {
            convergenceAgeTicks = 0;
        } else if (convergenceAgeTicks < Integer.MAX_VALUE) {
            convergenceAgeTicks++;
        }

        Throwable failure = planner.lastFailure();
        if (failure != null && failure != reportedPlannerFailure) {
            reportedPlannerFailure = failure;
            PlayerViewDistanceMod.LOGGER.error("PVD planner failed; optional expansion is paused", failure);
            movementBudgetRemaining = 0;
        }

        // A governor grant is a per-tick allowance. Keep the last completed,
        // generation-checked decision available while a replacement plan is
        // coalescing, so ordinary movement does not restart at radius two.
        if (currentPlan != null && failure == null) {
            movementBudgetRemaining = currentPlan.governor().graphCellBudget();
        }

        lastPvdServerThreadNanos = System.nanoTime() - started;
        pvdServerThreadNanos += lastPvdServerThreadNanos;
        maybeLogTelemetry();
    }

    public static List<PlayerStatus> getPlayerStates() {
        List<PlayerStatus> statuses = new ArrayList<>(players.size());
        for (CapturedPlayer player : players.values()) {
            AppliedSource source = appliedSources.get(player.key());
            int appliedView = source == null
                    ? Math.max(player.achievedViewDistance(),
                    Math.min(player.effectiveViewDistance(), simulationDistance))
                    : Math.max(0, source.ticketRadius() - LoadSource.LOADING_MARGIN);
            statuses.add(new PlayerStatus(
                    player.key().toUuid(),
                    player.name(),
                    player.requestedViewDistance(),
                    player.effectiveViewDistance(),
                    appliedView,
                    operatorOverrides.get(player.key()),
                    player.dimension(),
                    player.chunkX(),
                    player.chunkZ()
            ));
        }
        statuses.sort(Comparator.comparing(PlayerStatus::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(statuses);
    }

    public static StatusSnapshot status() {
        PlanResult plan = currentPlan;
        PlannerMetrics metrics = latestMetrics;
        GovernorDecision governor = plan == null ? null : plan.governor();
        return new StatusSnapshot(
                requestedGeneration,
                appliedGeneration,
                plan == null ? 0 : plan.desiredSources().size(),
                appliedSources.size(),
                plan == null ? 0 : plan.desiredUnionArea(),
                plan == null ? 0 : plan.appliedUnionArea(),
                plan == null ? 0 : plan.remainingChangedCells(),
                metrics == null ? 0 : metrics.averageTickNanos(),
                governor == null ? 0 : governor.p95TickNanos(),
                metrics == null ? 0 : metrics.pendingChunkWork(),
                metrics == null ? 0 : metrics.loadedChunks(),
                entityCount,
                mobCount,
                itemCount,
                governor == null ? "STARTING" : governor.state().name(),
                explicitTicketMutations,
                plan == null ? 0 : plan.plannerNanos(),
                lastPvdServerThreadNanos,
                serverViewDistance,
                simulationDistance
        );
    }

    private static void consumeLatestPlan() {
        PlanResult result = planner.latestResult();
        if (result == null || result.generation() != requestedGeneration
                || result.generation() <= appliedGeneration
                || result.appliedRevision() != appliedRevision
                || result.playerGeneration() != planner.playerGeneration()) {
            return;
        }
        applyPlan(result);
        currentPlan = result;
        appliedGeneration = result.generation();
        movementBudgetRemaining = result.governor().graphCellBudget();
    }

    private static void applyPlan(PlanResult result) {
        for (SourceMutation mutation : result.mutations()) {
            switch (mutation.kind()) {
                case REMOVE -> {
                    AppliedSource before = requireApplied(mutation.before());
                    appliedSources.remove(before.owner());
                    removeTicket(before, mutation.predictedChangedCells());
                    appliedRevision++;
                }
                case REASSIGN -> {
                    AppliedSource before = requireApplied(mutation.before());
                    AppliedSource after = AppliedSource.from(mutation.after());
                    appliedSources.remove(before.owner());
                    if (appliedSources.putIfAbsent(after.owner(), after) != null) {
                        throw new IllegalStateException("planner attempted to reassign onto an occupied owner");
                    }
                    appliedRevision++;
                }
                case ADD -> {
                    AppliedSource after = AppliedSource.from(mutation.after());
                    if (appliedSources.containsKey(after.owner())) {
                        throw new IllegalStateException("planner attempted to add an occupied source owner");
                    }
                    addTicket(after, mutation.predictedChangedCells());
                    appliedSources.put(after.owner(), after);
                    updateAchieved(after.owner(), after.ticketRadius());
                    appliedRevision++;
                }
                case RESIZE -> {
                    AppliedSource before = requireApplied(mutation.before());
                    AppliedSource after = AppliedSource.from(mutation.after());
                    addTicket(after, mutation.predictedChangedCells());
                    removeTicket(before, 0);
                    appliedSources.put(after.owner(), after);
                    updateAchieved(after.owner(), after.ticketRadius());
                    appliedRevision++;
                }
            }
        }
        updateAchievedCoverage(result.playerCoverage());
    }

    private static void updateAchievedCoverage(List<PlayerCoverage> coverage) {
        for (PlayerCoverage entry : coverage) {
            CapturedPlayer player = players.get(entry.player());
            if (player == null) {
                continue;
            }
            int achieved = Math.min(
                    player.effectiveViewDistance(),
                    Math.max(player.achievedViewDistance(), entry.achievedViewDistance()));
            if (achieved != player.achievedViewDistance()) {
                players.put(entry.player(), player.withAchieved(achieved));
            }
        }
    }

    private static AppliedSource requireApplied(AppliedSourceSnapshot expected) {
        AppliedSource source = appliedSources.get(expected.owner());
        if (source == null || !source.toSnapshot().equals(expected)) {
            throw new IllegalStateException("planner source base changed despite applied-revision validation");
        }
        return source;
    }

    private static void capturePlayer(ServerPlayer player) {
        PlayerKey key = PlayerKey.of(player.getUUID());
        int chunkX = Math.floorDiv(player.getBlockX(), 16);
        int chunkZ = Math.floorDiv(player.getBlockZ(), 16);
        String dimension = dimensionId(player.level());
        int effective = getEffectiveViewDistance(player);
        CapturedPlayer old = players.get(key);
        int achieved = old == null ? 0 : old.achievedViewDistance();
        CapturedPlayer captured = new CapturedPlayer(
                key,
                player.getGameProfile().name(),
                dimension,
                chunkX,
                chunkZ,
                player.requestedViewDistance(),
                effective,
                Math.min(achieved, effective)
        );
        if (captured.equals(old)) {
            return;
        }

        if (old != null) {
            handleStrictPlayerDelta(old, captured);
        }
        players.put(key, captured);
        planner.upsertPlayer(captured.toSnapshot());
        invalidateOutstandingPlan();
    }

    private static void handleStrictPlayerDelta(CapturedPlayer old, CapturedPlayer current) {
        AppliedSource applied = appliedSources.get(old.key());
        if (applied == null) {
            return;
        }
        boolean moved = !old.dimension().equals(current.dimension())
                || old.chunkX() != current.chunkX()
                || old.chunkZ() != current.chunkZ();
        int maximumTicketRadius = current.effectiveViewDistance() + LoadSource.LOADING_MARGIN;
        boolean noLongerNeeded = current.effectiveViewDistance() <= simulationDistance;
        boolean tooLarge = applied.ticketRadius() > maximumTicketRadius;
        if (!moved && !noLongerNeeded && !tooLarge) {
            return;
        }

        appliedSources.remove(old.key());
        appliedRevision++;
        PlayerKey replacement = identicalCenterReplacement(applied, old.key());
        if (replacement != null) {
            appliedSources.put(replacement, applied.withOwner(replacement));
            updateAchieved(replacement, applied.ticketRadius());
        } else {
            removeTicket(applied, 0);
        }
        if (!moved || noLongerNeeded || applied.ticketRadius() > maximumTicketRadius) {
            return;
        }

        // Region tickets are value-deduplicated by Minecraft. Mirroring an already-covered
        // destination in appliedSources would therefore create two logical sources for one
        // physical ticket; retiring either logical source could then remove coverage for both.
        if (hasCoveringSourceAt(current, applied.ticketRadius())) {
            return;
        }

        long changedCells = symmetricDifference(applied, current);
        if (canSpendMovementBudget(changedCells)) {
            AppliedSource relocated = new AppliedSource(
                    current.key(), current.dimension(), current.chunkX(), current.chunkZ(), applied.ticketRadius());
            addTicket(relocated, changedCells);
            appliedSources.put(current.key(), relocated);
            appliedRevision++;
        }
    }

    private static boolean hasCoveringSourceAt(CapturedPlayer player, int ticketRadius) {
        for (AppliedSource source : appliedSources.values()) {
            if (source.dimension().equals(player.dimension())
                    && source.chunkX() == player.chunkX()
                    && source.chunkZ() == player.chunkZ()
                    && source.ticketRadius() >= ticketRadius) {
                return true;
            }
        }
        return false;
    }

    private static boolean canSpendMovementBudget(long changedCells) {
        if (currentPlan == null || currentPlan.governor().state() == GovernorDecision.State.PAUSED_FROZEN
                || currentPlan.governor().state() == GovernorDecision.State.PAUSED_PRESSURE
                || changedCells > movementBudgetRemaining) {
            return false;
        }
        movementBudgetRemaining -= changedCells;
        return true;
    }

    private static long symmetricDifference(AppliedSource source, CapturedPlayer player) {
        long area = source.area();
        if (!source.dimension().equals(player.dimension())) {
            return area * 2L;
        }
        long diameter = source.ticketRadius() * 2L + 1L;
        long overlapX = Math.max(0, diameter - Math.abs((long) source.chunkX() - player.chunkX()));
        long overlapZ = Math.max(0, diameter - Math.abs((long) source.chunkZ() - player.chunkZ()));
        return 2L * (area - overlapX * overlapZ);
    }

    private static void recaptureAllPlayers() {
        if (server == null || planner == null) {
            return;
        }
        Set<PlayerKey> live = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            live.add(PlayerKey.of(player.getUUID()));
            capturePlayer(player);
        }
        for (PlayerKey key : List.copyOf(players.keySet())) {
            if (!live.contains(key)) {
                retireOwnedSource(key, true);
                players.remove(key);
                planner.removePlayer(key);
                invalidateOutstandingPlan();
            }
        }
    }

    private static void onOverrideChanged(PlayerKey key) {
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(key.toUuid());
        if (player != null) {
            retireOwnedSource(key, true);
            capturePlayer(player);
            refreshChunkTracking(player);
            planDirty = true;
        }
    }

    private static void refreshAllChunkTracking() {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshChunkTracking(player);
        }
    }

    private static void refreshChunkTracking(ServerPlayer player) {
        ServerChunkCache cache = player.level().getChunkSource();
        try {
            ChunkMap chunkMap = ((ServerChunkCacheAccessor) cache).playerviewdistance$getChunkMap();
            if (vmpGetWatcherMethod != null) {
                refreshVmpChunkTracking(chunkMap, player);
                return;
            }
            ((ChunkMapInvoker) chunkMap).playerviewdistance$updateChunkTracking(player);
        } catch (ClassCastException missingMixin) {
            throw new IllegalStateException(
                    "PlayerViewDistance's required ChunkMap tracking hook is missing. "
                            + "Update PVD or remove the mod that replaced ChunkMap/ServerChunkCache.", missingMixin);
        }
    }

    private static void initializeVmpAdapter(MinecraftServer minecraftServer) {
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            ChunkMap chunkMap = ((ServerChunkCacheAccessor) level.getChunkSource())
                    .playerviewdistance$getChunkMap();
            try {
                Method getter = chunkMap.getClass().getMethod("getAreaPlayerChunkWatchingManager");
                Object watcher = getter.invoke(chunkMap);
                if (watcher == null) {
                    throw new IllegalStateException("VMP returned a null area player watcher");
                }
                Method movePlayer = watcher.getClass().getMethod("movePlayer", long.class, ServerPlayer.class);
                if (movePlayer.getReturnType() != void.class) {
                    throw new NoSuchMethodException("unexpected VMP movePlayer return type");
                }
                vmpGetWatcherMethod = getter;
                vmpMovePlayerMethod = movePlayer;
                return;
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException failure) {
                throw new IllegalStateException(
                        "VMP is installed, but its per-player area-watcher compatibility hooks "
                                + "getAreaPlayerChunkWatchingManager()/movePlayer(long, ServerPlayer) are missing. "
                                + "Update PVD/VMP to compatible builds.", failure);
            }
        }
        throw new IllegalStateException("VMP adapter could not find a loaded server dimension");
    }

    private static void refreshVmpChunkTracking(ChunkMap chunkMap, ServerPlayer player) {
        try {
            int chunkX = Math.floorDiv(player.getBlockX(), 16);
            int chunkZ = Math.floorDiv(player.getBlockZ(), 16);
            int trackingRadius = getEffectiveViewDistance(player) + 1;
            player.setChunkTrackingView(ChunkTrackingView.of(
                    new ChunkPos(chunkX, chunkZ), trackingRadius));
            Object watcher = vmpGetWatcherMethod.invoke(chunkMap);
            long packed = ((long) chunkX & 0xffff_ffffL) | (((long) chunkZ & 0xffff_ffffL) << 32);
            vmpMovePlayerMethod.invoke(watcher, packed, player);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException(
                    "VMP per-player tracking refresh failed; update PVD/VMP to compatible builds", failure);
        }
    }

    private static PlannerMetrics captureMetrics(MinecraftServer minecraftServer) {
        List<DimensionMetrics> dimensions = new ArrayList<>();
        int pending = 0;
        int loaded = 0;
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            ServerChunkCache cache = level.getChunkSource();
            int dimensionPending = Math.max(
                    Math.max(0, cache.getPendingTasksCount()),
                    c2mePendingLoads(cache)
            );
            int dimensionLoaded = Math.max(0, cache.getLoadedChunksCount());
            pending = saturatingIntAdd(pending, dimensionPending);
            loaded = saturatingIntAdd(loaded, dimensionLoaded);
            dimensions.add(new DimensionMetrics(dimensionId(level), dimensionPending, dimensionLoaded));
        }
        dimensions.sort(Comparator.comparing(DimensionMetrics::dimension));
        long interval = minecraftServer.tickRateManager().nanosecondsPerTick();
        long smoothed = (long) (minecraftServer.getCurrentSmoothedTickTime() * 1_000_000.0f);
        return new PlannerMetrics(
                Math.max(1, interval),
                Math.max(0, minecraftServer.getAverageTickTimeNanos()),
                Math.max(0, smoothed),
                minecraftServer.getTickTimesNanos(),
                minecraftServer.tickRateManager().isFrozen(),
                minecraftServer.tickRateManager().isSprinting(),
                pending,
                loaded,
                dimensions,
                players.size(),
                mobCount,
                itemCount,
                entityCount,
                (int) Math.min(Integer.MAX_VALUE, convergenceAgeTicks)
        );
    }

    private static void initializeC2meNoTickAdapter(MinecraftServer minecraftServer) {
        for (ServerLevel level : minecraftServer.getAllLevels()) {
            ChunkMap chunkMap = ((ServerChunkCacheAccessor) level.getChunkSource()).playerviewdistance$getChunkMap();
            Object distanceManager = chunkMap.getDistanceManager();
            try {
                Method method = distanceManager.getClass().getMethod("c2me$getPendingLoadsCount");
                if (method.getReturnType() != long.class || method.getParameterCount() != 0) {
                    throw new NoSuchMethodException("unexpected c2me$getPendingLoadsCount signature");
                }
                c2mePendingLoadsMethod = method;
                PlayerViewDistanceMod.LOGGER.info(
                        "C2ME no-tick adapter active: its pending-load backlog is included in PVD governance");
                return;
            } catch (NoSuchMethodException missingHook) {
                throw new IllegalStateException(
                        "C2ME no-tick view distance is installed, but its pending-load compatibility hook "
                                + "c2me$getPendingLoadsCount() is missing. Update PVD/C2ME to compatible builds.",
                        missingHook
                );
            }
        }
        throw new IllegalStateException("C2ME no-tick adapter could not find a loaded server dimension");
    }

    private static int c2mePendingLoads(ServerChunkCache cache) {
        Method method = c2mePendingLoadsMethod;
        if (method == null) {
            return 0;
        }
        ChunkMap chunkMap = ((ServerChunkCacheAccessor) cache).playerviewdistance$getChunkMap();
        try {
            long pending = (long) method.invoke(chunkMap.getDistanceManager());
            return pending <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, pending);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException(
                    "C2ME no-tick pending-load compatibility hook failed during metrics capture", failure);
        }
    }

    private static List<AppliedSourceSnapshot> appliedSnapshots() {
        List<AppliedSourceSnapshot> snapshots = new ArrayList<>(appliedSources.size());
        for (AppliedSource source : appliedSources.values()) {
            snapshots.add(source.toSnapshot());
        }
        snapshots.sort(Comparator.comparing(AppliedSourceSnapshot::dimension)
                .thenComparingInt(AppliedSourceSnapshot::chunkX)
                .thenComparingInt(AppliedSourceSnapshot::chunkZ)
                .thenComparing(AppliedSourceSnapshot::owner));
        return List.copyOf(snapshots);
    }

    private static void addTicket(AppliedSource source, long predictedCells) {
        ServerLevel level = findLevel(source.dimension());
        if (level == null) {
            return;
        }
        long started = System.nanoTime();
        level.getChunkSource().addTicketWithRadius(
                PVD_LOADING, new ChunkPos(source.chunkX(), source.chunkZ()), source.ticketRadius());
        long elapsed = System.nanoTime() - started;
        explicitTicketMutations++;
        accumulateFeedback(predictedCells, elapsed);
    }

    private static void removeTicket(AppliedSource source, long predictedCells) {
        ServerLevel level = findLevel(source.dimension());
        if (level == null) {
            return;
        }
        long started = System.nanoTime();
        level.getChunkSource().removeTicketWithRadius(
                PVD_LOADING, new ChunkPos(source.chunkX(), source.chunkZ()), source.ticketRadius());
        long elapsed = System.nanoTime() - started;
        explicitTicketMutations++;
        accumulateFeedback(predictedCells, elapsed);
    }

    private static void retireOwnedSource(PlayerKey key, boolean strict) {
        AppliedSource removed = appliedSources.remove(key);
        if (removed != null) {
            appliedRevision++;
            PlayerKey replacement = identicalCenterReplacement(removed, key);
            if (replacement != null) {
                appliedSources.put(replacement, removed.withOwner(replacement));
                return;
            }
            removeTicket(removed, strict ? removed.area() : 0);
        }
    }

    private static PlayerKey identicalCenterReplacement(AppliedSource source, PlayerKey excluded) {
        PlayerKey best = null;
        for (CapturedPlayer player : players.values()) {
            if (player.key().equals(excluded)
                    || appliedSources.containsKey(player.key())
                    || !player.dimension().equals(source.dimension())
                    || player.chunkX() != source.chunkX()
                    || player.chunkZ() != source.chunkZ()
                    || player.effectiveViewDistance() <= simulationDistance
                    || player.effectiveViewDistance() + LoadSource.LOADING_MARGIN < source.ticketRadius()) {
                continue;
            }
            if (best == null || player.key().compareTo(best) < 0) {
                best = player.key();
            }
        }
        return best;
    }

    private static void removeAllPrivateSources() {
        boolean changed = !appliedSources.isEmpty();
        for (AppliedSource source : List.copyOf(appliedSources.values())) {
            removeTicket(source, source.area());
        }
        appliedSources.clear();
        if (changed) {
            appliedRevision++;
        }
    }

    private static ServerLevel findLevel(String dimension) {
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (dimensionId(level).equals(dimension)) {
                return level;
            }
        }
        return null;
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static void updateAchieved(PlayerKey owner, int ticketRadius) {
        CapturedPlayer player = players.get(owner);
        if (player != null) {
            int achieved = Math.min(player.effectiveViewDistance(),
                    Math.max(player.achievedViewDistance(), ticketRadius - LoadSource.LOADING_MARGIN));
            players.put(owner, player.withAchieved(achieved));
        }
    }

    private static void invalidateOutstandingPlan() {
        requestedGeneration = appliedGeneration;
        planDirty = true;
    }

    private static void accumulateFeedback(long predictedCells, long ticketNanos) {
        feedbackCells = saturatingLongAdd(feedbackCells, Math.max(0, predictedCells));
        feedbackTicketNanos = saturatingLongAdd(feedbackTicketNanos, Math.max(0, ticketNanos));
    }

    private static void reportGraphFeedback(PlannerMetrics metrics) {
        if (feedbackCells > 0 && planner != null) {
            planner.recordApplication(
                    feedbackCells,
                    saturatingLongAdd(feedbackTicketNanos, feedbackGraphNanos)
            );
        }
        feedbackCells = 0;
        feedbackTicketNanos = 0;
        feedbackGraphNanos = 0;
    }

    private static void persistOverrides() {
        if (planner == null || overrideRepository == null) {
            return;
        }
        Map<PlayerKey, Integer> snapshot = Map.copyOf(operatorOverrides);
        planner.requestPersistence(() -> {
            try {
                overrideRepository.save(snapshot);
            } catch (IOException | IllegalArgumentException failure) {
                PlayerViewDistanceMod.LOGGER.error("Could not atomically persist PVD overrides", failure);
            }
        });
    }

    private static void saveOverridesSynchronously() {
        if (overrideRepository == null) {
            return;
        }
        try {
            overrideRepository.save(Map.copyOf(operatorOverrides));
        } catch (IOException | IllegalArgumentException failure) {
            PlayerViewDistanceMod.LOGGER.error("Could not persist PVD overrides during shutdown", failure);
        }
    }

    private static void maybeLogTelemetry() {
        long now = System.nanoTime();
        long interval = ViewDistanceConfig.get().telemetryIntervalSeconds() * 1_000_000_000L;
        if (now - lastTelemetryNanos < interval) {
            return;
        }
        lastTelemetryNanos = now;
        StatusSnapshot status = status();
        PlayerViewDistanceMod.LOGGER.info(
                "PVD status: governor={}, sources={}/{}, union={}/{}, avgMSPT={}, p95MSPT={}, "
                        + "backlog={}, loaded={}, entities={} (mobs={}, items={}), mutations={}, planner={}us, main={}us",
                status.governorState(), status.appliedSourceCount(), status.desiredSourceCount(),
                status.appliedUnionArea(), status.desiredUnionArea(),
                nanosToMillis(status.averageTickNanos()), nanosToMillis(status.p95TickNanos()),
                status.pendingChunkWork(), status.loadedChunks(), status.entityCount(), status.mobCount(),
                status.itemCount(), status.explicitTicketMutations(), status.plannerNanos() / 1_000,
                status.pvdServerThreadNanos() / 1_000);
    }

    private static void verifyPrivateTicketType() {
        if (PVD_LOADING == TicketType.PLAYER_LOADING
                || PVD_LOADING.equals(TicketType.PLAYER_LOADING)
                || !PVD_LOADING.doesLoad()
                || PVD_LOADING.doesSimulate()
                || PVD_LOADING.persist()
                || PVD_LOADING.shouldKeepDimensionActive()
                || PVD_LOADING.canExpireIfUnloaded()
                || PVD_LOADING.hasTimeout()) {
            throw new IllegalStateException("PVD private ticket must be identity-distinct and loading-only");
        }
    }

    private static int clampVanillaDistance(int distance) {
        return Math.max(2, Math.min(32, distance));
    }

    private static int saturatingIntAdd(int first, int second) {
        return first > Integer.MAX_VALUE - second ? Integer.MAX_VALUE : first + second;
    }

    private static long saturatingLongAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    private static String nanosToMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private record CapturedPlayer(
            PlayerKey key,
            String name,
            String dimension,
            int chunkX,
            int chunkZ,
            int requestedViewDistance,
            int effectiveViewDistance,
            int achievedViewDistance
    ) {
        private PlayerSnapshot toSnapshot() {
            return new PlayerSnapshot(key, dimension, chunkX, chunkZ, effectiveViewDistance, achievedViewDistance);
        }

        private CapturedPlayer withAchieved(int achieved) {
            return new CapturedPlayer(key, name, dimension, chunkX, chunkZ,
                    requestedViewDistance, effectiveViewDistance, achieved);
        }
    }

    private record AppliedSource(
            PlayerKey owner,
            String dimension,
            int chunkX,
            int chunkZ,
            int ticketRadius
    ) {
        private AppliedSourceSnapshot toSnapshot() {
            return new AppliedSourceSnapshot(owner, dimension, chunkX, chunkZ, ticketRadius);
        }

        private AppliedSource withOwner(PlayerKey newOwner) {
            return new AppliedSource(newOwner, dimension, chunkX, chunkZ, ticketRadius);
        }

        private static AppliedSource from(AppliedSourceSnapshot snapshot) {
            return new AppliedSource(snapshot.owner(), snapshot.dimension(), snapshot.chunkX(),
                    snapshot.chunkZ(), snapshot.ticketRadius());
        }

        private long area() {
            return ChunkCells.squareArea(ticketRadius);
        }
    }

    public record PlayerStatus(
            UUID uuid,
            String name,
            int requestedViewDistance,
            int desiredViewDistance,
            int appliedViewDistance,
            Integer override,
            String dimension,
            int chunkX,
            int chunkZ
    ) {
    }

    public record StatusSnapshot(
            long requestedGeneration,
            long appliedGeneration,
            int desiredSourceCount,
            int appliedSourceCount,
            long desiredUnionArea,
            long appliedUnionArea,
            long remainingChangedCells,
            long averageTickNanos,
            long p95TickNanos,
            int pendingChunkWork,
            int loadedChunks,
            long entityCount,
            long mobCount,
            long itemCount,
            String governorState,
            long explicitTicketMutations,
            long plannerNanos,
            long pvdServerThreadNanos,
            int serverViewDistance,
            int simulationDistance
    ) {
    }
}
