package com.example.cyberdeck;

import com.example.cyberdeck.network.SetCityWaypointPacket;
import com.example.cyberdeck.trauma.TraumaTeamEvents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.modernity.neoncity.CityMapService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Accessible command fallback for the same scanner mode normally toggled with Tab. */
public final class CyberdeckCommands {
    private CyberdeckCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cyberdeck")
                .then(Commands.literal("map")
                        .executes(context -> openMap(context.getSource()))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> openMap(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "z"))))))
                .then(Commands.literal("waypoint")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(context -> setWaypoint(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "x"),
                                                IntegerArgumentType.getInteger(context, "z"))))))
                .then(Commands.literal("scanner")
                        .executes(context -> toggle(context.getSource()))
                        .then(Commands.literal("on")
                                .executes(context -> set(context.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(context -> set(context.getSource(), false)))
                        .then(Commands.literal("toggle")
                                .executes(context -> toggle(context.getSource()))))
                .then(Commands.literal("trauma")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> dispatchTrauma(
                                context.getSource(), context.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(context -> dispatchTrauma(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "target"))))));
    }

    private static int toggle(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return set(source, !CyberdeckState.isActive(player));
    }

    private static int openMap(CommandSourceStack source) throws CommandSyntaxException {
        CityMapService.open(source.getPlayerOrException(), true);
        return 1;
    }

    private static int openMap(CommandSourceStack source, int x, int z)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        CityMapService.open(player, true);
        PacketDistributor.sendToPlayer(player, new SetCityWaypointPacket(x, z));
        return 1;
    }

    private static int setWaypoint(CommandSourceStack source, int x, int z)
            throws CommandSyntaxException {
        PacketDistributor.sendToPlayer(
                source.getPlayerOrException(), new SetCityWaypointPacket(x, z));
        return 1;
    }

    private static int set(CommandSourceStack source, boolean active) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (active && !CyberdeckState.hasInstalledCyberdeck(player)) {
            source.sendFailure(Component.translatable("message.cyberdeck.cyberdeck_required"));
            return 0;
        }

        CyberdeckState.setActive(player, active);
        source.sendSuccess(() -> Component.literal(
                active ? "Cyberdeck scanner online" : "Cyberdeck scanner offline"), false);
        return 1;
    }

    private static int dispatchTrauma(CommandSourceStack source, ServerPlayer target) {
        if (!target.isAlive() || target.isCreative() || target.isSpectator()) {
            source.sendFailure(Component.translatable(
                    "command.cyberdeck.trauma.target_invalid", target.getDisplayName()));
            return 0;
        }
        if (!TraumaTeamEvents.requestForCommand(target)) {
            source.sendFailure(Component.translatable(
                    "command.cyberdeck.trauma.no_landing", target.getDisplayName()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.cyberdeck.trauma.dispatched", target.getDisplayName()), true);
        return 1;
    }
}
