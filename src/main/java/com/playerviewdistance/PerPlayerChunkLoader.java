package com.playerviewdistance;

import com.playerviewdistance.compat.ConnectionDistanceCeiling;
import com.playerviewdistance.compat.MoonriseNativeAdapter;
import com.playerviewdistance.api.DistanceBackend;
import com.playerviewdistance.api.DistanceSnapshot;
import com.playerviewdistance.api.PlayerViewDistanceApi;
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
import com.playerviewdistance.runtime.ComposedDistance;
import com.playerviewdistance.runtime.DefaultPlayerViewDistanceService;
import com.playerviewdistance.runtime.DistanceComposer;
import com.playerviewdistance.runtime.ResolvedLimits;
import com.playerviewdistance.mixin.ChunkMapInvoker;
import com.playerviewdistance.mixin.DistanceManagerAccessor;
import com.playerviewdistance.mixin.ServerCommonPacketListenerAccessor;
import com.playerviewdistance.mixin.ServerChunkCacheAccessor;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
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
import java.util.concurrent.ConcurrentHashMap;

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
    private static final Set<PlayerKey> externallyDirtyPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> pendingLevelViewDistances = new HashMap<>();
    private static final Map<String, Integer> pendingLevelSimulationDistances = new HashMap<>();

    private static MinecraftServer server;
    private static PlannerService planner;
    private static DefaultPlayerViewDistanceService distanceService;
    private static OverrideRepository overrideRepository;
    /** Immutable ceiling read from server.properties when PVD starts. */
    private static int configuredViewDistanceCap = 32;
    /** Current platform/mod-selected global distance; it may only lower the ceiling. */
    private static int platformViewDistance = 32;
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
    private static MoonriseNativeAdapter moonriseAdapter;
    private static PlanResult currentPlan;
    private static PlannerMetrics latestMetrics;

    private PerPlayerChunkLoader() {
    }

    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        players.clear();
        appliedSources.clear();
        operatorOverrides.clear();
        externallyDirtyPlayers.clear();
        pendingLevelViewDistances.clear();
        pendingLevelSimulationDistances.clear();
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
        moonriseAdapter = null;
        feedbackCells = 0;
        feedbackTicketNanos = 0;
        feedbackGraphNanos = 0;
        runtimeTicks = 0;
        platformViewDistance = clampVanillaDistance(
                minecraftServer.getPlayerList().getViewDistance());
        configuredViewDistanceCap = minecraftServer instanceof DedicatedServer dedicatedServer
                ? clampVanillaDistance(dedicatedServer.getProperties().viewDistance.get())
                : platformViewDistance;
        simulationDistance = minecraftServer.getPlayerList().getSimulationDistance();

        verifyPrivateTicketType();
        rescanEntityCounts(minecraftServer);
        planner = new PlannerService();
        distanceService = new DefaultPlayerViewDistanceService(
                uuid -> externallyDirtyPlayers.add(PlayerKey.of(uuid)));
        PlayerViewDistanceApi.install(distanceService);
        overrideRepository = new OverrideRepository(ViewDistanceConfig.configDirectory());
        OverrideRepository.LoadOutcome overrides = overrideRepository.load();
        if (overrides.success()) {
            operatorOverrides.putAll(overrides.overrides());
            PlatformEnvironment.logger().debug("{} ({} entries)", overrides.message(), operatorOverrides.size());
        } else {
            PlatformEnvironment.logger().error("{}", overrides.message());
        }

        boolean c2me = PlatformEnvironment.isModLoaded("c2me")
                || PlatformEnvironment.isModLoaded("c2me-notickvd");
        boolean c2meNoTick = PlatformEnvironment.isModLoaded("c2me-notickvd");
        boolean vmp = PlatformEnvironment.isModLoaded("vmp");
        boolean moonrise = PlatformEnvironment.isModLoaded("moonrise");
        if (moonrise) {
            moonriseAdapter = MoonriseNativeAdapter.create();
            PlatformEnvironment.logger().debug(
                    "Moonrise adapter active: PVD uses its native per-player loader without private tickets");
        }
        if (c2me) {
            PlatformEnvironment.logger().debug(
                    "C2ME detected: the simulation-distance player-loading floor remains authoritative; "
                            + "PVD adds loading-only coverage beyond it");
        }
        if (c2meNoTick) {
            initializeC2meNoTickAdapter(minecraftServer);
        }
        if (vmp && !moonrise) {
            initializeVmpAdapter(minecraftServer);
            PlatformEnvironment.logger().debug(
                    "VMP adapter active: its area watcher consumes PVD's per-player tracking distance");
        }
        planDirty = true;
        lastTelemetryNanos = System.nanoTime();
        PlatformEnvironment.logger().info("PVD is running.");
    }

    public static void shutdown(MinecraftServer minecraftServer) {
        if (server == null || minecraftServer != server) {
            return;
        }
        long started = System.nanoTime();
        if (moonriseAdapter != null) {
            try {
                moonriseAdapter.restoreAll(minecraftServer.getPlayerList().getPlayers());
            } catch (RuntimeException failure) {
                PlatformEnvironment.logger().error(
                        "PVD could not restore Moonrise player view-distance intent during shutdown", failure);
            }
        }
        removeAllPrivateSources();
        saveOverridesSynchronously();
        if (planner != null) {
            planner.close(Duration.ofSeconds(5));
            planner = null;
        }
        if (distanceService != null) {
            PlayerViewDistanceApi.uninstall(distanceService);
            distanceService.close();
            distanceService = null;
        }
        players.clear();
        appliedSources.clear();
        operatorOverrides.clear();
        externallyDirtyPlayers.clear();
        pendingLevelViewDistances.clear();
        pendingLevelSimulationDistances.clear();
        c2mePendingLoadsMethod = null;
        vmpGetWatcherMethod = null;
        vmpMovePlayerMethod = null;
        moonriseAdapter = null;
        server = null;
        pvdServerThreadNanos += System.nanoTime() - started;
        PlatformEnvironment.logger().debug("PVD runtime stopped; all private loading tickets were removed");
    }

    public static int getEffectiveViewDistance(ServerPlayer player) {
        return resolvePlayerDistances(player).sendingDistance();
    }

    public static int getEffectiveLoadingDistance(ServerPlayer player) {
        return resolvePlayerDistances(player).loadingDistance();
    }

    /** Moonrise already derives its loading floor from the untouched tick distance. */
    public static boolean shouldMaintainVanillaPlayerLoadingFloor() {
        return !PlatformEnvironment.isModLoaded("moonrise");
    }

    private static ResolvedPlayerDistances resolvePlayerDistances(ServerPlayer player) {
        PlayerKey key = PlayerKey.of(player.getUUID());
        Integer override = operatorOverrides.get(key);
        int configuredCandidate = ViewDistancePolicy.effectiveDistance(
                player.requestedViewDistance(), override, ViewDistanceConfig.get(), configuredViewDistanceCap);
        // The ChunkMap value is dimension-local. Mods which lower a particular
        // world's distance therefore remain authoritative without a named API.
        int ceiling = Math.min(configuredCandidate, currentLevelViewDistance(player.level()));
        ResolvedLimits limits = distanceService == null
                ? new ResolvedLimits(null, null, 0)
                : distanceService.resolve(player.getUUID());
        ComposedDistance composed = DistanceComposer.compose(
                ceiling, limits.loadingMaximum(), limits.sendingMaximum());
        return new ResolvedPlayerDistances(
                ceiling,
                limits.loadingMaximum(),
                limits.sendingMaximum(),
                composed.loadingDistance(),
                composed.sendingDistance(),
                limits.generation());
    }

    public static void initializeConnectionCeiling(ServerPlayer player, Connection connection) {
        if (server != null) {
            ((ConnectionDistanceCeiling) connection)
                    .playerviewdistance$setViewDistanceCeiling(getEffectiveViewDistance(player));
        }
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
        sendEffectiveCacheRadius(player);
        planDirty = true;
    }

    public static void onPlayerLeave(ServerPlayer player) {
        if (player == null) {
            return;
        }
        PlayerKey key = PlayerKey.of(player.getUUID());
        if (!players.containsKey(key) && !appliedSources.containsKey(key)) {
            return;
        }
        if (moonriseAdapter != null) {
            moonriseAdapter.forget(player);
        }
        retireOwnedSource(key, true);
        players.remove(key);
        if (planner != null) {
            planner.removePlayer(key);
        }
        externallyDirtyPlayers.remove(key);
        if (distanceService != null) {
            distanceService.removeSnapshot(player.getUUID());
        }
        setConnectionCeiling(player, 32);
        invalidateOutstandingPlan();
    }

    /** Called from ChunkMap.move at HEAD, before vanilla updates its tracking view. */
    public static void onPlayerMoved(ServerPlayer player) {
        // ChunkMap.move is also used while the configuration-phase player is
        // being prepared, before JOIN has made it a live PVD owner. Publishing
        // a private loading source during that transition can interleave with
        // vanilla/Lithium's initial player-ticket graph update.
        if (server != null) {
            captureRegisteredPlayer(player);
        }
    }

    /** Called after ServerPlayer has stored a new ClientInformation instance. */
    public static void onClientOptionsChanged(ServerPlayer player) {
        // A packet listener is assigned before the loader JOIN event. Treating
        // that pre-JOIN options packet as registration can publish a loading
        // source while vanilla is still constructing the player's graph state.
        // JOIN reads the already-stored options and performs the first capture.
        if (server == null || player.connection == null || !isRegistered(player)) {
            return;
        }
        captureRegisteredPlayer(player);
        refreshChunkTracking(player);
        sendEffectiveCacheRadius(player);
    }

    public static void onServerViewDistanceChanging(int newCap) {
        int clamped = clampVanillaDistance(newCap);
        if (platformViewDistance == clamped) {
            return;
        }
        platformViewDistance = clamped;
        if (server != null) {
            invalidateOutstandingPlan();
            recaptureAllPlayers();
        }
    }

    public static void onServerViewDistanceChanged() {
        if (server != null) {
            recaptureAllPlayers();
            refreshAllChunkTracking();
        }
    }

    public static void onServerSimulationDistanceChanging(int newSimulationDistance) {
        int clamped = clampVanillaDistance(newSimulationDistance);
        if (simulationDistance == clamped) {
            return;
        }
        simulationDistance = clamped;
        if (server != null) {
            invalidateOutstandingPlan();
            recaptureAllPlayers();
        }
    }

    public static void onServerSimulationDistanceChanged() {
        if (server != null) {
            recaptureAllPlayers();
        }
    }

    public static void onLevelViewDistanceChanging(ServerLevel level, int newDistance) {
        if (server == null || currentLevelViewDistance(level) == clampVanillaDistance(newDistance)) {
            return;
        }
        pendingLevelViewDistances.put(dimensionId(level), clampVanillaDistance(newDistance));
        invalidateOutstandingPlan();
        recapturePlayersInLevel(level);
    }

    public static void onLevelViewDistanceChanged(ServerLevel level) {
        if (server != null) {
            pendingLevelViewDistances.remove(dimensionId(level));
            recapturePlayersInLevel(level);
            refreshPlayersInLevel(level);
        }
    }

    public static void onLevelSimulationDistanceChanging(ServerLevel level, int newDistance) {
        if (server == null || currentLevelSimulationDistance(level) == clampVanillaDistance(newDistance)) {
            return;
        }
        pendingLevelSimulationDistances.put(
                dimensionId(level), clampVanillaDistance(newDistance));
        invalidateOutstandingPlan();
        recapturePlayersInLevel(level);
    }

    public static void onLevelSimulationDistanceChanged(ServerLevel level) {
        if (server != null) {
            pendingLevelSimulationDistances.remove(dimensionId(level));
            recapturePlayersInLevel(level);
        }
    }

    public static void onConfigReloaded(ConfigRepository.LoadOutcome outcome) {
        if (!outcome.success() || server == null) {
            return;
        }
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
        consumeExternalLimitChanges();
        if (moonriseAdapter != null) {
            // Moonrise has no change callback for its raw per-player holder.
            // Observe primitive values once per tick so uncooperative governors
            // can lower PVD without PVD's previous clamp becoming sticky.
            recaptureAllPlayers();
        }
        latestMetrics = captureMetrics(minecraftServer);
        reportGraphFeedback(latestMetrics);

        int liveSimulationDistance = minecraftServer.getPlayerList().getSimulationDistance();
        if (liveSimulationDistance != simulationDistance) {
            onServerSimulationDistanceChanging(liveSimulationDistance);
            onServerSimulationDistanceChanged();
        }
        int liveViewDistance = minecraftServer.getPlayerList().getViewDistance();
        if (liveViewDistance != platformViewDistance) {
            onServerViewDistanceChanging(liveViewDistance);
        }

        consumeLatestPlan();
        boolean noOutstandingRequest = requestedGeneration == appliedGeneration;
        boolean convergenceNeeded = currentPlan == null || currentPlan.remainingChangedCells() > 0;
        boolean governorHeartbeat = runtimeTicks % 20L == 0L;
        if (planDirty || requestedGeneration == 0
                || (noOutstandingRequest && (convergenceNeeded || governorHeartbeat))) {
            requestedGeneration = planner.requestPlan(
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
            PlatformEnvironment.logger().error("PVD planner failed; optional expansion is paused", failure);
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
                    Math.min(player.effectiveViewDistance(), player.simulationDistance()))
                    : Math.max(0, source.ticketRadius() - LoadSource.LOADING_MARGIN);
            statuses.add(new PlayerStatus(
                    player.key().toUuid(),
                    player.name(),
                    player.requestedViewDistance(),
                    player.effectiveViewDistance(),
                    appliedView,
                    player.simulationDistance(),
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
                configuredViewDistanceCap,
                platformViewDistance,
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
            // Coverage is a property of the projected physical union, not a
            // high-water mark. A strict move can retire the old source before
            // pressure allows its replacement; retaining the former radius
            // here would report chunks as backed when no PVD ticket covers
            // them and would keep seeding oversized replacements forever.
            int achieved = Math.min(
                    player.effectiveViewDistance(),
                    entry.achievedViewDistance());
            if (achieved != player.achievedViewDistance()) {
                CapturedPlayer updated = player.withAchieved(achieved);
                players.put(entry.player(), updated);
                republishDistanceSnapshot(entry.player(), updated);
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
        ResolvedPlayerDistances distances = resolvePlayerDistances(player);
        if (moonriseAdapter != null) {
            MoonriseNativeAdapter.Limits limits = moonriseAdapter.observeLimits(player);
            distances = distances.withAdditionalLimits(
                    limits.loadingMaximum(), limits.sendingMaximum());
            // Moonrise sends its radius packet from updateMaps. Raise the
            // connection ceiling before an expansion so that PVD does not
            // clamp Moonrise's one-shot packet to the previous radius.
            setConnectionCeiling(player, distances.pvdSendingCeiling());
            moonriseAdapter.apply(player, distances.loadingDistance(), distances.sendingDistance());

            // Preserve lower return-value governors which also compose with
            // Moonrise's ChunkMap method, then feed that result back into the
            // native loader rather than merely shrinking client packets.
            int platformDistance = getComposedPlatformDistance(player);
            if (platformDistance < distances.loadingDistance()
                    || platformDistance < distances.sendingDistance()) {
                distances = distances.withAdditionalLimit(platformDistance);
                moonriseAdapter.apply(player, distances.loadingDistance(), distances.sendingDistance());
            }
        } else {
            distances = distances.withAdditionalLimit(getComposedPlatformDistance(player));
        }
        int effective = distances.loadingDistance();
        int effectiveSending = distances.sendingDistance();
        int localSimulationDistance = moonriseAdapter == null
                ? currentLevelSimulationDistance(player.level())
                : clampVanillaDistance(moonriseAdapter.simulationDistance(player));
        CapturedPlayer old = players.get(key);
        int achieved = moonriseAdapter == null
                ? (old == null ? 0 : old.achievedViewDistance())
                : effective;
        CapturedPlayer captured = new CapturedPlayer(
                key,
                player.getGameProfile().name(),
                dimension,
                chunkX,
                chunkZ,
                player.requestedViewDistance(),
                effective,
                effectiveSending,
                Math.min(achieved, effective),
                localSimulationDistance
        );
        // The packet safety net stores PVD's own composable ceiling, not the
        // lower value currently chosen by an anonymous adaptive mixin. If the
        // other governor later recovers, its increase must be allowed up to
        // PVD's ceiling instead of being stuck at its previous low value.
        if (moonriseAdapter == null) {
            setConnectionCeiling(player, distances.pvdSendingCeiling());
        }
        if (captured.equals(old)) {
            publishDistanceSnapshot(player, captured, distances);
            return;
        }

        if (old != null) {
            handleStrictPlayerDelta(old, captured);
        }
        players.put(key, captured);
        if (moonriseAdapter == null) {
            planner.upsertPlayer(captured.toSnapshot());
        }
        publishDistanceSnapshot(player, captured, distances);
        if (moonriseAdapter == null && old != null
                && old.effectiveSendingDistance() != effectiveSending && player.connection != null) {
            player.connection.send(new ClientboundSetChunkCacheRadiusPacket(effectiveSending));
        }
        invalidateOutstandingPlan();
    }

    /** Only the loader JOIN callback may create a new PVD player owner. */
    private static void captureRegisteredPlayer(ServerPlayer player) {
        if (isRegistered(player)) {
            capturePlayer(player);
        }
    }

    private static boolean isRegistered(ServerPlayer player) {
        return players.containsKey(PlayerKey.of(player.getUUID()));
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
        boolean noLongerNeeded = current.effectiveViewDistance() <= current.simulationDistance();
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
            PlayerKey key = PlayerKey.of(player.getUUID());
            if (!players.containsKey(key)) {
                continue;
            }
            live.add(key);
            captureRegisteredPlayer(player);
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

    private static void recapturePlayersInLevel(ServerLevel level) {
        if (server == null || planner == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            captureRegisteredPlayer(player);
        }
    }

    private static void onOverrideChanged(PlayerKey key) {
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(key.toUuid());
        if (player != null && players.containsKey(key)) {
            captureRegisteredPlayer(player);
            refreshChunkTracking(player);
            sendEffectiveCacheRadius(player);
            planDirty = true;
        }
    }

    private static void consumeExternalLimitChanges() {
        if (server == null || externallyDirtyPlayers.isEmpty()) {
            return;
        }
        for (PlayerKey key : new ArrayList<>(externallyDirtyPlayers)) {
            if (!externallyDirtyPlayers.remove(key)) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(key.toUuid());
            if (player == null || !players.containsKey(key)) {
                continue;
            }
            captureRegisteredPlayer(player);
            refreshChunkTracking(player);
            sendEffectiveCacheRadius(player);
        }
    }

    private static void refreshAllChunkTracking() {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!isRegistered(player)) {
                continue;
            }
            refreshChunkTracking(player);
            sendEffectiveCacheRadius(player);
        }
    }

    private static void refreshPlayersInLevel(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (!isRegistered(player)) {
                continue;
            }
            refreshChunkTracking(player);
            sendEffectiveCacheRadius(player);
        }
    }

    private static void sendEffectiveCacheRadius(ServerPlayer player) {
        if (player.connection == null || !isRegistered(player)) {
            return;
        }
        CapturedPlayer captured = players.get(PlayerKey.of(player.getUUID()));
        int effective = captured == null
                ? getEffectiveViewDistance(player)
                : captured.effectiveSendingDistance();
        setConnectionCeiling(player, moonriseAdapter == null
                ? resolvePlayerDistances(player).sendingDistance()
                : effective);
        if (moonriseAdapter != null) {
            // Moonrise sends and orders this packet as part of updateMaps.
            return;
        }
        player.connection.send(new ClientboundSetChunkCacheRadiusPacket(effective));
    }

    private static void publishDistanceSnapshot(
            ServerPlayer player,
            CapturedPlayer captured,
            ResolvedPlayerDistances distances
    ) {
        if (distanceService == null) {
            return;
        }
        int backedLoading = Math.min(
                captured.effectiveViewDistance(),
                Math.max(2, Math.max(captured.simulationDistance(), captured.achievedViewDistance())));
        String governorState = currentPlan == null ? "STARTING" : currentPlan.governor().state().name();
        distanceService.publishSnapshot(new DistanceSnapshot(
                player.getUUID(),
                distances.ceiling(),
                distances.externalLoadingLimit(),
                distances.externalSendingLimit(),
                distances.loadingDistance(),
                distances.sendingDistance(),
                backedLoading,
                distances.sendingDistance(),
                moonriseAdapter == null
                        ? DistanceBackend.PRIVATE_LOADING_SOURCES
                        : DistanceBackend.NATIVE_PLAYER_LOADER,
                distances.limitGeneration(),
                governorState));
    }

    private static void republishDistanceSnapshot(PlayerKey key, CapturedPlayer captured) {
        if (server == null || distanceService == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(key.toUuid());
        if (player != null) {
            publishDistanceSnapshot(player, captured, resolvePlayerDistances(player));
        }
    }

    private static void setConnectionCeiling(ServerPlayer player, int distance) {
        if (player.connection == null) {
            return;
        }
        Connection connection = ((ServerCommonPacketListenerAccessor) (Object) player.connection)
                .playerviewdistance$getConnection();
        ((ConnectionDistanceCeiling) connection)
                .playerviewdistance$setViewDistanceCeiling(distance);
    }

    private static void refreshChunkTracking(ServerPlayer player) {
        if (!isRegistered(player)) {
            return;
        }
        if (moonriseAdapter != null) {
            moonriseAdapter.refresh(player);
            return;
        }
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

    private static int getComposedPlatformDistance(ServerPlayer player) {
        ChunkMap chunkMap = ((ServerChunkCacheAccessor) player.level().getChunkSource())
                .playerviewdistance$getChunkMap();
        return clampVanillaDistance(
                ((ChunkMapInvoker) chunkMap).playerviewdistance$getPlayerViewDistance(player));
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
            CapturedPlayer captured = players.get(PlayerKey.of(player.getUUID()));
            int trackingRadius = (captured == null
                    ? getEffectiveViewDistance(player)
                    : captured.effectiveSendingDistance()) + 1;
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
                PlatformEnvironment.logger().debug(
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
                    || player.effectiveViewDistance() <= player.simulationDistance()
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
        resetAchievedCoverage(null);
        if (changed) {
            appliedRevision++;
        }
    }

    private static void removePrivateSourcesInDimension(String dimension) {
        boolean changed = false;
        for (AppliedSource source : List.copyOf(appliedSources.values())) {
            if (!source.dimension().equals(dimension)) {
                continue;
            }
            appliedSources.remove(source.owner());
            removeTicket(source, source.area());
            changed = true;
        }
        resetAchievedCoverage(dimension);
        if (changed) {
            appliedRevision++;
        }
    }

    private static void resetAchievedCoverage(String dimension) {
        for (Map.Entry<PlayerKey, CapturedPlayer> entry : players.entrySet()) {
            CapturedPlayer player = entry.getValue();
            if (dimension != null && !dimension.equals(player.dimension())) {
                continue;
            }
            int floor = Math.min(player.effectiveViewDistance(), player.simulationDistance());
            if (player.achievedViewDistance() != floor) {
                CapturedPlayer reset = player.withAchieved(floor);
                entry.setValue(reset);
                if (planner != null) {
                    planner.upsertPlayer(reset.toSnapshot());
                }
                republishDistanceSnapshot(entry.getKey(), reset);
            }
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

    private static int currentLevelViewDistance(ServerLevel level) {
        Integer pending = pendingLevelViewDistances.get(dimensionId(level));
        if (pending != null) {
            return pending;
        }
        ChunkMap chunkMap = ((ServerChunkCacheAccessor) level.getChunkSource())
                .playerviewdistance$getChunkMap();
        return clampVanillaDistance(
                ((ChunkMapInvoker) chunkMap).playerviewdistance$getServerViewDistance());
    }

    private static int currentLevelSimulationDistance(ServerLevel level) {
        Integer pending = pendingLevelSimulationDistances.get(dimensionId(level));
        if (pending != null) {
            return pending;
        }
        if (moonriseAdapter != null) {
            return clampVanillaDistance(moonriseAdapter.simulationDistance(level));
        }
        ChunkMap chunkMap = ((ServerChunkCacheAccessor) level.getChunkSource())
                .playerviewdistance$getChunkMap();
        Object distanceManager = chunkMap.getDistanceManager();
        return clampVanillaDistance(
                ((DistanceManagerAccessor) distanceManager)
                        .playerviewdistance$getSimulationDistance());
    }

    private static void updateAchieved(PlayerKey owner, int ticketRadius) {
        CapturedPlayer player = players.get(owner);
        if (player != null) {
            int achieved = Math.min(player.effectiveViewDistance(),
                    Math.max(player.achievedViewDistance(), ticketRadius - LoadSource.LOADING_MARGIN));
            players.put(owner, player.withAchieved(achieved));
            republishDistanceSnapshot(owner, players.get(owner));
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
                PlatformEnvironment.logger().error("Could not atomically persist PVD overrides", failure);
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
            PlatformEnvironment.logger().error("Could not persist PVD overrides during shutdown", failure);
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
        PlatformEnvironment.logger().debug(
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

    private static Integer minimumLimit(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return Math.min(first, second);
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
            int effectiveSendingDistance,
            int achievedViewDistance,
            int simulationDistance
    ) {
        private PlayerSnapshot toSnapshot() {
            return new PlayerSnapshot(
                    key, dimension, chunkX, chunkZ,
                    effectiveViewDistance, achievedViewDistance, simulationDistance);
        }

        private CapturedPlayer withAchieved(int achieved) {
            return new CapturedPlayer(key, name, dimension, chunkX, chunkZ,
                    requestedViewDistance, effectiveViewDistance, effectiveSendingDistance,
                    achieved, simulationDistance);
        }
    }

    private record ResolvedPlayerDistances(
            int ceiling,
            Integer externalLoadingLimit,
            Integer externalSendingLimit,
            int loadingDistance,
            int sendingDistance,
            long limitGeneration
    ) {
        private int pvdSendingCeiling() {
            return DistanceComposer.compose(
                    ceiling, externalLoadingLimit, externalSendingLimit).sendingDistance();
        }

        private ResolvedPlayerDistances withAdditionalLimit(int limit) {
            int clamped = clampVanillaDistance(limit);
            return new ResolvedPlayerDistances(
                    ceiling,
                    externalLoadingLimit,
                    externalSendingLimit,
                    Math.min(loadingDistance, clamped),
                    Math.min(sendingDistance, clamped),
                    limitGeneration);
        }

        private ResolvedPlayerDistances withAdditionalLimits(
                Integer loadingLimit,
                Integer sendingLimit
        ) {
            Integer combinedLoading = minimumLimit(externalLoadingLimit, loadingLimit);
            Integer combinedSending = minimumLimit(externalSendingLimit, sendingLimit);
            ComposedDistance composed = DistanceComposer.compose(
                    ceiling, combinedLoading, combinedSending);
            return new ResolvedPlayerDistances(
                    ceiling,
                    combinedLoading,
                    combinedSending,
                    composed.loadingDistance(),
                    composed.sendingDistance(),
                    limitGeneration);
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
            int simulationDistance,
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
            int platformViewDistance,
            int simulationDistance
    ) {
    }
}
