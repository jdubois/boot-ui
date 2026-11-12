package io.github.jdubois.bootui.cli.ci;

import io.github.jdubois.bootui.client.JsonValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The opt-in runtime gate: one Runtime Insights observation kind a policy names in
 * {@code runtimeContext.failOnObservations}, judged from a {@code get_runtime_insights} read that queried that kind.
 *
 * <p>A proven observation fails the gate. Anything short of an evaluated check without a row, such as a check that did
 * not run, rows below their evidence minimum, rows left out by the limit, or paused capture, leaves the kind not
 * assessed, never passed.</p>
 */
final class CiRuntimeGate {

    /** The Runtime Insights security observations a policy can gate on. */
    static final List<String> GATEABLE_KINDS = List.of("anonymous-success-on-restricted-route", "anonymous-data-reach");

    static final String PASS = "PASS";
    static final String VIOLATION = "VIOLATION";
    static final String NOT_ASSESSED = "NOT_ASSESSED";
    static final Set<String> RESULTS = Set.of(PASS, VIOLATION, NOT_ASSESSED);
    static final Set<String> REASONS = Set.of(
            "-", "UNAVAILABLE", "PARTIAL_CAPTURE", "CHECK_NOT_RUN", "INSUFFICIENT", "PARTIAL", "OMITTED", "ERROR");
    private static final Set<String> ROW_STATUSES = Set.of("OBSERVED", "PARTIAL", "INSUFFICIENT");

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
     * Judges {@code kind} from a Runtime Insights report queried for it.
     *
     * @throws IllegalArgumentException when the report does not have the expected shape
     */
    static Result evaluate(String kind, JsonValue report) {
        JsonValue available = report.get("available");
        if (!available.isBoolean()) {
            throw new IllegalArgumentException("Runtime Insights report has no availability");
        }
        if (!available.asBoolean(false)) {
            return Result.notAssessed(kind, "UNAVAILABLE");
        }
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
