package com.playerviewdistance.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.playerviewdistance.PerPlayerChunkLoader;
import com.playerviewdistance.ViewDistanceConfig;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.List;
import java.util.Locale;

public final class PvdCommand {
    private PvdCommand() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext context,
            Commands.CommandSelection environment
    ) {
        dispatcher.register(Commands.literal("pvd")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("list")
                        .executes(command -> executeList(command.getSource())))
                .then(Commands.literal("status")
                        .executes(command -> executeStatus(command.getSource())))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                                        .executes(command -> executeSet(
                                                command.getSource(),
                                                EntityArgument.getPlayer(command, "player"),
                                                IntegerArgumentType.getInteger(command, "distance"))))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(command -> executeReset(
                                        command.getSource(),
                                        EntityArgument.getPlayer(command, "player")))))
                .then(Commands.literal("reload")
                        .executes(command -> executeReload(command.getSource()))));
    }

    private static int executeList(CommandSourceStack source) {
        List<PerPlayerChunkLoader.PlayerStatus> players = PerPlayerChunkLoader.getPlayerStates();
        if (players.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No players are currently tracked."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("PlayerViewDistance players:"), false);
        for (PerPlayerChunkLoader.PlayerStatus player : players) {
            String override = player.override() == null ? "" : ", override=" + player.override();
            String line = String.format(Locale.ROOT,
                    "%s: requested=%d, desired=%d, applied=%d, simulation=%d%s, %s [%d,%d]",
                    player.name(), player.requestedViewDistance(), player.desiredViewDistance(),
                    player.appliedViewDistance(), player.simulationDistance(), override,
                    player.dimension(), player.chunkX(), player.chunkZ());
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return players.size();
    }

    private static int executeStatus(CommandSourceStack source) {
        PerPlayerChunkLoader.StatusSnapshot status = PerPlayerChunkLoader.status();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "PVD %s | generation=%d/%d | sources=%d/%d | union=%d/%d | remaining=%d",
                status.governorState(), status.appliedGeneration(), status.requestedGeneration(),
                status.appliedSourceCount(), status.desiredSourceCount(), status.appliedUnionArea(),
                status.desiredUnionArea(), status.remainingChangedCells())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "MSPT avg=%.2f p95=%.2f | backlog=%d | loaded=%d | view cap=%d | simulation=%d",
                millis(status.averageTickNanos()), millis(status.p95TickNanos()),
                status.pendingChunkWork(), status.loadedChunks(), status.serverViewDistance(),
                status.simulationDistance())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "entities=%d (mobs=%d, items=%d) | ticket mutations=%d | planner=%.3fms | PVD main=%.3fms",
                status.entityCount(), status.mobCount(), status.itemCount(), status.explicitTicketMutations(),
                millis(status.plannerNanos()), millis(status.pvdServerThreadNanos()))), false);
        return 1;
    }

    private static int executeSet(CommandSourceStack source, ServerPlayer target, int distance) {
        PerPlayerChunkLoader.setOperatorOverride(target.getUUID(), distance);
        source.sendSuccess(() -> Component.literal(
                "Persistently set " + target.getGameProfile().name() + " to view distance " + distance), true);
        return 1;
    }

    private static int executeReset(CommandSourceStack source, ServerPlayer target) {
        PerPlayerChunkLoader.removeOperatorOverride(target.getUUID());
        source.sendSuccess(() -> Component.literal(
                "Removed the persistent view-distance override for " + target.getGameProfile().name()), true);
        return 1;
    }

    private static int executeReload(CommandSourceStack source) {
        var outcome = ViewDistanceConfig.reload();
        if (!outcome.success()) {
            source.sendFailure(Component.literal(outcome.message()));
            return 0;
        }
        PerPlayerChunkLoader.onConfigReloaded(outcome);
        var config = outcome.config();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Reloaded schema %d: min=%d, max=%d, governor=%s, telemetry=%ds",
                config.schemaVersion(), config.minViewDistance(), config.maxViewDistance(),
                config.governorProfile(), config.telemetryIntervalSeconds())), true);
        return 1;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
