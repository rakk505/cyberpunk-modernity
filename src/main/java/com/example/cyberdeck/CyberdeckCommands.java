package com.example.cyberdeck;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Accessible command fallback for the same scanner mode normally toggled with Tab. */
public final class CyberdeckCommands {
    private CyberdeckCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cyberdeck")
                .then(Commands.literal("scanner")
                        .executes(context -> toggle(context.getSource()))
                        .then(Commands.literal("on")
                                .executes(context -> set(context.getSource(), true)))
                        .then(Commands.literal("off")
                                .executes(context -> set(context.getSource(), false)))
                        .then(Commands.literal("toggle")
                                .executes(context -> toggle(context.getSource())))));
    }

    private static int toggle(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return set(source, !CyberdeckState.isActive(player));
    }

    private static int set(CommandSourceStack source, boolean active) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (active && !CyberdeckState.isWearingCyberdeck(player)) {
            source.sendFailure(Component.literal("Equip a Cyberdeck helmet first"));
            return 0;
        }

        CyberdeckState.setActive(player, active);
        source.sendSuccess(() -> Component.literal(
                active ? "Cyberdeck scanner online" : "Cyberdeck scanner offline"), false);
        return 1;
    }
}
