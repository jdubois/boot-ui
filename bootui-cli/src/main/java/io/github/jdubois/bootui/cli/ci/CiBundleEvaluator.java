package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.JsonParseException;
import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Revalidates a saved CI assessment without contacting the application. */
public final class CiBundleEvaluator {

    private static final int MAX_BUNDLE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BUNDLE_BYTES_PLUS_ONE = MAX_BUNDLE_BYTES + 1;
    private static final Pattern ASSESSMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Pattern RUNTIME_KIND = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Set<String> REQUIRED_ROOT_KEYS = Set.of(
            "schemaVersion",
            "assessmentId",
            "status",
            "bootUiVersion",
            "instanceId",
            "runId",
            "policySha256",
            "advisors",
            "errors");
    private static final Set<String> ALLOWED_ROOT_KEYS = Set.of(
            "schemaVersion",
            "assessmentId",
            "status",
            "bootUiVersion",
            "instanceId",
            "runId",
            "policySha256",
            "advisors",
            "errors",
            "runtimeContext");
    private static final Set<String> RUNTIME_CONTEXT_KEYS = Set.of(
            "status",
            "workloadAttribution",
            "lowFootprintActive",
            "capturePaused",
            "httpExchangesRead",
            "coverage",
            "observations",
            "omittedObservations",
            "checksNotRunCount",
            "notExercisedRouteCount",
            "limitationsCount");
    private static final Set<String> RUNTIME_COVERAGE_KEYS = Set.of(
            "sources", "retainedEvents", "byRequestId", "byExecutionId", "byTraceId", "unlinked", "droppedEvents");
    private static final Set<String> RUNTIME_OBSERVATION_KEYS = Set.of("kind", "status", "eligible", "affected");
    private static final Set<String> ADVISOR_KEYS = Set.of(
            "name",
            "required",
            "status",
            "reason",
            "findingCount",
            "rulesSkipped",
            "rulesEvaluated",
            "subjectsAnalyzed",
            "usable",
            "coverageComplete",
            "limitations",
            "findings");
    private static final Set<String> FINDING_KEYS = Set.of("ruleId", "severity", "count");
    private static final Set<String> ERROR_KEYS = Set.of("code");
    private static final Set<String> OPTIONAL_SKIP_REASONS = Set.of("UNAVAILABLE", "PANEL_DISABLED", "PANEL_READ_ONLY");

    private CiBundleEvaluator() {}

    public static Result evaluate(Path bundlePath, String expectedAssessmentId, Path policyPath) throws IOException {
        JsonValue bundle;
        try {
            bundle = readBundle(bundlePath);
            return evaluate(bundle, expectedAssessmentId, CiPolicy.read(policyPath));
        } catch (JsonParseException | IllegalArgumentException | ArithmeticException invalid) {
            return new Result("NOT_ASSESSED", 1);
        }
    }

