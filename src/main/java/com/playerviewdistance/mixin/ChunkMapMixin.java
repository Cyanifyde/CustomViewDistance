package com.playerviewdistance.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    /*
     * Seed vanilla's calculation with PVD's candidate without cancelling the
     * method.  The early order lets composable governors reduce that value.
     */
    @ModifyExpressionValue(
            method = "getPlayerViewDistance",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;requestedViewDistance()I"
            ),
            order = 1
    )
    private int playerviewdistance$provideCandidateDistance(
            int requestedDistance,
            ServerPlayer player
    ) {
        return PerPlayerChunkLoader.getEffectiveViewDistance(player);
    }

    /*
     * This is deliberately a chaining return modifier, not a cancellable
     * callback: lower results from other governors survive, while attempts to
     * raise beyond PVD's ceiling are clamped at the final injector phase.
     */
    @ModifyReturnValue(
            method = "getPlayerViewDistance",
            at = @At("RETURN"),
            order = 20_000
    )
    private int playerviewdistance$enforceFinalCeiling(
            int resolvedDistance,
            ServerPlayer player
    ) {
        return Math.max(2, Math.min(
                resolvedDistance,
                PerPlayerChunkLoader.getEffectiveViewDistance(player)));
    }

    @Inject(method = "setServerViewDistance", at = @At("HEAD"))
    private void playerviewdistance$beforeServerViewDistance(int viewDistance, CallbackInfo callback) {
        PerPlayerChunkLoader.onLevelViewDistanceChanging(this.level, viewDistance);
    }

    @ModifyArg(
            method = "setServerViewDistance",
            at = @At(
                    value = "INVOKE",
                    // The invokevirtual constant-pool owner is ChunkMap.DistanceManager even
                    // though updatePlayerTickets is inherited from the base DistanceManager.
                    // Matching the declaring class compiles but fails against production jars.
                    target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;updatePlayerTickets(I)V"
            ),
            index = 0
    )
    private int playerviewdistance$useSimulationDistanceForGlobalLoading(int ignoredViewDistance) {
        Object distanceManager = ((ChunkMap) (Object) this).getDistanceManager();
        int simulationDistance = ((DistanceManagerAccessor) distanceManager)
                .playerviewdistance$getSimulationDistance();
        return Math.max(2, Math.min(32, simulationDistance));
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
