package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.BootUiCatalog;
import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import io.github.jdubois.bootui.client.ToolResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded live-target CI attach and assessment orchestration.
 *
 * <p>This is a client-side workflow exception, not a diagnostic tool or MCP command. It invokes only the selected
 * existing advisor tools, uses the current overview/catalog projections, and never starts or owns the application.
 */
public final class CiAssessmentRunner {

    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final int MAX_BUNDLE_BYTES = 2 * 1024 * 1024;
    static final int MAX_SUMMARY_BYTES = 256 * 1024;
    static final int MAX_DETAIL_PAGE_SIZE = 200;
    static final int MAX_TOTAL_DETAILS = 20_000;
    static final int MAX_TOTAL_PAGES = 1_000;
    static final Duration MAX_ASSESSMENT_BUDGET = Duration.ofMinutes(3);
    static final int MAX_RUNTIME_OBSERVATIONS = 20;

    private static final Pattern ASSESSMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final Pattern SAFE_RULE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Pattern SAFE_RUNTIME_KIND = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern SAFE_RUNTIME_SOURCE = Pattern.compile("[a-z][a-z0-9.-]{0,63}");
    private static final Duration MAX_RUNTIME_CONTEXT_BUDGET = Duration.ofSeconds(10);
    private static final Duration FINAL_IDENTITY_MARGIN = Duration.ofSeconds(1);
    private static final String RUNTIME_INSIGHTS_TOOL = "get_runtime_insights";
    private static final String RUNTIME_INSIGHTS_PANEL = "runtime-insights";
    private static final Set<String> RUNTIME_OBSERVATION_STATUSES = Set.of("OBSERVED", "PARTIAL", "INSUFFICIENT");
    private static final int LEGACY_SCHEMA_VERSION = 1;
    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final Set<String> LEGACY_WITNESS_KEYS = Set.of(
            "schemaVersion",
            "assessmentId",
            "targetUrl",
            "apiPath",
            "policySha256",
            "bootUiVersion",
            "instanceId",
            "runId",
            "attachedAt",
            "advisors");
    private static final Set<String> WITNESS_KEYS = Set.of(
            "schemaVersion",
            "assessmentId",
            "targetUrl",
            "apiPath",
            "policySha256",
            "bootUiVersion",
            "frameworkName",
            "instanceId",
            "runId",
            "attachedAt",
            "advisors");
    private static final Set<String> ATTACH_ERRORS = Set.of(
            "POLICY_READ_FAILED",
            "INVALID_POLICY",
            "INVALID_TARGET",
            "INVALID_CATALOG",
            "UNSUPPORTED_FRAMEWORK",
            "UNSUPPORTED_ADVISOR_FOR_FRAMEWORK",
            "MISSING_IDENTITY",
            "REQUIRED_ADVISOR_UNAVAILABLE",
            "ASSESSMENT_BUDGET_EXCEEDED",
            "TARGET_REQUEST_FAILED",
            "WITNESS_WRITE_FAILED");

    private final String url;
    private final String apiPath;
    private final Duration timeout;
    private final String assessmentId;
    private final Path policyPath;
    private final Path witnessPath;
    private final Path outputDirectory;
    private final List<AdvisorResult> collected = new ArrayList<>();
    private String attachFailureCode;

    public CiAssessmentRunner(
            String url,
            String apiPath,
            int timeoutSeconds,
            String assessmentId,
            Path policyPath,
            Path witnessPath,
            Path outputDirectory) {
        this.url = validateTarget(url);
        if (timeoutSeconds < 1 || timeoutSeconds > 180) {
            throw new IllegalArgumentException("--timeout must be between 1 and 180 seconds");
        }
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.apiPath = validateApiPath(new BootUiClientOptions(this.url, apiPath, null, timeout).apiPath());
        this.assessmentId = validateAssessmentId(assessmentId);
        this.policyPath = policyPath;
        this.witnessPath = witnessPath;
        this.outputDirectory = outputDirectory;
    }

    public int attach() {
        attachFailureCode = null;
        long deadline = System.nanoTime() + MAX_ASSESSMENT_BUDGET.toNanos();
        CiPolicy policy;
        try {
            policy = CiPolicy.read(policyPath);
        } catch (IOException unreadable) {
            return failAttach("POLICY_READ_FAILED");
        } catch (IllegalArgumentException invalid) {
            return failAttach("INVALID_POLICY");
        }
        try (BootUiClient client = client()) {
            BootUiCatalog catalog = requireCatalog(client, deadline);
            Identity identity = identity(client, deadline);
            List<JsonValue> advisors = preflight(policy, catalog, identity.framework());
            Map<String, JsonValue> witness = new LinkedHashMap<>();
            witness.put("schemaVersion", JsonValue.of(CURRENT_SCHEMA_VERSION));
            witness.put("assessmentId", JsonValue.of(assessmentId));
            witness.put("targetUrl", JsonValue.of(url));
            witness.put("apiPath", JsonValue.of(apiPath));
            witness.put("policySha256", JsonValue.of(policy.sha256()));
            witness.put("bootUiVersion", JsonValue.of(identity.bootUiVersion()));
            witness.put("frameworkName", JsonValue.of(identity.framework().displayName()));
            witness.put("instanceId", JsonValue.of(identity.instanceId()));
            witness.put("runId", JsonValue.of(identity.runId()));
            witness.put("attachedAt", JsonValue.of(Instant.now().toString()));
            witness.put("advisors", JsonValue.array(advisors));
            writeNewFile(witnessPath, JsonWriter.pretty(JsonValue.object(witness)));
            return 0;
        } catch (AssessmentFailure failure) {
            return failAttach(failure.code());
        } catch (BootUiClientException requestFailure) {
            return failAttach(System.nanoTime() >= deadline ? "ASSESSMENT_BUDGET_EXCEEDED" : "TARGET_REQUEST_FAILED");
        } catch (IOException witnessFailure) {
            return failAttach("WITNESS_WRITE_FAILED");
        }
    }

    public String attachFailureCode() {
        return attachFailureCode;
    }

    public int assess() {
        collected.clear();
        long deadline = System.nanoTime() + MAX_ASSESSMENT_BUDGET.toNanos();
        try {
            Path absoluteOutput = outputDirectory.toAbsolutePath().normalize();
            Path parent = absoluteOutput.getParent();
            if (parent == null) {
                throw new IOException("Output directory has no parent");
            }
            Files.createDirectories(parent);
            Files.createDirectory(absoluteOutput);
        } catch (IOException | RuntimeException failure) {
            throw new BootUiClientException("Cannot create a fresh assessment output directory", failure);
        }

        Assessment state = new Assessment(assessmentId, outputDirectory);
        try {
            CiPolicy policy = null;
            IOException policyReadFailure = null;
            IllegalArgumentException invalidPolicy = null;
            try {
                policy = CiPolicy.read(policyPath);
                state.policy(policy);
            } catch (IOException unreadable) {
                policyReadFailure = unreadable;
            } catch (IllegalArgumentException invalid) {
                invalidPolicy = invalid;
            }
            JsonValue witness = readWitness();
            if (policy == null) {
                if (policyReadFailure != null) {
                    throw policyReadFailure;
                }
                if (invalidPolicy != null) {
                    throw invalidPolicy;
                }
                throw new IllegalStateException("Policy was not loaded");
            }
            validateWitness(witness, policy);
            try (BootUiClient client = client()) {
                BootUiCatalog before = requireCatalog(client, deadline);
                Identity current = identity(client, deadline);
                requireSameIdentity(witness, current);
                state.context(policy, current);
                preflight(policy, before, current.framework());
                List<AdvisorResult> results = collect(policy, before, client, deadline);
                RuntimeContext runtimeContext = collectRuntimeInsights(policy, before, client, deadline);
                state.runtimeContext(runtimeContext);
                Identity after = identity(client, deadline);
                requireSameIdentity(witness, after);
                if (!policy.sha256().equals(CiPolicy.read(policyPath).sha256())) {
                    throw new AssessmentFailure("POLICY_CHANGED");
                }
                state.finish(policy, current, results, List.of());
            }
        } catch (AssessmentFailure failure) {
            state.finishError(failure.code(), collected);
        } catch (BootUiClientException requestFailure) {
            state.finishError(
                    System.nanoTime() >= deadline ? "ASSESSMENT_BUDGET_EXCEEDED" : "TARGET_REQUEST_FAILED", collected);
        } catch (IOException ioFailure) {
            state.finishError("ASSESSMENT_IO_FAILED", collected);
        } catch (RuntimeException failure) {
            state.finishError("ASSESSMENT_ERROR", collected);
        }
        state.writeOutputs();
        return state.exitCode();
    }