    private static Result evaluate(JsonValue bundle, String expectedAssessmentId, CiPolicy policy) {
        requireObject(bundle);
        requireKeys(bundle, REQUIRED_ROOT_KEYS, ALLOWED_ROOT_KEYS);
        if (!bundle.get("schemaVersion").isNumber()
                || !"1".equals(bundle.get("schemaVersion").asDisplayText())
                || !isAssessmentId(expectedAssessmentId)
                || !expectedAssessmentId.equals(bundle.get("assessmentId").asString(null))) {
            throw invalid();
        }
        String reportedStatus = bundle.get("status").asString(null);
        if (reportedStatus == null
                || !Set.of("PASS", "VIOLATION", "NOT_ASSESSED").contains(reportedStatus)) {
            throw invalid();
        }
        JsonValue errors = bundle.get("errors");
        validateErrors(errors);
        if (errors.size() > 0) {
            if (!"NOT_ASSESSED".equals(reportedStatus)) {
                throw invalid();
            }
            return new Result("NOT_ASSESSED", 1);
        }
        if ("NOT_ASSESSED".equals(reportedStatus)
                || !policy.sha256().equals(bundle.get("policySha256").asString(null))
                || !isHexDigest(bundle.get("policySha256").asString(null))
                || !isSafeVersion(bundle.get("bootUiVersion").asString(null))
                || !isRunId(bundle.get("instanceId").asString(null))
                || !isRunId(bundle.get("runId").asString(null))) {
            throw invalid();
        }
        validateRuntimeContext(bundle.get("runtimeContext"), policy);

        JsonValue advisors = bundle.get("advisors");
        if (!advisors.isArray() || advisors.size() != policy.advisors().size()) {
            throw invalid();
        }
        Set<CiPolicy.Advisor> seen = new HashSet<>();
        boolean violation = false;
        long totalFindingCount = 0;
        for (JsonValue advisor : advisors.values()) {
            CiPolicy.Advisor name = validateAdvisor(advisor, policy);
            if (!seen.add(name)) {
                throw invalid();
            }
            CiPolicy.Selection selection = policy.advisors().get(name);
            String status = advisor.get("status").asString(null);
            if ("SKIPPED".equals(status)) {
                validateSkipped(advisor, selection);
            } else {
                violation |= validateAssessed(advisor, name, selection);
            }
            totalFindingCount = Math.addExact(totalFindingCount, integer(advisor.get("findingCount")));
            if (totalFindingCount > CiAssessmentRunner.MAX_TOTAL_DETAILS) {
                throw invalid();
            }
        }
        if (seen.size() != policy.advisors().size()) {
            throw invalid();
        }
        String status = violation ? "VIOLATION" : "PASS";
        if (!status.equals(reportedStatus)) {
            throw invalid();
        }
        return new Result(status, violation ? 3 : 0);
    }

    private static CiPolicy.Advisor validateAdvisor(JsonValue advisor, CiPolicy policy) {
        requireObject(advisor);
        requireKeys(advisor, ADVISOR_KEYS);
        String policyName = advisor.get("name").asString(null);
        CiPolicy.Advisor name = null;
        for (CiPolicy.Advisor candidate : policy.orderedAdvisors()) {
            if (candidate.policyName().equals(policyName)) {
                name = candidate;
                break;
            }
        }
        if (name == null) {
            throw invalid();
        }
        CiPolicy.Selection selection = policy.advisors().get(name);
        if (!advisor.get("required").isBoolean()
                || advisor.get("required").asBoolean(false) != selection.required()
                || !advisor.get("findingCount").isNumber()
                || !advisor.get("rulesSkipped").isNumber()
                || !advisor.get("rulesEvaluated").isNumber()
                || !advisor.get("subjectsAnalyzed").isNumber()
                || !advisor.get("usable").isBoolean()
                || !advisor.get("coverageComplete").isBoolean()
                || !advisor.get("limitations").isArray()
                || !advisor.get("findings").isArray()) {
            throw invalid();
        }
        return name;
    }

    private static void validateSkipped(JsonValue advisor, CiPolicy.Selection selection) {
        String reason = advisor.get("reason").asString(null);
        if (selection.required()
                || reason == null
                || !OPTIONAL_SKIP_REASONS.contains(reason)
                || integer(advisor.get("findingCount")) != 0
                || integer(advisor.get("rulesSkipped")) != 0
                || integer(advisor.get("rulesEvaluated")) != 0
                || integer(advisor.get("subjectsAnalyzed")) != 0
                || advisor.get("usable").asBoolean(true)
                || advisor.get("coverageComplete").asBoolean(true)
                || advisor.get("limitations").size() != 0
                || advisor.get("findings").size() != 0) {
            throw invalid();
        }
    }

