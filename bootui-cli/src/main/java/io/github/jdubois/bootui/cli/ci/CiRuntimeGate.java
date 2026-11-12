package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.JsonValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private CiRuntimeGate() {}

    /** One gated kind's outcome: {@code reason} is {@code -} unless the kind was not assessed. */
    record Result(String kind, String result, String reason, long affected) {

        static Result notAssessed(String kind, String reason) {
            return new Result(kind, NOT_ASSESSED, reason, 0);
        }

        JsonValue toJson() {
            Map<String, JsonValue> row = new LinkedHashMap<>();
            row.put("kind", JsonValue.of(kind));
            row.put("result", JsonValue.of(result));
            row.put("reason", JsonValue.of(reason));
            row.put("affected", JsonValue.of(affected));
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
        return SINK_KIND.equals(kind) ? evaluateSinks(report) : evaluateInsights(kind, report);
    }

    private static Result evaluateSinks(JsonValue report) {
        JsonValue rows = report.get("rows");
        JsonValue sensors = report.get("sensors");
        if (!rows.isArray() || !sensors.isArray()) {
            throw new IllegalArgumentException("Side Effects report has no rows or sensors");
        }
        long affected = 0;
        boolean observed = false;
        for (JsonValue row : rows.values()) {
            long count = row.get("count").asLong(-1);
            if (count < 0) {
                throw new IllegalArgumentException("Side Effects row has no count");
            }
            if (SINKS_SENSOR.equals(row.get("sensor").asString(null))
                    && SINK_ROW_KINDS.contains(row.get("kind").asString(null))) {
                observed = true;
                affected = Math.addExact(affected, count);
            }
        }
        if (observed) {
            return new Result(SINK_KIND, VIOLATION, "-", affected);
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
        if (sensor.get("dropped").asLong(1) != 0) {
            return Result.notAssessed(SINK_KIND, "PARTIAL_CAPTURE");
        }
        if (report.get("unconfirmedSinkMatches").asLong(1) != 0) {
            return Result.notAssessed(SINK_KIND, "UNCONFIRMED");
        }
        if (report.get("omitted").asLong(1) != 0) {
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
            if (!ROW_STATUSES.contains(status) || observation.get("affected").asLong(-1) < 0) {
                throw new IllegalArgumentException("Runtime Insights observation has an unknown status or count");
            }
            if (!kind.equals(observation.get("kind").asString(null))) {
                continue;
            }
            if ("OBSERVED".equals(status)) {
                observed = true;
                affected = Math.addExact(affected, observation.get("affected").asLong(0));
            } else if (incomplete == null) {
                incomplete = status;
            }
        }
        if (observed) {
            return new Result(kind, VIOLATION, "-", affected);
        }
        JsonValue lowFootprint = report.get("lowFootprint");
        if (lowFootprint.get("active").asBoolean(false)
                || lowFootprint.get("paused").asBoolean(false)) {
            return Result.notAssessed(kind, "PARTIAL_CAPTURE");
        }
        for (JsonValue check : checksNotRun.values()) {
            if (check.asString("").startsWith(kind + ":")) {
                return Result.notAssessed(kind, "CHECK_NOT_RUN");
            }
        }
        if (incomplete != null) {
            return Result.notAssessed(kind, incomplete);
        }
        if (report.get("omitted").asLong(0) > 0) {
            return Result.notAssessed(kind, "OMITTED");
        }
        return new Result(kind, PASS, "-", 0);
    }
}
