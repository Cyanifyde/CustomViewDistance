package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(method = "getPlayerViewDistance", at = @At("HEAD"), cancellable = true)
    private void playerviewdistance$getPlayerViewDistance(
            ServerPlayer player,
            CallbackInfoReturnable<Integer> callback
    ) {
        callback.setReturnValue(PerPlayerChunkLoader.getEffectiveViewDistance(player));
    }

    @Inject(method = "setServerViewDistance", at = @At("HEAD"))
    private void playerviewdistance$beforeServerViewDistance(int viewDistance, CallbackInfo callback) {
        PerPlayerChunkLoader.onServerViewDistanceChanging(viewDistance);
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
        return this.level.getServer().getPlayerList().getSimulationDistance();
    }

    @Inject(method = "setServerViewDistance", at = @At("TAIL"))
    private void playerviewdistance$afterServerViewDistance(int viewDistance, CallbackInfo callback) {
        PerPlayerChunkLoader.onServerViewDistanceChanged();
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void playerviewdistance$captureMove(ServerPlayer player, CallbackInfo callback) {
        PerPlayerChunkLoader.onPlayerMoved(player);
    }
}
