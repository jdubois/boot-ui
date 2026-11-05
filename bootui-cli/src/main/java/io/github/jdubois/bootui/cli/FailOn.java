package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The CLI-side finding-severity and evidence-sufficiency gates behind {@code --fail-on}.
 *
 * <p>The option is consumed here and never reaches the MCP arguments: the gate reads the advisor answer the
 * tool already returned. A gate that cannot be evaluated is an error, never a pass — a {@code NOT_SCANNED}
 * report carries zero counts too, and exiting {@code 0} on it would let an unscanned build through.
 *
 * <p>{@code PARTIAL} is evaluated, not refused: it means some evidence was unavailable, which is the normal
 * state of an advisor on a real application (the Hibernate advisor is always {@code PARTIAL}). The retained
 * findings still gate, and the stderr note says the absence of a finding is not proof of its absence.
 */
final class FailOn {

    /** The severities the gate understands, most severe first. Any other severity in an answer is ignored. */
    enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        INFO;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A severity threshold or an evidence-sufficiency gate. */
    enum Criterion {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        INFO,
        INSUFFICIENT;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the gate decided; {@code message} goes to stderr when it is not blank. */
    record Verdict(int exitCode, String message) {}

    /** Scan statuses whose counts are evidence. Any other status (NOT_SCANNED, DISABLED, ERROR, ...) is not. */
    private static final Set<String> EVALUATED = Set.of("SCANNED", "PARTIAL");

    private static final String NON_NEGATIVE_INTEGER = "0|[1-9][0-9]*";
    private static final String ZERO = "0";

    private FailOn() {}

    /** The accepted severity values as shown in usage errors. */
    static String accepted() {
        return Arrays.stream(Severity.values()).map(Severity::label).collect(Collectors.joining(", "));
    }

    /** Reads a {@code --fail-on} value allowed for the selected tool, ignoring case. */
    static Criterion parse(String toolName, String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("INSUFFICIENT".equals(normalized) && FailOnTools.supportsInsufficient(toolName)) {
            return Criterion.INSUFFICIENT;
        }
        if (FailOnTools.supportsSeverity(toolName)) {
            for (Severity severity : Severity.values()) {
                if (severity.name().equals(normalized)) {
                    return Criterion.valueOf(severity.name());
                }
            }
        }
        throw new IllegalArgumentException(
                "'" + value + "' is not accepted here; use one of " + FailOnTools.accepted(toolName) + ".");
    }

    /** Decides the exit code for a successful tool answer. */
    static Verdict evaluate(JsonValue payload, Criterion criterion, String toolName) {
        if (criterion == Criterion.INSUFFICIENT) {
            return evaluateInsufficient(payload, toolName);
        }
        return evaluateSeverity(payload, Severity.valueOf(criterion.name()));
    }

    private static Verdict evaluateSeverity(JsonValue payload, Severity threshold) {
        JsonValue counts = payload.get("severityCounts");
        // Every advisor lists every severity, even with nothing found, so an empty array is not a clean result.
        if (!counts.isArray() || counts.size() == 0) {
            return unevaluable("the answer has no severityCounts entries");
        }
        Map<String, String> met = new LinkedHashMap<>();
        Map<String, String> ignored = new LinkedHashMap<>();
        for (JsonValue entry : counts.values()) {
            JsonValue severity = entry.get("severity");
            JsonValue count = entry.get("count");
            if (!severity.isString()) {
                return unevaluable("a severityCounts entry has no string severity");
            }
            String name = severity.asString("");
            if (!count.isNumber() || !count.asDisplayText().matches(NON_NEGATIVE_INTEGER)) {
                return unevaluable("a count in severityCounts is not a non-negative integer");
            }
            String text = count.asDisplayText();
            if (ZERO.equals(text)) {
                continue;
            }
            if (!known(name)) {
                // The name is payload text going to a terminal, so only a plain word is echoed.
                ignored.merge(isWord(name) ? name : "?", text, (first, second) -> first + "+" + second);
            } else if (atOrAbove(name, threshold)) {
                met.merge(name.trim().toUpperCase(Locale.ROOT), text, (first, second) -> first + "+" + second);
            }
        }
        String status = payload.get("scan").get("status").asString(null);
        if (status == null) {
            return unevaluable("the answer has no scan status");
        }
        if (!EVALUATED.contains(status)) {
            return unevaluable("the scan status is " + (isWord(status) ? status : "not recognised")
                    + ", so its counts are not evidence");
        }
        JsonValue usable = payload.get("evidence").get("usable");
        if (met.isEmpty() && usable.isBoolean() && !usable.asBoolean(true)) {
            // Zero counts from a scan that assessed nothing, such as an architecture scan that found no classes.
            return unevaluable("the scan found no usable evidence, so zero findings is not an assessment");
        }
        List<String> notes = new ArrayList<>();
        if (!met.isEmpty()) {
            notes.add("--fail-on " + threshold.label() + ": found "
                    + met.entrySet().stream()
                            .map(found -> found.getKey() + "=" + found.getValue())
                            .collect(Collectors.joining(", "))
                    + ".");
        }
        if (!ignored.isEmpty()) {
            notes.add("note: --fail-on does not count "
                    + ignored.entrySet().stream()
                            .map(found -> found.getKey() + "=" + found.getValue())
                            .collect(Collectors.joining(", "))
                    + ".");
        }
        if ("PARTIAL".equals(status) && met.isEmpty()) {
            notes.add("note: the scan is PARTIAL, so having no finding at or above " + threshold.name()
                    + " is not proof there is none; see the evidence in the output.");
        }
        return new Verdict(met.isEmpty() ? ExitCodes.SUCCESS : ExitCodes.THRESHOLD, String.join("\n", notes));
    }

