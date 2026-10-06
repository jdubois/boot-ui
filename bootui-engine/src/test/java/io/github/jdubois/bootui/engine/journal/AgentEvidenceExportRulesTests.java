package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CodeInventoryAgentReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryChangesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryDependenciesReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryMethodsReport;
import io.github.jdubois.bootui.core.dto.CodeInventoryReport;
import io.github.jdubois.bootui.core.dto.CodePathsAgentReport;
import io.github.jdubois.bootui.core.dto.CodePathsBeansReport;
import io.github.jdubois.bootui.core.dto.CodePathsProbeDto;
import io.github.jdubois.bootui.core.dto.CodePathsProbesReport;
import io.github.jdubois.bootui.core.dto.CodePathsReport;
import io.github.jdubois.bootui.core.dto.CodePathsRequestTreeReport;
import io.github.jdubois.bootui.core.dto.CodePathsRouteTreeReport;
import io.github.jdubois.bootui.core.dto.RuntimeAgentEvidenceDto;
import io.github.jdubois.bootui.core.dto.RuntimeSideEffectChangesDto;
import io.github.jdubois.bootui.core.dto.SideEffectsAgentReport;
import io.github.jdubois.bootui.core.dto.SideEffectsReport;
import io.github.jdubois.bootui.core.dto.SideEffectsSensorReport;
import io.github.jdubois.bootui.engine.codepaths.HandlerMethods;
import io.github.jdubois.bootui.engine.codepaths.IssuingMethod;
import io.github.jdubois.bootui.engine.inventory.CodeInventoryService;
import io.github.jdubois.bootui.engine.model.ClassInvocation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The agent evidence contract's export rule ({@code docs/PLAN-v2.md} §5.17, M5-11): the panels, Runtime Insights'
 * Export JSON and Copy for AI, the MCP tools, and the CLI carry only what the Code Paths and Code Inventory reads
 * return, so those reads' shapes are the whole export. Every field they can carry is listed here, each reviewed as
 * metadata: a code identifier, a route template or masked observed path, a request id, a time, a count, a status, or a
 * sentence BootUI wrote. A new field, as Side Effects targets or probe hits will add, fails this test until it is
 * reviewed against the live exposure policy and listed.
 */
class AgentEvidenceExportRulesTests {

    private static final List<Class<?>> READS = List.of(
            CodePathsReport.class,
            CodePathsRouteTreeReport.class,
            CodePathsRequestTreeReport.class,
            CodePathsAgentReport.class,
            CodePathsBeansReport.class,
            CodePathsProbesReport.class,
            CodePathsProbeDto.class,
            CodeInventoryReport.class,
            CodeInventoryAgentReport.class,
            CodeInventoryChangesReport.class,
            CodeInventoryMethodsReport.class,
            CodeInventoryDependenciesReport.class,
            RuntimeAgentEvidenceDto.class,
            HandlerMethods.class,
            IssuingMethod.class,
            CodeInventoryService.ChangedCode.class,
            ClassInvocation.class,
            SideEffectsReport.class,
            SideEffectsSensorReport.class,
            SideEffectsAgentReport.class,
            RuntimeSideEffectChangesDto.class,
            RunSideEffects.class);

