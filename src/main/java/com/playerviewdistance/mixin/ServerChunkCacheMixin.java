package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "setSimulationDistance", at = @At("HEAD"))
    private void playerviewdistance$beforeLevelSimulationDistance(
            int distance,
            CallbackInfo callback
    ) {
        PerPlayerChunkLoader.onLevelSimulationDistanceChanging(this.level, distance);
    }

    @Inject(method = "setSimulationDistance", at = @At("TAIL"))
    private void playerviewdistance$afterLevelSimulationDistance(
            int distance,
            CallbackInfo callback
    ) {
        PerPlayerChunkLoader.onLevelSimulationDistanceChanged(this.level);
    }
}
