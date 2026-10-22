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
 * The CLI-side severity gate behind {@code --fail-on}.
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

    /** What the gate decided; {@code message} goes to stderr when it is not blank. */
    record Verdict(int exitCode, String message) {}

    /** Scan statuses whose counts are evidence. Any other status (NOT_SCANNED, DISABLED, ERROR, ...) is not. */
    private static final Set<String> EVALUATED = Set.of("SCANNED", "PARTIAL");

    private static final String NON_NEGATIVE_INTEGER = "0|[1-9][0-9]*";
    private static final String ZERO = "0";

    private FailOn() {}

    /** The accepted values as shown in usage errors: {@code critical, high, medium, low, info}. */
    static String accepted() {
        return Arrays.stream(Severity.values()).map(Severity::label).collect(Collectors.joining(", "));
    }

    /** Reads a {@code --fail-on} value, ignoring case. */
    static Severity parse(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (Severity severity : Severity.values()) {
            if (severity.name().equals(normalized)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a severity; use one of " + accepted() + ".");
    }

    /** Decides the exit code for a successful advisor answer. */
    static Verdict evaluate(JsonValue payload, Severity threshold) {
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