    private BootUiClient client() {
        return BootUiClient.direct(new BootUiClientOptions(url, apiPath, null, timeout));
    }

    private BootUiCatalog requireCatalog(BootUiClient client, long deadline) {
        BootUiCatalog catalog = client.catalogBounded(MAX_RESPONSE_BYTES, remaining(deadline));
        if (!catalog.enabled()
                || catalog.serverVersion() == null
                || catalog.serverVersion().isBlank()
                || catalog.maxResults() < 1) {
            throw new AssessmentFailure("INVALID_TARGET");
        }
        return catalog;
    }

    private Identity identity(BootUiClient client, long deadline) {
        JsonValue overview = client.getBounded("/overview", MAX_RESPONSE_BYTES, remaining(deadline));
        CiFramework framework = overview.isObject()
                ? CiFramework.fromName(overview.get("frameworkName").asString(null))
                : null;
        if (framework == null) {
            throw new AssessmentFailure("UNSUPPORTED_FRAMEWORK");
        }
        String version = overview.get("bootUiVersion").asString(null);
        JsonValue run = overview.get("run");
        String instanceId = run.get("instanceId").asString(null);
        String runId = run.get("runId").asString(null);
        if (version == null || !SAFE_VERSION.matcher(version).matches() || !isRunId(instanceId) || !isRunId(runId)) {
            throw new AssessmentFailure("MISSING_IDENTITY");
        }
        return new Identity(framework, version, instanceId, runId);
    }

    private List<JsonValue> preflight(CiPolicy policy, BootUiCatalog catalog, CiFramework framework) {
        if (!framework.supportsPolicy(policy)) {
            throw new AssessmentFailure("UNSUPPORTED_ADVISOR_FOR_FRAMEWORK");
        }
        List<JsonValue> outcomes = new ArrayList<>();
        for (CiPolicy.Advisor advisor : policy.orderedAdvisors()) {
            CiPolicy.Selection selection = policy.advisors().get(advisor);
            String unavailable = unavailability(advisor, catalog);
            if (unavailable != null && selection.required()) {
                throw new AssessmentFailure("REQUIRED_ADVISOR_UNAVAILABLE");
            }
            outcomes.add(object(
                    "advisor",
                    advisor.policyName(),
                    "required",
                    selection.required(),
                    "preflight",
                    unavailable == null ? "READY" : unavailable));
        }
        return outcomes;
    }

    private List<AdvisorResult> collect(CiPolicy policy, BootUiCatalog catalog, BootUiClient client, long deadline) {
        List<AdvisorResult> output = new ArrayList<>();
        int remainingDetails = MAX_TOTAL_DETAILS;
        int remainingPages = MAX_TOTAL_PAGES;
        for (CiPolicy.Advisor advisor : policy.orderedAdvisors()) {
            if (System.nanoTime() >= deadline) {
                throw new AssessmentFailure("ASSESSMENT_BUDGET_EXCEEDED");
            }
            CiPolicy.Selection selection = policy.advisors().get(advisor);
            String unavailable = unavailability(advisor, catalog);
            if (unavailable != null) {
                if (selection.required()) {
                    throw new AssessmentFailure("REQUIRED_ADVISOR_UNAVAILABLE");
                }
                AdvisorResult skipped = AdvisorResult.skipped(advisor, selection.required(), unavailable);
                output.add(skipped);
                collected.add(skipped);
                continue;
            }
            ToolResult scan =
                    client.invokeBounded(advisor.scanTool(), Map.of(), MAX_RESPONSE_BYTES, remaining(deadline));
            if (!scan.successful()) {
                throw new AssessmentFailure("SCAN_FAILED");
            }
            ToolResult reportResult =
                    client.invokeBounded(advisor.reportTool(), Map.of(), MAX_RESPONSE_BYTES, remaining(deadline));
            if (!reportResult.successful()) {
                throw new AssessmentFailure("REPORT_FAILED");
            }
            JsonValue report = reportResult.payload();
            EvaluatedReport evaluated = evaluateReport(advisor, selection, report);
            if (!evaluated
                    .scanId()
                    .equals(scan.payload().get("violationDetails").get("scanId").asString(null))) {
                throw new AssessmentFailure("SCAN_REPORT_MISMATCH");
            }
            JsonValue scanSummary = scan.payload();
            JsonValue scanStatus = scanSummary.get("scan");
            JsonValue scanEvidence = scanSummary.get("evidence");
            if (!"SCANNED".equals(scanStatus.get("status").asString(null))
                    || integer(scanStatus.get("rulesEvaluated")) != integer(report.get("rulesEvaluated"))
                    || integer(scanStatus.get(advisor.subjectField())) != integer(report.get(advisor.subjectField()))
                    || integer(scanStatus.get("violationsFound"))
                            != evaluated.findings().size()
                    || integer(scanSummary.get("findingsFound"))
                            != evaluated.findings().size()
                    || !scanEvidence.get("usable").isBoolean()
                    || !scanEvidence.get("usable").asBoolean(false)
                    || !scanEvidence.get("coverageComplete").isBoolean()
                    || !scanEvidence.get("coverageComplete").asBoolean(false)
                    || !scanEvidence.get("limitations").isArray()
                    || scanEvidence.get("limitations").size() != 0
                    || !severityCounts(scanSummary.get("severityCounts")).equals(evaluated.severityCounts())) {
                throw new AssessmentFailure("SCAN_REPORT_MISMATCH");
            }
            PagedFindings pages =
                    pageFindings(advisor, evaluated, catalog, client, remainingDetails, remainingPages, deadline);
            remainingDetails -= pages.detailCount();
            remainingPages -= pages.pageCount();
            AdvisorResult completed = new AdvisorResult(
                    advisor,
                    selection.required(),
                    "ASSESSED",
                    evaluated.totalFindings(),
                    evaluated.rulesSkipped(),
                    evaluated.rulesEvaluated(),
                    evaluated.subjectsAnalyzed(),
                    true,
                    true,
                    List.of(),
                    pages.findings(),
                    null,
                    evaluated.findings().stream()
                            .anyMatch(finding -> finding.severity().meets(selection.failOn())));
            output.add(completed);
            collected.add(completed);
        }
        return List.copyOf(output);
    }

