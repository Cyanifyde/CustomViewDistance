package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Required vanilla player-loader hooks. The mixin config plugin omits this
 * entire mixin only when Moonrise's native replacement is present.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapVanillaLoadingMixin {
    /* Seed vanilla with PVD's candidate so later governors may reduce it. */
    @Redirect(
            method = "getPlayerViewDistance",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;requestedViewDistance()I"
            )
    )
    private int playerviewdistance$provideCandidateDistance(ServerPlayer player) {
        return PerPlayerChunkLoader.getEffectiveViewDistance(player);
    }

    @ModifyArg(
            method = "setServerViewDistance",
            at = @At(
                    value = "INVOKE",
                    // The invokevirtual constant-pool owner is ChunkMap.DistanceManager even
                    // though updatePlayerTickets is inherited from the base DistanceManager.
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
}
