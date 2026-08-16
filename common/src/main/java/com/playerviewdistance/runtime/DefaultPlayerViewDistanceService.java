package com.playerviewdistance.runtime;

import com.playerviewdistance.api.DistanceSnapshot;
import com.playerviewdistance.api.LimiterHandle;
import com.playerviewdistance.api.PlayerViewDistanceService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultPlayerViewDistanceService implements PlayerViewDistanceService, AutoCloseable {
    private final ConcurrentHashMap<String, Registration> registrations =
            new ConcurrentHashMap<String, Registration>();
    private final ConcurrentHashMap<UUID, DistanceSnapshot> snapshots =
            new ConcurrentHashMap<UUID, DistanceSnapshot>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LimitChangeListener listener;
    private final Object mutationLock = new Object();

    public DefaultPlayerViewDistanceService(LimitChangeListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public LimiterHandle registerLimiter(String providerId) {
        validateProviderId(providerId);
        synchronized (mutationLock) {
            ensureOpen();
            Registration created = new Registration(providerId);
            Registration existing = registrations.putIfAbsent(providerId, created);
            if (existing != null) {
                throw new IllegalArgumentException("Limiter provider is already registered: " + providerId);
            }
            generation.incrementAndGet();
            return created;
        }
    }

    @Override
    public DistanceSnapshot getSnapshot(UUID playerId) {
        return snapshots.get(Objects.requireNonNull(playerId, "playerId"));
    }

    public ResolvedLimits resolve(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (mutationLock) {
            Integer loading = null;
            Integer sending = null;
            for (Registration registration : registrations.values()) {
                Limits limits = registration.limits.get(playerId);
                if (limits == null) continue;
                loading = minimum(loading, limits.loadingMaximum);
                sending = minimum(sending, limits.sendingMaximum);
            }
            return new ResolvedLimits(loading, sending, generation.get());
        }
    }

    public void publishSnapshot(DistanceSnapshot snapshot) {
        synchronized (mutationLock) {
            if (!closed.get()) {
                snapshots.put(snapshot.playerId(), snapshot);
            }
        }
    }

    public void removeSnapshot(UUID playerId) {
        snapshots.remove(playerId);
    }

    public int registeredLimiterCount() {
        return registrations.size();
    }

    public long generation() {
        return generation.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<UUID> affected = new ArrayList<UUID>();
        synchronized (mutationLock) {
            for (Registration registration : registrations.values()) {
                if (registration.registrationClosed.compareAndSet(false, true)) {
                    affected.addAll(registration.limits.keySet());
                    registration.limits.clear();
                }
            }
            registrations.clear();
            snapshots.clear();
            generation.incrementAndGet();
        }
        for (UUID playerId : affected) listener.onLimitsChanged(playerId);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("PlayerViewDistance service is closed");
    }

    private static void validateProviderId(String providerId) {
        Objects.requireNonNull(providerId, "providerId");
        if (providerId.length() == 0 || providerId.length() > 128
                || !providerId.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException(
                    "providerId must contain 1-128 letters, digits, '.', '_', ':', or '-'");
        }
    }

    private static Integer validateLimit(Integer value, String name) {
        if (value != null && (value.intValue() < 2 || value.intValue() > 32)) {
            throw new IllegalArgumentException(name + " must be null or in [2, 32]");
        }
        return value;
    }

    private static Integer minimum(Integer first, Integer second) {
        if (first == null) return second;
        if (second == null) return first;
        return Integer.valueOf(Math.min(first.intValue(), second.intValue()));
    }

    private final class Registration implements LimiterHandle {
        private final String providerId;
        private final ConcurrentHashMap<UUID, Limits> limits = new ConcurrentHashMap<UUID, Limits>();
        private final AtomicBoolean registrationClosed = new AtomicBoolean();

        private Registration(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public void setLimit(UUID playerId, Integer loadingMaximum, Integer sendingMaximum) {
            Objects.requireNonNull(playerId, "playerId");
            Integer loading = validateLimit(loadingMaximum, "loadingMaximum");
            Integer sending = validateLimit(sendingMaximum, "sendingMaximum");
            if (loading == null && sending == null) {
                clear(playerId);
                return;
            }
            Limits replacement = new Limits(loading, sending);
            boolean changed;
            synchronized (mutationLock) {
                if (registrationClosed.get() || closed.get()) {
                    throw new IllegalStateException("Limiter provider is closed: " + providerId);
                }
                Limits previous = limits.put(playerId, replacement);
                changed = !replacement.equals(previous);
                if (changed) generation.incrementAndGet();
            }
            if (changed) listener.onLimitsChanged(playerId);
        }

        @Override
        public void clear(UUID playerId) {
            Objects.requireNonNull(playerId, "playerId");
            boolean changed;
            synchronized (mutationLock) {
                if (registrationClosed.get() || closed.get()) return;
                changed = limits.remove(playerId) != null;
                if (changed) generation.incrementAndGet();
            }
            if (changed) listener.onLimitsChanged(playerId);
        }

        @Override
        public void close() {
            List<UUID> affected;
            synchronized (mutationLock) {
                if (!registrationClosed.compareAndSet(false, true)) return;
                registrations.remove(providerId, this);
                affected = new ArrayList<UUID>(limits.keySet());
                limits.clear();
                generation.incrementAndGet();
            }
            for (UUID playerId : affected) {
                listener.onLimitsChanged(playerId);
            }
        }
    }

    private static final class Limits {
        private final Integer loadingMaximum;
        private final Integer sendingMaximum;

        private Limits(Integer loadingMaximum, Integer sendingMaximum) {
            this.loadingMaximum = loadingMaximum;
            this.sendingMaximum = sendingMaximum;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Limits)) return false;
            Limits that = (Limits) other;
            return Objects.equals(loadingMaximum, that.loadingMaximum)
                    && Objects.equals(sendingMaximum, that.sendingMaximum);
        }

        @Override
        public int hashCode() {
            return Objects.hash(loadingMaximum, sendingMaximum);
        }
    }
}