    private RuntimeContext collectRuntimeInsights(
            CiPolicy policy, BootUiCatalog catalog, BootUiClient client, long deadline) {
        if (!policy.includeInsights()) {
            return RuntimeContext.empty("NOT_REQUESTED");
        }
        BootUiCatalog.CatalogTool tool = catalog.tool(RUNTIME_INSIGHTS_TOOL);
        if (tool == null || !tool.panelEnabled()) {
            return RuntimeContext.empty("UNAVAILABLE");
        }
        if (tool.action()
                || !RUNTIME_INSIGHTS_PANEL.equals(tool.panel())
                || !"QUERY_LIMIT".equals(tool.schema())
                || !tool.arguments().containsAll(List.of("query", "limit"))) {
            return RuntimeContext.empty("ERROR");
        }

        long remainingNanos = deadline - System.nanoTime();
        long reservedNanos = timeout.plus(FINAL_IDENTITY_MARGIN).toNanos();
        if (remainingNanos <= reservedNanos) {
            return RuntimeContext.empty("ERROR");
        }
        Duration contextBudget =
                Duration.ofNanos(Math.min(MAX_RUNTIME_CONTEXT_BUDGET.toNanos(), remainingNanos - reservedNanos));
        int observationLimit = Math.min(MAX_RUNTIME_OBSERVATIONS, Math.max(1, catalog.maxResults()));
        Map<String, JsonValue> arguments = Map.of("query", JsonValue.of(""), "limit", JsonValue.of(observationLimit));
        try {
            ToolResult result =
                    client.invokeBounded(RUNTIME_INSIGHTS_TOOL, arguments, MAX_RESPONSE_BYTES, contextBudget);
            if (!result.successful()) {
                return RuntimeContext.empty(result.status() == 403 || result.status() == 404 ? "UNAVAILABLE" : "ERROR");
            }
            return projectRuntimeInsights(result.payload(), observationLimit);
        } catch (BootUiClientException requestFailure) {
            if (System.nanoTime() >= deadline) {
                throw new AssessmentFailure("ASSESSMENT_BUDGET_EXCEEDED");
            }
            return RuntimeContext.empty("ERROR");
        } catch (AssessmentFailure invalidContext) {
            if ("ASSESSMENT_BUDGET_EXCEEDED".equals(invalidContext.code())) {
                throw invalidContext;
            }
            return RuntimeContext.empty("ERROR");
        } catch (ArithmeticException invalidCount) {
            return RuntimeContext.empty("ERROR");
        } catch (RuntimeException unexpectedContextFailure) {
            return RuntimeContext.empty("ERROR");
        }
    }

    private RuntimeContext projectRuntimeInsights(JsonValue report, int observationLimit) {
        if (!report.isObject() || !report.get("available").isBoolean()) {
            throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
        }
        if (!report.get("available").asBoolean(false)) {
            return RuntimeContext.empty("UNAVAILABLE");
        }

        JsonValue lowFootprint = report.get("lowFootprint");
        boolean lowFootprintActive = false;
        boolean capturePaused = false;
        if (!lowFootprint.isMissing()) {
            if (!lowFootprint.isObject()
                    || !lowFootprint.get("active").isBoolean()
                    || !lowFootprint.get("paused").isBoolean()) {
                throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
            }
            lowFootprintActive = lowFootprint.get("active").asBoolean(false);
            capturePaused = lowFootprint.get("paused").asBoolean(false);
            if (capturePaused && !lowFootprintActive) {
                throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
            }
        }

        long sources = 0;
        long retainedEvents = 0;
        long byRequestId = 0;
        long byExecutionId = 0;
        long byTraceId = 0;
        long unlinked = 0;
        long droppedEvents = 0;
        JsonValue coverage = report.get("coverage");
        if (!coverage.isArray() || coverage.size() > 64) {
            throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
        }
        Set<String> seenSources = new HashSet<>();
        for (JsonValue source : coverage.values()) {
            String name = source.get("source").asString(null);
            long events = nonnegative(source.get("events"));
            long requestLinked = nonnegative(source.get("byRequestId"));
            long executionLinked = nonnegative(source.get("byExecutionId"));
            long traceLinked = nonnegative(source.get("byTraceId"));
            long sourceUnlinked = nonnegative(source.get("unlinked"));
            long dropped = nonnegative(source.get("dropped"));
            long linked = Math.addExact(
                    Math.addExact(requestLinked, executionLinked), Math.addExact(traceLinked, sourceUnlinked));
            if (name == null
                    || !SAFE_RUNTIME_SOURCE.matcher(name).matches()
                    || !seenSources.add(name)
                    || events != linked) {
                throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
            }
            sources++;
            retainedEvents = Math.addExact(retainedEvents, events);
            byRequestId = Math.addExact(byRequestId, requestLinked);
            byExecutionId = Math.addExact(byExecutionId, executionLinked);
            byTraceId = Math.addExact(byTraceId, traceLinked);
            unlinked = Math.addExact(unlinked, sourceUnlinked);
            droppedEvents = Math.addExact(droppedEvents, dropped);
        }

        long httpExchangesRead = nonnegative(report.get("requests"));
        long omittedObservations = nonnegative(report.get("omitted"));
        long checksNotRun = stringArraySize(report.get("checksNotRun"));
        long notExercisedRoutes = Math.addExact(
                stringArraySize(report.get("notExercised")), nonnegative(report.get("notExercisedOmitted")));
        long limitations = stringArraySize(report.get("limitations"));
        JsonValue listed = report.get("observations");
        if (!listed.isArray() || listed.size() > observationLimit) {
            throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
        }
        List<RuntimeObservation> observations = new ArrayList<>();
        for (JsonValue observation : listed.values()) {
            String kind = observation.get("kind").asString(null);
            String status = observation.get("status").asString(null);
            long eligible = nonnegative(observation.get("eligible"));
            long affected = nonnegative(observation.get("affected"));
            if (kind == null
                    || !SAFE_RUNTIME_KIND.matcher(kind).matches()
                    || status == null
                    || !RUNTIME_OBSERVATION_STATUSES.contains(status)) {
                throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
            }
            observations.add(new RuntimeObservation(kind, status, eligible, affected));
        }
        return new RuntimeContext(
                lowFootprintActive || capturePaused ? "PARTIAL" : "CAPTURED",
                lowFootprintActive,
                capturePaused,
                httpExchangesRead,
                sources,
                retainedEvents,
                byRequestId,
                byExecutionId,
                byTraceId,
                unlinked,
                droppedEvents,
                observations,
                omittedObservations,
                checksNotRun,
                notExercisedRoutes,
                limitations);
    }

    private static long nonnegative(JsonValue value) {
        long number = integer(value);
        if (number < 0) {
            throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
        }
        return number;
    }

    private static long stringArraySize(JsonValue value) {
        if (!value.isArray() || value.values().stream().anyMatch(item -> !item.isString())) {
            throw new AssessmentFailure("INVALID_RUNTIME_CONTEXT");
        }
        return value.size();
    }

