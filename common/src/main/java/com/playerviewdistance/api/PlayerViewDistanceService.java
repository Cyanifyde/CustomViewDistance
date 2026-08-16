package com.playerviewdistance.api;

import java.util.UUID;

public interface PlayerViewDistanceService {
    LimiterHandle registerLimiter(String providerId);

    DistanceSnapshot getSnapshot(UUID playerId);
}
