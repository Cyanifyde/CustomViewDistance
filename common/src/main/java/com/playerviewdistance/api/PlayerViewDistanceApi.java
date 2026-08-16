package com.playerviewdistance.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerViewDistanceApi {
    private static final AtomicReference<PlayerViewDistanceService> SERVICE = new AtomicReference<PlayerViewDistanceService>();

    private PlayerViewDistanceApi() {
    }

    public static Optional<PlayerViewDistanceService> get() {
        return Optional.ofNullable(SERVICE.get());
    }

    public static void install(PlayerViewDistanceService service) {
        if (!SERVICE.compareAndSet(null, service)) {
            throw new IllegalStateException("PlayerViewDistance service is already installed");
        }
    }

    public static void uninstall(PlayerViewDistanceService service) {
        SERVICE.compareAndSet(service, null);
    }
}