    private EvaluatedReport evaluateReport(CiPolicy.Advisor advisor, CiPolicy.Selection selection, JsonValue report) {
        JsonValue evidence = report.get("evidence");
        if (!evidence.isObject()
                || !evidence.get("usable").isBoolean()
                || !evidence.get("usable").asBoolean(false)
                || !evidence.get("coverageComplete").isBoolean()
                || !evidence.get("coverageComplete").asBoolean(false)) {
            throw new AssessmentFailure("EVIDENCE_UNEVALUABLE");
        }
        if (integer(report.get(advisor.subjectField())) <= 0) {
            throw new AssessmentFailure("ZERO_SUBJECTS");
        }
        JsonValue scan = report.get("scan");
        if (!"SCANNED".equals(scan.get("status").asString(null))) {
            throw new AssessmentFailure("SCAN_INCOMPLETE");
        }
        long rulesEvaluated = integer(report.get("rulesEvaluated"));
        if (rulesEvaluated <= 0 || integer(scan.get("rulesEvaluated")) != rulesEvaluated) {
            throw new AssessmentFailure("INVALID_REPORT");
        }
        for (String errorField : List.of("analysisErrors", "diagnostics")) {
            JsonValue errors = report.get(errorField);
            boolean neutralDatabaseDiagnostics = advisor == CiPolicy.Advisor.DATABASE
                    && errorField.equals("diagnostics")
                    && errors.isArray()
                    && errors.values().stream()
                            .allMatch(item -> "INFO".equals(item.get("level").asString(null)));
            if (!errors.isMissing() && (!errors.isArray() || errors.size() != 0 && !neutralDatabaseDiagnostics)) {
                throw new AssessmentFailure("ADVISOR_ERRORS");
            }
        }
        JsonValue limitations = evidence.get("limitations");
        JsonValue skipped = report.get("rulesSkipped");
        if (advisor == CiPolicy.Advisor.DATABASE
                && (skipped.isMissing()
                        || report.get("rulesErrored").isMissing()
                        || !report.get("diagnostics").isArray()
                        || !report.get("truncated").isBoolean())) {
            throw new AssessmentFailure("INVALID_REPORT");
        }
        boolean invalidSkipped = advisor == CiPolicy.Advisor.DATABASE
                ? !skipped.isMissing() && integer(skipped) < 0
                : nonzeroIfPresent(skipped);
        if (!limitations.isArray()
                || limitations.size() != 0
                || invalidSkipped
                || nonzeroIfPresent(report.get("rulesErrored"))
                || !report.get("truncated").isMissing()
                        && (!report.get("truncated").isBoolean()
                                || report.get("truncated").asBoolean(false))) {
            throw new AssessmentFailure("ADVISOR_ERRORS");
        }

        JsonValue details = report.get("violationDetails");
        String scanId = details.get("scanId").asString(null);
        long totalDetails = integer(details.get("total"));
        long retainedDetails = integer(details.get("retained"));
        if (!isNonblank(scanId)
                || totalDetails < 0
                || retainedDetails < 0
                || totalDetails != retainedDetails
                || details.get("truncated").asBoolean(true)) {
            throw new AssessmentFailure("DETAILS_INCOMPLETE");
        }
        JsonValue results = report.get(advisor.resultsField());
        if (!results.isArray()) {
            throw new AssessmentFailure("INVALID_REPORT");
        }
        JsonValue suppression = report.get("suppression");
        if (!suppression.isMissing()) {
            long suppressedFindings = integer(suppression.get("suppressedFindings"));
            long omitted = integer(suppression.get("omitted"));
            if (suppressedFindings < 0
                    || omitted < 0
                    || suppressedFindings != 0
                    || omitted != 0
                    || !isEmptyArray(suppression.get("incompleteRules"))
                    || !isEmptyArray(suppression.get("findings"))) {
                throw new AssessmentFailure("SUPPRESSION_PRESENT");
            }
        }

        List<RuleFinding> findings = new ArrayList<>();
        Set<String> seenRules = new HashSet<>();
        long activeTotal = 0;
        for (JsonValue rule : results.values()) {
            String id = rule.get("id").asString(null);
            long count = integer(rule.get("violationCount"));
            long suppressed = rule.get("suppressedCount").isMissing() ? 0 : integer(rule.get("suppressedCount"));
            JsonValue dismissedValue = rule.get("dismissed");
            boolean dismissed = dismissedValue.asBoolean(false);
            String severityText = rule.get("severity").asString(null);
            String status = rule.get("status").asString(null);
            if (!isSafeRuleId(id)
                    || !seenRules.add(id)
                    || count < 0
                    || suppressed < 0
                    || !dismissedValue.isBoolean()
                    || dismissed
                    || isNonblank(rule.get("coverageNote").asString(null))) {
                throw new AssessmentFailure(dismissed ? "SUPPRESSION_PRESENT" : "INVALID_REPORT");
            }
            if (status == null
                    || !Set.of("PASS", "VIOLATION", "SKIPPED").contains(status)
                    || "VIOLATION".equals(status) != (count > 0)
                    || advisor != CiPolicy.Advisor.DATABASE && "SKIPPED".equals(status)) {
                throw new AssessmentFailure("INVALID_REPORT");
            }
            if (count > 0) {
                CiPolicy.Severity severity = parseSeverity(severityText);
                activeTotal = Math.addExact(activeTotal, count);
                findings.add(new RuleFinding(id, severity, count, suppressed));
                if (severity.meets(selection.failOn())) {
                    // Recorded as a finding; errors discovered later still take aggregate precedence.
                }
            } else if (suppressed > 0) {
                throw new AssessmentFailure("SUPPRESSION_PRESENT");
            }
        }
        long reportTotal = integer(report.get("violationsFound"));
        long databaseSkipped = advisor == CiPolicy.Advisor.DATABASE ? integer(skipped) : 0;
        long databaseErrored = advisor == CiPolicy.Advisor.DATABASE ? integer(report.get("rulesErrored")) : 0;
        Map<CiPolicy.Severity, Long> counts = new EnumMap<>(CiPolicy.Severity.class);
        for (CiPolicy.Severity severity : CiPolicy.Severity.values()) {
            counts.put(severity, 0L);
        }
        for (RuleFinding finding : findings) {
            counts.put(finding.severity(), Math.addExact(counts.get(finding.severity()), finding.count()));
        }
        if (reportTotal < 0
                || reportTotal != findings.size()
                || integer(scan.get("violationsFound")) != reportTotal
                || integer(scan.get(advisor.subjectField())) != integer(report.get(advisor.subjectField()))
                || totalDetails != activeTotal
                || !severityCounts(report.get("severityCounts")).equals(counts)
                || reportTotal > rulesEvaluated
                || advisor == CiPolicy.Advisor.DATABASE
                        && (databaseSkipped < 0
                                || databaseErrored < 0
                                || databaseSkipped > rulesEvaluated
                                || databaseErrored > rulesEvaluated - databaseSkipped
                                || reportTotal > rulesEvaluated - databaseSkipped - databaseErrored
                                || databaseSkipped + databaseErrored >= rulesEvaluated)) {
            throw new AssessmentFailure("COUNTS_INCONSISTENT");
        }
        return new EvaluatedReport(
                scanId,
                List.copyOf(findings),
                activeTotal,
                advisor == CiPolicy.Advisor.DATABASE ? integer(skipped) : 0,
                rulesEvaluated,
                integer(report.get(advisor.subjectField())),
                Map.copyOf(counts));
    }

    private static Map<CiPolicy.Severity, Long> severityCounts(JsonValue value) {
        if (!value.isArray() || value.size() != CiPolicy.Severity.values().length) {
            throw new AssessmentFailure("COUNTS_INCONSISTENT");
        }
        Map<CiPolicy.Severity, Long> counts = new EnumMap<>(CiPolicy.Severity.class);
        for (JsonValue item : value.values()) {
            CiPolicy.Severity severity = parseSeverity(item.get("severity").asString(null));
            long count = integer(item.get("count"));
            if (count < 0 || counts.putIfAbsent(severity, count) != null) {
                throw new AssessmentFailure("COUNTS_INCONSISTENT");
            }
        }
        return counts;
    }

