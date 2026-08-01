package dev.modernity.neoncity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Player-facing story journal commands; fixer gigs remain available through fixer interaction. */
public final class MissionCommands {
    private MissionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("missions")
                .requires(CommandSourceStack::isPlayer)
                .executes(context -> list(context.getSource()))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> status(context.getSource())))
                .then(Commands.literal("abandon")
                        .executes(context -> abandon(context.getSource())))
                .then(Commands.literal("start")
                        .then(Commands.argument("mission", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        StoryMissionCatalog.definitions().stream()
                                                .map(StoryMissionCatalog.StoryMission::id)
                                                .toList(), builder))
                                .executes(context -> start(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "mission"))))));
    }

    private static int list(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<StoryMissionCatalog.StoryMission> available =
                MissionService.availableStoryMissions(player);
        if (available.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No story missions are currently available. Street Cred: "
                            + PartyService.sharedStreetCred(player)), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                        "Available story missions // Street Cred "
                                + PartyService.sharedStreetCred(player))
                .withStyle(ChatFormatting.AQUA), false);
        for (StoryMissionCatalog.StoryMission mission : available) {
            source.sendSuccess(() -> Component.literal(
                    mission.id() + " // " + mission.encounter().title()
                            + " // +" + mission.encounter().streetCred() + " Street Cred"), false);
        }
        return available.size();
    }

    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MissionService.ActiveMission mission = MissionService.activeMission(player).orElse(null);
        if (mission == null) {
            source.sendSuccess(() -> Component.literal("No active mission or gig."), false);
            return 1;
        }
        MissionService.ContractContext context = MissionService.contractContext(player).orElse(null);
        source.sendSuccess(() -> Component.literal(
                (context == null ? "CONTRACT" : context.kind().displayName())
                        + " // " + mission.title() + " // " + mission.objective()
                        + " // " + (context == null || context.deployed() ? "ACTIVE" : "STAGED")),
                false);
        return 1;
    }

    private static int start(CommandSourceStack source, String missionId)
            throws CommandSyntaxException {
        return MissionService.startStory(source.getPlayerOrException(), missionId) ? 1 : 0;
    }

    private static int abandon(CommandSourceStack source) throws CommandSyntaxException {
        if (MissionService.abandon(source.getPlayerOrException())) return 1;
        source.sendFailure(Component.literal("No active mission or gig."));
        return 0;
    }
}
