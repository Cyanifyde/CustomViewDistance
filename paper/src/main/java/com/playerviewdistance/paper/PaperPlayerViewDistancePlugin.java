package com.playerviewdistance.paper;

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import com.playerviewdistance.api.DistanceBackend;
import com.playerviewdistance.api.DistanceSnapshot;
import com.playerviewdistance.api.PlayerViewDistanceApi;
import com.playerviewdistance.api.PlayerViewDistanceService;
import com.playerviewdistance.config.ConfigData;
import com.playerviewdistance.config.ConfigRepository;
import com.playerviewdistance.config.OverrideRepository;
import com.playerviewdistance.config.ViewDistancePolicy;
import com.playerviewdistance.core.ChunkCells;
import com.playerviewdistance.core.DimensionMetrics;
import com.playerviewdistance.core.GovernorDecision;
import com.playerviewdistance.core.LoadSource;
import com.playerviewdistance.core.NativePlanResult;
import com.playerviewdistance.core.NativePlannerService;
import com.playerviewdistance.core.PlannerMetrics;
import com.playerviewdistance.core.PlayerKey;
import com.playerviewdistance.core.PlayerSnapshot;
import com.playerviewdistance.runtime.ComposedDistance;
import com.playerviewdistance.runtime.DefaultPlayerViewDistanceService;
import com.playerviewdistance.runtime.DistanceComposer;
import com.playerviewdistance.runtime.ResolvedLimits;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** API-only Paper/Folia implementation backed by the native player chunk loader. */
public final class PaperPlayerViewDistancePlugin extends JavaPlugin implements Listener {
    private static final int ENGINE_MINIMUM = 2;
    private static final int ENGINE_MAXIMUM = 32;

    private final ConcurrentHashMap<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerTask> playerTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> overrides = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean shutdownFinished = new AtomicBoolean();
    private final Object schedulerLifecycle = new Object();
    private int activeSchedulerCalls;

    private ConfigRepository configRepository;
    private OverrideRepository overrideRepository;
    private NativePlannerService planner;
    private DefaultPlayerViewDistanceService distanceService;
    private ScheduledTask globalTask;
    private volatile PlannerMetrics latestMetrics;
    private volatile long requestedGeneration;
    private volatile Throwable reportedPlannerFailure;

