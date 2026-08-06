package com.example.cyberdeck.radio;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /radio on} and {@code /radio off}. In a party, only the leader may throw the switch. */
public final class RadioCommand {
    private RadioCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> radio = Commands.literal("radio")
                .then(Commands.literal("on").executes(context -> toggle(context.getSource(), true)))
                .then(Commands.literal("off")
                        .executes(context -> toggle(context.getSource(), false)));
        dispatcher.register(radio);
    }

    private static int toggle(CommandSourceStack source, boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("commands.cyberdeck.radio.player_only"));
            return 0;
        }
        RadioService.ToggleResult result = RadioService.toggle(player, on);
        switch (result) {
            case NOT_PARTY_LEADER -> {
                source.sendFailure(Component.translatable("commands.cyberdeck.radio.not_leader"));
                return 0;
            }
            case CHANGED_FOR_PARTY -> source.sendSuccess(
                    () -> Component.translatable(on
                            ? "commands.cyberdeck.radio.on_party"
                            : "commands.cyberdeck.radio.off_party"), false);
            case CHANGED -> source.sendSuccess(
                    () -> Component.translatable(on
                            ? "commands.cyberdeck.radio.on"
                            : "commands.cyberdeck.radio.off"), false);
        }
        return 1;
    }
}
