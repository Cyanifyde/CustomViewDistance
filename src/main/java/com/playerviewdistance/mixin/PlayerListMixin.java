package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "setViewDistance", at = @At("HEAD"))
    private void playerviewdistance$viewDistanceChanging(int distance, CallbackInfo callback) {
        PerPlayerChunkLoader.onServerViewDistanceChanging(distance);
    }

    @Inject(method = "setViewDistance", at = @At("TAIL"))
    private void playerviewdistance$viewDistanceChanged(int distance, CallbackInfo callback) {
        PerPlayerChunkLoader.onServerViewDistanceChanged();
    }

    @Inject(method = "setSimulationDistance", at = @At("HEAD"))
    private void playerviewdistance$simulationDistanceChanging(int distance, CallbackInfo callback) {
        PerPlayerChunkLoader.onServerSimulationDistanceChanging(distance);
    }

    @Inject(method = "setSimulationDistance", at = @At("TAIL"))
    private void playerviewdistance$simulationDistanceChanged(int distance, CallbackInfo callback) {
        PerPlayerChunkLoader.onServerSimulationDistanceChanged();
    }
}
