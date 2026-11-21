package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.RequestJournalProfileDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightCheckDto;
import io.github.jdubois.bootui.core.dto.RuntimeInsightsReportDto;
import io.github.jdubois.bootui.core.dto.RuntimeObservationDetailDto;
import io.github.jdubois.bootui.engine.insights.AiUsageByRoute;
import io.github.jdubois.bootui.engine.insights.AppEventCapture;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsService;
import io.github.jdubois.bootui.engine.insights.SqlCapture;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only, adapter-independent projection of durable journal history.
 *
 * <p>Archive reads reconstruct typed {@link JournalEntry entries}; they never consult the current runtime journal,
 * adapters, or live agent state.</p>
 */
public final class DurableJournalArchiveService {

    public static final int MAX_EVENTS_PER_RUN = RuntimeJournalSettings.DEFAULT_MAX_EVENTS;
    public static final long MAX_EVENT_BYTES_PER_RUN = RuntimeJournalSettings.DEFAULT_MAX_BYTES_CAP;
    public static final int MAX_MULTI_RUNS = 100;

    private static final int HISTORY_SCAN_LIMIT = 32;
    private static final String HISTORICAL_AGENT_UNAVAILABLE =
            "Historical archive reports cannot consult the current BootUI agent; agent-only evidence is unavailable.";
    private static final ScopeAuthorizer SAME_NAMESPACE =
            (readerNamespace, scope) -> readerNamespace != null && readerNamespace.equals(scope.namespace());

    private final DurableJournalStore store;
    private final ExposurePolicy exposure;
    private final String readerNamespace;
    private final ScopeAuthorizer authorizer;
    private final DurableJournalEvidence evidence;
    private volatile String acceptedNamespace;

    public DurableJournalArchiveService(DurableJournalStore store, ExposurePolicy exposure) {
        this(store, exposure, null, SAME_NAMESPACE);
    }

    public static DurableJournalArchiveService forNamespace(
            DurableJournalStore store, ExposurePolicy exposure, String namespace) {
        return new DurableJournalArchiveService(store, exposure, requiredNamespace(namespace), SAME_NAMESPACE);
    }

    /** Creates a reader that may deliberately cross namespace boundaries. */
    public static DurableJournalArchiveService crossNamespaceReader(
            DurableJournalStore store, ExposurePolicy exposure) {
        return new DurableJournalArchiveService(store, exposure, null, (namespace, scope) -> true);
    }

