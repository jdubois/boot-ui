package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.JsonValue;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The opt-in runtime gate: one security observation kind a policy names in {@code runtimeContext.failOnObservations},
 * judged from one bounded read: {@code get_runtime_insights} queried for a Runtime Insights kind, or
 * {@code get_side_effects} queried for the {@code security-sinks} sensor for {@code request-input-in-sink}.
 *
 * <p>A proven observation fails the gate. Anything short of positive evidence that the check ran completely and found
 * nothing, such as a check that did not run, rows below their evidence minimum, rows left out by the limit, paused or
 * lossy capture, request-value matching that is off, or matches still awaiting confirmation, leaves the kind not
 * assessed, never passed.</p>
 */
final class CiRuntimeGate {

    /** The Runtime Insights security observations a policy can gate on; they need {@code includeInsights}. */
    static final List<String> RUNTIME_INSIGHTS_KINDS =
            List.of("anonymous-success-on-restricted-route", "anonymous-data-reach");

    /** Request input reaching a sink, from the BootUI agent's {@code security-sinks} sensor. */
    static final String SINK_KIND = "request-input-in-sink";

    static final List<String> GATEABLE_KINDS =
            List.of(RUNTIME_INSIGHTS_KINDS.get(0), RUNTIME_INSIGHTS_KINDS.get(1), SINK_KIND);

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String NOT_ASSESSED = "NOT_ASSESSED";
    private static final Set<String> ROW_STATUSES = Set.of("OBSERVED", "PARTIAL", "INSUFFICIENT");
    private static final String SINKS_SENSOR = "security-sinks";
    /** The security-sinks row kinds that say request input reached a sink; the sensor's JDK checks are not. */
    private static final Set<String> SINK_ROW_KINDS = Set.of("SQL text", "command", "file path", "outbound URL");

    static String toolFor(String kind) {
        return SINK_KIND.equals(kind) ? "get_side_effects" : "get_runtime_insights";
    }

    static String panelFor(String kind) {
        return SINK_KIND.equals(kind) ? "side-effects" : "runtime-insights";
    }

    static String queryFor(String kind) {
        return SINK_KIND.equals(kind) ? SINKS_SENSOR : kind;
    }

    /** A gated kind's weakness classes and OWASP Top 10 (2021) category. */
    record Classification(List<String> cwe, String owasp) {}

    static Classification classification(String kind) {
        return switch (kind) {
            case SINK_KIND -> new Classification(List.of("CWE-74"), "A03:2021-Injection");
            case "anonymous-data-reach" ->
                new Classification(List.of("CWE-306", "CWE-862"), "A01:2021-Broken Access Control");
            default -> new Classification(List.of("CWE-862"), "A01:2021-Broken Access Control");
        };
    }

    private CiRuntimeGate() {}

    /** The most call sites a violation keeps. */
    static final int MAX_CALL_SITES = 5;

