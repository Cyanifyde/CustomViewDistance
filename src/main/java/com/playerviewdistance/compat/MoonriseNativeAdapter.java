package com.playerviewdistance.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bridges PVD to Moonrise's native per-player loader without linking PVD's
 * production artifact against Moonrise. Moonrise deliberately replaces the
 * vanilla player ticket and tracking paths, so using its holder is both less
 * work and more correct than layering PVD tickets over its chunk system.
 */
public final class MoonriseNativeAdapter {
    private static final String PLAYER_PATCH =
            "ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer";
    private static final String LEVEL_PATCH =
            "ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel";
    private static final String HOLDER =
            "ca.spottedleaf.moonrise.patches.chunk_system.player."
                    + "RegionizedPlayerChunkLoader$ViewDistanceHolder";
    private static final String DISTANCES =
            "ca.spottedleaf.moonrise.patches.chunk_system.player."
                    + "RegionizedPlayerChunkLoader$ViewDistances";
    private static final String PLATFORM_HOOKS = "ca.spottedleaf.moonrise.common.PlatformHooks";

    private final Method playerHolderGetter;
    private final Method levelHolderGetter;
    private final Method distancesGetter;
    private final Method loadGetter;
    private final Method sendGetter;
    private final Method tickGetter;
    private final Method loadSetter;
    private final Method sendSetter;
    private final Method platformViewGetter;
    private final Method platformSendGetter;
    private final Method platformTickGetter;
    private final Method updateMaps;
    private final Object platformHooks;
    private final Map<UUID, PlayerIntent> intents = new HashMap<>();

    private MoonriseNativeAdapter(
            Method playerHolderGetter,
            Method levelHolderGetter,
            Method distancesGetter,
            Method loadGetter,
            Method sendGetter,
            Method tickGetter,
            Method loadSetter,
            Method sendSetter,
            Method platformViewGetter,
            Method platformSendGetter,
            Method platformTickGetter,
            Method updateMaps,
            Object platformHooks
    ) {
        this.playerHolderGetter = playerHolderGetter;
        this.levelHolderGetter = levelHolderGetter;
        this.distancesGetter = distancesGetter;
        this.loadGetter = loadGetter;
        this.sendGetter = sendGetter;
        this.tickGetter = tickGetter;
        this.loadSetter = loadSetter;
        this.sendSetter = sendSetter;
        this.platformViewGetter = platformViewGetter;
        this.platformSendGetter = platformSendGetter;
        this.platformTickGetter = platformTickGetter;
        this.updateMaps = updateMaps;
        this.platformHooks = platformHooks;
    }

    /** Performs the startup hook audit before any player state is changed. */
    public static MoonriseNativeAdapter create() {
        try {
            ClassLoader loader = MoonriseNativeAdapter.class.getClassLoader();
            Class<?> playerPatch = Class.forName(PLAYER_PATCH, false, loader);
            Class<?> levelPatch = Class.forName(LEVEL_PATCH, false, loader);
            Class<?> holder = Class.forName(HOLDER, false, loader);
            Class<?> distances = Class.forName(DISTANCES, false, loader);
            Class<?> hooks = Class.forName(PLATFORM_HOOKS, false, loader);

            Method hooksGetter = hooks.getMethod("get");
            Object platformHooks = hooksGetter.invoke(null);
            if (platformHooks == null) {
                throw new ReflectiveOperationException("Moonrise PlatformHooks.get() returned null");
            }

            return new MoonriseNativeAdapter(
                    playerPatch.getMethod("moonrise$getViewDistanceHolder"),
                    levelPatch.getMethod("moonrise$getViewDistanceHolder"),
                    holder.getMethod("getViewDistances"),
                    distances.getMethod("loadViewDistance"),
                    distances.getMethod("sendViewDistance"),
                    distances.getMethod("tickViewDistance"),
                    holder.getMethod("setLoadViewDistance", int.class),
                    holder.getMethod("setSendViewDistance", int.class),
                    hooks.getMethod("getViewDistance", ServerPlayer.class),
                    hooks.getMethod("getSendViewDistance", ServerPlayer.class),
                    hooks.getMethod("getTickViewDistance", ServerPlayer.class),
                    hooks.getMethod("updateMaps", ServerLevel.class, ServerPlayer.class),
                    platformHooks
            );
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException(
                    "Moonrise is installed, but its per-player loader hooks do not match PVD. "
                            + "Update PVD/Moonrise or remove one of them.", failure);
        }
    }

