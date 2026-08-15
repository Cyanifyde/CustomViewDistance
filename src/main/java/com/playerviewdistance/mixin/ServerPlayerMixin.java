package com.playerviewdistance.mixin;

import com.playerviewdistance.PerPlayerChunkLoader;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "updateOptions", at = @At("TAIL"))
    private void playerviewdistance$updateTrackingImmediately(
            ClientInformation information,
            CallbackInfo callback
    ) {
        PerPlayerChunkLoader.onClientOptionsChanged((ServerPlayer) (Object) this);
    }
}
