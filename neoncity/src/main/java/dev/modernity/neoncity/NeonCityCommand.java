package dev.modernity.neoncity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Administrative generation/status commands for previews and large builds. */
public final class NeonCityCommand {
    private NeonCityCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("neoncity")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("locate")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> locate(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "z"))))))
                        .then(Commands.literal("atlas")
                                .then(Commands.argument("district", StringArgumentType.word())
                                        .executes(context -> atlas(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "district")))))
                        .then(Commands.literal("generate")
                                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 12))
                                                        .executes(context -> enqueue(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "chunkX"),
                                                                IntegerArgumentType.getInteger(context, "chunkZ"),
                                                                IntegerArgumentType.getInteger(context, "radius")))))))
        );
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(String.format(
                "Megacity: enabled=%s, districts=%d, edges=%d, generated=%d, queued=%d, seed=%d, fingerprint=%s",
                NeonCityGenerator.isEnabled(),
                NeonCityGenerator.layout().nodes().size(),
                NeonCityGenerator.layout().edges().size(),
                NeonCityGenerator.generatedChunks(),
                NeonCityGenerator.pendingChunks(),
                NeonCityGenerator.layout().seed(),
                NeonCityGenerator.GENERATOR_FINGERPRINT)), false);
        return NeonCityGenerator.generatedChunks();
    }

    private static int locate(CommandSourceStack source, int x, int z) {
        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
        source.sendSuccess(() -> Component.literal(String.format(
                "%s at (%d,%d): zone=%s, infrastructure=%s, center=(%d,%d), distance=%.3f",
                sample.district().label(), x, z, sample.zone(), sample.roadClass(),
                sample.location().primary().x(), sample.location().primary().z(),
                sample.location().normalizedDistance())), false);
        return sample.district().ordinal() + 1;
    }

    private static int enqueue(CommandSourceStack source, int chunkX, int chunkZ, int radius) {
        int added = NeonCityGenerator.enqueueAroundChunk(chunkX, chunkZ, radius);
        source.sendSuccess(() -> Component.literal(String.format(
                "Queued %d chunks around (%d,%d), radius %d. Chunks generate when loaded.",
                added, chunkX, chunkZ, radius)), true);
        return added;
    }

    private static int atlas(CommandSourceStack source, String code) {
        District district;
        try {
            String normalized = code.toUpperCase(Locale.ROOT);
            if (normalized.length() != 1) throw new IllegalArgumentException();
            district = District.valueOf(normalized + "_CORP");
        } catch (IllegalArgumentException error) {
            source.sendFailure(Component.literal("District must be one letter from A through Z."));
            return 0;
        }
        var placement = ArnisPatchLibrary.findNearest(
                NeonCityGenerator.layout(), district, 96);
        if (placement.isEmpty()) {
            source.sendFailure(Component.literal(
                    district.label() + " has no curated Arnis atlas in this build."));
            return 0;
        }
        ArnisPatchLibrary.Placement found = placement.get();
        String zone = found.patch().placementZones().iterator().next().name();
        source.sendSuccess(() -> Component.literal(String.format(
                "%s zone=%s atlas=%s chunk=(%d,%d) block=(%d,%d) sourceTile=(%d,%d) "
                        + "transform=%s/%s sourceSurfaceY=%d",
                district.label(), zone, found.patch().catalogId(), found.chunkX(), found.chunkZ(),
                found.chunkX() << 4, found.chunkZ() << 4,
                found.sourceTileX(), found.sourceTileZ(), found.mirror(), found.rotation(),
                found.patch().sourceSurfaceY())), false);
        return 1;
    }
}