    private PagedFindings pageFindings(
            CiPolicy.Advisor advisor,
            EvaluatedReport evaluated,
            BootUiCatalog catalog,
            BootUiClient client,
            int remainingDetails,
            int remainingPages,
            long deadline) {
        List<JsonValue> findings = new ArrayList<>();
        int pages = 0;
        int details = 0;
        int limit = Math.max(1, Math.min(MAX_DETAIL_PAGE_SIZE, catalog.maxResults()));
        for (RuleFinding finding : evaluated.findings()) {
            long offset = 0;
            long expectedTotal = -1;
            long expectedRetained = -1;
            long expectedSuppressed = -1;
            boolean more;
            do {
                if (System.nanoTime() >= deadline) {
                    throw new AssessmentFailure("ASSESSMENT_BUDGET_EXCEEDED");
                }
                if (++pages > remainingPages || details >= remainingDetails) {
                    throw new AssessmentFailure("DETAIL_BUDGET_EXCEEDED");
                }
                Map<String, JsonValue> args = new LinkedHashMap<>();
                args.put("id", JsonValue.of(finding.ruleId()));
                args.put("scanId", JsonValue.of(evaluated.scanId()));
                args.put("offset", JsonValue.of(offset));
                args.put("limit", JsonValue.of(limit));
                ToolResult pageResult =
                        client.invokeBounded(advisor.detailsTool(), args, MAX_RESPONSE_BYTES, remaining(deadline));
                if (!pageResult.successful()) {
                    throw new AssessmentFailure("DETAIL_PAGE_FAILED");
                }
                JsonValue page = pageResult.payload();
                JsonValue violations = page.get("violations");
                JsonValue metadata = page.get("page");
                if (!page.get("scanId").isString()
                        || !evaluated.scanId().equals(page.get("scanId").asString(null))
                        || !finding.ruleId().equals(page.get("ruleId").asString(null))
                        || !violations.isArray()
                        || !metadata.isObject()) {
                    throw new AssessmentFailure("DETAIL_PAGE_INCONSISTENT");
                }
                long returned = integer(metadata.get("returned"));
                long total = integer(page.get("violationCount"));
                long retained = integer(page.get("retainedCount"));
                long suppressed = integer(page.get("suppressedCount"));
                long pageOffset = integer(metadata.get("offset"));
                long pageLimit = integer(metadata.get("limit"));
                long pageTotal = integer(metadata.get("total"));
                long pageMatched = integer(metadata.get("matched"));
                boolean truncated = page.get("truncated").asBoolean(true);
                if (!metadata.get("hasMore").isBoolean()) {
                    throw new AssessmentFailure("DETAIL_PAGE_INCONSISTENT");
                }
                more = metadata.get("hasMore").asBoolean(false);
                if (returned < 0
                        || returned != violations.size()
                        || pageOffset != offset
                        || pageLimit < 1
                        || pageLimit > limit
                        || returned > pageLimit
                        || total < 0
                        || retained < 0
                        || suppressed < 0
                        || pageTotal != retained
                        || pageMatched != retained
                        || truncated
                        || (returned == 0 && more)
                        || more != offset + returned < pageMatched
                        || (expectedTotal >= 0 && expectedTotal != total)
                        || (expectedRetained >= 0 && expectedRetained != retained)
                        || (expectedSuppressed >= 0 && expectedSuppressed != suppressed)) {
                    throw new AssessmentFailure("DETAIL_PAGE_INCONSISTENT");
                }
                expectedTotal = total;
                expectedRetained = retained;
                expectedSuppressed = suppressed;
                if (suppressed != 0 || hasTrue(page.get("suppressed"))) {
                    throw new AssessmentFailure("SUPPRESSION_PRESENT");
                }
                requireAlignedOrEmpty(page.get("identities"), violations.size());
                requireAlignedOrEmpty(page.get("suppressed"), violations.size());
                if (retained != total || retained != finding.count() + finding.suppressedCount()) {
                    throw new AssessmentFailure("DETAILS_INCOMPLETE");
                }
                if (returned > remainingDetails - details) {
                    throw new AssessmentFailure("DETAIL_BUDGET_EXCEEDED");
                }
                details += Math.toIntExact(returned);
                offset = Math.addExact(offset, returned);
                if (more && returned == 0) {
                    throw new AssessmentFailure("DETAIL_PAGE_NO_PROGRESS");
                }
            } while (more);
            if (offset != finding.count() + finding.suppressedCount()) {
                throw new AssessmentFailure("DETAILS_INCOMPLETE");
            }
            findings.add(object(
                    "ruleId", finding.ruleId(), "severity", finding.severity().name(), "count", finding.count()));
        }
        return new PagedFindings(List.copyOf(findings), details, pages);
    }

    private String unavailability(CiPolicy.Advisor advisor, BootUiCatalog catalog) {
        BootUiCatalog.CatalogTool scan = catalog.tool(advisor.scanTool());
        BootUiCatalog.CatalogTool report = catalog.tool(advisor.reportTool());
        BootUiCatalog.CatalogTool details = catalog.tool(advisor.detailsTool());
        if (scan == null || report == null || details == null) {
            return "UNAVAILABLE";
        }
        if (!scan.panel().equals(report.panel())
                || !scan.panel().equals(details.panel())
                || !scan.action()
                || report.action()
                || details.action()
                || !details.arguments().containsAll(List.of("id", "scanId", "offset", "limit"))) {
            throw new AssessmentFailure("INVALID_CATALOG");
        }
        if (!scan.panelEnabled() || !report.panelEnabled() || !details.panelEnabled()) {
            return "PANEL_DISABLED";
        }
        if (scan.panelReadOnly()) {
            return "PANEL_READ_ONLY";
        }
        return null;
    }

