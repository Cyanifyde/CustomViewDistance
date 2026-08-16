package com.playerviewdistance.runtime;

import java.util.UUID;

public interface LimitChangeListener {
    void onLimitsChanged(UUID playerId);
}
