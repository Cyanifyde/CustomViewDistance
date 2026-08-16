package com.playerviewdistance.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge handles two configuration payloads on Netty's event loop and completes their
 * configuration tasks inline. Completing a task can start prepare-spawn chunk work, whose
 * distance graph is server-thread confined. Marshal that state transition to the server.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class NeoForgeConfigurationTaskMixin {
    @Inject(method = "finishCurrentTask", at = @At("HEAD"), cancellable = true)
    private void playerviewdistance$finishConfigurationTaskOnServerThread(
            ConfigurationTask.Type type, CallbackInfo callback) {
        ServerConfigurationPacketListenerImpl listener =
                (ServerConfigurationPacketListenerImpl) (Object) this;
        MinecraftServer server = ((ServerCommonPacketListenerAccessor) (Object) listener)
                .playerviewdistance$getServer();
        if (server.isSameThread()) {
            return;
        }

        callback.cancel();
        if (!listener.isAcceptingMessages()) {
            return;
        }

        server.executeIfPossible(() -> {
            if (listener.isAcceptingMessages()) {
                ((ServerConfigurationPacketListenerInvoker) (Object) listener)
                        .playerviewdistance$finishCurrentTask(type);
            }
        });
    }
}
