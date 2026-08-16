package com.playerviewdistance.api;

import java.util.UUID;

public interface LimiterHandle extends AutoCloseable {
    String providerId();

    void setLimit(UUID playerId, Integer loadingMaximum, Integer sendingMaximum);

    void clear(UUID playerId);

    @Override
    void close();
}
