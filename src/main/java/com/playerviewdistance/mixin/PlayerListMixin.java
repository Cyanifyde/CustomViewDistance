package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "setSimulationDistance", at = @At("TAIL"))
    private void playerviewdistance$simulationDistanceChanged(int distance, CallbackInfo callback) {
        PerPlayerChunkLoader.onSimulationDistanceChanged(distance);
    }
}
