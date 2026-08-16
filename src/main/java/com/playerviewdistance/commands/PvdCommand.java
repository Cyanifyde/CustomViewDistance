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

import java.util.Comparator;
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
            source.sendSuccess(() -> Component.literal("No players."), false);
            return 0;
        }

        double average = players.stream()
                .mapToInt(PerPlayerChunkLoader.PlayerStatus::appliedViewDistance)
                .average()
                .orElse(0.0);
        source.sendSuccess(() -> Component.literal("Average is " + formatDistance(average) + "."), false);
        List<PerPlayerChunkLoader.PlayerStatus> highest = players.stream()
                .sorted(Comparator.comparingInt(PerPlayerChunkLoader.PlayerStatus::appliedViewDistance)
                        .reversed()
                        .thenComparing(PerPlayerChunkLoader.PlayerStatus::name,
                                String.CASE_INSENSITIVE_ORDER))
                .limit(10)
                .toList();
        for (PerPlayerChunkLoader.PlayerStatus player : highest) {
            String line = "Player " + player.name() + " has " + player.appliedViewDistance() + ".";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return highest.size();
    }

    private static int executeStatus(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            for (PerPlayerChunkLoader.PlayerStatus status : PerPlayerChunkLoader.getPlayerStates()) {
                if (status.uuid().equals(player.getUUID())) {
                    source.sendSuccess(() -> Component.literal(
                            "Currently at " + status.appliedViewDistance() + "."), false);
                    return 1;
                }
            }
            source.sendSuccess(() -> Component.literal("Currently unavailable."), false);
            return 0;
        }
        return executeList(source);
    }

    private static int executeSet(CommandSourceStack source, ServerPlayer target, int distance) {
        PerPlayerChunkLoader.setOperatorOverride(target.getUUID(), distance);
        source.sendSuccess(() -> Component.literal("Set to " + distance + "."), true);
        return 1;
    }

    private static int executeReset(CommandSourceStack source, ServerPlayer target) {
        PerPlayerChunkLoader.removeOperatorOverride(target.getUUID());
        source.sendSuccess(() -> Component.literal("Reset."), true);
        return 1;
    }

    private static int executeReload(CommandSourceStack source) {
        var outcome = ViewDistanceConfig.reload();
        if (!outcome.success()) {
            source.sendFailure(Component.literal(outcome.message()));
            return 0;
        }
        PerPlayerChunkLoader.onConfigReloaded(outcome);
        source.sendSuccess(() -> Component.literal("Reloaded."), true);
        return 1;
    }

    private static String formatDistance(double distance) {
        if (distance == Math.rint(distance)) {
            return Integer.toString((int) distance);
        }
        return String.format(Locale.ROOT, "%.1f", distance);
    }
}