    /**
     * Captures changes not made by PVD and returns all Moonrise lower limits.
     * A raw value of -1 means inherit and therefore contributes no limit.
     */
    public Limits observeLimits(ServerPlayer player) {
        RawDistances playerRaw = readRaw(playerHolder(player));
        RawDistances worldRaw = readRaw(levelHolder(player.level()));
        PlayerIntent state = intents.get(player.getUUID());
        if (state == null) {
            state = new PlayerIntent(playerRaw.load(), playerRaw.send());
            intents.put(player.getUUID(), state);
        } else if (!state.applying) {
            if (state.lastAppliedLoad == null || playerRaw.load() != state.lastAppliedLoad) {
                state.externalLoad = playerRaw.load();
            }
            if (state.lastAppliedSend == null || playerRaw.send() != state.lastAppliedSend) {
                state.externalSend = playerRaw.send();
            }
        }

        Integer loading = minimum(
                loadingLimit(state.externalLoad),
                loadingLimit(worldRaw.load()));
        Integer sending = minimum(
                sendingLimit(state.externalSend),
                sendingLimit(worldRaw.send()));
        return new Limits(loading, sending);
    }

    /** Applies a fully composed target through Moonrise and returns its effective radii. */
    public AppliedDistances apply(ServerPlayer player, int loadingDistance, int sendingDistance) {
        int targetLoad = clamp(loadingDistance) + 1;
        int targetSend = Math.min(clamp(sendingDistance), targetLoad - 1);
        PlayerIntent state = intents.get(player.getUUID());
        if (state == null) {
            observeLimits(player);
            state = intents.get(player.getUUID());
        }

        Object holder = playerHolder(player);
        RawDistances current = readRaw(holder);
        if (current.load() != targetLoad || current.send() != targetSend) {
            int currentLoading = effectiveViewDistance(player);
            int currentSending = effectiveSendDistance(player);
            state.applying = true;
            try {
                boolean shrinking = targetLoad - 1 < currentLoading || targetSend < currentSending;
                if (shrinking) {
                    invokeVoid(sendSetter, holder, targetSend);
                    invokeVoid(loadSetter, holder, targetLoad);
                } else {
                    invokeVoid(loadSetter, holder, targetLoad);
                    invokeVoid(sendSetter, holder, targetSend);
                }
                updateMaps(player);
            } finally {
                state.applying = false;
            }
        }
        state.lastAppliedLoad = targetLoad;
        state.lastAppliedSend = targetSend;
        return new AppliedDistances(effectiveViewDistance(player), effectiveSendDistance(player));
    }

    public void refresh(ServerPlayer player) {
        updateMaps(player);
    }

    public int simulationDistance(ServerPlayer player) {
        return invokeInt(platformTickGetter, platformHooks, player);
    }

    public int simulationDistance(ServerLevel level) {
        RawDistances raw = readRaw(levelHolder(level));
        return raw.tick() < 0 ? 2 : raw.tick();
    }