    /** Every field, each reviewed as metadata. */
    private static final Set<String> METADATA = Set.of(
            // Method probes (M5-8): code identifiers, states, bounds, times, counts, request ids, exception type names,
            // and sentences BootUI or its agent wrote; never an argument or a return value.
            "CodePathsProbesReport.available",
            "CodePathsProbesReport.unavailableReason",
            "CodePathsProbesReport.maxActive",
            "CodePathsProbesReport.maxInvocations",
            "CodePathsProbesReport.windowSeconds",
            "CodePathsProbesReport.probes",
            "CodePathsProbesReport.limitations",
            "CodePathsProbeDto.id",
            "CodePathsProbeDto.method",
            "CodePathsProbeDto.className",
            "CodePathsProbeDto.methodName",
            "CodePathsProbeDto.descriptor",
            "CodePathsProbeDto.state",
            "CodePathsProbeDto.endReason",
            "CodePathsProbeDto.failure",
            "CodePathsProbeDto.removal",
            "CodePathsProbeDto.waitingForClass",
            "CodePathsProbeDto.async",
            "CodePathsProbeDto.maxInvocations",
            "CodePathsProbeDto.windowSeconds",
            "CodePathsProbeDto.requestedAt",
            "CodePathsProbeDto.endsAt",
            "CodePathsProbeDto.endedAt",
            "CodePathsProbeDto.invocations",
            "CodePathsProbeDto.recorded",
            "CodePathsProbeDto.dropped",
            "CodePathsProbeDto.hits",
            "CodePathsProbeHitDto.time",
            "CodePathsProbeHitDto.durationMicros",
            "CodePathsProbeHitDto.threadKind",
            "CodePathsProbeHitDto.requestId",
            "CodePathsProbeHitDto.outcome",
            "CodePathsProbeHitDto.exceptionType",
            "CodePathsProbeHitDto.caller",
            // Argument and return shapes (M5-8, D44): types, nullness, sizes, and presence, read without running
            // application code; a string's length, a char[] or byte[] length, and an enum constant's name only under
            // FULL exposure; none under METADATA_ONLY; and never in an agent read (MCP, the CLI), whatever the
            // exposure.
            "CodePathsProbesReport.shapesAvailable",
            "CodePathsProbesReport.shapesUnavailableReason",
            "CodePathsProbeDto.recordShapes",
            "CodePathsProbeDto.shapesHiddenReason",
            "CodePathsProbeDto.shapesDropped",
            "CodePathsProbeHitDto.arguments",
            "CodePathsProbeHitDto.argumentsNotRecorded",
            "CodePathsProbeHitDto.returned",
            "CodePathsProbeHitDto.shapesIncomplete",
            "CodePathsValueShapeDto.kind",
            "CodePathsValueShapeDto.declaredType",
            "CodePathsValueShapeDto.type",
            "CodePathsValueShapeDto.size",
            "CodePathsValueShapeDto.present",
            "CodePathsValueShapeDto.constant",
            "CodePathsValueShapeDto.withheld",
            // Side Effects (M5-5a): a command's file name, never its arguments or environment; code identifiers; route
            // templates and thread families; request ids; times; counts; exit statuses; sentences BootUI wrote.
            "SideEffectsAgentReport.available",
            "SideEffectsAgentReport.limitations",
            "SideEffectsAgentReport.matched",
            "SideEffectsAgentReport.omitted",
            "SideEffectsAgentReport.query",
            "SideEffectsAgentReport.rows",
            "SideEffectsAgentReport.sensors",
            "SideEffectsAgentReport.unavailableReason",
            "SideEffectsHookDto.id",
            "SideEffectsHookDto.present",
            "SideEffectsHookDto.recorded",
            "SideEffectsHookDto.selfTest",
            "SideEffectsHookDto.transformed",
            "SideEffectsHookDto.type",
            "SideEffectsReport.available",
            "SideEffectsReport.limitations",
            "SideEffectsReport.sensors",
            "SideEffectsReport.unavailableReason",
            // Side effects in the run comparison and the run summary (M5-7b, D45): the same targets and owners as
            // Side Effects rows, masked again, never a value; per-sensor verdicts and counts; sentences BootUI wrote.
            "RuntimeSideEffectChangesDto.available",
            "RuntimeSideEffectChangesDto.unavailableReason",
            "RuntimeSideEffectChangesDto.partial",
            "RuntimeSideEffectChangesDto.sensors",
            "RuntimeSideEffectChangesDto.changes",
            "RuntimeSideEffectChangesDto.changesTotal",
            "RuntimeSideEffectChangesDto.limitations",
            "RuntimeSideEffectSensorDto.sensor",
            "RuntimeSideEffectSensorDto.status",
            "RuntimeSideEffectSensorDto.reason",
            "RuntimeSideEffectSensorDto.added",
            "RuntimeSideEffectSensorDto.removed",
            "RuntimeSideEffectSensorDto.notExercised",
            "RuntimeSideEffectChangeDto.sensor",
            "RuntimeSideEffectChangeDto.kind",
            "RuntimeSideEffectChangeDto.target",
            "RuntimeSideEffectChangeDto.scope",
            "RuntimeSideEffectChangeDto.owner",
            "RuntimeSideEffectChangeDto.change",
            "RuntimeSideEffectChangeDto.client",
            "RuntimeSideEffectChangeDto.count",
            "RuntimeSideEffectChangeDto.sentence",
            "RunSideEffects.unavailableReason",
            "RunSideEffects.routesHidden",
            "RunSideEffects.sensors",
            "RunSideEffects.keys",
            "Sensor.id",
            "Sensor.reason",
            "Sensor.startupReason",
            "Sensor.omittedKeys",
            "Key.sensor",
            "Key.kind",
            "Key.target",
            "Key.scope",
            "Key.owner",
            "Key.client",
            "Key.count",
            // Network (M5-5b): a host and port or a looked-up name, never a byte; a client label BootUI wrote; how a
            // panel captures it and that panel's id.
            "SideEffectsRowDto.attribution",
            "SideEffectsRowDto.callSite",
            "SideEffectsRowDto.capture",
            "SideEffectsRowDto.capturedBy",
            "SideEffectsRowDto.client",
            "SideEffectsRowDto.completed",
            "SideEffectsRowDto.count",
            // Security sinks (M5-6b): BootUI's sentence and the parameter's name, never its value.
            "SideEffectsRowDto.detail",
            "SideEffectsRowDto.exemplarRequestIds",
            "SideEffectsRowDto.failed",
            "SideEffectsRowDto.firstSeen",
            "SideEffectsRowDto.insideMethod",
            "SideEffectsRowDto.kind",
            "SideEffectsRowDto.lastExitStatus",
            "SideEffectsRowDto.lastSeen",
            "SideEffectsRowDto.location",
            "SideEffectsRowDto.maxMillis",
            "SideEffectsRowDto.nonZeroExits",
            "SideEffectsRowDto.origin",
            "SideEffectsRowDto.parameter",
            "SideEffectsRowDto.scope",
            "SideEffectsRowDto.sensor",
            "SideEffectsRowDto.target",
            "SideEffectsRowDto.totalMillis",
            "SideEffectsSensorDto.dropped",
            "SideEffectsSensorDto.group",
            "SideEffectsSensorDto.hooks",
            "SideEffectsSensorDto.id",
            "SideEffectsSensorDto.label",
            "SideEffectsSensorDto.occurrences",
            "SideEffectsSensorDto.reason",
            "SideEffectsSensorDto.rows",
            "SideEffectsSensorDto.state",
            // The opt-in sensor's runtime switch (M5-14): its id, booleans, a state, and sentences BootUI wrote.
            "SideEffectsSensorDto.toggle",
            "JavaAgentSensorToggleDto.available",
            "JavaAgentSensorToggleDto.configured",
            "JavaAgentSensorToggleDto.enabled",
            "JavaAgentSensorToggleDto.failure",
            "JavaAgentSensorToggleDto.id",
            "JavaAgentSensorToggleDto.optInReason",
            "JavaAgentSensorToggleDto.overridden",
            "JavaAgentSensorToggleDto.state",
            "JavaAgentSensorToggleDto.unavailableReason",
            "SideEffectsSensorReport.available",
            "SideEffectsSensorReport.limitations",
            "SideEffectsSensorReport.page",
            "SideEffectsSensorReport.rows",
            "SideEffectsSensorReport.sensor",
            "SideEffectsSensorReport.unavailableReason",
            "ChangedClass.className",
            "ChangedClass.methods",
            "ChangedClass.routes",
            "ChangedCode.classes",
            "ChangedCode.fingerprint",
            "ChangedCode.note",
            "ChangedCode.previousRun",
            "ChangedCode.scanReason",
            "ChangedCode.scanStatus",
            "ChangedCode.unavailableReason",
            "ClassInvocation.calleeClass",
            "ClassInvocation.callerClass",
            "ClassInvocation.calls",
            "HandlerMethods.assemblyOnly",
            "HandlerMethods.handlerNanos",
            "HandlerMethods.methods",
            "HandlerMethods.requests",
            "HandlerMethods.route",
            "HandlerMethods.stampedCalls",
            "HandlerMethods.unstampedCalls",
            "HandlerMethods.unstampedNanos",
            "IssuingMethod.key",
            "IssuingMethod.repository",
            "IssuingMethod.repositoryKey",
            "Method.key",
            "Method.label",
            "Method.ownNanos",
            "RuntimeAgentEvidenceDto.maxBytes",
            "RuntimeAgentEvidenceDto.retainedBytes",
            "RuntimeAgentEvidenceDto.stores",
            "RuntimeAgentEvidenceStoreDto.counts",
            "RuntimeAgentEvidenceStoreDto.maxBytes",
            "RuntimeAgentEvidenceStoreDto.note",
            "RuntimeAgentEvidenceStoreDto.panel",
            "RuntimeAgentEvidenceStoreDto.retainedBytes",
            "RuntimeAgentEvidenceStoreDto.store",
            "RuntimeAgentEvidenceStoreDto.visible",
            "CodePathsNodeDto.p50Millis",
            "CodePathsNodeDto.p95Millis",
            "CodePathsRouteDto.p50Millis",
            "CodePathsRouteDto.p95Millis",
            "CodeInventoryAgentReport.dependencies",
            "CodeInventoryAgentReport.methods",
            "CodeInventoryAgentReport.omitted",
            "CodeInventoryAgentReport.query",
            "CodeInventoryAgentReport.summary",
            "CodeInventoryAgentReport.total",
            "CodeInventoryAgentReport.view",
            "CodeInventoryChangeCountsDto.added",
            "CodeInventoryChangeCountsDto.changed",
            "CodeInventoryChangeCountsDto.executed",
            "CodeInventoryChangeCountsDto.notExecuted",
            "CodeInventoryChangeCountsDto.note",
            "CodeInventoryChangeCountsDto.partial",
            "CodeInventoryChangeCountsDto.previousRun",
            "CodeInventoryChangeCountsDto.removed",
            "CodeInventoryChangeCountsDto.scanStatus",
            "CodeInventoryChangesReport.available",
            "CodeInventoryChangesReport.changes",
            "CodeInventoryChangesReport.counts",
            "CodeInventoryChangesReport.page",
            "CodeInventoryChangesReport.unavailableReason",
            "CodeInventoryClassDto.changed",
            "CodeInventoryClassDto.className",
            "CodeInventoryClassDto.executed",
            "CodeInventoryClassDto.methods",
            "CodeInventoryClassDto.neverExecuted",
            "CodeInventoryClassDto.notTracked",
            "CodeInventoryClassDto.packageName",
            "CodeInventoryDependenciesReport.available",
            "CodeInventoryDependenciesReport.counts",
            "CodeInventoryDependenciesReport.dependencies",
            "CodeInventoryDependenciesReport.page",
            "CodeInventoryDependenciesReport.unavailableReason",
            "CodeInventoryDependencyCountsDto.declared",
            "CodeInventoryDependencyCountsDto.declaredReason",
            "CodeInventoryDependencyCountsDto.loaded",
            "CodeInventoryDependencyCountsDto.loadedEarlier",
            "CodeInventoryDependencyCountsDto.notLoaded",
            "CodeInventoryDependencyCountsDto.undeclared",
            "CodeInventoryDependencyDto.artifactId",
            "CodeInventoryDependencyDto.classesLoaded",
            "CodeInventoryDependencyDto.classesLoadedTotal",
            "CodeInventoryDependencyDto.declared",
            "CodeInventoryDependencyDto.firstLoadEpochMillis",
            "CodeInventoryDependencyDto.firstRequestId",
            "CodeInventoryDependencyDto.firstRoute",
            "CodeInventoryDependencyDto.groupId",
            "CodeInventoryDependencyDto.jar",
            "CodeInventoryDependencyDto.loadedAt",
            "CodeInventoryDependencyDto.status",
            "CodeInventoryDependencyDto.version",
            "CodeInventoryMethodCountsDto.classes",
            "CodeInventoryMethodCountsDto.executed",
            "CodeInventoryMethodCountsDto.generated",
            "CodeInventoryMethodCountsDto.methods",
            "CodeInventoryMethodCountsDto.neverExecuted",
            "CodeInventoryMethodCountsDto.notTracked",
            "CodeInventoryMethodCountsDto.packages",
            "CodeInventoryMethodCountsDto.tracked",
            "CodeInventoryMethodDto.change",
            "CodeInventoryMethodDto.className",
            "CodeInventoryMethodDto.descriptor",
            "CodeInventoryMethodDto.firstHitEpochMillis",
            "CodeInventoryMethodDto.firstRequestId",
            "CodeInventoryMethodDto.firstRoute",
            "CodeInventoryMethodDto.key",
            "CodeInventoryMethodDto.name",
            "CodeInventoryMethodDto.notTrackedReason",
            "CodeInventoryMethodDto.packageName",
            "CodeInventoryMethodDto.status",
            "CodeInventoryMethodsReport.available",
            "CodeInventoryMethodsReport.classes",
            "CodeInventoryMethodsReport.methods",
            "CodeInventoryMethodsReport.packages",
            "CodeInventoryMethodsReport.page",
            "CodeInventoryMethodsReport.unavailableReason",
            "CodeInventoryPackageDto.classes",
            "CodeInventoryPackageDto.executed",
            "CodeInventoryPackageDto.methods",
            "CodeInventoryPackageDto.name",
            "CodeInventoryPackageDto.neverExecuted",
            "CodeInventoryPackageDto.notTracked",
            "CodeInventoryReport.available",
            "CodeInventoryReport.changes",
            "CodeInventoryReport.dependencies",
            "CodeInventoryReport.limitations",
            "CodeInventoryReport.methods",
            "CodeInventoryReport.recordingClearedAt",
            "CodeInventoryReport.run",
            "CodeInventoryReport.scan",
            "CodeInventoryReport.unavailableReason",
            "CodeInventoryRunDto.application",
            "CodeInventoryRunDto.claimedAtEpochMillis",
            "CodeInventoryRunDto.generation",
            "CodeInventoryRunDto.mode",
            "CodeInventoryRunDto.packages",
            "CodeInventoryRunDto.readyAtEpochMillis",
            "CodeInventoryScanDto.classes",
            "CodeInventoryScanDto.durationMillis",
            "CodeInventoryScanDto.reason",
            "CodeInventoryScanDto.reused",
            "CodeInventoryScanDto.roots",
            "CodeInventoryScanDto.skipped",
            "CodeInventoryScanDto.status",
            "CodePathsAgentReport.available",
            "CodePathsAgentReport.excludedMethods",
            "CodePathsAgentReport.hottestNodes",
            "CodePathsAgentReport.limitations",
            "CodePathsAgentReport.matched",
            "CodePathsAgentReport.omitted",
            "CodePathsAgentReport.query",
            "CodePathsAgentReport.routes",
            "CodePathsAgentReport.unavailableReason",
            "CodePathsBeanEdgeDto.calls",
            "CodePathsBeanEdgeDto.declared",
            "CodePathsBeanEdgeDto.from",
            "CodePathsBeanEdgeDto.fromType",
            "CodePathsBeanEdgeDto.observable",
            "CodePathsBeanEdgeDto.observed",
            "CodePathsBeanEdgeDto.to",
            "CodePathsBeanEdgeDto.toType",
            "CodePathsBeanEdgeDto.unobservableReason",
            "CodePathsBeansReport.available",
            "CodePathsBeansReport.beansAvailable",
            "CodePathsBeansReport.declaredEdges",
            "CodePathsBeansReport.edges",
            "CodePathsBeansReport.limitations",
            "CodePathsBeansReport.notCalled",
            "CodePathsBeansReport.observedEdges",
            "CodePathsBeansReport.omitted",
            "CodePathsBeansReport.unavailableReason",
            "CodePathsCallsDto.callsPerRequest",
            "CodePathsCallsDto.kind",
            "CodePathsCallsDto.totalMillis",
            "CodePathsExcludedMethodDto.method",
            "CodePathsExcludedMethodDto.reason",
            "CodePathsMethodDto.callers",
            "CodePathsMethodDto.method",
            "CodePathsMethodDto.routes",
            "CodePathsMethodTimeDto.className",
            "CodePathsMethodTimeDto.method",
            "CodePathsMethodTimeDto.methodName",
            "CodePathsMethodTimeDto.selfMillis",
            "CodePathsMethodTimeDto.share",
            "CodePathsNodeDto.async",
            "CodePathsNodeDto.calls",
            "CodePathsNodeDto.callsPerRequest",
            "CodePathsNodeDto.children",
            "CodePathsNodeDto.className",
            "CodePathsNodeDto.depth",
            "CodePathsNodeDto.id",
            "CodePathsNodeDto.kind",
            "CodePathsNodeDto.method",
            "CodePathsNodeDto.methodName",
            "CodePathsNodeDto.parent",
            "CodePathsNodeDto.phase",
            "CodePathsNodeDto.requests",
            "CodePathsNodeDto.selfMillis",
            "CodePathsNodeDto.share",
            "CodePathsNodeDto.totalMillis",
            "CodePathsReport.available",
            "CodePathsReport.excludedMethods",
            "CodePathsReport.limitations",
            "CodePathsReport.routes",
            "CodePathsReport.status",
            "CodePathsReport.unavailableReason",
            "CodePathsRequestTreeReport.assemblyOnly",
            "CodePathsRequestTreeReport.asyncMillis",
            "CodePathsRequestTreeReport.available",
            "CodePathsRequestTreeReport.cut",
            "CodePathsRequestTreeReport.droppedCalls",
            "CodePathsRequestTreeReport.found",
            "CodePathsRequestTreeReport.limitations",
            "CodePathsRequestTreeReport.nodes",
            "CodePathsRequestTreeReport.ownMillis",
            "CodePathsRequestTreeReport.requestId",
            "CodePathsRequestTreeReport.route",
            "CodePathsRequestTreeReport.topMethods",
            "CodePathsRequestTreeReport.unavailableReason",
            "CodePathsRouteDto.assemblyOnly",
            "CodePathsRouteDto.asyncMillis",
            "CodePathsRouteDto.firstRequestMillis",
            "CodePathsRouteDto.meanMillis",
            "CodePathsRouteDto.nodes",
            "CodePathsRouteDto.route",
            "CodePathsRouteDto.topMethods",
            "CodePathsRouteDto.warmRequests",
            "CodePathsRouteTreeReport.assemblyOnly",
            "CodePathsRouteTreeReport.available",
            "CodePathsRouteTreeReport.depth",
            "CodePathsRouteTreeReport.exemplarRequestIds",
            "CodePathsRouteTreeReport.firstRequestId",
            "CodePathsRouteTreeReport.firstRequestMillis",
            "CodePathsRouteTreeReport.found",
            "CodePathsRouteTreeReport.handlerMillis",
            "CodePathsRouteTreeReport.limitations",
            "CodePathsRouteTreeReport.methods",
            "CodePathsRouteTreeReport.nodes",
            "CodePathsRouteTreeReport.ownMillis",
            "CodePathsRouteTreeReport.page",
            "CodePathsRouteTreeReport.route",
            "CodePathsRouteTreeReport.shareOf",
            "CodePathsRouteTreeReport.unavailableReason",
            "CodePathsRouteTreeReport.warmRequests",
            "CodePathsStatusDto.foldedCalls",
            "CodePathsStatusDto.fragments",
            "CodePathsStatusDto.generation",
            "CodePathsStatusDto.malformedFragments",
            "CodePathsStatusDto.routeNodeBudget",
            "CodePathsStatusDto.routeNodes",
            "CodePathsStatusDto.routedTrees",
            "CodePathsStatusDto.routes",
            "CodePathsStatusDto.settledTrees",
            "CodePathsStatusDto.staleFragments",
            "CodePathsStatusDto.unroutedTrees",
            "PageMetadata.hasMore",
            "PageMetadata.limit",
            "PageMetadata.matched",
            "PageMetadata.offset",
            "PageMetadata.returned",
            "PageMetadata.total");

