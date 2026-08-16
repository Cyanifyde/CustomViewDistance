package com.playerviewdistance.neoforge;

import com.playerviewdistance.PlatformEnvironment;
import com.playerviewdistance.PerPlayerChunkLoader;
import com.playerviewdistance.ViewDistanceConfig;
import com.playerviewdistance.commands.PvdCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(NeoForgePlayerViewDistanceMod.MOD_ID)
public final class NeoForgePlayerViewDistanceMod {
    public static final String MOD_ID = "playerviewdistance";

    public NeoForgePlayerViewDistanceMod() {
        if (!isDedicatedServer()) {
            return;
        }
        PlatformEnvironment.install(
                FMLPaths.CONFIGDIR.get(),
                modId -> ModList.get().isLoaded(modId));
        ViewDistanceConfig.initialize();

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(this::onEntityLeave);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    /**
     * Dist is injected into NeoForge's patched game artifact rather than its
     * published loader/universal API jars.  Resolve it at runtime so this thin
     * adapter can be compiled without running NeoForm, while still remaining a
     * strict no-op on physical clients and integrated servers.
     */
    private static boolean isDedicatedServer() {
        try {
            Class<?> environment = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = environment.getMethod("getDist").invoke(null);
            return "DEDICATED_SERVER".equals(String.valueOf(dist));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "PVD cannot determine the NeoForge physical side", failure);
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        PerPlayerChunkLoader.init(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        PerPlayerChunkLoader.shutdown(event.getServer());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        PerPlayerChunkLoader.onServerTick(event.getServer());
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PerPlayerChunkLoader.onPlayerJoin(player);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PerPlayerChunkLoader.onPlayerLeave(player);
        }
    }

    private void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PerPlayerChunkLoader.onEntityLoad(event.getEntity(), level);
        }
    }

    private void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            PerPlayerChunkLoader.onEntityUnload(event.getEntity(), level);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        PvdCommand.register(
                event.getDispatcher(),
                event.getBuildContext(),
                event.getCommandSelection());
    }
}
