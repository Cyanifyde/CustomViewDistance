package com.playerviewdistance.mixin;

import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls the configuration state-machine transition after it reaches the server thread. */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface ServerConfigurationPacketListenerInvoker {
    @Invoker("finishCurrentTask")
    void playerviewdistance$finishCurrentTask(ConfigurationTask.Type type);
}