    @Test
    void everyCountTheJournalStatusCanReportIsReviewedMetadata() {
        assertThat(AgentEvidence.COUNTS)
                .as(
                        "a count is a number of trees, routes, nodes, calls, loads, probes, probe hits, or rows, or the bytes"
                                + " of an index")
                .containsExactlyInAnyOrder(
                        "requestTrees",
                        "routes",
                        "routeNodes",
                        "indexBytes",
                        "firstCalls",
                        "firstCallsWithRequest",
                        "firstLoads",
                        "probes",
                        "probeHits",
                        "sideEffectRows",
                        "sideEffectsWaiting");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new AgentEvidence.Usage(0L, 0L, java.util.Map.of("targets", 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyFieldTheAgentEvidenceReadsCanExportIsReviewedMetadata() {
        assertThat(fields())
                .as("a new field of a Code Paths or Code Inventory read: review it against the live exposure policy"
                        + " (values never leave a store) and list it in METADATA")
                .isEqualTo(new TreeSet<>(METADATA));
    }

    private static Set<String> fields() {
        Set<String> fields = new TreeSet<>();
        Set<Class<?>> seen = new java.util.HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(READS);
        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (!type.isRecord() || !seen.add(type)) {
                continue;
            }
            for (RecordComponent component : type.getRecordComponents()) {
                fields.add(type.getSimpleName() + "." + component.getName());
                enqueue(component.getGenericType(), pending);
            }
        }
        return fields;
    }

    private static void enqueue(Type type, Deque<Class<?>> pending) {
        if (type instanceof Class<?> raw) {
            if (raw.getPackageName().startsWith("io.github.jdubois.bootui")) {
                pending.push(raw);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                enqueue(argument, pending);
            }
        }
    }
}