    private JsonValue readWitness() throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(witnessPath)) {
            bytes = input.readNBytes(16 * 1024 + 1);
        }
        if (bytes.length > 16 * 1024) {
            throw new AssessmentFailure("INVALID_WITNESS");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new AssessmentFailure("INVALID_WITNESS");
        }
        JsonValue witness;
        try {
            witness = JsonValue.parseStrict(text);
        } catch (JsonParseException invalid) {
            throw new AssessmentFailure("INVALID_WITNESS");
        }
        if (!witness.isObject()) {
            throw new AssessmentFailure("INVALID_WITNESS");
        }
        if ("NOT_ASSESSED".equals(witness.get("status").asString(null))) {
            JsonValue errors = witness.get("errors");
            String code = errors.isArray() && errors.size() == 1
                    ? errors.values().get(0).get("code").asString(null)
                    : null;
            if (!Set.copyOf(witness.names()).equals(Set.of("schemaVersion", "assessmentId", "status", "errors"))
                    || !witness.get("schemaVersion").isNumber()
                    || !isSchemaVersion(witness.get("schemaVersion"), LEGACY_SCHEMA_VERSION)
                    || !assessmentId.equals(witness.get("assessmentId").asString(null))
                    || code == null
                    || !ATTACH_ERRORS.contains(code)
                    || !Set.copyOf(errors.values().get(0).names()).equals(Set.of("code"))) {
                throw new AssessmentFailure("INVALID_WITNESS");
            }
            throw new AssessmentFailure(code);
        }
        int schemaVersion = witnessSchemaVersion(witness);
        Set<String> allowedKeys = schemaVersion == LEGACY_SCHEMA_VERSION ? LEGACY_WITNESS_KEYS : WITNESS_KEYS;
        List<String> unknown = new ArrayList<>(witness.names());
        unknown.removeAll(allowedKeys);
        if (!unknown.isEmpty()) {
            throw new AssessmentFailure("INVALID_WITNESS");
        }
        return witness;
    }

    private void validateWitness(JsonValue witness, CiPolicy policy) {
        int schemaVersion = witnessSchemaVersion(witness);
        CiFramework framework = schemaVersion == LEGACY_SCHEMA_VERSION
                ? CiFramework.SPRING_BOOT
                : CiFramework.fromName(witness.get("frameworkName").asString(null));
        for (String required : WITNESS_KEYS) {
            if ((schemaVersion == LEGACY_SCHEMA_VERSION ? LEGACY_WITNESS_KEYS : WITNESS_KEYS).contains(required)
                    && witness.get(required).isMissing()) {
                throw new AssessmentFailure("INVALID_WITNESS");
            }
        }
        if (framework == null
                || !framework.supportsPolicy(policy)
                || !assessmentId.equals(witness.get("assessmentId").asString(null))
                || !url.equals(witness.get("targetUrl").asString(null))
                || !apiPath.equals(witness.get("apiPath").asString(null))
                || !policy.sha256().equals(witness.get("policySha256").asString(null))
                || !isRunId(witness.get("instanceId").asString(null))
                || !isRunId(witness.get("runId").asString(null))
                || !SAFE_VERSION
                        .matcher(witness.get("bootUiVersion").asString(""))
                        .matches()
                || !isValidTimestamp(witness.get("attachedAt").asString(null))
                || !witnessAdvisorsMatch(witness.get("advisors"), policy)) {
            throw new AssessmentFailure("WITNESS_MISMATCH");
        }
    }

    private static boolean witnessAdvisorsMatch(JsonValue advisors, CiPolicy policy) {
        if (!advisors.isArray() || advisors.size() != policy.advisors().size()) {
            return false;
        }
        Map<String, Boolean> recorded = new LinkedHashMap<>();
        for (JsonValue advisor : advisors.values()) {
            if (!advisor.isObject()
                    || !Set.of("advisor", "required", "preflight").containsAll(advisor.names())
                    || advisor.names().size() != 3
                    || !advisor.get("advisor").isString()
                    || !advisor.get("required").isBoolean()
                    || !advisor.get("preflight").isString()) {
                return false;
            }
            String name = advisor.get("advisor").asString(null);
            if (name == null
                    || recorded.putIfAbsent(name, advisor.get("required").asBoolean(false)) != null) {
                return false;
            }
            String preflight = advisor.get("preflight").asString(null);
            if (!Set.of("READY", "UNAVAILABLE", "PANEL_DISABLED", "PANEL_READ_ONLY")
                    .contains(preflight)) {
                return false;
            }
        }
        if (recorded.size() != policy.advisors().size()) {
            return false;
        }
        for (CiPolicy.Advisor advisor : policy.orderedAdvisors()) {
            CiPolicy.Selection selection = policy.advisors().get(advisor);
            Boolean required = recorded.get(advisor.policyName());
            if (required == null || required != selection.required()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidTimestamp(String value) {
        if (!isNonblank(value)) {
            return false;
        }
        try {
            Instant.parse(value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private void requireSameIdentity(JsonValue witness, Identity identity) {
        CiFramework witnessFramework = witnessSchemaVersion(witness) == LEGACY_SCHEMA_VERSION
                ? CiFramework.SPRING_BOOT
                : CiFramework.fromName(witness.get("frameworkName").asString(null));
        if (identity.framework() != witnessFramework
                || !identity.bootUiVersion().equals(witness.get("bootUiVersion").asString(null))
                || !identity.instanceId().equals(witness.get("instanceId").asString(null))
                || !identity.runId().equals(witness.get("runId").asString(null))) {
            throw new AssessmentFailure("IDENTITY_CHANGED");
        }
    }

    private static int witnessSchemaVersion(JsonValue witness) {
        JsonValue version = witness.get("schemaVersion");
        if (isSchemaVersion(version, LEGACY_SCHEMA_VERSION)) {
            return LEGACY_SCHEMA_VERSION;
        }
        if (isSchemaVersion(version, CURRENT_SCHEMA_VERSION)) {
            return CURRENT_SCHEMA_VERSION;
        }
        throw new AssessmentFailure("INVALID_WITNESS");
    }

    private static boolean isSchemaVersion(JsonValue value, int expected) {
        return value.isNumber() && Integer.toString(expected).equals(value.asDisplayText());
    }

    private int failAttach(String code) {
        attachFailureCode = code;
        writeFailureIfPossible(witnessPath, code);
        return 1;
    }

    private void writeFailureIfPossible(Path path, String code) {
        try {
            Map<String, JsonValue> failure = new LinkedHashMap<>();
            failure.put("schemaVersion", JsonValue.of(1));
            failure.put("assessmentId", JsonValue.of(assessmentId));
            failure.put("status", JsonValue.of("NOT_ASSESSED"));
            failure.put("errors", JsonValue.array(List.of(object("code", code))));
            writeNewFile(path, JsonWriter.pretty(JsonValue.object(failure)));
        } catch (Exception ignored) {
            // A missing/unwritable path is reported by the caller; never replace an existing witness.
        }
    }

    private static void writeNewFile(Path path, String value) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Output path has no parent");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".bootui-ci-", ".tmp");
        try {
            Files.writeString(
                    temporary, value, java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.createLink(absolute, temporary);
                Files.delete(temporary);
            } catch (UnsupportedOperationException unsupported) {
                Files.move(temporary, absolute);
            } catch (IOException linkFailure) {
                if (Files.exists(absolute)) {
                    throw linkFailure;
                }
                Files.move(temporary, absolute);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String validateTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A literal loopback --url is required");
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("--url is not a valid URI");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || !(scheme.equals("http") || scheme.equals("https"))
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getPort() < 1
                || uri.getPort() > 65535
                || !isLiteralLoopback(host)) {
            throw new IllegalArgumentException("--url must use an explicit port and a literal loopback address");
        }
        if (uri.getRawPath() != null && uri.getRawPath().contains("\\")) {
            throw new IllegalArgumentException("--url path is invalid");
        }
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }

    private static String validateApiPath(String path) {
        try {
            URI uri = new URI(path);
            if (uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getRawPath() == null
                    || !uri.getRawPath().startsWith("/")
                    || uri.getRawPath().contains("\\")) {
                throw new IllegalArgumentException(
                        "--api-path must be a local absolute path without query or fragment");
            }
            return uri.getRawPath();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("--api-path is not a valid path");
        }
    }

    private static boolean isLiteralLoopback(String host) {
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (literal.equals("::1")) {
            return true;
        }
        String[] parts = literal.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException invalid) {
                return false;
            }
            if (octets[i] > 255) return false;
        }
        return octets[0] == 127;
    }

    private static String validateAssessmentId(String id) {
        if (id == null || !ASSESSMENT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("assessment-id must match [A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
        }
        return id;
    }

    private static boolean isRunId(String value) {
        return value != null && value.matches("[0-9a-f]{8}");
    }

    private static boolean isSafeRuleId(String value) {
        return value != null && SAFE_RULE_ID.matcher(value).matches();
    }

    private static boolean isNonblank(String value) {
        return value != null && !value.isBlank();
    }

    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new AssessmentFailure("ASSESSMENT_BUDGET_EXCEEDED");
        }
        return Duration.ofNanos(nanos);
    }

    private static CiPolicy.Severity parseSeverity(String value) {
        try {
            return CiPolicy.Severity.valueOf(value);
        } catch (Exception invalid) {
            throw new AssessmentFailure("UNKNOWN_SEVERITY");
        }
    }

    private static boolean isEmptyArray(JsonValue value) {
        return value.isMissing() || value.isArray() && value.size() == 0;
    }

    private static boolean nonzeroIfPresent(JsonValue value) {
        if (value.isMissing()) return false;
        long count = integer(value);
        return count < 0 || count != 0;
    }

    private static long integer(JsonValue value) {
        if (!value.isNumber() || !value.asDisplayText().matches("-?(0|[1-9][0-9]*)")) {
            return -1;
        }
        try {
            return Long.parseLong(value.asDisplayText());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static void requireAlignedOrEmpty(JsonValue values, int expectedSize) {
        if (!values.isMissing() && (!values.isArray() || values.size() != 0 && values.size() != expectedSize)) {
            throw new AssessmentFailure("DETAIL_PAGE_INCONSISTENT");
        }
    }

    private static boolean hasTrue(JsonValue value) {
        if (!value.isMissing() && !value.isArray()) {
            throw new AssessmentFailure("DETAIL_PAGE_INCONSISTENT");
        }
        return value.isArray()
                && value.values().stream().anyMatch(element -> {
                    if (!element.isBoolean()) {
                        throw new AssessmentFailure("DETAIL_PAGE_INCONSISTENT");
                    }
                    return element.asBoolean(false);
                });
    }

    private static JsonValue object(Object... members) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        for (int i = 0; i < members.length; i += 2) {
            String key = (String) members[i];
            Object value = members[i + 1];
            if (value instanceof String text) {
                values.put(key, JsonValue.of(text));
            } else if (value instanceof Boolean bool) {
                values.put(key, JsonValue.of(bool));
            } else if (value instanceof Integer number) {
                values.put(key, JsonValue.of(number.longValue()));
            } else if (value instanceof Long number) {
                values.put(key, JsonValue.of(number));
            } else if (value instanceof JsonValue json) {
                values.put(key, json);
            } else {
                throw new IllegalArgumentException("Unsupported JSON field value");
            }
        }
        return JsonValue.object(values);
    }

    private record Identity(CiFramework framework, String bootUiVersion, String instanceId, String runId) {}

    private record RuleFinding(String ruleId, CiPolicy.Severity severity, long count, long suppressedCount) {}

    private record EvaluatedReport(
            String scanId,
            List<RuleFinding> findings,
            long totalFindings,
            long rulesSkipped,
            long rulesEvaluated,
            long subjectsAnalyzed,
            Map<CiPolicy.Severity, Long> severityCounts) {}

    private record PagedFindings(List<JsonValue> findings, int detailCount, int pageCount) {}

    private record RuntimeObservation(String kind, String status, long eligible, long affected) {}

    private record RuntimeContext(
            String status,
            boolean lowFootprintActive,
            boolean capturePaused,
            long httpExchangesRead,
            long coverageSources,
            long retainedEvents,
            long byRequestId,
            long byExecutionId,
            long byTraceId,
            long unlinked,
            long droppedEvents,
            List<RuntimeObservation> observations,
            long omittedObservations,
            long checksNotRunCount,
            long notExercisedRouteCount,
            long limitationsCount) {

        private RuntimeContext {
            observations = List.copyOf(observations);
        }

        static RuntimeContext empty(String status) {
            return new RuntimeContext(status, false, false, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0);
        }

        JsonValue toJson() {
            return object(
                    "status",
                    status,
                    "workloadAttribution",
                    "UNVERIFIED",
                    "lowFootprintActive",
                    lowFootprintActive,
                    "capturePaused",
                    capturePaused,
                    "httpExchangesRead",
                    httpExchangesRead,
                    "coverage",
                    object(
                            "sources",
                            coverageSources,
                            "retainedEvents",
                            retainedEvents,
                            "byRequestId",
                            byRequestId,
                            "byExecutionId",
                            byExecutionId,
                            "byTraceId",
                            byTraceId,
                            "unlinked",
                            unlinked,
                            "droppedEvents",
                            droppedEvents),
                    "observations",
                    JsonValue.array(observations.stream()
                            .map(observation -> object(
                                    "kind",
                                    observation.kind(),
                                    "status",
                                    observation.status(),
                                    "eligible",
                                    observation.eligible(),
                                    "affected",
                                    observation.affected()))
                            .toList()),
                    "omittedObservations",
                    omittedObservations,
                    "checksNotRunCount",
                    checksNotRunCount,
                    "notExercisedRouteCount",
                    notExercisedRouteCount,
                    "limitationsCount",
                    limitationsCount);
        }
    }

    private static final class AssessmentFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String code;

        AssessmentFailure(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private static final class AdvisorResult {

        private final CiPolicy.Advisor advisor;
        private final boolean required;
        private final String status;
        private final long total;
        private final long rulesSkipped;
        private final long rulesEvaluated;
        private final long subjectsAnalyzed;
        private final boolean usable;
        private final boolean coverageComplete;
        private final List<JsonValue> limitations;
        private final List<JsonValue> findings;
        private final String reason;
        private final boolean violation;

        AdvisorResult(
                CiPolicy.Advisor advisor,
                boolean required,
                String status,
                long total,
                long rulesSkipped,
                long rulesEvaluated,
                long subjectsAnalyzed,
                boolean usable,
                boolean coverageComplete,
                List<JsonValue> limitations,
                List<JsonValue> findings,
                String reason,
                boolean violation) {
            this.advisor = advisor;
            this.required = required;
            this.status = status;
            this.total = total;
            this.rulesSkipped = rulesSkipped;
            this.rulesEvaluated = rulesEvaluated;
            this.subjectsAnalyzed = subjectsAnalyzed;
            this.usable = usable;
            this.coverageComplete = coverageComplete;
            this.limitations = limitations;
            this.findings = findings;
            this.reason = reason;
            this.violation = violation;
        }

        static AdvisorResult skipped(CiPolicy.Advisor advisor, boolean required, String reason) {
            return new AdvisorResult(
                    advisor, required, "SKIPPED", 0, 0, 0, 0, false, false, List.of(), List.of(), reason, false);
        }
    }

    private static final class Assessment {

        private final String assessmentId;
        private final Path outputDirectory;
        private CiPolicy policy;
        private Identity identity;
        private List<AdvisorResult> results = List.of();
        private List<String> errors = List.of();
        private boolean violation;
        private RuntimeContext runtimeContext = RuntimeContext.empty("NOT_COLLECTED");

        Assessment(String assessmentId, Path outputDirectory) {
            this.assessmentId = assessmentId;
            this.outputDirectory = outputDirectory;
        }

        void context(CiPolicy policy, Identity identity) {
            this.policy = policy;
            this.identity = identity;
        }

        void policy(CiPolicy policy) {
            this.policy = policy;
            runtimeContext = RuntimeContext.empty(policy.includeInsights() ? "NOT_COLLECTED" : "NOT_REQUESTED");
        }

        void runtimeContext(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
        }

        void finish(CiPolicy policy, Identity identity, List<AdvisorResult> results, List<String> errors) {
            this.policy = policy;
            this.identity = identity;
            this.results = results;
            this.errors = errors;
            this.violation = results.stream().anyMatch(result -> result.violation);
        }

        void finishError(String code, List<AdvisorResult> partialResults) {
            errors = List.of(code);
            results = List.copyOf(partialResults);
            violation = results.stream().anyMatch(result -> result.violation);
        }

        int exitCode() {
            if (!errors.isEmpty()) {
                return 1;
            }
            return violation ? 3 : 0;
        }

        void writeOutputs() {
            List<Path> written = new ArrayList<>();
            try {
                JsonValue bundle = toBundle();
                String json = JsonWriter.pretty(bundle) + "\n";
                if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BUNDLE_BYTES) {
                    throw new IOException("Bundle exceeded its byte limit");
                }
                String summary = markdown(bundle);
                String xml = junit(bundle);
                for (Map.Entry<String, String> report : List.of(
                        Map.entry("junit.xml", xml),
                        Map.entry("bundle.json", json),
                        Map.entry("summary.md", summary))) {
                    Path path = outputDirectory.resolve(report.getKey());
                    writeNewFile(path, report.getValue());
                    written.add(path);
                }
            } catch (IOException | RuntimeException failure) {
                for (Path path : written) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw new BootUiClientException("Could not write complete BootUI assessment reports", failure);
            }
        }

        private JsonValue toBundle() {
            Map<String, JsonValue> root = new LinkedHashMap<>();
            root.put("schemaVersion", JsonValue.of(identity == null ? LEGACY_SCHEMA_VERSION : CURRENT_SCHEMA_VERSION));
            root.put("assessmentId", JsonValue.of(assessmentId));
            root.put("status", JsonValue.of(errors.isEmpty() ? (violation ? "VIOLATION" : "PASS") : "NOT_ASSESSED"));
            if (identity != null) {
                root.put("frameworkName", JsonValue.of(identity.framework().displayName()));
            }
            root.put("bootUiVersion", JsonValue.of(identity == null ? "" : identity.bootUiVersion()));
            root.put("instanceId", JsonValue.of(identity == null ? "" : identity.instanceId()));
            root.put("runId", JsonValue.of(identity == null ? "" : identity.runId()));
            root.put("policySha256", JsonValue.of(policy == null ? "" : policy.sha256()));
            root.put("runtimeContext", runtimeContext.toJson());
            root.put(
                    "advisors",
                    JsonValue.array(results.stream()
                            .map(result -> {
                                Map<String, JsonValue> row = new LinkedHashMap<>();
                                row.put("name", JsonValue.of(result.advisor.policyName()));
                                row.put("required", JsonValue.of(result.required));
                                row.put("status", JsonValue.of(result.status));
                                row.put("reason", JsonValue.of(result.reason));
                                row.put("findingCount", JsonValue.of(result.total));
                                row.put("rulesSkipped", JsonValue.of(result.rulesSkipped));
                                row.put("rulesEvaluated", JsonValue.of(result.rulesEvaluated));
                                row.put("subjectsAnalyzed", JsonValue.of(result.subjectsAnalyzed));
                                row.put("usable", JsonValue.of(result.usable));
                                row.put("coverageComplete", JsonValue.of(result.coverageComplete));
                                row.put("limitations", JsonValue.array(result.limitations));
                                row.put("findings", JsonValue.array(result.findings));
                                return JsonValue.object(row);
                            })
                            .toList()));
            root.put(
                    "errors",
                    JsonValue.array(
                            errors.stream().map(error -> object("code", error)).toList()));
            return JsonValue.object(root);
        }

        private String markdown(JsonValue bundle) {
            StringBuilder output = new StringBuilder();
            output.append("# BootUI assessment: ")
                    .append(bundle.get("status").asString("NOT_ASSESSED"))
                    .append("\n\n")
                    .append("Assessment `")
                    .append(assessmentId);
            if (bundle.get("frameworkName").isString()) {
                output.append("` — ").append(bundle.get("frameworkName").asString(""));
            } else {
                output.append("`");
            }
            output.append(" — BootUI ")
                    .append(bundle.get("bootUiVersion").asString("unknown"))
                    .append("; instance/run `")
                    .append(bundle.get("instanceId").asString("unknown"))
                    .append("/")
                    .append(bundle.get("runId").asString("unknown"))
                    .append("`.\n\n")
                    .append("| Advisor | Required | Status | Findings | Rules skipped |\n|---|---:|---|---:|---:|\n");
            for (JsonValue advisor : bundle.get("advisors").values()) {
                output.append("| ")
                        .append(advisor.get("name").asString(""))
                        .append(" | ")
                        .append(advisor.get("required").asBoolean(false))
                        .append(" | ")
                        .append(advisor.get("status").asString(""))
                        .append(" | ")
                        .append(advisor.get("findingCount").asLong(0))
                        .append(" | ")
                        .append(advisor.get("rulesSkipped").asLong(0))
                        .append(" |\n");
            }
            JsonValue context = bundle.get("runtimeContext");
            String contextStatus = context.get("status").asString("ERROR");
            if (policy != null && !"NOT_REQUESTED".equals(contextStatus)) {
                output.append("\n### Runtime Insights context\n\n")
                        .append("Status: `")
                        .append(contextStatus)
                        .append("`. ");
                if ("CAPTURED".equals(contextStatus) || "PARTIAL".equals(contextStatus)) {
                    output.append("Workload attribution: `UNVERIFIED`. This bounded projection is incomplete and ")
                            .append("does not prove that the CI workload exercised this process. Runtime observations ")
                            .append("do not affect the advisor verdict.\n\n")
                            .append("HTTP exchanges read: ")
                            .append(context.get("httpExchangesRead").asLong(0))
                            .append("; observations listed: ")
                            .append(context.get("observations").size())
                            .append("; observations omitted: ")
                            .append(context.get("omittedObservations").asLong(0))
                            .append("; not-exercised routes counted: ")
                            .append(context.get("notExercisedRouteCount").asLong(0))
                            .append(".\n\n");
                    JsonValue coverage = context.get("coverage");
                    output.append("Retained events: ")
                            .append(coverage.get("retainedEvents").asLong(0))
                            .append(" across ")
                            .append(coverage.get("sources").asLong(0))
                            .append(" sources (request-linked: ")
                            .append(coverage.get("byRequestId").asLong(0))
                            .append(", execution-linked: ")
                            .append(coverage.get("byExecutionId").asLong(0))
                            .append(", trace-linked: ")
                            .append(coverage.get("byTraceId").asLong(0))
                            .append(", unlinked: ")
                            .append(coverage.get("unlinked").asLong(0))
                            .append("); dropped events: ")
                            .append(coverage.get("droppedEvents").asLong(0))
                            .append("; checks not run: ")
                            .append(context.get("checksNotRunCount").asLong(0))
                            .append("; limitations: ")
                            .append(context.get("limitationsCount").asLong(0))
                            .append(".\n");
                    if (context.get("lowFootprintActive").asBoolean(false)) {
                        output.append("Low-footprint mode was active; capture paused: ")
                                .append(context.get("capturePaused").asBoolean(false))
                                .append(".\n");
                    }
                    if (context.get("observations").size() > 0) {
                        output.append("\n| Kind | Status | Eligible | Affected |\n|---|---|---:|---:|\n");
                        for (JsonValue observation : context.get("observations").values()) {
                            output.append("| ")
                                    .append(observation.get("kind").asString(""))
                                    .append(" | ")
                                    .append(observation.get("status").asString(""))
                                    .append(" | ")
                                    .append(observation.get("eligible").asLong(0))
                                    .append(" | ")
                                    .append(observation.get("affected").asLong(0))
                                    .append(" |\n");
                        }
                    }
                } else {
                    output.append("No Runtime Insights data was captured; this is not evidence that the application ")
                            .append("was idle or healthy. Runtime observations do not affect the advisor verdict.\n");
                }
            }
            if (!errors.isEmpty()) {
                output.append("\nAssessment errors: ")
                        .append(errors.size())
                        .append(" (see the sanitized bundle for error codes).\n");
            }
            String text = output.toString();
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SUMMARY_BYTES) {
                throw new IllegalStateException("Summary exceeded its byte limit");
            }
            return text;
        }

        private String junit(JsonValue bundle) {
            String status = bundle.get("status").asString("NOT_ASSESSED");
            long failures = results.stream().filter(result -> result.violation).count();
            int errorsCount = "NOT_ASSESSED".equals(status) ? Math.max(1, errors.size()) : 0;
            int skipped = (int) bundle.get("advisors").values().stream()
                    .filter(advisor -> "SKIPPED".equals(advisor.get("status").asString(null)))
                    .count();
            StringBuilder xml = new StringBuilder();
            int tests = results.size() + errorsCount;
            xml.append("<testsuite name=\"bootui-ci\" tests=\"")
                    .append(tests)
                    .append("\" failures=\"")
                    .append(failures)
                    .append("\" errors=\"")
                    .append(errorsCount)
                    .append("\" skipped=\"")
                    .append(skipped)
                    .append("\">\n");
            for (AdvisorResult result : results) {
                xml.append("  <testcase classname=\"bootui.ci\" name=\"")
                        .append(result.advisor.policyName())
                        .append("\">");
                if (result.status.equals("SKIPPED")) {
                    xml.append("<skipped message=\"Optional advisor is unavailable or disabled\"/>");
                } else if (result.violation) {
                    xml.append("<failure message=\"Advisor met the configured severity threshold\"/>");
                }
                xml.append("</testcase>\n");
            }
            if (errorsCount > 0) {
                xml.append("  <testcase classname=\"bootui.ci\" name=\"assessment\">")
                        .append("<error message=\"BootUI assessment could not be completed\"/>")
                        .append("</testcase>\n");
            }
            xml.append("</testsuite>\n");
            return xml.toString();
        }
    }
}
