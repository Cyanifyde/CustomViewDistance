package com.playerviewdistance.mixin;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.playerviewdistance.PerPlayerChunkLoader;

@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @Unique
    private long playerviewdistance$graphStartedNanos;

    @Shadow
    protected abstract void updatePlayerTickets(int distance);

    @Inject(method = "updateSimulationDistance", at = @At("TAIL"))
    private void playerviewdistance$keepLoadingFloorAtSimulationDistance(int distance, CallbackInfo callback) {
        if (PerPlayerChunkLoader.shouldMaintainVanillaPlayerLoadingFloor()) {
            this.updatePlayerTickets(distance);
        }
    }

    @Inject(method = "runAllUpdates", at = @At("HEAD"))
    private void playerviewdistance$startGraphTiming(CallbackInfoReturnable<Boolean> callback) {
        this.playerviewdistance$graphStartedNanos = System.nanoTime();
    }

    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void playerviewdistance$finishGraphTiming(CallbackInfoReturnable<Boolean> callback) {
        PerPlayerChunkLoader.onChunkGraphTiming(
                Math.max(0, System.nanoTime() - this.playerviewdistance$graphStartedNanos));
    }
}
