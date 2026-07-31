package dev.modernity.neoncity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;

/** Administrative generation/status commands for previews and large builds. */
public final class NeonCityCommand {
    private static final List<String> DISTRICT_CODES = Arrays.stream(District.values())
            .map(District::code)
            .toList();

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
                        .then(Commands.literal("port")
                                .executes(context -> port(context.getSource())))
                        .then(Commands.literal("atlas")
                                .then(Commands.argument("district", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                        .executes(context -> atlas(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "district")))))
                        .then(Commands.literal("merchant")
                                .then(Commands.argument("district", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                        .executes(context -> merchant(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "district")))))
                        .then(Commands.literal("teleport")
                                .then(Commands.argument("district", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                        .executes(context -> teleport(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "district")))))
                        .then(Commands.literal("mission")
                                .then(Commands.literal("reload")
                                        .executes(context -> reloadMissions(context.getSource())))
                                .then(Commands.literal("clear")
                                        .executes(context -> clearMission(context.getSource())))
                                .then(Commands.literal("start")
                                        .then(Commands.argument("definition", StringArgumentType.word())
                                                .suggests((context, builder) ->
                                                        SharedSuggestionProvider.suggest(
                                                                MissionCatalog.definitions().stream()
                                                                        .map(MissionCatalog.MissionDefinition::id)
                                                                        .toList(),
                                                                builder))
                                                .then(Commands.argument(
                                                                "district",
                                                                StringArgumentType.word())
                                                        .suggests((context, builder) ->
                                                                SharedSuggestionProvider.suggest(
                                                                        DISTRICT_CODES, builder))
                                                        .executes(context -> startMission(
                                                                context.getSource(),
                                                                StringArgumentType.getString(
                                                                        context, "definition"),
                                                                StringArgumentType.getString(
                                                                        context, "district")))))))
                        .then(Commands.literal("generate")
                                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 12))
                                                        .executes(context -> enqueue(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "chunkX"),
                                                                IntegerArgumentType.getInteger(context, "chunkZ"),
                                                                IntegerArgumentType.getInteger(context, "radius")))))))
                        .then(Commands.literal("generate_now")
                                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 4))
                                                        .executes(context -> generateNow(
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

    private static int port(CommandSourceStack source) {
        UCorpPortGeneration.Plan plan = UCorpPortGeneration.plan(NeonCityGenerator.layout());
        BlockPos port = new BlockPos(
                plan.worldX(plan.portStart(), 0),
                NeonCityGenerator.CITY_GROUND_Y + 1,
                plan.worldZ(plan.portStart(), 0));
        BlockPos shore = new BlockPos(
                plan.worldX(plan.shoreline(), 0),
                NeonCityGenerator.WATER_Y,
                plan.worldZ(plan.shoreline(), 0));
        source.sendSuccess(() -> Component.literal(String.format(
                "U Corp port: origin=(%d,%d), outward=(%d,%d), terminal=(%d,%d), "
                        + "shore=(%d,%d), forward=%d..%d, halfWidth=%d, Portships=%d",
                plan.originX(), plan.originZ(), plan.forwardX(), plan.forwardZ(),
                port.getX(), port.getZ(), shore.getX(), shore.getZ(),
                plan.portStart(), plan.oceanEnd(), plan.oceanHalfWidth(),
                plan.portships().size())), false);
        for (UCorpPortGeneration.Portship ship : plan.portships()) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "Portship %d: center=(%d,%d), chunk=(%d,%d), bounds=[%d..%d, %d..%d]",
                    ship.index() + 1, ship.centerX(), ship.centerZ(),
                    Math.floorDiv(ship.centerX(), 16), Math.floorDiv(ship.centerZ(), 16),
                    ship.minX(), ship.maxX(), ship.minZ(), ship.maxZ())), false);
        }
        return plan.portships().size();
    }

    private static int enqueue(CommandSourceStack source, int chunkX, int chunkZ, int radius) {
        int added = NeonCityGenerator.enqueueAroundChunk(chunkX, chunkZ, radius);
        source.sendSuccess(() -> Component.literal(String.format(
                "Queued %d chunks around (%d,%d), radius %d. Chunks generate when loaded.",
                added, chunkX, chunkZ, radius)), true);
        return added;
    }

    private static int generateNow(
            CommandSourceStack source,
            int chunkX,
            int chunkZ,
            int radius) {
        int generated = NeonCityGenerator.generateNow(
                source.getLevel(), chunkX, chunkZ, radius);
        source.sendSuccess(() -> Component.literal(String.format(
                "Generated %d new chunks around (%d,%d), radius %d.",
                generated, chunkX, chunkZ, radius)), true);
        return generated;
    }

    private static int atlas(CommandSourceStack source, String code) {
        Optional<District> parsed = parseDistrict(code);
        if (parsed.isEmpty()) {
            source.sendFailure(Component.literal("District must be one letter from A through Z."));
            return 0;
        }
        District district = parsed.get();
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

    private static int merchant(CommandSourceStack source, String code) {
        Optional<District> parsed = parseDistrict(code);
        if (parsed.isEmpty()) {
            source.sendFailure(Component.literal("District must be one letter from A through Z."));
            return 0;
        }
        District district = parsed.get();
        Optional<MerchantTruckLibrary.TruckCandidate> candidate =
                MerchantTruckLibrary.canonicalBlackTruck(district);
        if (candidate.isEmpty()) {
            source.sendFailure(Component.literal(
                    district.label() + " has no park footprint large enough for a merchant truck."));
            return 0;
        }
        MerchantTruckLibrary.TruckCandidate found = candidate.get();
        source.sendSuccess(() -> Component.literal(String.format(
                "%s fixer truck block=(%d,%d,%d) chunk=(%d,%d) cluster=(%d,%d)",
                district.label(), found.minX(), found.groundY() + 1, found.minZ(),
                found.chunkX(), found.chunkZ(), found.clusterX(), found.clusterZ())), false);
        return 1;
    }

    private static int reloadMissions(CommandSourceStack source) {
        try {
            int count = MissionCatalog.reloadConfiguration();
            source.sendSuccess(() -> Component.literal(String.format(
                    "Loaded %d missions from %s.",
                    count, MissionCatalog.configurationPath().toAbsolutePath())), true);
            return count;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int clearMission(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!MissionService.abandon(player)) {
            source.sendFailure(Component.literal("No active mission to clear."));
            return 0;
        }
        return 1;
    }

    private static int startMission(
            CommandSourceStack source,
            String definitionId,
            String districtCode) throws CommandSyntaxException {
        Optional<District> parsed = parseDistrict(districtCode);
        if (parsed.isEmpty()) {
            source.sendFailure(Component.literal("District must be one letter from A through Z."));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = (ServerLevel) player.level();
        if (!NeonCityGenerator.isEnabled() || !NeonCityGenerator.isMegacityWorld(level)) {
            source.sendFailure(Component.literal(
                    "Configured missions require a Project Moon Megacity world."));
            return 0;
        }
        if (!MissionService.startConfigured(player, definitionId, parsed.get())) return 0;
        MissionService.ActiveMission mission = MissionService.activeMission(player).orElseThrow();
        source.sendSuccess(() -> Component.literal(String.format(
                "Staged %s in District %s at (%d, %d, %d).",
                mission.title(), mission.targetDistrict().code(),
                mission.target().getX(), mission.target().getY(), mission.target().getZ())), true);
        return 1;
    }

    private static int teleport(CommandSourceStack source, String code)
            throws CommandSyntaxException {
        Optional<District> parsed = parseDistrict(code);
        if (parsed.isEmpty()) {
            source.sendFailure(Component.literal("District must be one letter from A through Z."));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel destinationLevel = source.getServer().overworld();
        if (!NeonCityGenerator.isEnabled()
                || !NeonCityGenerator.isMegacityWorld(destinationLevel)) {
            source.sendFailure(Component.literal(
                    "District teleport is only available in a Project Moon Megacity world."));
            return 0;
        }

        District district = parsed.get();
        BlockPos station = QuicktimeTravelService.canonicalStation(district);
        ChunkPos centerChunk = ChunkPos.containing(station);
        NeonCityGenerator.generateNow(destinationLevel, centerChunk.x(), centerChunk.z(), 0);
        if (!NeonCityGenerator.isGenerated(centerChunk)) {
            source.sendFailure(Component.literal(
                    "Could not prepare the center of " + district.label() + "."));
            return 0;
        }

        QuicktimeTravelService.installCanonicalStations(destinationLevel, centerChunk);
        Optional<BlockPos> arrival = QuicktimeTravelService.findSafeArrival(
                destinationLevel, player, station);
        if (arrival.isEmpty()) {
            source.sendFailure(Component.literal(
                    "The center of " + district.label() + " has no safe arrival point."));
            return 0;
        }

        ServerLevel sourceLevel = player.level();
        BlockPos sourcePos = player.blockPosition();
        BlockPos destination = arrival.get();
        if (!QuicktimeTravelService.teleportPlayer(player, destinationLevel, destination)) {
            source.sendFailure(Component.literal(
                    "Teleport to " + district.label() + " failed."));
            return 0;
        }

        sourceLevel.playSound(null, sourcePos, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 0.7F, 1.35F);
        destinationLevel.playSound(null, destination, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 0.7F, 1.55F);
        source.sendSuccess(() -> Component.literal(String.format(
                "Teleported to %s center at (%d, %d, %d).",
                district.label(), destination.getX(), destination.getY(), destination.getZ())), true);
        return 1;
    }

    static Optional<District> parseDistrict(String code) {
        String normalized = code.toUpperCase(Locale.ROOT);
        if (normalized.length() != 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(District.valueOf(normalized + "_CORP"));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }
}