    /** A frame as the agent names it, {@code Class#method}: code identity, never a value. */
    static final Pattern CALL_SITE =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]{0,250}#[A-Za-z_$<][A-Za-z0-9_$<>]{0,120}");

    /**
     * One gated kind's outcome: {@code reason} is {@code -} unless the kind was not assessed, and {@code callSites} are
     * the first application frames a violation's rows name, when its source records them.
     */
    record Result(String kind, String result, String reason, long affected, List<String> callSites) {

        Result {
            callSites = List.copyOf(callSites);
        }

        Result(String kind, String result, String reason, long affected) {
            this(kind, result, reason, affected, List.of());
        }

        static Result notAssessed(String kind, String reason) {
            return new Result(kind, NOT_ASSESSED, reason, 0);
        }

        JsonValue toJson() {
            Map<String, JsonValue> row = new LinkedHashMap<>();
            row.put("kind", JsonValue.of(kind));
            row.put("result", JsonValue.of(result));
            row.put("reason", JsonValue.of(reason));
            row.put("affected", JsonValue.of(affected));
            row.put(
                    "callSites",
                    JsonValue.array(callSites.stream().map(JsonValue::of).toList()));
            return JsonValue.object(row);
        }
    }

    /**
     * Judges {@code kind} from the report {@link #toolFor} returned for {@link #queryFor}.
     *
     * @throws IllegalArgumentException when the report does not have the expected shape
     */
    static Result evaluate(String kind, JsonValue report) {
        JsonValue available = report.get("available");
        if (!available.isBoolean()) {
            throw new IllegalArgumentException("Report has no availability");
        }
        if (!available.asBoolean(false)) {
            return Result.notAssessed(kind, "UNAVAILABLE");
        }
        if (!queryFor(kind).equals(report.get("query").asString(null))) {
            throw new IllegalArgumentException("Report does not match the requested query");
        }
        return SINK_KIND.equals(kind) ? evaluateSinks(report) : evaluateInsights(kind, report);
    }

    static Result start(String kind, JsonValue report) {
        Result result = evaluate(kind, report);
        if ("UNAVAILABLE".equals(result.reason())) {
            return result;
        }
        if (SINK_KIND.equals(kind)) {
            if ("NO_SINK_CHECKS".equals(result.reason()) && count(report.get("requestValuesHeld")) > 0) {
                return Result.notAssessed(kind, "PREEXISTING_EVIDENCE");
            }
            if ("NO_REQUEST_VALUES".equals(result.reason())
                    && count(report.get("requestValueChecks")) == 0
                    && count(report.get("unconfirmedSinkMatches")) == 0
                    && count(report.get("omitted")) == 0) {
                return new Result(kind, PASS, "-", 0);
            }
        } else if (count(report.get("requests")) == 0
                && count(report.get("omitted")) == 0
                && report.get("observations").isArray()
                && report.get("observations").values().stream()
                        .allMatch(row -> "INSUFFICIENT".equals(row.get("status").asString(null))
                                && count(row.get("eligible")) == 0
                                && count(row.get("affected")) == 0)
                && Set.of("NO_REQUESTS", "CHECK_NOT_RUN", "CHECK_NOT_EVALUATED", "INSUFFICIENT")
                        .contains(result.reason())) {
            return new Result(kind, PASS, "-", 0);
        }
        return PASS.equals(result.result()) || VIOLATION.equals(result.result())
                ? Result.notAssessed(kind, "PREEXISTING_EVIDENCE")
                : result;
    }

    static boolean validStart(JsonValue start, List<String> kinds) {
        if (!start.isArray() || start.size() != kinds.size()) {
            return false;
        }
        Set<String> reasons = Set.of(
                "UNAVAILABLE",
                "ERROR",
                "UNSUPPORTED",
                "SENSOR_NOT_RECORDING",
                "REQUEST_VALUES_OFF",
                "PARTIAL_CAPTURE",
                "UNCONFIRMED",
                "OMITTED",
                "PREEXISTING_EVIDENCE",
                "CHECK_NOT_RUN",
                "CHECK_NOT_EVALUATED",
                "NO_REQUESTS",
                "NO_REQUEST_VALUES",
                "NO_SINK_CHECKS",
                "INSUFFICIENT",
                "PARTIAL");
        for (int i = 0; i < kinds.size(); i++) {
            JsonValue row = start.get(i);
            String result = row.get("result").asString("");
            String reason = row.get("reason").asString("");
            if (!row.isObject()
                    || !Set.copyOf(row.names()).equals(Set.of("kind", "result", "reason", "affected", "callSites"))
                    || !kinds.get(i).equals(row.get("kind").asString(null))
                    || !"0".equals(row.get("affected").toJson())
                    || !row.get("callSites").isArray()
                    || row.get("callSites").size() != 0
                    || !(PASS.equals(result) && "-".equals(reason)
                            || NOT_ASSESSED.equals(result) && reasons.contains(reason))) {
                return false;
            }
        }
        return true;
    }

    private static Result evaluateSinks(JsonValue report) {
        JsonValue rows = report.get("rows");
        JsonValue sensors = report.get("sensors");
        if (!rows.isArray() || !sensors.isArray()) {
            throw new IllegalArgumentException("Side Effects report has no rows or sensors");
        }
        long affected = 0;
        boolean observed = false;
        Set<String> callSites = new LinkedHashSet<>();
        for (JsonValue row : rows.values()) {
            long count = count(row.get("count"));
            if (count <= 0) {
                throw new IllegalArgumentException("Side Effects row has no count");
            }
            if (SINKS_SENSOR.equals(row.get("sensor").asString(null))
                    && SINK_ROW_KINDS.contains(row.get("kind").asString(null))) {
                observed = true;
                affected = Math.addExact(affected, count);
                String callSite = row.get("callSite").asString("");
                if (callSites.size() < MAX_CALL_SITES
                        && CALL_SITE.matcher(callSite).matches()) {
                    callSites.add(callSite);
                }
            }
        }
        if (observed) {
            return new Result(SINK_KIND, VIOLATION, "-", affected, List.copyOf(callSites));
        }
        JsonValue sensor = JsonValue.MISSING;
        for (JsonValue candidate : sensors.values()) {
            if (SINKS_SENSOR.equals(candidate.get("id").asString(null))) {
                sensor = candidate;
            }
        }
        JsonValue matching = report.get("requestValueMatching");
        if (!matching.isBoolean()) {
            return Result.notAssessed(SINK_KIND, "UNSUPPORTED");
        }
        if (!"recording".equals(sensor.get("state").asString(null))) {
            return Result.notAssessed(SINK_KIND, "SENSOR_NOT_RECORDING");
        }
        if (!matching.asBoolean(false)) {
            return Result.notAssessed(SINK_KIND, "REQUEST_VALUES_OFF");
        }
        if (count(sensor.get("dropped")) != 0 || count(report.get("requestValueChecksSkipped")) != 0) {
            return Result.notAssessed(SINK_KIND, "PARTIAL_CAPTURE");
        }
        if (count(report.get("requestValuesHeld")) == 0) {
            return Result.notAssessed(SINK_KIND, "NO_REQUEST_VALUES");
        }
        if (count(report.get("requestValueChecks")) == 0) {
            return Result.notAssessed(SINK_KIND, "NO_SINK_CHECKS");
        }
        if (count(report.get("unconfirmedSinkMatches")) != 0) {
            return Result.notAssessed(SINK_KIND, "UNCONFIRMED");
        }
        if (count(report.get("omitted")) != 0) {
            return Result.notAssessed(SINK_KIND, "OMITTED");
        }
        return new Result(SINK_KIND, PASS, "-", 0);
    }

    private static Result evaluateInsights(String kind, JsonValue report) {
        JsonValue observations = report.get("observations");
        JsonValue checksNotRun = report.get("checksNotRun");
        if (!observations.isArray() || !checksNotRun.isArray()) {
            throw new IllegalArgumentException("Runtime Insights report has no observations or checks");
        }
        long affected = 0;
        boolean observed = false;
        String incomplete = null;
        for (JsonValue observation : observations.values()) {
            String status = observation.get("status").asString(null);
            if (status == null || !ROW_STATUSES.contains(status)) {
                throw new IllegalArgumentException("Runtime Insights observation has an unknown status or count");
            }
            long count = count(observation.get("affected"));
            if (!kind.equals(observation.get("kind").asString(null))) {
                continue;
            }
            if ("OBSERVED".equals(status)) {
                if (count == 0) {
                    throw new IllegalArgumentException("An observed row has no affected requests");
                }
                observed = true;
                affected = Math.addExact(affected, count);
            } else if (incomplete == null) {
                incomplete = status;
            }
        }
        if (observed) {
            return new Result(kind, VIOLATION, "-", affected);
        }
        JsonValue completeWindow = report.get("completeWindow");
        if (!completeWindow.isBoolean()) {
            return Result.notAssessed(kind, "UNSUPPORTED");
        }
        if (!completeWindow.asBoolean(false)) {
            return Result.notAssessed(kind, "PARTIAL_CAPTURE");
        }
        JsonValue lowFootprint = report.get("lowFootprint");
        if (!lowFootprint.isMissing()
                && (!lowFootprint.isObject()
                        || !lowFootprint.get("active").isBoolean()
                        || !lowFootprint.get("paused").isBoolean())) {
            throw new IllegalArgumentException("Runtime Insights capture state is malformed");
        }
        if (lowFootprint.get("active").asBoolean(false)
                || lowFootprint.get("paused").asBoolean(false)) {
            return Result.notAssessed(kind, "PARTIAL_CAPTURE");
        }
        JsonValue coverage = report.get("coverage");
        if (!coverage.isArray()) {
            throw new IllegalArgumentException("Runtime Insights report has no coverage");
        }
        for (JsonValue source : coverage.values()) {
            if (count(source.get("dropped")) != 0) {
                return Result.notAssessed(kind, "PARTIAL_CAPTURE");
            }
        }
        for (JsonValue check : checksNotRun.values()) {
            if (!check.isString()) {
                throw new IllegalArgumentException("Runtime Insights check is malformed");
            }
            if (check.asString("").startsWith(kind + ":")) {
                return Result.notAssessed(kind, "CHECK_NOT_RUN");
            }
        }
        if (incomplete != null) {
            return Result.notAssessed(kind, incomplete);
        }
        if (count(report.get("omitted")) > 0) {
            return Result.notAssessed(kind, "OMITTED");
        }
        if (count(report.get("requests")) == 0) {
            return Result.notAssessed(kind, "NO_REQUESTS");
        }
        JsonValue evaluated = report.get("evaluatedChecks");
        if (!evaluated.isArray() || evaluated.values().stream().anyMatch(value -> !value.isString())) {
            return Result.notAssessed(kind, "UNSUPPORTED");
        }
        if (evaluated.values().stream().noneMatch(value -> kind.equals(value.asString(null)))) {
            return Result.notAssessed(kind, "CHECK_NOT_EVALUATED");
        }
        return new Result(kind, PASS, "-", 0);
    }

    private static long count(JsonValue value) {
        long number = value.asLong(-1);
        if (!value.isNumber() || number < 0 || !Long.toString(number).equals(value.asDisplayText())) {
            throw new IllegalArgumentException("Runtime gate count is missing or not a nonnegative integer");
        }
        return number;
    }
}
