package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    /*
     * This is deliberately a chaining return modifier, not a cancellable
     * callback: lower results from other governors survive, while attempts to
     * raise beyond PVD's ceiling are clamped at the final injector phase.
     */
    @Inject(
            method = "getPlayerViewDistance",
            at = @At("RETURN"),
            cancellable = true
    )
    private void playerviewdistance$enforceFinalCeiling(
            ServerPlayer player,
            CallbackInfoReturnable<Integer> callback
    ) {
        callback.setReturnValue(Math.max(2, Math.min(
                callback.getReturnValue(),
                PerPlayerChunkLoader.getEffectiveViewDistance(player))));
    }

    @Inject(method = "setServerViewDistance", at = @At("HEAD"))
    private void playerviewdistance$beforeServerViewDistance(int viewDistance, CallbackInfo callback) {
        PerPlayerChunkLoader.onLevelViewDistanceChanging(this.level, viewDistance);
    }

    @Inject(method = "setServerViewDistance", at = @At("TAIL"))
    private void playerviewdistance$afterServerViewDistance(int viewDistance, CallbackInfo callback) {
        PerPlayerChunkLoader.onLevelViewDistanceChanged(this.level);
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void playerviewdistance$captureMove(ServerPlayer player, CallbackInfo callback) {
        PerPlayerChunkLoader.onPlayerMoved(player);
    }
}
