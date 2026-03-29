package com.playerviewdistance.commands;

import com.playerviewdistance.CircleTemplate;
import com.playerviewdistance.PerPlayerChunkLoader;
import com.playerviewdistance.ViewDistanceConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Map;
import java.util.UUID;

public final class PvdCommand {

    private PvdCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                net.minecraft.commands.CommandBuildContext context,
                                Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("pvd")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("list")
                        .executes(ctx -> executeList(ctx.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                                        .executes(ctx -> executeSet(
                                                ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "distance"))))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> executeReset(
                                        ctx.getSource(),
                                        EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("reload")
                        .executes(ctx -> executeReload(ctx.getSource())))
        );
    }

    private static int executeList(CommandSourceStack source) {
        Map<UUID, PerPlayerChunkLoader.PlayerState> states = PerPlayerChunkLoader.getPlayerStates();
        if (states.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No players tracked."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("--- PlayerViewDistance Player List ---"), false);
        for (var entry : states.entrySet()) {
            UUID uuid = entry.getKey();
            PerPlayerChunkLoader.PlayerState state = entry.getValue();
            ServerPlayer player = source.getServer().getPlayerList().getPlayer(uuid);
            String name = player != null ? player.getGameProfile().name() : uuid.toString();
            int clientRd = player != null ? player.requestedViewDistance() : -1;
            boolean hasOverride = PerPlayerChunkLoader.hasOverride(uuid);
            int overrideVal = PerPlayerChunkLoader.getOverride(uuid);
            int chunks = CircleTemplate.getChunkCount(state.rd);

            String overrideStr = hasOverride ? " [override: " + overrideVal + "]" : "";
            String line = String.format("  %s: effective=%d, client=%d, chunks=%d%s",
                    name, state.rd, clientRd, chunks, overrideStr);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return states.size();
    }

    private static int executeSet(CommandSourceStack source, ServerPlayer target, int distance) {
        PerPlayerChunkLoader.setOperatorOverride(target.getUUID(), distance);
        source.sendSuccess(() -> Component.literal(
                "Set view distance override for " + target.getGameProfile().name() + " to " + distance), true);
        return 1;
    }

    private static int executeReset(CommandSourceStack source, ServerPlayer target) {
        PerPlayerChunkLoader.removeOperatorOverride(target.getUUID());
        source.sendSuccess(() -> Component.literal(
                "Reset view distance override for " + target.getGameProfile().name()), true);
        return 1;
    }

    private static int executeReload(CommandSourceStack source) {
        ViewDistanceConfig.reload();
        ViewDistanceConfig config = ViewDistanceConfig.get();
        source.sendSuccess(() -> Component.literal(String.format(
                "Config reloaded: min=%d, max=%d, moveCheck=%dt, rdPoll=%dt, workers=%d",
                config.minViewDistance, config.maxViewDistance,
                config.moveCheckIntervalTicks, config.rdPollIntervalTicks, config.workerThreadCount)), true);
        return 1;
    }
}