    /** Restores the latest non-PVD player intent, including the inherit sentinel. */
    public void restoreAll(Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            PlayerIntent state = intents.remove(player.getUUID());
            if (state == null) {
                continue;
            }
            restore(player, state);
        }
        intents.clear();
    }

    public void forget(ServerPlayer player) {
        intents.remove(player.getUUID());
    }

    private void restore(ServerPlayer player, PlayerIntent state) {
        Object holder = playerHolder(player);
        RawDistances current = readRaw(holder);
        state.applying = true;
        try {
            int externalEffectiveLoad = state.externalLoad < 0
                    ? effectiveViewDistance(player)
                    : state.externalLoad - 1;
            int externalEffectiveSend = state.externalSend < 0
                    ? effectiveSendDistance(player)
                    : state.externalSend;
            boolean shrinking = externalEffectiveLoad < effectiveViewDistance(player)
                    || externalEffectiveSend < effectiveSendDistance(player);
            if (shrinking) {
                if (current.send() != state.externalSend) {
                    invokeVoid(sendSetter, holder, state.externalSend);
                }
                if (current.load() != state.externalLoad) {
                    invokeVoid(loadSetter, holder, state.externalLoad);
                }
            } else {
                if (current.load() != state.externalLoad) {
                    invokeVoid(loadSetter, holder, state.externalLoad);
                }
                if (current.send() != state.externalSend) {
                    invokeVoid(sendSetter, holder, state.externalSend);
                }
            }
            updateMaps(player);
        } finally {
            state.applying = false;
        }
    }

    private Object playerHolder(ServerPlayer player) {
        return invokeObject(playerHolderGetter, player);
    }

    private Object levelHolder(ServerLevel level) {
        return invokeObject(levelHolderGetter, level);
    }

    private RawDistances readRaw(Object holder) {
        Object distances = invokeObject(distancesGetter, holder);
        int load = invokeInt(loadGetter, distances);
        int send = invokeInt(sendGetter, distances);
        int tick = invokeInt(tickGetter, distances);
        validateRaw(load, send, tick);
        return new RawDistances(load, send, tick);
    }

    private int effectiveViewDistance(ServerPlayer player) {
        return invokeInt(platformViewGetter, platformHooks, player);
    }

    private int effectiveSendDistance(ServerPlayer player) {
        return invokeInt(platformSendGetter, platformHooks, player);
    }

    private void updateMaps(ServerPlayer player) {
        invokeVoid(updateMaps, platformHooks, player.level(), player);
    }

    private static void validateRaw(int load, int send, int tick) {
        if ((load != -1 && (load < 3 || load > 33))
                || (send != -1 && (send < 0 || send > 32))
                || (tick != -1 && (tick < 0 || tick > 32))) {
            throw new IllegalStateException(
                    "Moonrise returned invalid raw view distances: load=" + load
                            + ", send=" + send + ", tick=" + tick);
        }
    }

    private static Integer loadingLimit(int raw) {
        return raw < 0 ? null : raw - 1;
    }

    private static Integer sendingLimit(int raw) {
        return raw < 0 ? null : raw;
    }

    private static Integer minimum(Integer first, Integer second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return Math.min(first, second);
    }

    private static int clamp(int distance) {
        return Math.max(2, Math.min(32, distance));
    }

    private static Object invokeObject(Method method, Object owner, Object... arguments) {
        try {
            Object result = method.invoke(owner, arguments);
            if (result == null) {
                throw new IllegalStateException("Moonrise hook " + method.getName() + " returned null");
            }
            return result;
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw invocationFailure(method, failure);
        }
    }

    private static int invokeInt(Method method, Object owner, Object... arguments) {
        Object result = invokeObject(method, owner, arguments);
        if (!(result instanceof Integer value)) {
            throw new IllegalStateException("Moonrise hook " + method.getName() + " did not return int");
        }
        return value;
    }

    private static void invokeVoid(Method method, Object owner, Object... arguments) {
        try {
            method.invoke(owner, arguments);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw invocationFailure(method, failure);
        }
    }

    private static IllegalStateException invocationFailure(Method method, ReflectiveOperationException failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : failure;
        return new IllegalStateException(
                "Moonrise per-player loader hook failed: " + method.getName(), cause);
    }

    public record Limits(Integer loadingMaximum, Integer sendingMaximum) {
    }

    public record AppliedDistances(int loadingDistance, int sendingDistance) {
    }

    private record RawDistances(int load, int send, int tick) {
    }

    private static final class PlayerIntent {
        private int externalLoad;
        private int externalSend;
        private Integer lastAppliedLoad;
        private Integer lastAppliedSend;
        private boolean applying;

        private PlayerIntent(int externalLoad, int externalSend) {
            this.externalLoad = externalLoad;
            this.externalSend = externalSend;
        }
    }
}
