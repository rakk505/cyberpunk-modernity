package dev.modernity.neoncity;

import com.example.cyberdeck.network.GigJournalPacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
                        .then(roadCommands())
                        .then(osmSampleCommands())
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
                        .then(gigSiteCommands())
                        .then(Commands.literal("story_anchor")
                                .then(Commands.argument("mission", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                SharedSuggestionProvider.suggest(
                                                        StoryMissionCatalog.definitions().stream()
                                                                .map(StoryMissionCatalog.StoryMission::id)
                                                                .toList(),
                                                        builder))
                                        .then(Commands.argument("node", StringArgumentType.word())
                                                .executes(context -> storyAnchor(
                                                        context.getSource(),
                                                        StringArgumentType.getString(
                                                                context, "mission"),
                                                        StringArgumentType.getString(
                                                                context, "node"))))))
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

    private static LiteralArgumentBuilder<CommandSourceStack> gigSiteCommands() {
        return Commands.literal("gig_sites")
                .then(Commands.literal("parse")
                        .then(Commands.argument("district", StringArgumentType.greedyString())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                .executes(context -> parseGigSites(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "district")))))
                .then(Commands.literal("rescan")
                        .then(Commands.argument("district", StringArgumentType.greedyString())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                .executes(context -> rescanGigSites(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "district")))))
                .then(Commands.literal("prune")
                        .then(Commands.argument("district", StringArgumentType.word())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                .then(Commands.argument("site", StringArgumentType.greedyString())
                                        .executes(context -> pruneGigSite(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "district"),
                                                StringArgumentType.getString(
                                                        context, "site"))))))
                .then(Commands.literal("export")
                        .then(Commands.argument("artifact", StringArgumentType.word())
                                .then(Commands.argument(
                                                "districts", StringArgumentType.greedyString())
                                        .executes(context -> exportGigSites(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "artifact"),
                                                StringArgumentType.getString(
                                                        context, "districts"))))))
                .then(Commands.literal("merge")
                        .then(Commands.argument("artifact", StringArgumentType.word())
                                .then(Commands.argument("shards", StringArgumentType.greedyString())
                                        .executes(context -> mergeGigSites(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "artifact"),
                                                StringArgumentType.getString(
                                                        context, "shards"))))))
                .then(Commands.literal("audit_reads")
                        .then(Commands.argument("district", StringArgumentType.greedyString())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                .executes(context -> auditGigSiteReads(
                                        context.getSource(), StringArgumentType.getString(
                                                context, "district")))))
                .then(Commands.literal("audit_plans")
                        .then(Commands.argument("district", StringArgumentType.greedyString())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                .executes(context -> auditGigSitePlans(
                                        context.getSource(), StringArgumentType.getString(
                                                context, "district")))));
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
                        .executes(context -> exportTrace(context.getSource())))
                .then(Commands.literal("benchmark")
                        .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                                .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                                        .then(Commands.argument(
                                                        "radius",
                                                        IntegerArgumentType.integer(0, 12))
                                                .then(Commands.argument(
                                                                "seconds",
                                                                IntegerArgumentType.integer(
                                                                        1, 3_600))
                                                        .executes(context -> benchmarkTrace(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(
                                                                        context, "chunkX"),
                                                                IntegerArgumentType.getInteger(
                                                                        context, "chunkZ"),
                                                                IntegerArgumentType.getInteger(
                                                                        context, "radius"),
                                                                IntegerArgumentType.getInteger(
                                                                        context,
                                                                        "seconds"))))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> roadCommands() {
        return Commands.literal("roads")
                .executes(context -> roadOverlayStatus(context.getSource()))
                .then(Commands.literal("on")
                        .executes(context -> enableRoadOverlay(
                                context.getSource(), RoadDebugOverlayService.DEFAULT_RADIUS))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(
                                        RoadDebugOverlayService.MIN_RADIUS,
                                        RoadDebugOverlayService.MAX_RADIUS))
                                .executes(context -> enableRoadOverlay(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("off")
                        .executes(context -> disableRoadOverlay(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> roadOverlayStatus(context.getSource())))
                .then(Commands.literal("junction")
                        .then(Commands.argument("district", StringArgumentType.greedyString())
                                .suggests((context, builder) ->
                                        SharedSuggestionProvider.suggest(DISTRICT_CODES, builder))
                                .executes(context -> roadJunction(
                                        context.getSource(), StringArgumentType.getString(
                                                context, "district")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> osmSampleCommands() {
        return Commands.literal("osm_sample")
                .then(Commands.literal("list")
                        .executes(context -> listOsmSamples(context.getSource())))
                .then(Commands.literal("load")
                        .executes(context -> osmSampleLoadWarning(
                                context.getSource(), "singapore"))
                        .then(Commands.literal("confirm")
                                .executes(context -> startOsmSampleLoad(
                                        context.getSource(), "singapore")))
                        .then(Commands.argument("sample", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        OsmRoadSample.suggestions(), builder))
                                .executes(context -> osmSampleLoadWarning(
                                        context.getSource(), StringArgumentType.getString(
                                                context, "sample")))
                                .then(Commands.literal("confirm")
                                        .executes(context -> startOsmSampleLoad(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "sample"))))))
                .then(Commands.literal("cancel")
                        .executes(context -> cancelOsmSampleLoad(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> osmSampleStatus(context.getSource())))
                .then(Commands.literal("overlay")
                        .then(Commands.literal("on")
                                .executes(context -> enableOsmSampleOverlay(
                                        context.getSource(),
                                        ArnisOsmDebugService.DEFAULT_OVERLAY_RADIUS))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(
                                                ArnisOsmDebugService.MIN_OVERLAY_RADIUS,
                                                ArnisOsmDebugService.MAX_OVERLAY_RADIUS))
                                        .executes(context -> enableOsmSampleOverlay(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(
                                                        context, "radius")))))
                        .then(Commands.literal("off")
                                .executes(context -> disableOsmSampleOverlay(
                                        context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> osmSampleOverlayStatus(
                                        context.getSource()))));
    }

    private static int listOsmSamples(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Available OSM samples ("
                + OsmRoadSample.samples().size() + "): "
                + String.join(", ", OsmRoadSample.samples().stream()
                        .map(OsmRoadSample.Sample::id).toList())), false);
        return OsmRoadSample.samples().size();
    }

    private static int osmSampleLoadWarning(CommandSourceStack source, String sampleName) {
        var sample = OsmRoadSample.find(sampleName);
        if (sample.isEmpty()) {
            source.sendFailure(Component.literal("Unknown OSM sample '" + sampleName
                    + "'. Use /neoncity osm_sample list."));
            return 0;
        }
        source.sendFailure(Component.literal(
                "Loading " + sample.get().name() + " (" + sample.get().id()
                        + ") permanently replaces every block above Y=67 in the aligned "
                        + "16x16-chunk region containing you. Run /neoncity osm_sample load "
                        + sampleName + " confirm to proceed."));
        return 0;
    }

    private static int startOsmSampleLoad(CommandSourceStack source, String sampleName)
            throws CommandSyntaxException {
        var sample = OsmRoadSample.find(sampleName);
        if (sample.isEmpty()) {
            source.sendFailure(Component.literal("Unknown OSM sample '" + sampleName
                    + "'. Use /neoncity osm_sample list."));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        if (!ArnisOsmDebugService.start(player, sample.get())) {
            source.sendFailure(Component.literal(
                    "An OSM atlas load is already active or the sample atlas is unavailable."));
            return 0;
        }
        ArnisOsmDebugService.Status status = ArnisOsmDebugService.status();
        source.sendSuccess(() -> Component.literal(String.format(
                "Loading %s (%s) into chunks (%d,%d)..(%d,%d), "
                        + "one tile per tick.",
                status.sampleName(), status.sampleId(),
                status.originChunkX(), status.originChunkZ(),
                status.originChunkX() + 15, status.originChunkZ() + 15)), true);
        return 1;
    }

    private static int cancelOsmSampleLoad(CommandSourceStack source) {
        boolean cancelled = ArnisOsmDebugService.cancel();
        source.sendSuccess(() -> Component.literal(cancelled
                ? "OSM atlas load cancelled. Already replaced chunks are not restored."
                : "No OSM atlas load is active."), true);
        return cancelled ? 1 : 0;
    }

    private static int osmSampleStatus(CommandSourceStack source) {
        ArnisOsmDebugService.Status status = ArnisOsmDebugService.status();
        source.sendSuccess(() -> Component.literal(status.active()
                ? String.format("OSM atlas load active: %s, %d/%d tiles at origin chunk (%d,%d).",
                        status.sampleId(), status.completed(), status.total(),
                        status.originChunkX(), status.originChunkZ())
                : "No OSM atlas load is active."), false);
        return status.completed();
    }

    private static int enableOsmSampleOverlay(CommandSourceStack source, int radius)
            throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ArnisOsmDebugService.enableOverlay(player, radius);
        ArnisOsmDebugService.OverlayStatus status =
                ArnisOsmDebugService.overlayStatus(player);
        source.sendSuccess(() -> Component.literal(String.format(
                "OSM overlay enabled for %s at atlas origin chunk (%d,%d), radius %d. "
                        + "Red=blocked, over one-block step, or missing headroom; "
                        + "cyan=primary; yellow=secondary; "
                        + "green=local; white=service; magenta=trunk.",
                status.sampleId(), status.originChunkX(), status.originChunkZ(),
                status.radius())), false);
        return 1;
    }

    private static int disableOsmSampleOverlay(CommandSourceStack source)
            throws CommandSyntaxException {
        boolean disabled = ArnisOsmDebugService.disableOverlay(source.getPlayerOrException());
        source.sendSuccess(() -> Component.literal(disabled
                ? "OSM overlay disabled."
                : "OSM overlay was not enabled."), false);
        return disabled ? 1 : 0;
    }

    private static int osmSampleOverlayStatus(CommandSourceStack source)
            throws CommandSyntaxException {
        ArnisOsmDebugService.OverlayStatus status =
                ArnisOsmDebugService.overlayStatus(source.getPlayerOrException());
        source.sendSuccess(() -> Component.literal(status.enabled()
                ? String.format("OSM overlay enabled: sample=%s, origin=(%d,%d), radius=%d.",
                        status.sampleId(), status.originChunkX(), status.originChunkZ(),
                        status.radius())
                : "OSM overlay disabled."), false);
        return status.enabled() ? 1 : 0;
    }

    private static int enableRoadOverlay(CommandSourceStack source, int radius)
            throws CommandSyntaxException {
        if (!NeonCityGenerator.isMegacityWorld(source.getLevel())) {
            source.sendFailure(Component.literal(
                    "Road debugging is only available in a Project Moon Megacity world."));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        RoadDebugOverlayService.enable(player, radius);
        source.sendSuccess(() -> Component.literal(String.format(
                "Road overlay enabled (%d blocks), including mapped OSM atlas streets. "
                        + "Green=local, cyan=primary/boulevard, yellow=secondary/highway, "
                        + "orange=bridge, magenta=motorway/rail, red=highway buffer, "
                        + "white=service/plaza, gray=alley.",
                radius)), false);
        return 1;
    }

    private static int disableRoadOverlay(CommandSourceStack source)
            throws CommandSyntaxException {
        boolean disabled = RoadDebugOverlayService.disable(source.getPlayerOrException());
        source.sendSuccess(() -> Component.literal(disabled
                ? "Road overlay disabled."
                : "Road overlay was not enabled."), false);
        return disabled ? 1 : 0;
    }

    private static int roadOverlayStatus(CommandSourceStack source)
            throws CommandSyntaxException {
        int radius = RoadDebugOverlayService.radius(source.getPlayerOrException());
        source.sendSuccess(() -> Component.literal(radius > 0
                ? "Road overlay enabled with radius " + radius + "."
                : "Road overlay disabled. Use /neoncity roads on [radius]."), false);
        return radius;
    }

    private static int roadJunction(CommandSourceStack source, String code) {
        Optional<District> district = parseDistrict(code);
        if (district.isEmpty()) {
            source.sendFailure(Component.literal("Unknown district code."));
            return 0;
        }
        Optional<NeonCityGenerator.HighwayFeederDebug> feeder =
                NeonCityGenerator.highwayFeederDebug(district.get());
        if (feeder.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No arterial highway feeder is available for " + district.get().label() + "."));
            return 0;
        }
        NeonCityGenerator.HighwayFeederDebug found = feeder.get();
        source.sendSuccess(() -> Component.literal(String.format(
                "%s arterial junction: highway=(%.1f,%.1f), arterial=(%.1f,%.1f), class=%s.",
                district.get().label(), found.startX(), found.startZ(),
                found.endX(), found.endZ(), found.targetRoad())), false);
        return 1;
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

    private static int benchmarkTrace(
            CommandSourceStack source, int chunkX, int chunkZ, int radius, int seconds)
            throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        if (!NeonCityGenerator.isMegacityWorld(level)) {
            source.sendFailure(Component.literal(
                    "Generation tracing is only available in a Project Moon Megacity world."));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        // Prepare the anchor and teleport there so the chunk system loads its surroundings;
        // otherwise queued chunks never become loaded-and-stampable.
        NeonCityGenerator.generateNow(level, chunkX, chunkZ, 0);
        int blockX = (chunkX << 4) + 8;
        int blockZ = (chunkZ << 4) + 8;
        int y = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                blockX, blockZ) + 1;
        BlockPos destination = new BlockPos(blockX, y, blockZ);
        if (!QuicktimeTravelService.teleportPlayer(player, level, destination)) {
            source.sendFailure(Component.literal(
                    "Could not teleport to the benchmark anchor."));
            return 0;
        }
        if (!CityGenerationTrace.start(level, seconds)) {
            source.sendFailure(Component.literal(
                    "A generation trace is already active; stop it before starting another."));
            return 0;
        }
        CityGenerationTrace.markBenchmark(chunkX, chunkZ, radius);
        int added = NeonCityGenerator.enqueueAroundChunk(chunkX, chunkZ, radius);
        source.sendSuccess(() -> Component.literal(String.format(
                "Benchmark trace: teleported to chunk (%d,%d), queued %d chunks (radius %d), "
                        + "tracing %ds. For a clean A/B, run at the SAME anchor in a fresh world "
                        + "with the SAME seed (already-generated chunks will not regenerate).",
                chunkX, chunkZ, added, radius, seconds)), true);
        return added;
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

    private static int parseGigSites(CommandSourceStack source, String code) {
        return parseGigSites(source, code, false);
    }

    private static int rescanGigSites(CommandSourceStack source, String code) {
        return parseGigSites(source, code, true);
    }

    private static int parseGigSites(
            CommandSourceStack source, String code, boolean rebuild) {
        Optional<District> parsed = parseDistrict(code);
        if (parsed.isEmpty()) {
            source.sendFailure(Component.literal("Unknown district code."));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        if (!NeonCityGenerator.isEnabled() || !NeonCityGenerator.isMegacityWorld(level)) {
            source.sendFailure(Component.literal(
                    "Gig sites can only be parsed in a Project Moon Megacity world."));
            return 0;
        }
        District district = parsed.get();
        GigSiteData.ScanResult result;
        try {
            GigSiteData data = GigSiteData.get(level);
            result = rebuild
                    ? data.rebuildCandidates(level, district)
                    : data.ensureCandidates(level, district);
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(
                    "Gig-site parse failed: " + exception.getMessage()));
            return 0;
        }
        List<MissionBuildingPlanner.Site> sites = result.sites();
        source.sendSuccess(() -> Component.literal(String.format(
                "Gig scan summary // district=%s // candidates=%d // regions=%d // "
                        + "buildings=%d // ready=%d // reused=%s // structural=%s // filters=%s",
                district.commandCode(), sites.size(), result.regions(), result.buildings(),
                result.readyBuildings(), result.reused(), result.structuralDecisions(),
                result.filterRejections())), false);
        if (sites.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No verified gig buildings were found in " + district.label() + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(
                "Persisted %d verified gig buildings in %s.",
                sites.size(), district.label())).withStyle(ChatFormatting.AQUA), false);
        for (MissionBuildingPlanner.Site site : sites) {
            BlockPos door = site.entrance().position();
            BlockPos approach = MissionBuildingPlanner.navigationTarget(site);
            source.sendSuccess(() -> Component.literal(String.format(
                    "%s // door=(%d,%d,%d) // approach=(%d,%d,%d) // floors=%d",
                    site.id(), door.getX(), door.getY(), door.getZ(),
                    approach.getX(), approach.getY(), approach.getZ(),
                    site.floorYs().size())), false);
        }
        return sites.size();
    }

    private static int exportGigSites(
            CommandSourceStack source, String artifact, String districtList) {
        List<District> districts;
        try {
            districts = parseDistrictList(districtList);
            GigSiteData.ArtifactResult result = GigSiteData.exportShard(
                    source.getServer().overworld(), artifact, districts);
            source.sendSuccess(() -> Component.literal(String.format(
                    "Exported gig-site shard // path=%s // districts=%d // sites=%d // "
                            + "deficient=%s",
                    result.path(), result.districts(), result.sites(), result.deficient()))
                    .withStyle(result.deficient().isEmpty()
                            ? ChatFormatting.AQUA : ChatFormatting.YELLOW), false);
            return result.sites();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Gig-site export failed: "
                    + exception.getMessage()));
            return 0;
        }
    }

    private static int pruneGigSite(
            CommandSourceStack source, String districtCode, String siteId) {
        District district = parseDistrict(districtCode).orElse(null);
        if (district == null || siteId == null || siteId.isBlank()) {
            source.sendFailure(Component.literal("Unknown district or empty site ID."));
            return 0;
        }
        GigSiteData data = GigSiteData.get(source.getServer().overworld());
        int before = data.candidates(district).size();
        if (before <= GigSiteData.MIN_FIXED_SITES_PER_DISTRICT) {
            source.sendFailure(Component.literal(
                    "Cannot prune " + district.label() + " below "
                            + GigSiteData.MIN_FIXED_SITES_PER_DISTRICT + " sites."));
            return 0;
        }
        if (!data.remove(district, siteId)) {
            source.sendFailure(Component.literal("Unknown gig site " + siteId));
            return 0;
        }
        int retained = data.candidates(district).size();
        source.sendSuccess(() -> Component.literal(
                        "Pruned " + siteId + " from " + district.label()
                                + "; retained=" + retained)
                .withStyle(ChatFormatting.YELLOW), false);
        return retained;
    }

    private static int mergeGigSites(
            CommandSourceStack source, String artifact, String shardList) {
        try {
            List<String> shards = splitTokens(shardList);
            GigSiteData.ArtifactResult result = GigSiteData.mergeShards(
                    source.getServer().overworld(), artifact, shards);
            source.sendSuccess(() -> Component.literal(String.format(
                    "Merged gig-site catalog // path=%s // format=%d // seed=%d // "
                            + "layout=%d // generator=%s // districts=%d // sites=%d",
                    result.path(), GigSiteData.FORMAT_VERSION,
                    NeonCityGenerator.contentSeed(), NeonCityGenerator.layout().seed(),
                    NeonCityGenerator.GENERATOR_FINGERPRINT,
                    result.districts(), result.sites())).withStyle(ChatFormatting.AQUA), false);
            return result.sites();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("Gig-site merge failed: "
                    + exception.getMessage()));
            return 0;
        }
    }

    private static int auditGigSiteReads(CommandSourceStack source, String districtCode) {
        District district = parseDistrict(districtCode).orElse(null);
        if (district == null) {
            source.sendFailure(Component.literal("Unknown district code."));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        List<MissionBuildingPlanner.Site> candidates = GigSiteData.get(level).candidates(district);
        if (candidates.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No pre-analyzed gig markers for " + district.label()));
            return 0;
        }
        Set<Long> candidateChunks = candidateChunks(candidates);
        Set<Long> loadedBefore = loadedChunks(level, candidateChunks);
        int generatedBefore = NeonCityGenerator.generatedChunks();
        long scansBefore = ArnisBuildingAtlas.compilationRequests();

        AmbientGigData.OwnerKey owner = new AmbientGigData.OwnerKey(false,
                UUID.nameUUIDFromBytes(("cyberdeck:catalog-audit:" + district.name())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        AmbientGigService.ensureBoard(level, owner, district);
        List<AmbientGigService.DiscoveredGig> offers =
                AmbientGigService.availableOffers(level, owner, district);
        long mapMarkers = CityMapService.markers(
                        NeonCityGenerator.fixedLayout(), Optional.empty(), List.of(), offers)
                .stream()
                .filter(marker -> marker.kind()
                        == com.example.cyberdeck.network.OpenCityMapPacket.MarkerKind.AVAILABLE_GIG)
                .count();
        int journalEntries = GigJournalPacket.availableGigs(offers).size();

        Set<Long> newlyLoaded = loadedChunks(level, candidateChunks);
        newlyLoaded.removeAll(loadedBefore);
        boolean unchanged = loadedBefore.isEmpty()
                && ArnisBuildingAtlas.compilationRequests() == scansBefore
                && NeonCityGenerator.generatedChunks() == generatedBefore
                && newlyLoaded.isEmpty();
        boolean projections = offers.size() == AmbientGigService.OFFERS_PER_DISTRICT
                && mapMarkers == offers.size() && journalEntries == offers.size();
        String summary = String.format(
                "Gig read audit // district=%s // offers=%d // map_markers=%d // "
                        + "journal_entries=%d // candidate_chunks=%d // loaded_before=%d // "
                        + "scans=%d->%d // generated=%d->%d // new_candidate_chunks=%d",
                district.commandCode(), offers.size(), mapMarkers, journalEntries,
                candidateChunks.size(), loadedBefore.size(), scansBefore,
                ArnisBuildingAtlas.compilationRequests(), generatedBefore,
                NeonCityGenerator.generatedChunks(), newlyLoaded.size());
        if (!unchanged || !projections) {
            source.sendFailure(Component.literal("FAILED: " + summary));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("PASS: " + summary)
                .withStyle(ChatFormatting.AQUA), false);
        return offers.size();
    }

    private static int auditGigSitePlans(CommandSourceStack source, String districtCode) {
        District district = parseDistrict(districtCode).orElse(null);
        if (district == null) {
            source.sendFailure(Component.literal("Unknown district code."));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        if (!NeonCityGenerator.isEnabled() || !NeonCityGenerator.isMegacityWorld(level)) {
            source.sendFailure(Component.literal(
                    "Gig plans can only be audited in a Project Moon Megacity world."));
            return 0;
        }
        List<MissionBuildingPlanner.Site> candidates =
                GigSiteData.get(level).candidates(district);
        Set<Long> allCandidateChunks = candidateChunks(candidates);
        long scansBefore = ArnisBuildingAtlas.compilationRequests();
        ArrayList<String> failures = new ArrayList<>();
        int passed = 0;
        for (MissionBuildingPlanner.Site structural : candidates) {
            Set<Long> loadedBefore = loadedChunks(level, allCandidateChunks);
            Set<Long> selectedChunks = candidateChunks(List.of(structural));
            // Arnis facade completion may inspect one chunk beyond the planner's one-chunk
            // stabilization ring; neither layer may reach an unrelated remote candidate.
            Set<Long> selectedGenerationHalo = chunkHalo(selectedChunks, 2);
            MissionBuildingPlanner.RestorationSnapshot original = null;
            try {
                MissionBuildingPlanner.Site usable =
                        MissionBuildingPlanner.repairStructuralFloorMasks(level, structural);
                if (usable == null) {
                    String structuralFailure =
                            MissionBuildingPlanner.siteGeometryFailure(level, structural);
                    failures.add(structural.id() + "=structural " + structuralFailure);
                    continue;
                }
                long salt = MegacityLayout.mix(
                        NeonCityGenerator.contentSeed() ^ usable.planSeed(),
                        district.ordinal(), usable.id().hashCode());
                MissionBuildingPlanner.Site planned =
                        MissionBuildingPlanner.withMissionInteriorPlan(
                                level, usable, salt);
                if (!MissionBuildingPlanner.hasExplosiveCanisterPlan(planned)
                        || planned.decorations().isEmpty()
                        || !MissionBuildingPlanner.preflightInteriorPlan(level, planned)) {
                    failures.add(structural.id() + "=interior "
                            + MissionBuildingPlanner.preflightFailure(level, planned));
                    continue;
                }
                original = MissionBuildingPlanner.captureOriginalStates(level, planned);
                if (MissionBuildingPlanner.install(level, planned)
                                != MissionBuildingPlanner.InstallationResult.INSTALLED
                        || !MissionBuildingPlanner.auditDepthFirstTraversal(
                                level, planned).accessible()
                        || !MissionBuildingPlanner.hasAccessibleObjectivePath(level, planned)) {
                    failures.add(structural.id() + "=installed DFS");
                    continue;
                }
                passed++;
            } catch (RuntimeException exception) {
                failures.add(structural.id() + "=" + exception.getClass().getSimpleName());
            } finally {
                if (original != null
                        && !MissionBuildingPlanner.restoreOriginalStates(level, original)) {
                    failures.add(structural.id() + "=restore");
                }
                Set<Long> unexpected = loadedChunks(level, allCandidateChunks);
                unexpected.removeAll(loadedBefore);
                unexpected.removeAll(selectedGenerationHalo);
                if (!unexpected.isEmpty()) {
                    failures.add(structural.id() + "=loaded other candidate chunks "
                            + unexpected.size());
                }
            }
        }
        if (ArnisBuildingAtlas.compilationRequests() != scansBefore) {
            failures.add("Arnis scans=" + scansBefore + "->"
                    + ArnisBuildingAtlas.compilationRequests());
        }
        String summary = String.format(
                "Gig plan audit // district=%s // passed=%d/%d // scans=%d->%d // failures=%s",
                district.commandCode(), passed, candidates.size(), scansBefore,
                ArnisBuildingAtlas.compilationRequests(), failures.stream().limit(8).toList());
        if (passed != candidates.size() || !failures.isEmpty()) {
            source.sendFailure(Component.literal("FAILED: " + summary));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("PASS: " + summary)
                .withStyle(ChatFormatting.AQUA), false);
        return passed;
    }

    private static Set<Long> chunkHalo(Set<Long> chunks, int radius) {
        HashSet<Long> halo = new HashSet<>();
        for (long packed : chunks) {
            int centerX = ChunkPos.getX(packed);
            int centerZ = ChunkPos.getZ(packed);
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    halo.add(ChunkPos.pack(centerX + dx, centerZ + dz));
                }
            }
        }
        return halo;
    }

    private static Set<Long> candidateChunks(List<MissionBuildingPlanner.Site> candidates) {
        HashSet<Long> chunks = new HashSet<>();
        for (MissionBuildingPlanner.Site site : candidates) {
            for (int chunkZ = Math.floorDiv(site.bounds().minZ(), 16);
                    chunkZ <= Math.floorDiv(site.bounds().maxZ(), 16); chunkZ++) {
                for (int chunkX = Math.floorDiv(site.bounds().minX(), 16);
                        chunkX <= Math.floorDiv(site.bounds().maxX(), 16); chunkX++) {
                    chunks.add(ChunkPos.pack(chunkX, chunkZ));
                }
            }
        }
        return chunks;
    }

    private static Set<Long> loadedChunks(ServerLevel level, Set<Long> candidates) {
        HashSet<Long> loaded = new HashSet<>();
        for (long packed : candidates) {
            int chunkX = ChunkPos.getX(packed);
            int chunkZ = ChunkPos.getZ(packed);
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) loaded.add(packed);
        }
        return loaded;
    }

    private static List<District> parseDistrictList(String value) {
        LinkedHashSet<District> districts = new LinkedHashSet<>();
        List<String> tokens = splitTokens(value);
        for (String token : tokens) {
            District district = parseDistrict(token).orElseThrow(() ->
                    new IllegalArgumentException("unknown district code " + token));
            if (!districts.add(district)) {
                throw new IllegalArgumentException("duplicate district code " + token);
            }
        }
        if (districts.isEmpty()) throw new IllegalArgumentException("no districts supplied");
        return List.copyOf(districts);
    }

    private static List<String> splitTokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.trim().split("[,\\s]+"))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private static int storyAnchor(
            CommandSourceStack source, String missionId, String nodeId) {
        StoryMissionCatalog.StoryMission mission;
        StoryMissionCatalog.StoryNode node;
        try {
            mission = StoryMissionCatalog.definition(missionId);
            node = mission.node(nodeId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        if (node.type() != StoryMissionCatalog.NodeType.TALK
                && node.type() != StoryMissionCatalog.NodeType.DELIVER) {
            source.sendFailure(Component.literal(
                    "Only TALK and DELIVER nodes have a character anchor."));
            return 0;
        }
        ServerLevel level = source.getServer().overworld();
        if (!NeonCityGenerator.isEnabled() || !NeonCityGenerator.isMegacityWorld(level)) {
            source.sendFailure(Component.literal(
                    "Story anchors can only be resolved in a Project Moon Megacity world."));
            return 0;
        }
        try {
            BlockPos anchor = MainlineQuestService.nodePosition(level, mission, node);
            source.sendSuccess(() -> Component.literal(String.format(
                    "%s/%s character anchor=(%d,%d,%d), road=%s, sky=%s",
                    mission.id(), node.id(), anchor.getX(), anchor.getY(), anchor.getZ(),
                    NeonCityGenerator.roadAt(anchor.getX(), anchor.getZ()),
                    level.canSeeSky(anchor.above()))).withStyle(ChatFormatting.AQUA), false);
            return 1;
        } catch (IllegalStateException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
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