    @Override
    public void onEnable() {
        stopping.set(false);
        shutdownFinished.set(false);
        configRepository = new ConfigRepository(getDataFolder().toPath());
        ConfigRepository.LoadOutcome config = configRepository.initialize();
        if (!config.success()) {
            getLogger().severe(config.message());
        }

        overrideRepository = new OverrideRepository(getDataFolder().toPath());
        OverrideRepository.LoadOutcome loadedOverrides = overrideRepository.load();
        if (loadedOverrides.success()) {
            for (Map.Entry<PlayerKey, Integer> entry : loadedOverrides.overrides().entrySet()) {
                overrides.put(entry.getKey().toUuid(), entry.getValue());
            }
        } else {
            getLogger().severe(loadedOverrides.message());
        }

        planner = new NativePlannerService();
        distanceService = new DefaultPlayerViewDistanceService(this::markPlayerDirty);
        PlayerViewDistanceApi.install(distanceService);
        getServer().getServicesManager().register(
                PlayerViewDistanceService.class, distanceService, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);

        for (Player player : getServer().getOnlinePlayers()) {
            schedulePlayer(player);
        }
        globalTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
                this, ignored -> globalTick(), 1L, 1L);
        getLogger().info("PVD is running.");
    }

    @Override
    public void onDisable() {
        prepareShutdown();
        finishShutdown();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == this) prepareShutdown();
    }

    private void prepareShutdown() {
        List<PlayerTask> tasks;
        synchronized (schedulerLifecycle) {
            if (!stopping.compareAndSet(false, true)) return;
            tasks = new ArrayList<>(playerTasks.values());
            playerTasks.clear();
        }
        if (globalTask != null) globalTask.cancel();
        for (PlayerTask task : tasks) task.cancel();
        if (!awaitSchedulerDrain(Duration.ofSeconds(5))) {
            getLogger().severe("PVD could not drain every scheduler callback before shutdown.");
        }
        restoreOnlinePlayers(new HashMap<>(players));
    }

    private void finishShutdown() {
        if (!shutdownFinished.compareAndSet(false, true)) return;
        saveOverridesSynchronously();
        if (planner != null) {
            planner.close(Duration.ofSeconds(5));
        }
        if (distanceService != null) {
            getServer().getServicesManager().unregister(PlayerViewDistanceService.class, distanceService);
            PlayerViewDistanceApi.uninstall(distanceService);
            distanceService.close();
        }
        players.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        schedulePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClientOptions(PlayerClientOptionsChangeEvent event) {
        if (event.hasViewDistanceChanged()) markPlayerDirty(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChanged(PlayerChangedWorldEvent event) {
        markPlayerDirty(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        markPlayerDirty(event.getPlayer().getUniqueId());
    }

    private void schedulePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        synchronized (schedulerLifecycle) {
            if (stopping.get()) return;
            PlayerTask existing = playerTasks.get(playerId);
            if (existing != null && existing.player() == player) return;
            if (existing != null) {
                playerTasks.remove(playerId, existing);
                existing.cancel();
                clearPlayerState(playerId);
            }

            PlayerTask registration = new PlayerTask(player);
            ScheduledTask scheduled = player.getScheduler().runAtFixedRate(
                    this,
                    ignored -> tickPlayer(player),
                    () -> retirePlayer(playerId, registration),
                    1L,
                    1L);
            if (scheduled == null) return;
            registration.attach(scheduled);
            playerTasks.put(playerId, registration);
        }
    }

    private void tickPlayer(Player player) {
        if (!enterSchedulerCall()) return;
        try {
            tickPlayerActive(player);
        } finally {
            exitSchedulerCall();
        }
    }

    private void tickPlayerActive(Player player) {
        if (!player.isOnline()) return;
        long started = System.nanoTime();
        UUID playerId = player.getUniqueId();
        PlayerState previous = players.get(playerId);

        int observedLoadingRaw = player.getViewDistance();
        int observedSendingRaw = player.getSendViewDistance();
        int worldLoading = normalizeDistance(player.getWorld().getViewDistance(), ENGINE_MAXIMUM);
        int worldSending = normalizeDistance(player.getWorld().getSendViewDistance(), worldLoading);
        int observedLoading = normalizeDistance(observedLoadingRaw, worldLoading);
        int observedSending = normalizeDistance(observedSendingRaw, worldSending);
        int clientRequest = normalizeDistance(player.getClientViewDistance(), ENGINE_MAXIMUM);
        int ceiling = ViewDistancePolicy.effectiveDistance(
                clientRequest, overrides.get(playerId), configRepository.current(), worldLoading);
        int inheritedSendingMaximum = Math.min(
                worldSending, Math.min(clientRequest, observedLoading));

        IntentObservation loadingObservation = observeIntent(
                observedLoadingRaw,
                previous == null ? null : Integer.valueOf(previous.observedLoadingRaw()),
                previous == null ? null : previous.pendingPvdLoadingRaw(),
                previous == null ? null : Integer.valueOf(previous.externalLoadingRaw()),
                ceiling,
                worldLoading);
        IntentObservation sendingObservation = observeIntent(
                observedSendingRaw,
                previous == null ? null : Integer.valueOf(previous.observedSendingRaw()),
                previous == null ? null : previous.pendingPvdSendingRaw(),
                previous == null ? null : Integer.valueOf(previous.externalSendingRaw()),
                ceiling,
                inheritedSendingMaximum);
        int externalLoadingRaw = loadingObservation.externalRaw();
        int externalSendingRaw = sendingObservation.externalRaw();
        int externalLoading = normalizeDistance(externalLoadingRaw, worldLoading);
        int externalSending = normalizeDistance(externalSendingRaw, worldSending);

        ResolvedLimits cooperative = distanceService.resolve(playerId);
        Integer combinedLoadingLimit = minimum(Integer.valueOf(externalLoading), cooperative.loadingMaximum());
        Integer combinedSendingLimit = minimum(Integer.valueOf(externalSending), cooperative.sendingMaximum());
        ComposedDistance desired = DistanceComposer.compose(
                ceiling, combinedLoadingLimit, combinedSendingLimit);

        int approvedLoading = observedLoading;
        NativePlanResult plan = planner.latestResult();
        if (plan != null
                && plan.playerGeneration() == planner.playerGeneration()) {
            // The global-region scheduler and a player's entity scheduler have
            // no guaranteed ordering, especially on Folia. A newer metrics
            // request can therefore be queued between publication and this
            // entity turn. The published result is still state-safe when its
            // player generation matches: NativePlannerService never publishes
            // a result superseded while it was being computed. At worst its
            // pressure sample is one scheduler turn old and the next immutable
            // result replaces it; requiring the metrics generation to be the
            // latest one can starve every expansion forever.
            Integer approved = plan.approvedLoadingDistances().get(PlayerKey.of(playerId));
            if (approved != null) approvedLoading = Math.max(observedLoading, approved.intValue());
        }
        int targetLoading = desired.loadingDistance() < observedLoading
                ? desired.loadingDistance()
                : Math.min(desired.loadingDistance(), approvedLoading);
        int targetSending = Math.min(desired.sendingDistance(), targetLoading);

        Integer pendingPvdLoadingRaw = loadingObservation.pendingPvdRaw();
        Integer pendingPvdSendingRaw = sendingObservation.pendingPvdRaw();
        boolean mutateLoading = mutationRequired(
                targetLoading, observedLoading, pendingPvdLoadingRaw);
        boolean mutateSending = mutationRequired(
                targetSending, observedSending, pendingPvdSendingRaw);
        long applicationStarted = System.nanoTime();
        if (mutateSending && targetSending < observedSending) {
            player.setSendViewDistance(targetSending);
            pendingPvdSendingRaw = pendingAfterMutation(targetSending, observedSending);
        }
        if (mutateLoading) {
            player.setViewDistance(targetLoading);
            pendingPvdLoadingRaw = pendingAfterMutation(targetLoading, observedLoading);
        }
        if (mutateSending && targetSending >= observedSending) {
            player.setSendViewDistance(targetSending);
            pendingPvdSendingRaw = pendingAfterMutation(targetSending, observedSending);
        }
        if (mutateLoading) {
            long changedCells = ChunkCells.changedCells(
                    observedLoading + LoadSource.LOADING_MARGIN,
                    targetLoading + LoadSource.LOADING_MARGIN);
            planner.recordApplication(changedCells, System.nanoTime() - applicationStarted);
        }

        int chunkX = player.getLocation().getBlockX() >> 4;
        int chunkZ = player.getLocation().getBlockZ() >> 4;
        int simulationDistance = normalizeDistance(player.getSimulationDistance(), ENGINE_MINIMUM);
        int sentChunkCount = previous == null ? 0 : previous.sentChunkCount();
        if ((getServer().getCurrentTick() & 15) == 0) {
            sentChunkCount = player.getSentChunkKeys().size();
        }
        PlayerState current = new PlayerState(
                playerId,
                player.getName(),
                player.getWorld().getKey().toString(),
                chunkX,
                chunkZ,
                clientRequest,
                ceiling,
                externalLoadingRaw,
                externalSendingRaw,
                externalLoading,
                externalSending,
                cooperative.loadingMaximum(),
                cooperative.sendingMaximum(),
                desired.loadingDistance(),
                desired.sendingDistance(),
                observedLoading,
                observedSending,
                simulationDistance,
                pendingPvdLoadingRaw,
                pendingPvdSendingRaw,
                observedLoadingRaw,
                observedSendingRaw,
                cooperative.generation(),
                sentChunkCount);
        players.put(playerId, current);
        planner.upsertPlayer(new PlayerSnapshot(
                PlayerKey.of(playerId), current.dimension(), chunkX, chunkZ,
                current.desiredLoading(), current.appliedLoading(), simulationDistance));
        publishSnapshot(current);

        long elapsed = System.nanoTime() - started;
        if (elapsed > 5_000_000L) {
            getLogger().fine("PVD player capture exceeded 5ms for " + playerId);
        }
    }

    private void globalTick() {
        if (!enterSchedulerCall()) return;
        try {
            globalTickActive();
        } finally {
            exitSchedulerCall();
        }
    }

    private void globalTickActive() {
        if (planner == null) return;
        Server server = getServer();
        long[] tickTimes = server.getTickTimes();
        long[] recent = tail(tickTimes, 200);
        long tickInterval = Math.max(1L, (long) (1_000_000_000.0
                / Math.max(1.0f, server.getServerTickManager().getTickRate())));
        long average = Math.max(0L, (long) (server.getAverageTickTime() * 1_000_000.0));
        List<DimensionMetrics> dimensions = new ArrayList<>();
        int loaded = 0;
        for (World world : server.getWorlds()) {
            int count = Math.max(0, world.getChunkCount());
            loaded += count;
            dimensions.add(new DimensionMetrics(world.getKey().toString(), 0, count));
        }
        latestMetrics = new PlannerMetrics(
                tickInterval,
                average,
                average,
                recent,
                server.getServerTickManager().isFrozen(),
                server.getServerTickManager().isSprinting(),
                0,
                loaded,
                dimensions,
                players.size(),
                0,
                0,
                0,
                0);
        requestedGeneration = planner.requestPlan(latestMetrics);
        Throwable failure = planner.lastFailure();
        if (failure != null && failure != reportedPlannerFailure) {
            reportedPlannerFailure = failure;
            getLogger().severe("PVD native planner failed; optional expansion is paused: " + failure);
        }
    }

    private boolean enterSchedulerCall() {
        synchronized (schedulerLifecycle) {
            if (stopping.get()) return false;
            activeSchedulerCalls++;
            return true;
        }
    }

    private void exitSchedulerCall() {
        synchronized (schedulerLifecycle) {
            activeSchedulerCalls--;
            if (activeSchedulerCalls == 0) schedulerLifecycle.notifyAll();
        }
    }

    private boolean awaitSchedulerDrain(Duration timeout) {
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        synchronized (schedulerLifecycle) {
            while (activeSchedulerCalls != 0 && remaining > 0) {
                try {
                    TimeUnit.NANOSECONDS.timedWait(schedulerLifecycle, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remaining = deadline - System.nanoTime();
            }
            return activeSchedulerCalls == 0;
        }
    }

    private void publishSnapshot(PlayerState state) {
        distanceService.publishSnapshot(new DistanceSnapshot(
                state.playerId(),
                state.ceiling(),
                minimum(Integer.valueOf(state.externalLoading()),
                        state.cooperativeLoadingLimit()),
                minimum(Integer.valueOf(state.externalSending()),
                        state.cooperativeSendingLimit()),
                state.desiredLoading(),
                state.desiredSending(),
                state.appliedLoading(),
                state.appliedSending(),
                DistanceBackend.NATIVE_PLAYER_LOADER,
                state.limitGeneration(),
                governorState()));
    }

    private String governorState() {
        NativePlanResult plan = planner == null ? null : planner.latestResult();
        return plan == null ? "STARTING" : plan.governor().state().name();
    }

    private void markPlayerDirty(UUID playerId) {
        // Every live player is captured on its next entity-scheduler turn.
        // The callback can originate on any thread and deliberately touches no Bukkit state.
    }

    private void removePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerTask task;
        synchronized (schedulerLifecycle) {
            task = playerTasks.get(playerId);
            if (task != null && task.player() != player) return;
            if (task != null) playerTasks.remove(playerId, task);
        }
        if (task != null) task.cancel();
        clearPlayerState(playerId);
    }

    private void retirePlayer(UUID playerId, PlayerTask registration) {
        if (!enterSchedulerCall()) return;
        try {
            if (playerTasks.remove(playerId, registration)) clearPlayerState(playerId);
        } finally {
            exitSchedulerCall();
        }
    }

    private void clearPlayerState(UUID playerId) {
        players.remove(playerId);
        if (planner != null) planner.removePlayer(PlayerKey.of(playerId));
        if (distanceService != null) distanceService.removeSnapshot(playerId);
    }

    private void restoreOnlinePlayers(Map<UUID, PlayerState> states) {
        List<Player> online = new ArrayList<>(getServer().getOnlinePlayers());
        CountDownLatch pending = new CountDownLatch(online.size());
        for (Player player : online) {
            PlayerState state = states.get(player.getUniqueId());
            AtomicBoolean completed = new AtomicBoolean();
            Runnable complete = () -> {
                if (completed.compareAndSet(false, true)) pending.countDown();
            };
            Runnable restore = () -> {
                try {
                    if (state != null && player.isOnline()) restorePlayer(player, state);
                } finally {
                    complete.run();
                }
            };
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                restore.run();
            } else if (!isEnabled()
                    || !player.getScheduler().execute(this, restore, complete, 0L)) {
                complete.run();
            }
        }
        try {
            if (!pending.await(2, TimeUnit.SECONDS)) {
                getLogger().severe("PVD could not restore " + pending.getCount()
                        + " player distance state(s) before shutdown.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void restorePlayer(Player player, PlayerState state) {
        int currentSending = normalizeDistance(
                player.getSendViewDistance(), player.getWorld().getSendViewDistance());
        int restoredSending = normalizeDistance(
                state.externalSendingRaw(), player.getWorld().getSendViewDistance());
        if (restoredSending < currentSending) player.setSendViewDistance(state.externalSendingRaw());
        player.setViewDistance(state.externalLoadingRaw());
        if (restoredSending >= currentSending) player.setSendViewDistance(state.externalSendingRaw());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!sender.hasPermission("playerviewdistance.admin")) {
            sender.sendMessage("You do not have permission to use PVD.");
            return true;
        }
        if (arguments.length == 0) {
            sender.sendMessage("Usage: /pvd <set|reset|list|reload|status>");
            return true;
        }
        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "set" -> commandSet(sender, arguments);
            case "reset" -> commandReset(sender, arguments);
            case "list" -> commandList(sender);
            case "reload" -> commandReload(sender);
            case "status" -> commandStatus(sender);
            default -> sender.sendMessage("Usage: /pvd <set|reset|list|reload|status>");
        }
        return true;
    }

    private void commandSet(CommandSender sender, String[] arguments) {
        if (arguments.length != 3) {
            sender.sendMessage("Usage: /pvd set <player> <2..32>");
            return;
        }
        Player player = getServer().getPlayerExact(arguments[1]);
        if (player == null) {
            sender.sendMessage("Player is not online: " + arguments[1]);
            return;
        }
        int distance;
        try {
            distance = Integer.parseInt(arguments[2]);
        } catch (NumberFormatException invalid) {
            sender.sendMessage("Distance must be an integer in [2, 32].");
            return;
        }
        if (distance < ENGINE_MINIMUM || distance > ENGINE_MAXIMUM) {
            sender.sendMessage("Distance must be an integer in [2, 32].");
            return;
        }
        overrides.put(player.getUniqueId(), Integer.valueOf(distance));
        persistOverrides();
        markPlayerDirty(player.getUniqueId());
        sender.sendMessage("Set to " + distance + ".");
    }

    private void commandReset(CommandSender sender, String[] arguments) {
        if (arguments.length != 2) {
            sender.sendMessage("Usage: /pvd reset <player>");
            return;
        }
        Player player = getServer().getPlayerExact(arguments[1]);
        if (player == null) {
            sender.sendMessage("Player is not online: " + arguments[1]);
            return;
        }
        overrides.remove(player.getUniqueId());
        persistOverrides();
        markPlayerDirty(player.getUniqueId());
        sender.sendMessage("Reset.");
    }

    private void commandList(CommandSender sender) {
        if (players.isEmpty()) {
            sender.sendMessage("No players.");
            return;
        }
        List<PlayerState> states = new ArrayList<>(players.values());
        double average = states.stream().mapToInt(PlayerState::appliedSending).average().orElse(0.0);
        sender.sendMessage("Average is " + formatDistance(average) + ".");
        states.sort(Comparator.comparingInt(PlayerState::appliedSending)
                .reversed()
                .thenComparing(PlayerState::name, String.CASE_INSENSITIVE_ORDER));
        for (PlayerState state : states.subList(0, Math.min(10, states.size()))) {
            sender.sendMessage("Player " + state.name() + " has " + state.appliedSending() + ".");
        }
    }

    private void commandReload(CommandSender sender) {
        getServer().getAsyncScheduler().runNow(this, ignored -> {
            ConfigRepository.LoadOutcome outcome = configRepository.reload();
            reply(sender, outcome.success() ? "Reloaded." : outcome.message());
            if (outcome.success()) {
                for (UUID playerId : players.keySet()) markPlayerDirty(playerId);
            }
        });
    }

    private void commandStatus(CommandSender sender) {
        if (sender instanceof Player player) {
            PlayerState state = players.get(player.getUniqueId());
            int distance = state == null
                    ? normalizeDistance(player.getSendViewDistance(), player.getWorld().getSendViewDistance())
                    : state.appliedSending();
            sender.sendMessage("Currently at " + distance + ".");
            return;
        }
        commandList(sender);
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            player.getScheduler().execute(this, () -> sender.sendMessage(message), null, 0L);
        } else {
            getServer().getGlobalRegionScheduler().execute(this, () -> sender.sendMessage(message));
        }
    }

    private static String formatDistance(double distance) {
        if (distance == Math.rint(distance)) {
            return Integer.toString((int) distance);
        }
        return String.format(Locale.ROOT, "%.1f", distance);
    }

    private void persistOverrides() {
        if (planner == null || overrideRepository == null) return;
        Map<PlayerKey, Integer> snapshot = overrideSnapshot();
        planner.requestPersistence(() -> {
            try {
                overrideRepository.save(snapshot);
            } catch (IOException failure) {
                getLogger().severe("Could not persist PVD overrides: " + failure.getMessage());
            }
        });
    }

    private void saveOverridesSynchronously() {
        if (overrideRepository == null) return;
        try {
            overrideRepository.save(overrideSnapshot());
        } catch (IOException failure) {
            getLogger().severe("Could not persist PVD overrides during shutdown: " + failure.getMessage());
        }
    }

    private Map<PlayerKey, Integer> overrideSnapshot() {
        Map<PlayerKey, Integer> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> entry : overrides.entrySet()) {
            snapshot.put(PlayerKey.of(entry.getKey()), entry.getValue());
        }
        return snapshot;
    }

    private static IntentObservation observeIntent(
            int observedRaw,
            Integer previousObservedRaw,
            Integer pendingPvdRaw,
            Integer previousExternalRaw,
            int pvdCeiling,
            int inheritedMaximum) {
        if (previousObservedRaw == null || previousExternalRaw == null) {
            // Bukkit exposes only the effective value. On first capture it is
            // commonly bounded by the client request (sending) or world value
            // (loading), even though the raw per-player intent is still -1.
            // Only a value strictly below every inherited boundary proves a
            // pre-existing per-player lower limit.
            int inferredExternalRaw = observedRaw < inheritedMaximum ? observedRaw : -1;
            return new IntentObservation(inferredExternalRaw, null);
        }
        if (pendingPvdRaw == null) {
            return observedRaw == previousObservedRaw.intValue() || observedRaw == pvdCeiling
                    ? new IntentObservation(previousExternalRaw.intValue(), null)
                    : new IntentObservation(observedRaw, null);
        }

        int pending = pendingPvdRaw.intValue();
        if (observedRaw == pending) {
            return new IntentObservation(previousExternalRaw.intValue(), null);
        }
        if (observedRaw == pvdCeiling) {
            // A client-options, override, config, or world-cap transition can
            // move Paper's effective value before this task observes the raw
            // target change. It is PVD/platform state, not foreign intent.
            // Retain an obsolete pending value so mutationRequired replaces
            // its holder target even when the new effective value already
            // equals the desired ceiling.
            return new IntentObservation(previousExternalRaw.intValue(), pendingPvdRaw);
        }

        // Paper exposes Moonrise's last effective distance, while the setter
        // changes a holder target that the native loader reaches later. Any
        // observation on the old-effective -> pending-target segment can
        // therefore be progress (or a still-unapplied request), not a new
        // third-party limit. Retain the external intent and the pending target.
        // A value outside that segment cannot be caused by this PVD mutation,
        // so an uncooperative plugin's change is adopted on this owning turn.
        int lower = Math.min(previousObservedRaw.intValue(), pending);
        int upper = Math.max(previousObservedRaw.intValue(), pending);
        if (observedRaw >= lower && observedRaw <= upper) {
            return new IntentObservation(previousExternalRaw.intValue(), pendingPvdRaw);
        }
        return new IntentObservation(observedRaw, null);
    }

    private static boolean mutationRequired(
            int target, int observed, Integer pendingPvdRaw) {
        if (pendingPvdRaw != null) {
            return pendingPvdRaw.intValue() != target;
        }
        return target != observed;
    }

    private static Integer pendingAfterMutation(int target, int observed) {
        return target == observed ? null : Integer.valueOf(target);
    }

    private static int normalizeDistance(int value, int fallback) {
        int resolved = value < ENGINE_MINIMUM ? fallback : value;
        return Math.max(ENGINE_MINIMUM, Math.min(ENGINE_MAXIMUM, resolved));
    }

    private static Integer minimum(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Integer.valueOf(Math.min(first.intValue(), second.intValue()));
    }

    private static long[] tail(long[] values, int limit) {
        int count = Math.min(values.length, limit);
        long[] result = new long[count];
        System.arraycopy(values, values.length - count, result, 0, count);
        return result;
    }

    private record PlayerState(
            UUID playerId,
            String name,
            String dimension,
            int chunkX,
            int chunkZ,
            int clientRequest,
            int ceiling,
            int externalLoadingRaw,
            int externalSendingRaw,
            int externalLoading,
            int externalSending,
            Integer cooperativeLoadingLimit,
            Integer cooperativeSendingLimit,
            int desiredLoading,
            int desiredSending,
            int appliedLoading,
            int appliedSending,
            int simulationDistance,
            Integer pendingPvdLoadingRaw,
            Integer pendingPvdSendingRaw,
            int observedLoadingRaw,
            int observedSendingRaw,
            long limitGeneration,
            int sentChunkCount
    ) {
    }

    private record IntentObservation(int externalRaw, Integer pendingPvdRaw) {
    }

    private static final class PlayerTask {
        private final Player player;
        private ScheduledTask task;

        private PlayerTask(Player player) {
            this.player = player;
        }

        private Player player() {
            return player;
        }

        private void attach(ScheduledTask task) {
            this.task = task;
        }

        private void cancel() {
            ScheduledTask scheduled = task;
            if (scheduled != null && !scheduled.isCancelled()) scheduled.cancel();
        }
    }
}