    private static Verdict evaluateInsufficient(JsonValue payload, String toolName) {
        if (!payload.isObject()
                || !payload.get("available").isBoolean()
                || !payload.get("available").asBoolean(false)) {
            return unevaluable("the answer is unavailable");
        }
        JsonValue insufficient;
        JsonValue partial;
        long listedInsufficient = 0;
        long listedPartial = 0;
        switch (toolName) {
            case "get_runtime_insights" -> {
                insufficient = payload.get("insufficient");
                partial = payload.get("partial");
                if (!payload.get("observations").isArray() || !nonNegativeInteger(payload.get("omitted"))) {
                    return unevaluable("the runtime-insights list is incomplete or misshapen");
                }
                for (JsonValue observation : payload.get("observations").values()) {
                    if (!observation.isObject() || !observation.get("status").isString()) {
                        return unevaluable("a listed observation has no status");
                    }
                    String status = observation.get("status").asString("");
                    if (!Set.of("OBSERVED", "INSUFFICIENT", "PARTIAL").contains(status)) {
                        return unevaluable("an observation status is not recognised");
                    }
                    listedInsufficient += "INSUFFICIENT".equals(status) ? 1 : 0;
                    listedPartial += "PARTIAL".equals(status) ? 1 : 0;
                }
            }
            case "get_runtime_insight" -> {
                JsonValue observation = payload.get("observation");
                if (!observation.isObject() || !observation.get("status").isString()) {
                    return unevaluable("the observation has no status");
                }
                String status = observation.get("status").asString("");
                return switch (status) {
                    case "OBSERVED", "FINDING" -> new Verdict(ExitCodes.SUCCESS, "");
                    case "INSUFFICIENT", "PARTIAL" ->
                        new Verdict(
                                ExitCodes.THRESHOLD, "--fail-on insufficient: observation status is " + status + ".");
                    case "UNAVAILABLE", "NOT_APPLICABLE" -> unevaluable("the observation status is " + status);
                    default -> unevaluable("the observation status is not recognised");
                };
            }
            case "get_exceptions" -> {
                JsonValue caught = payload.get("caughtInCode");
                if (!caught.isObject()
                        || !caught.get("available").isBoolean()
                        || !caught.get("available").asBoolean(false)) {
                    return unevaluable("caught-exception evidence is unavailable");
                }
                insufficient = caught.get("insufficient");
                partial = caught.get("partial");
            }
            default -> {
                return unevaluable("the selected tool does not report evidence sufficiency");
            }
        }
        if (!nonNegativeInteger(insufficient) || !nonNegativeInteger(partial)) {
            return unevaluable("the answer has no valid insufficient and partial counts");
        }
        long insufficientCount = insufficient.asLong(-1);
        long partialCount = partial.asLong(-1);
        if ("get_runtime_insights".equals(toolName)) {
            long omittedCount = payload.get("omitted").asLong(0);
            if (insufficientCount < listedInsufficient || partialCount < listedPartial) {
                return unevaluable("the runtime-insights aggregate counts do not match the listed results");
            }
            long hiddenInsufficient = insufficientCount - listedInsufficient;
            long hiddenPartial = partialCount - listedPartial;
            if (hiddenInsufficient > omittedCount || hiddenPartial > omittedCount - hiddenInsufficient) {
                return unevaluable("the runtime-insights aggregate counts do not match the listed results");
            }
        }
        if ("get_runtime_insights".equals(toolName)
                && insufficientCount == 0
                && partialCount == 0
                && payload.get("observations").size() == 0
                && payload.get("omitted").asLong(0) == 0) {
            JsonValue query = payload.get("query");
            JsonValue requests = payload.get("requests");
            if (!query.isString()
                    || !"all".equalsIgnoreCase(query.asString("").trim())
                    || !nonNegativeInteger(requests)
                    || requests.asLong(0) == 0) {
                return unevaluable("an empty result requires query=all and request activity");
            }
        }
        if (insufficientCount > 0 || partialCount > 0) {
            return new Verdict(
                    ExitCodes.THRESHOLD,
                    "--fail-on insufficient: found " + insufficientCount + " insufficient and " + partialCount
                            + " partial evidence rows.");
        }
        return new Verdict(ExitCodes.SUCCESS, "");
    }

    private static boolean nonNegativeInteger(JsonValue value) {
        return value.isNumber() && value.asDisplayText().matches(NON_NEGATIVE_INTEGER);
    }

    private static boolean isWord(String text) {
        return text.matches("[A-Za-z_]{1,32}");
    }

    private static boolean known(String severity) {
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(Severity.values()).anyMatch(known -> known.name().equals(normalized));
    }

    private static boolean atOrAbove(String severity, Severity threshold) {
        String normalized = severity.trim().toUpperCase(Locale.ROOT);
        for (Severity known : Severity.values()) {
            if (known.name().equals(normalized)) {
                return known.compareTo(threshold) <= 0;
            }
        }
        return false;
    }

    private static Verdict unevaluable(String reason) {
        return new Verdict(ExitCodes.ERROR, "cannot evaluate --fail-on: " + reason + ".");
    }
}
