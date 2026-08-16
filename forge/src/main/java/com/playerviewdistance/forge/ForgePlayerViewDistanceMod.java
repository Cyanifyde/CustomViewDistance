package com.playerviewdistance.forge;

import com.playerviewdistance.PlatformEnvironment;
import com.playerviewdistance.PerPlayerChunkLoader;
import com.playerviewdistance.ViewDistanceConfig;
import com.playerviewdistance.commands.PvdCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(ForgePlayerViewDistanceMod.MOD_ID)
public final class ForgePlayerViewDistanceMod {
    public static final String MOD_ID = "playerviewdistance";

    public ForgePlayerViewDistanceMod(FMLJavaModLoadingContext context) {
        PlatformEnvironment.install(FMLPaths.CONFIGDIR.get(), ForgeModLookup::isLoaded);
        ViewDistanceConfig.initialize();

        ServerStartedEvent.BUS.addListener(event -> PerPlayerChunkLoader.init(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> PerPlayerChunkLoader.shutdown(event.getServer()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(
                event -> PerPlayerChunkLoader.onServerTick(event.server()));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                PerPlayerChunkLoader.onPlayerJoin(player);
            }
        });
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                PerPlayerChunkLoader.onPlayerLeave(player);
            }
        });
        EntityJoinLevelEvent.BUS.addListener(event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                PerPlayerChunkLoader.onEntityLoad(event.getEntity(), level);
            }
        });
        EntityLeaveLevelEvent.BUS.addListener(event -> {
            if (event.getLevel() instanceof ServerLevel level) {
                PerPlayerChunkLoader.onEntityUnload(event.getEntity(), level);
            }
        });
        RegisterCommandsEvent.BUS.addListener(ForgePlayerViewDistanceMod::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        PvdCommand.register(
                event.getDispatcher(),
                event.getBuildContext(),
                event.getCommandSelection());
    }
}