    private static boolean validateAssessed(JsonValue advisor, CiPolicy.Advisor name, CiPolicy.Selection selection) {
        if (!"ASSESSED".equals(advisor.get("status").asString(null))
                || !advisor.get("reason").isNull()
                || !advisor.get("usable").asBoolean(false)
                || !advisor.get("coverageComplete").asBoolean(false)
                || advisor.get("limitations").size() != 0) {
            throw invalid();
        }
        long findingCount = integer(advisor.get("findingCount"));
        long rulesSkipped = integer(advisor.get("rulesSkipped"));
        long rulesEvaluated = integer(advisor.get("rulesEvaluated"));
        long subjectsAnalyzed = integer(advisor.get("subjectsAnalyzed"));
        long activeRules = rulesEvaluated - rulesSkipped;
        if (findingCount < 0
                || rulesEvaluated <= 0
                || subjectsAnalyzed <= 0
                || name != CiPolicy.Advisor.DATABASE && rulesSkipped != 0
                || name == CiPolicy.Advisor.DATABASE && (rulesSkipped < 0 || rulesSkipped >= rulesEvaluated)
                || advisor.get("findings").size() > activeRules) {
            throw invalid();
        }
        Set<String> seenRules = new HashSet<>();
        boolean violation = false;
        long observedFindingCount = 0;
        for (JsonValue finding : advisor.get("findings").values()) {
            requireObject(finding);
            requireKeys(finding, FINDING_KEYS);
            String ruleId = finding.get("ruleId").asString(null);
            String severityText = finding.get("severity").asString(null);
            long count = integer(finding.get("count"));
            if (ruleId == null
                    || !RULE_ID.matcher(ruleId).matches()
                    || !seenRules.add(ruleId)
                    || count <= 0
                    || severityText == null) {
                throw invalid();
            }
            CiPolicy.Severity severity = parseSeverity(severityText);
            observedFindingCount = Math.addExact(observedFindingCount, count);
            violation |= severity.meets(selection.failOn());
        }
        if (observedFindingCount != findingCount) {
            throw invalid();
        }
        return violation;
    }

    private static void validateRuntimeContext(JsonValue context, CiPolicy policy) {
        if (context.isMissing()) {
            if (policy.includeInsights()) {
                throw invalid();
            }
            return;
        }
        requireObject(context);
        requireKeys(context, RUNTIME_CONTEXT_KEYS);
        String status = context.get("status").asString(null);
        if (status == null
                || !"UNVERIFIED".equals(context.get("workloadAttribution").asString(null))
                || !context.get("lowFootprintActive").isBoolean()
                || !context.get("capturePaused").isBoolean()
                || !policy.includeInsights() && !"NOT_REQUESTED".equals(status)
                || policy.includeInsights()
                        && !Set.of("CAPTURED", "PARTIAL", "UNAVAILABLE", "ERROR")
                                .contains(status)) {
            throw invalid();
        }
        boolean lowFootprintActive = context.get("lowFootprintActive").asBoolean(false);
        boolean capturePaused = context.get("capturePaused").asBoolean(false);
        if (capturePaused && !lowFootprintActive || "PARTIAL".equals(status) != lowFootprintActive) {
            throw invalid();
        }

        JsonValue coverage = context.get("coverage");
        requireObject(coverage);
        requireKeys(coverage, RUNTIME_COVERAGE_KEYS);
        long sources = nonnegative(integer(coverage.get("sources")));
        long retainedEvents = nonnegative(integer(coverage.get("retainedEvents")));
        long byRequestId = nonnegative(integer(coverage.get("byRequestId")));
        long byExecutionId = nonnegative(integer(coverage.get("byExecutionId")));
        long byTraceId = nonnegative(integer(coverage.get("byTraceId")));
        long unlinked = nonnegative(integer(coverage.get("unlinked")));
        long droppedEvents = nonnegative(integer(coverage.get("droppedEvents")));
        if (sources > 64
                || sources == 0 && (retainedEvents != 0 || droppedEvents != 0)
                || retainedEvents
                        != Math.addExact(
                                Math.addExact(byRequestId, byExecutionId), Math.addExact(byTraceId, unlinked))) {
            throw invalid();
        }

        long httpExchangesRead = nonnegative(integer(context.get("httpExchangesRead")));
        long omittedObservations = nonnegative(integer(context.get("omittedObservations")));
        long checksNotRunCount = nonnegative(integer(context.get("checksNotRunCount")));
        long notExercisedRouteCount = nonnegative(integer(context.get("notExercisedRouteCount")));
        long limitationsCount = nonnegative(integer(context.get("limitationsCount")));
        JsonValue observations = context.get("observations");
        if (!observations.isArray() || observations.size() > CiAssessmentRunner.MAX_RUNTIME_OBSERVATIONS) {
            throw invalid();
        }
        for (JsonValue observation : observations.values()) {
            requireObject(observation);
            requireKeys(observation, RUNTIME_OBSERVATION_KEYS);
            String kind = observation.get("kind").asString(null);
            String observationStatus = observation.get("status").asString(null);
            if (kind == null
                    || !RUNTIME_KIND.matcher(kind).matches()
                    || observationStatus == null
                    || !Set.of("OBSERVED", "PARTIAL", "INSUFFICIENT").contains(observationStatus)) {
                throw invalid();
            }
            nonnegative(integer(observation.get("eligible")));
            nonnegative(integer(observation.get("affected")));
        }

        if (!Set.of("CAPTURED", "PARTIAL").contains(status)
                && (lowFootprintActive
                        || capturePaused
                        || httpExchangesRead != 0
                        || sources != 0
                        || retainedEvents != 0
                        || byRequestId != 0
                        || byExecutionId != 0
                        || byTraceId != 0
                        || unlinked != 0
                        || droppedEvents != 0
                        || observations.size() != 0
                        || omittedObservations != 0
                        || checksNotRunCount != 0
                        || notExercisedRouteCount != 0
                        || limitationsCount != 0)) {
            throw invalid();
        }
    }

