package com.playerviewdistance;

import com.playerviewdistance.commands.PvdCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlayerViewDistanceMod implements ModInitializer {
    public static final String MOD_ID = "playerviewdistance";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PlatformEnvironment.install(
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir(),
                net.fabricmc.loader.api.FabricLoader.getInstance()::isModLoaded);
        ViewDistanceConfig.initialize();

        ServerLifecycleEvents.SERVER_STARTED.register(PerPlayerChunkLoader::init);
        ServerLifecycleEvents.SERVER_STOPPING.register(PerPlayerChunkLoader::shutdown);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PerPlayerChunkLoader.onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PerPlayerChunkLoader.onPlayerLeave(handler.player));

        ServerEntityEvents.ENTITY_LOAD.register(PerPlayerChunkLoader::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(PerPlayerChunkLoader::onEntityUnload);
        ServerTickEvents.END_SERVER_TICK.register(PerPlayerChunkLoader::onServerTick);

        CommandRegistrationCallback.EVENT.register(PvdCommand::register);
    }
}