    public DurableJournalArchiveService(
            DurableJournalStore store, ExposurePolicy exposure, String readerNamespace, ScopeAuthorizer authorizer) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.exposure = exposure;
        this.readerNamespace = optionalNamespace(readerNamespace);
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
        this.evidence = new DurableJournalEvidence(store, MAX_EVENTS_PER_RUN, MAX_EVENT_BYTES_PER_RUN);
    }

    /** Runs newest-first for the exact namespace/application pair. */
    public List<DurableJournalRun> listRuns(String namespace, String application) {
        return listRuns(namespace, application, DurableJournalQuery.DEFAULT_PAGE_SIZE);
    }

    /** Runs newest-first for the exact namespace/application pair, bounded by {@link #MAX_MULTI_RUNS}. */
    public List<DurableJournalRun> listRuns(String namespace, String application, int limit) {
        String requestedNamespace = requiredNamespace(namespace);
        int bounded = limit <= 0 ? DurableJournalQuery.DEFAULT_PAGE_SIZE : Math.min(limit, MAX_MULTI_RUNS);
        String sourceNamespace = operationNamespace(requestedNamespace);
        authorizeNamespace(sourceNamespace, requestedNamespace);
        return store.listRuns(requestedNamespace, application, bounded).stream()
                .filter(run -> permitted(sourceNamespace, run.scope()))
                .toList();
    }

    /** Queries the persisted feed after authorizing and, when fixed, constraining its namespace. */
    public DurableJournalFeedPage queryFeed(DurableJournalFeedQuery query) {
        DurableJournalFeedQuery requested = Objects.requireNonNull(query, "query must not be null");
        String sourceNamespace = operationNamespace(requested.namespace());
        String fixedNamespace = selectedNamespace();
        if (requested.namespace() != null) {
            authorizeNamespace(sourceNamespace, requested.namespace());
        } else if (fixedNamespace != null) {
            requested = withNamespace(requested, fixedNamespace);
        }
        DurableJournalFeedPage page = store.queryFeed(requested);
        List<DurableJournalFeedItem> allowed = page.entries().stream()
                .filter(item -> permitted(sourceNamespace, item.origin().scope()))
                .toList();
        return new DurableJournalFeedPage(allowed, page.nextCursor(), page.hasMore());
    }

    /** Builds a request profile solely from the run's typed events and safe durable details. */
    public RequestJournalProfileDto profile(DurableJournalScope scope, String requestId) {
        LoadedRun loaded = load(scope);
        RequestJournalProfiles profiles = new RequestJournalProfiles(
                        null, null, 0, Integer.MAX_VALUE, panel -> true, exposure)
                .maxHandoff(Duration.ofMillis(RequestJournalProfiles.DEFAULT_MAX_HANDOFF_MILLIS));
        RequestJournalProfileDto profile = profiles.profileFrom(loaded.snapshot(), requestId);
        List<String> notes = new ArrayList<>(profile.notes());
        notes.addAll(loaded.profileLimitations());
        return withNotes(profile, notes);
    }

    /** Projects Runtime Insights solely from persisted run evidence and metadata. */
    public RuntimeInsightsReportDto insights(DurableJournalScope scope) {
        return project(scope).report();
    }

    /** Projects one Runtime Insights detail solely from persisted run evidence and metadata. */
    public RuntimeObservationDetailDto insight(DurableJournalScope scope, String id) {
        ProjectedRun projected = project(scope);
        return projected.service().insightFrom(projected.snapshot(), id);
    }

    /** Projects multiple runs in order, authorizing the whole request before reading any run. */
    public DurableJournalArchiveReport multiRunInsights(List<DurableJournalScope> scopes) {
        Objects.requireNonNull(scopes, "scopes must not be null");
        if (scopes.size() > MAX_MULTI_RUNS) {
            throw new IllegalArgumentException("At most " + MAX_MULTI_RUNS + " runs can be projected at once");
        }
        if (scopes.isEmpty()) {
            return new DurableJournalArchiveReport(List.of());
        }
        List<DurableJournalScope> requested = List.copyOf(scopes);
        String sourceNamespace = operationNamespace(requested.get(0).namespace());
        for (DurableJournalScope scope : requested) {
            authorize(sourceNamespace, scope);
        }
        List<DurableJournalArchiveReport.Run> reports = new ArrayList<>(requested.size());
        Set<DurableJournalScope> seen = new LinkedHashSet<>();
        for (DurableJournalScope scope : requested) {
            if (seen.add(scope)) {
                reports.add(project(scope).archiveRun());
            }
        }
        return new DurableJournalArchiveReport(reports);
    }

    private ProjectedRun project(DurableJournalScope scope) {
        LoadedRun loaded = load(scope);
        List<String> limitations = loaded.insightLimitations();
        List<RunSummary> summaries = summaries(loaded, limitations);
        RuntimeInsightsService service = new RuntimeInsightsService(
                null, null, panel -> true, loaded.metadata().stack(), () -> summaries, Long.MAX_VALUE);
        service.setPoolSizes(loaded.metadata().poolSizes()::get);
        service.setSqlCapture(() -> historicalSqlCapture(loaded.metadata()));
        service.setAppEventCapture(() -> AppEventCapture.notRecorded(
                "Historical application-event capture capability was not persisted, so application events are"
                        + " unavailable."));
        service.setExposure(exposure);
        service.setAgent(() -> HISTORICAL_AGENT_UNAVAILABLE, Duration.ZERO);
        RuntimeInsightsReportDto report = withHistoricalCapabilities(
                withLimitations(service.reportFrom(loaded.snapshot()), limitations), loaded.metadata());
        DurableJournalArchiveReport.Completeness completeness = new DurableJournalArchiveReport.Completeness(
                loaded.complete(), loaded.metadata().phase(), loaded.snapshot().omittedEvents(), limitations);
        return new ProjectedRun(
                new DurableJournalArchiveReport.Run(
                        new DurableJournalOrigin(scope, loaded.metadata().instanceId()), completeness, report),
                report,
                service,
                loaded.snapshot());
    }

    private LoadedRun load(DurableJournalScope scope) {
        DurableJournalScope key = Objects.requireNonNull(scope, "scope must not be null");
        authorize(operationNamespace(key.namespace()), key);
        DurableJournalRun run = store.findRun(key);
        if (run == null) {
            throw new DurableJournalUnavailableException("Durable journal run does not exist: " + key);
        }
        DurableJournalRunMetadata metadata = store.findRunMetadata(key);
        if (metadata == null) {
            throw new DurableJournalUnavailableException("Durable journal run metadata is missing for " + key);
        }
        validateRun(key, run, metadata);
        JournalEvidenceSnapshot snapshot = evidence.read(key);
        List<DurableJournalDetail> details = details(key, snapshot.entries());
        DetailRead detailRead = enrich(snapshot, details);
        return new LoadedRun(run, metadata, detailRead.snapshot(), detailRead.missingDetails());
    }

    private List<DurableJournalDetail> details(DurableJournalScope scope, List<JournalEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        long first = entries.stream().mapToLong(JournalEntry::sequence).min().orElseThrow();
        int limit = Math.min(DurableJournalStore.MAX_DETAILS_PER_QUERY, entries.size() * 2);
        return store.listDetails(scope, first, limit);
    }

    private List<RunSummary> summaries(LoadedRun loaded, List<String> limitations) {
        List<RunSummary> summaries = new ArrayList<>(2);
        if (loaded.metadata().runSummary() != null) {
            summaries.add(loaded.metadata().runSummary());
        }
        DurableJournalRun current = loaded.run();
        for (DurableJournalRun candidate :
                store.listRuns(current.scope().namespace(), current.scope().application(), HISTORY_SCAN_LIMIT)) {
            if (!candidate.scope().equals(current.scope())
                    && candidate.startedAtEpochMillis() < current.startedAtEpochMillis()) {
                authorize(operationNamespace(current.scope().namespace()), candidate.scope());
                DurableJournalRunMetadata metadata = store.findRunMetadata(candidate.scope());
                if (metadata == null) {
                    limitations.add("The previous durable run "
                            + candidate.scope().run() + " has no metadata, so run-to-run comparisons are partial.");
                    return List.copyOf(summaries);
                }
                validateRun(candidate.scope(), candidate, metadata);
                if (metadata.runSummary() != null) {
                    summaries.add(metadata.runSummary());
                    return List.copyOf(summaries);
                }
                limitations.add("The previous durable run " + candidate.scope().run()
                        + " has no persisted RunSummary, so run-to-run comparisons are partial.");
                return List.copyOf(summaries);
            }
        }
        return List.copyOf(summaries);
    }

    private void validateRun(DurableJournalScope scope, DurableJournalRun run, DurableJournalRunMetadata metadata) {
        JournalStatus status = metadata.journalStatus();
        if (!scope.run().equals(status.runId())) {
            throw new DurableJournalUnavailableException(
                    "Stored durable journal metadata names run " + status.runId() + " for " + scope);
        }
        if (run.instanceId() != null && !metadata.instanceId().equals(run.instanceId())) {
            throw new DurableJournalUnavailableException(
                    "Stored durable journal metadata instance does not match its run for " + scope);
        }
        if (!metadata.instanceId().equals(status.instanceId())) {
            throw new DurableJournalUnavailableException(
                    "Stored durable journal metadata instance does not match its journal status for " + scope);
        }
        if (metadata.runSummary() != null
                && !scope.run().equals(metadata.runSummary().header().runId())) {
            throw new DurableJournalUnavailableException(
                    "Stored durable journal RunSummary names another run for " + scope);
        }
    }

    private static DetailRead enrich(JournalEvidenceSnapshot snapshot, List<DurableJournalDetail> details) {
        Map<Long, DurableJournalDetail> requests = details.stream()
                .filter(detail -> detail.kind() == DurableJournalDetailKind.REQUEST)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DurableJournalDetail::journalSequence, detail -> detail));
        Map<Long, DurableJournalDetail> exceptions = details.stream()
                .filter(detail -> detail.kind() == DurableJournalDetailKind.EXCEPTION)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        DurableJournalDetail::journalSequence, detail -> detail));
        List<JournalEntry> entries = new ArrayList<>(snapshot.entries().size());
        boolean missing = false;
        for (JournalEntry entry : snapshot.entries()) {
            RuntimeEvent event = entry.event();
            RuntimeEventPayload replacement = event.payload();
            if (event.payload() instanceof HttpPayload http) {
                DurableJournalDetail detail = requests.get(entry.sequence());
                if (detail == null) {
                    missing = true;
                } else {
                    replacement = new HttpPayload(
                            firstNonBlank(http.method(), detail.requestMethod()),
                            http.path(),
                            firstNonBlank(http.routeTemplate(), detail.routeTemplate()),
                            firstNonBlank(http.operation(), detail.operation()),
                            http.status(),
                            http.resources(),
                            http.timing(),
                            http.asyncStarted());
                }
            } else if (event.payload() instanceof ExceptionPayload exception) {
                DurableJournalDetail detail = exceptions.get(entry.sequence());
                if (detail == null) {
                    missing = true;
                } else {
                    replacement = new ExceptionPayload(
                            firstNonBlank(exception.groupId(), detail.exceptionGroupId()),
                            firstNonBlank(exception.exceptionClass(), detail.exceptionClass()),
                            firstNonBlank(exception.signature(), detail.signature()),
                            exceptionTypes(exception.types(), detail),
                            exception.marks(),
                            exception.logged());
                }
            }
            entries.add(
                    replacement == event.payload()
                            ? entry
                            : new JournalEntry(
                                    entry.sequence(), event.withPayload(replacement), entry.estimatedBytes()));
        }
        return new DetailRead(snapshot.withEntries(entries), missing);
    }

    private static List<String> exceptionTypes(List<String> payloadTypes, DurableJournalDetail detail) {
        LinkedHashSet<String> values = new LinkedHashSet<>(payloadTypes);
        values.addAll(detail.types());
        values.addAll(detail.causes());
        return values.stream()
                .filter(Objects::nonNull)
                .limit(ExceptionPayload.MAX_TYPES)
                .toList();
    }

    private static RequestJournalProfileDto withNotes(RequestJournalProfileDto profile, List<String> notes) {
        return new RequestJournalProfileDto(
                profile.available(),
                profile.unavailableReason(),
                profile.requestId(),
                profile.route(),
                profile.startedAt(),
                profile.durationMicros(),
                profile.status(),
                profile.resources(),
                profile.timeline(),
                profile.gcPauses(),
                profile.routeComparison(),
                profile.touched(),
                notes,
                profile.orm(),
                profile.handoffs(),
                profile.lateHandoffs(),
                profile.authorizations(),
                profile.baselineComparison());
    }

    private static RuntimeInsightsReportDto withLimitations(RuntimeInsightsReportDto report, List<String> limitations) {
        if (limitations.isEmpty()) {
            return report;
        }
        LinkedHashSet<String> all = new LinkedHashSet<>(report.limitations());
        all.addAll(limitations);
        return new RuntimeInsightsReportDto(
                report.available(),
                report.unavailableReason(),
                report.window(),
                report.coverage(),
                report.checks(),
                report.observations(),
                List.copyOf(all),
                report.notExercised(),
                report.notExercisedOmitted());
    }

    private static RuntimeInsightsReportDto withHistoricalCapabilities(
            RuntimeInsightsReportDto report, DurableJournalRunMetadata metadata) {
        if (!metadata.journalSettings().records(JournalSource.AI)) {
            return report;
        }
        String reason =
                "The historical AI token threshold was not persisted, so large-call classification is unavailable.";
        List<RuntimeInsightCheckDto> checks = report.checks().stream()
                .map(check -> AiUsageByRoute.KIND.equals(check.kind())
                                && !"NOT_APPLICABLE".equals(check.status())
                                && !"UNAVAILABLE".equals(check.status())
                        ? new RuntimeInsightCheckDto(
                                check.kind(),
                                check.title(),
                                "PARTIAL",
                                check.eligibleRequests(),
                                check.findings(),
                                joinReason(check.reason(), reason))
                        : check)
                .toList();
        return new RuntimeInsightsReportDto(
                report.available(),
                report.unavailableReason(),
                report.window(),
                report.coverage(),
                checks,
                report.observations(),
                report.limitations(),
                report.notExercised(),
                report.notExercisedOmitted());
    }

    private static String joinReason(String first, String second) {
        return first == null || first.isBlank() ? second : first + " " + second;
    }

    private static SqlCapture historicalSqlCapture(DurableJournalRunMetadata metadata) {
        DurableJournalRunMetadata.SqlCaptureQualification qualification = metadata.sqlCaptureQualification();
        if (qualification == null) {
            return SqlCapture.notRecorded(
                    "Historical SQL capture capability was not persisted, so SQL and connection evidence is"
                            + " unavailable.");
        }
        if (qualification.executionCount() == 0) {
            return SqlCapture.notRecorded(
                    "The historical run had no qualified SQL execution source, so executed statements are unavailable.");
        }
        if (qualification.incomplete() || qualification.preparationCount() > 0) {
            return SqlCapture.recordedExcept(
                    "Historical SQL capture was incomplete: preparation-only and incompletely qualified sources are not"
                            + " counted as executions.");
        }
        return SqlCapture.capturing();
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static DurableJournalFeedQuery withNamespace(DurableJournalFeedQuery query, String namespace) {
        return new DurableJournalFeedQuery(
                namespace,
                query.application(),
                query.run(),
                query.instanceId(),
                query.source(),
                query.type(),
                query.severity(),
                query.text(),
                query.route(),
                query.requestId(),
                query.since(),
                query.until(),
                query.noRequest(),
                query.cursor(),
                query.pageSize());
    }

    private void authorize(String sourceNamespace, DurableJournalScope scope) {
        if (!permitted(sourceNamespace, scope)) {
            throw new SecurityException(
                    "Durable journal scope " + scope + " is outside authorized namespace " + sourceNamespace);
        }
    }

    private void authorizeNamespace(String sourceNamespace, String namespace) {
        if (namespace == null) {
            return;
        }
        DurableJournalScope probe = new DurableJournalScope(namespace, "__authorization__", "__authorization__");
        if (!permitted(sourceNamespace, probe)) {
            throw new SecurityException(
                    "Durable journal namespace " + namespace + " is outside authorized namespace " + sourceNamespace);
        }
    }

    private boolean permitted(String sourceNamespace, DurableJournalScope scope) {
        if (scope == null) {
            return false;
        }
        try {
            return authorizer.permits(sourceNamespace, scope);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String operationNamespace(String requestedNamespace) {
        if (readerNamespace != null) {
            return readerNamespace;
        }
        String requested = optionalNamespace(requestedNamespace);
        if (requested == null) {
            return selectedNamespace();
        }
        synchronized (this) {
            if (acceptedNamespace == null) {
                acceptedNamespace = requested;
            }
            return acceptedNamespace;
        }
    }

    private String selectedNamespace() {
        return readerNamespace == null ? acceptedNamespace : readerNamespace;
    }

    private static String requiredNamespace(String namespace) {
        String value = optionalNamespace(namespace);
        if (value == null) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return value;
    }

    private static String optionalNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return null;
        }
        if (namespace.length() > DurableJournalScope.MAX_NAMESPACE_LENGTH) {
            throw new IllegalArgumentException("namespace is too long");
        }
        return namespace;
    }

    /** Authorization function evaluated for every persisted scope read. */
    @FunctionalInterface
    public interface ScopeAuthorizer {

        boolean permits(String readerNamespace, DurableJournalScope scope);
    }

    private record DetailRead(JournalEvidenceSnapshot snapshot, boolean missingDetails) {}

    private record ProjectedRun(
            DurableJournalArchiveReport.Run archiveRun,
            RuntimeInsightsReportDto report,
            RuntimeInsightsService service,
            JournalEvidenceSnapshot snapshot) {}

    private record LoadedRun(
            DurableJournalRun run,
            DurableJournalRunMetadata metadata,
            JournalEvidenceSnapshot snapshot,
            boolean missingDetails) {

        private static final String OFFLOAD_TRACE_LOSS =
                "HTTP requests are missing from durable history: memory offload discarded them before sequencing "
                        + "or the durable writer lost them. This archive is incomplete; affected trace-only calls are "
                        + "not attributed to a request.";

        List<String> profileLimitations() {
            List<String> result = new ArrayList<>();
            if (metadata.phase() != DurableJournalRunMetadata.Phase.END) {
                result.add("This request comes from a run whose durable metadata is not an end snapshot; newer work may"
                        + " not have been persisted.");
            }
            if (snapshot.partial()) {
                result.add("The durable reader omitted " + snapshot.omittedEvents()
                        + " older events, so this profile may be partial.");
            }
            if (metadata.durableDroppedEvents() > 0 || metadata.durableQuarantinedEvents() > 0) {
                result.add("The durable capture lost " + metadata.durableDroppedEvents()
                        + " entries and quarantined " + metadata.durableQuarantinedEvents()
                        + "; this profile may omit work.");
            }
            if (!metadata.offloadTraceLoss().isEmpty()) {
                result.add(OFFLOAD_TRACE_LOSS);
            }
            if (missingDetails) {
                result.add("Safe durable detail rows are absent for part of this history, so request and exception"
                        + " metadata may be partial.");
            }
            result.add(
                    "Historical slow-request and N+1 thresholds were not persisted, so this profile applies neither.");
            result.add("The historical agent handoff window was not persisted; executor work uses the current default"
                    + " window only when such durable events were present.");
            return List.copyOf(result);
        }

        List<String> insightLimitations() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            if (metadata.phase() != DurableJournalRunMetadata.Phase.END) {
                result.add("The source run was interrupted or remains active: this report is based on its latest"
                        + " durable metadata checkpoint, not a completed-run snapshot.");
            }
            if (snapshot.partial()) {
                result.add("The bounded durable reader omitted " + snapshot.omittedEvents()
                        + " older entries, so this report is partial.");
            }
            if (metadata.durableDroppedEvents() > 0 || metadata.durableQuarantinedEvents() > 0) {
                result.add("The durable capture dropped " + metadata.durableDroppedEvents()
                        + " entries and quarantined " + metadata.durableQuarantinedEvents()
                        + "; the report is incomplete and absence must not be read as no event.");
            }
            if (!metadata.offloadTraceLoss().isEmpty()) {
                result.add(OFFLOAD_TRACE_LOSS);
            }
            if (missingDetails) {
                result.add("Safe durable detail rows are absent for part of the retained history; exception metadata is"
                        + " partial.");
            }
            if (metadata.runSummary() == null) {
                result.add("This run has no persisted RunSummary; run-to-run comparison details are partial.");
            }
            if ((snapshot.records(JournalSource.SQL) || snapshot.records(JournalSource.CONNECTION))
                    && metadata.sqlCaptureQualification() == null) {
                result.add("Historical SQL capture qualification was not persisted, so SQL checks are unavailable.");
            }
            if (snapshot.records(JournalSource.HTTP) && metadata.declaredRoutes() == null) {
                result.add("The historical declared-route inventory was not persisted; unexercised routes are"
                        + " unavailable.");
            } else if (snapshot.records(JournalSource.HTTP)
                    && metadata.declaredRoutes().count() > 0) {
                DurableJournalRunMetadata.DeclaredRoutes routes = metadata.declaredRoutes();
                result.add("Only the count of declared routes was persisted"
                        + (routes.incompleteReason() == null ? "" : " (" + routes.incompleteReason() + ")")
                        + "; unexercised route names are unavailable.");
            }
            if (snapshot.entries().stream().anyMatch(entry -> entry.event().payload() instanceof ConnectionPayload)
                    && metadata.poolSizes().isEmpty()) {
                result.add(
                        "Historical connection-pool maxima are absent, so exhaustion-rate estimates are unavailable.");
            }
            if (snapshot.records(JournalSource.AI)) {
                result.add("The historical AI token threshold was not persisted; threshold-only AI observations are"
                        + " unavailable.");
            }
            result.add(HISTORICAL_AGENT_UNAVAILABLE);
            return new ArrayList<>(result);
        }

        boolean complete() {
            return metadata.phase() == DurableJournalRunMetadata.Phase.END
                    && snapshot.complete()
                    && !missingDetails
                    && metadata.runSummary() != null
                    && metadata.durableDroppedEvents() == 0
                    && metadata.durableQuarantinedEvents() == 0
                    && metadata.offloadTraceLoss().isEmpty();
        }
    }
}