    private static long nonnegative(long value) {
        if (value < 0) {
            throw invalid();
        }
        return value;
    }

    private static void validateErrors(JsonValue errors) {
        if (!errors.isArray() || errors.size() > 64) {
            throw invalid();
        }
        for (JsonValue error : errors.values()) {
            requireObject(error);
            requireKeys(error, ERROR_KEYS);
            String code = error.get("code").asString(null);
            if (code == null || !ERROR_CODE.matcher(code).matches()) {
                throw invalid();
            }
        }
    }

    private static JsonValue readBundle(Path path) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BUNDLE_BYTES_PLUS_ONE);
        }
        if (bytes.length == 0 || bytes.length > MAX_BUNDLE_BYTES) {
            throw invalid();
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
            throw invalid();
        }
        return JsonValue.parseStrict(text);
    }

    private static long integer(JsonValue value) {
        if (!value.isNumber() || !INTEGER.matcher(value.asDisplayText()).matches()) {
            throw invalid();
        }
        try {
            return Long.parseLong(value.asDisplayText());
        } catch (NumberFormatException overflow) {
            throw invalid();
        }
    }

    private static CiPolicy.Severity parseSeverity(String value) {
        try {
            return CiPolicy.Severity.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    private static void requireObject(JsonValue value) {
        if (!value.isObject()) {
            throw invalid();
        }
    }

    private static void requireKeys(JsonValue value, Set<String> keys) {
        if (!value.names().containsAll(keys) || !keys.containsAll(value.names())) {
            throw invalid();
        }
    }

    private static void requireKeys(JsonValue value, Set<String> required, Set<String> allowed) {
        if (!value.names().containsAll(required) || !allowed.containsAll(value.names())) {
            throw invalid();
        }
    }

    private static boolean isAssessmentId(String value) {
        return value != null && ASSESSMENT_ID.matcher(value).matches();
    }

    private static boolean isSafeVersion(String value) {
        return value != null && VERSION.matcher(value).matches();
    }

    private static boolean isRunId(String value) {
        return value != null && value.matches("[0-9a-f]{8}");
    }

    private static boolean isHexDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid CI assessment bundle");
    }

    public record Result(String status, int exitCode) {}
}
