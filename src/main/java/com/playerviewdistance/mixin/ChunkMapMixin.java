package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import com.playerviewdistance.ViewDistanceConfig;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    private int serverViewDistance;

    @Inject(method = "getPlayerViewDistance", at = @At("HEAD"), cancellable = true)
    private void playerviewdistance$getPlayerViewDistance(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        int effective = PerPlayerChunkLoader.getEffectiveViewDistance(player);
        cir.setReturnValue(Mth.clamp(effective, 2, ViewDistanceConfig.get().maxViewDistance));
    }

    @Inject(method = "setServerViewDistance", at = @At("TAIL"))
    private void playerviewdistance$overrideServerViewDistance(int viewDistance, CallbackInfo ci) {
        PerPlayerChunkLoader.setAdminMaxViewDistance(Mth.clamp(viewDistance, 2, 32));
        // Override ChunkMap's internal tracking range to the mod's max so it can
        // track and send chunks beyond the server.properties view-distance
        this.serverViewDistance = ViewDistanceConfig.get().maxViewDistance;
    }
}
