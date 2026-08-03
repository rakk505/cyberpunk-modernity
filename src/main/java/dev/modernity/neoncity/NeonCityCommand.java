package dev.modernity.neoncity;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
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
    private static final int MAX_BUILDING_SUMMARY_LINES = 24;
    private static final List<String> DISTRICT_CODES = Arrays.stream(District.values())
            .map(District::commandCode)
            .toList();

    private NeonCityCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("neoncity")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(traceCommands())
                        .then(pregenCommands())
                        .then(Commands.literal("locate")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> locate(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "x"),
                                                        IntegerArgumentType.getInteger(context, "z"))))))
                        .then(Commands.literal("port")
                                .executes(context -> port(context.getSource())))
                        .then(buildingCommands())
                        .then(Commands.literal("atlas")
                                .then(Commands.argument("district", StringArgumentType.greedyString())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                        .executes(context -> atlas(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "district")))))
                        .then(Commands.literal("merchant")
                                .then(Commands.argument("district", StringArgumentType.greedyString())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                        .executes(context -> merchant(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "district")))))
                        .then(Commands.literal("teleport")
                                .then(Commands.argument("district", StringArgumentType.greedyString())
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
                                                                StringArgumentType.greedyString())
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

    private static LiteralArgumentBuilder<CommandSourceStack> buildingCommands() {
        return Commands.literal("buildings")
                .requires(CommandSourceStack::isPlayer)
                .then(Commands.literal("summary")
                        .executes(context -> buildingSummary(
                                context.getSource(), BuildingInspectionService.DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(
                                        0, BuildingInspectionService.MAX_RADIUS))
                                .executes(context -> buildingSummary(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("inspect")
                        .executes(context -> inspectBuilding(
                                context.getSource(), BuildingInspectionService.DEFAULT_RADIUS))
                        .then(Commands.literal("off")
                                .executes(context -> clearBuildingInspection(
                                        context.getSource())))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(
                                        0, BuildingInspectionService.MAX_RADIUS))
                                .executes(context -> inspectBuilding(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pregenCommands() {
        return Commands.literal("pregen")
                .then(Commands.literal("status")
                        .executes(context -> pregenStatus(context.getSource())))
                .then(Commands.literal("pause")
                        .executes(context -> pausePregen(context.getSource())))
                .then(Commands.literal("resume")
                        .executes(context -> resumePregen(context.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> traceCommands() {
        return Commands.literal("trace")
                .then(Commands.literal("status")
                        .executes(context -> traceStatus(context.getSource())))
                .then(Commands.literal("start")
                        .executes(context -> startTrace(context.getSource(), 60))
                        .then(Commands.argument(
                                        "seconds", IntegerArgumentType.integer(1, 3_600))
                                .executes(context -> startTrace(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("stop")
                        .executes(context -> stopTrace(context.getSource())))
                .then(Commands.literal("export")
                        .executes(context -> exportTrace(context.getSource())));
    }

    private static int status(CommandSourceStack source) {
        CityPriorityPreGenerator.Status pregen = CityPriorityPreGenerator.status();
        source.sendSuccess(() -> Component.literal(String.format(
                "Megacity: enabled=%s, districts=%d, edges=%d, generated=%d, queued=%d, "
                        + "urgent=%d, near=%d, pregen=%d/%d, seed=%d, fingerprint=%s",
                NeonCityGenerator.isEnabled(),
                NeonCityGenerator.layout().nodes().size(),
                NeonCityGenerator.layout().edges().size(),
                NeonCityGenerator.generatedChunks(),
                NeonCityGenerator.pendingChunks(),
                NeonCityGenerator.urgentPendingChunks(),
                NeonCityGenerator.nearPendingChunks(),
                pregen.complete(),
                pregen.total(),
                NeonCityGenerator.layout().seed(),
                NeonCityGenerator.GENERATOR_FINGERPRINT)), false);
        return NeonCityGenerator.generatedChunks();
    }

    private static int pregenStatus(CommandSourceStack source) {
        CityPriorityPreGenerator.Status status = CityPriorityPreGenerator.status();
        source.sendSuccess(() -> Component.literal(String.format(
                "Priority pre-generation: complete=%d/%d, remaining=%d, paused=%s, loading=%s",
                status.complete(), status.total(), status.remaining(), status.paused(),
                status.loading())), false);
        return status.complete();
    }

    private static int pausePregen(CommandSourceStack source) {
        CityPriorityPreGenerator.pause();
        source.sendSuccess(() -> Component.literal("Priority pre-generation paused."), true);
        return 1;
    }

    private static int resumePregen(CommandSourceStack source) {
        CityPriorityPreGenerator.resume();
        source.sendSuccess(() -> Component.literal("Priority pre-generation resumed."), true);
        return 1;
    }

    private static int startTrace(CommandSourceStack source, int seconds) {
        if (!NeonCityGenerator.isMegacityWorld(source.getLevel())) {
            source.sendFailure(Component.literal(
                    "Generation tracing is only available in a Project Moon Megacity world."));
            return 0;
        }
        if (!CityGenerationTrace.start(source.getLevel(), seconds)) {
            source.sendFailure(Component.literal(
                    "A generation trace is already active; stop it before starting another."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "Generation trace started for %d seconds.", seconds)), true);
        return 1;
    }

    private static int stopTrace(CommandSourceStack source) {
        if (!CityGenerationTrace.stop()) {
            source.sendFailure(Component.literal("No generation trace is active."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Generation trace stopped."), true);
        traceStatus(source);
        return 1;
    }

    private static int traceStatus(CommandSourceStack source) {
        CityGenerationTrace.Status status = CityGenerationTrace.status();
        if (!status.available()) {
            source.sendFailure(Component.literal(
                    "No generation trace is active and no completed trace is available."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "Generation trace: active=%s, elapsed=%.1f/%ds, chunks=%d, failures=%d, "
                        + "rate=%.2f chunks/s, stamp avg/p50/p95/p99="
                        + "%.2f/%.2f/%.2f/%.2f ms",
                status.active(), status.elapsedSeconds(), status.targetSeconds(),
                status.chunks(), status.failures(), status.chunksPerSecond(),
                status.averageStampMillis(), status.p50StampMillis(),
                status.p95StampMillis(), status.p99StampMillis())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "Queue avg=%.2f ms, load avg=%.2f ms, direct changes=%d, "
                        + "pregen skips foreground/tick-budget=%d/%d, samples=%d, reason=%s",
                status.averageQueueMillis(), status.averageLoadMillis(),
                status.directBlockChanges(), status.foregroundSkips(),
                status.tickBudgetSkips(), status.sampledRecords(), status.stopReason())), false);
        source.sendSuccess(() -> Component.literal(String.format(
                "Foreground batching: batches=%d, extra chunks=%d, budget stops=%d",
                status.foregroundBatches(), status.foregroundExtraChunks(),
                status.foregroundBudgetStops())), false);
        if (status.lookaheadSamples() > 0L) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "Driving headroom: ready=%.1f blocks, latest/min=%.2f/%.2f seconds, "
                            + "samples=%d, position-fallback=%d",
                    status.latestReadyAheadBlocks(), status.latestHeadroomSeconds(),
                    status.minimumHeadroomSeconds(), status.lookaheadSamples(),
                    status.positionFallbackSamples())), false);
        }
        return (int) Math.min(Integer.MAX_VALUE, status.chunks());
    }

    private static int exportTrace(CommandSourceStack source) {
        try {
            Path exported = CityGenerationTrace.export(source.getLevel());
            if (exported == null) {
                source.sendFailure(Component.literal("There is no generation trace to export."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "Generation trace exported to " + exported.toAbsolutePath()), true);
            return 1;
        } catch (IOException exception) {
            source.sendFailure(Component.literal(
                    "Could not export generation trace: " + exception.getMessage()));
            return 0;
        }
    }

    private static int buildingSummary(CommandSourceStack source, int radius)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BuildingInspectionService.ScanView view;
        try {
            view = BuildingInspectionService.scan(player, radius);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        long ready = view.buildings().stream()
                .filter(MissionBuildingPlanner.BuildingLabel::missionReady)
                .count();
        source.sendSuccess(() -> Component.literal(String.format(
                        "Building atlas // %s // radius=%d // source=%s // walkable=%d // "
                                + "buildings=%d // ready=%d // scan=%s",
                        view.district().label(), view.radius(), view.cached() ? "cache" : "loaded world",
                        view.scan().walkableCellCount(), view.buildings().size(), ready,
                        formatBounds(view.scan().scanBounds())))
                .withStyle(ChatFormatting.AQUA), false);
        int shown = Math.min(MAX_BUILDING_SUMMARY_LINES, view.buildings().size());
        for (int index = 0; index < shown; index++) {
            MissionBuildingPlanner.BuildingLabel building = view.buildings().get(index);
            source.sendSuccess(() -> buildingSummaryLine(building), false);
        }
        int omitted = view.buildings().size() - shown;
        if (omitted > 0) {
            source.sendSuccess(() -> Component.literal(
                    omitted + " additional labels omitted; use a smaller radius."), false);
        }
        if (view.buildings().isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No segmented buildings intersect the loaded scan area."), false);
        }
        return Math.max(1, view.buildings().size());
    }

    private static int inspectBuilding(CommandSourceStack source, int radius)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        BuildingInspectionService.Inspection inspection;
        try {
            inspection = BuildingInspectionService.inspect(player, radius);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        MissionBuildingPlanner.BuildingLabel building = inspection.building();
        source.sendSuccess(() -> Component.literal(String.format(
                        "%s // %s // %s // bounds=%s",
                        building.missionReady() ? "READY" : "REJECTED",
                        building.id(), inspection.view().district().label(),
                        formatBounds(building.bounds())))
                .withStyle(building.missionReady()
                        ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        for (int index = 0; index < building.floorYs().size(); index++) {
            int floor = index + 1;
            int y = building.floorYs().get(index);
            int cells = index < building.walkableCellsPerFloor().size()
                    ? building.walkableCellsPerFloor().get(index) : 0;
            source.sendSuccess(() -> Component.literal(String.format(
                    "F%d // y=%d // walkable=%d", floor, y, cells)), false);
        }
        source.sendSuccess(() -> Component.literal("Decision // " + building.decision()), false);
        inspection.site().ifPresent(site -> source.sendSuccess(() -> Component.literal(String.format(
                "Plan // entrance=(%d,%d,%d) %s // target=(%d,%d,%d) // stairs=%d // routes=%d",
                site.entrance().position().getX(), site.entrance().position().getY(),
                site.entrance().position().getZ(), site.entrance().outward(),
                site.target().getX(), site.target().getY(), site.target().getZ(),
                site.stairs().size(), site.patrolRoutes().size())), false));
        source.sendSuccess(() -> Component.literal(String.format(
                "Player-local overlay active for %d seconds with %d markers.",
                BuildingInspectionService.OVERLAY_LIFETIME_TICKS / 20,
                inspection.debugPointCount())), false);
        return Math.max(1, inspection.debugPointCount());
    }

    private static int clearBuildingInspection(CommandSourceStack source)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!BuildingInspectionService.clear(player)) {
            source.sendFailure(Component.literal("No building inspection overlay is active."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Building inspection overlay cleared."), false);
        return 1;
    }

    private static Component buildingSummaryLine(
            MissionBuildingPlanner.BuildingLabel building) {
        String floors = java.util.stream.IntStream.range(0, building.floorYs().size())
                .mapToObj(index -> "F" + (index + 1) + "@" + building.floorYs().get(index)
                        + "=" + (index < building.walkableCellsPerFloor().size()
                                ? building.walkableCellsPerFloor().get(index) : 0))
                .collect(java.util.stream.Collectors.joining(","));
        return Component.literal(building.missionReady() ? "[READY] " : "[REJECT] ")
                .withStyle(building.missionReady()
                        ? ChatFormatting.GREEN : ChatFormatting.RED)
                .append(Component.literal(building.id()
                        + " // " + floors
                        + " // " + formatBounds(building.bounds())
                        + " // " + building.decision())
                        .withStyle(ChatFormatting.GRAY));
    }

    private static String formatBounds(
            net.minecraft.world.level.levelgen.structure.BoundingBox bounds) {
        return String.format("[%d..%d,%d..%d,%d..%d]",
                bounds.minX(), bounds.maxX(), bounds.minY(), bounds.maxY(),
                bounds.minZ(), bounds.maxZ());
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
            source.sendFailure(Component.literal("Unknown district code."));
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
            source.sendFailure(Component.literal("Unknown district code."));
            return 0;
        }
        District district = parsed.get();
        VendorService.ensureDistrictVendors(source.getLevel(), district);
        Optional<VendorAnchorData.Anchor> anchor =
                VendorAnchorData.get(source.getLevel()).fixer(district);
        if (anchor.isEmpty()) {
            source.sendFailure(Component.literal(
                    district.label() + " has no safe Arnis facade for a fixer stall."));
            return 0;
        }
        VendorAnchorData.Anchor found = anchor.get();
        source.sendSuccess(() -> Component.literal(String.format(
                "%s fixer stall block=(%d,%d,%d) chunk=(%d,%d)",
                district.label(), found.merchantPos().getX(), found.merchantPos().getY(),
                found.merchantPos().getZ(),
                Math.floorDiv(found.merchantPos().getX(), 16),
                Math.floorDiv(found.merchantPos().getZ(), 16))), false);
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
            source.sendFailure(Component.literal("Unknown district code."));
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
            source.sendFailure(Component.literal("Unknown district code."));
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
        return District.fromCode(code);
    }
}
