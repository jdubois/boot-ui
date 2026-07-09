package io.github.jdubois.bootui.engine.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Shared severity-ranking and severity-tally helpers reused by every advisor scanner (Architecture,
 * Hibernate, Memory, REST API, Pentesting, CRaC, GraalVM, the Quarkus app advisor, and the Quarkus
 * Security advisor). Every scanner sorts and groups its findings by the same fixed, most-severe-first
 * vocabulary; this class is the single source of truth for that ordering so each scanner only supplies
 * its own DTO's severity/countable accessors and severity-count DTO constructor.
 *
 * <p>{@link io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports} intentionally keeps its
 * own severity vocabulary (it also ranks {@code UNKNOWN}/{@code NONE}), but can still reuse {@link
 * #rank(List, String)} with its own order.
 */
public final class SeverityOrder {

    /** The severity vocabulary shared by every advisor scanner, most severe first. */
    public static final List<String> DEFAULT = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

    private SeverityOrder() {}

    /**
     * Ranks {@code severity} within {@code order} (lower is more severe); a value not found in
     * {@code order} sorts after every known severity.
     */
    public static int rank(List<String> order, String severity) {
        int index = order.indexOf(severity);
        return index >= 0 ? index : order.size();
    }

    /** {@link #rank(List, String)} using the {@link #DEFAULT} vocabulary. */
    public static int rank(String severity) {
        return rank(DEFAULT, severity);
    }

    /**
     * Builds a zero-initialized, insertion-ordered tally of {@code results} by severity: every severity in
     * {@code order} is present (even with a zero count), and each element of {@code results} for which
     * {@code countable} holds increments its {@code severityOf} bucket. An element whose severity isn't in
     * {@code order} is silently skipped, matching every scanner's prior behavior.
     */
    public static <T> Map<String, Integer> counts(
            List<String> order, List<T> results, Predicate<T> countable, Function<T, String> severityOf) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String severity : order) {
            counts.put(severity, 0);
        }
        for (T result : results) {
            if (countable.test(result)) {
                counts.computeIfPresent(severityOf.apply(result), (ignored, count) -> count + 1);
            }
        }
        return counts;
    }

    /** {@link #counts(List, List, Predicate, Function)} using the {@link #DEFAULT} vocabulary. */
    public static <T> Map<String, Integer> counts(
            List<T> results, Predicate<T> countable, Function<T, String> severityOf) {
        return counts(DEFAULT, results, countable, severityOf);
    }

    /** {@link #counts(List, List, Predicate, Function)} for a list that is already fully countable. */
    public static <T> Map<String, Integer> counts(List<String> order, List<T> results, Function<T, String> severityOf) {
        return counts(order, results, ignored -> true, severityOf);
    }

    /** {@link #counts(List, List, Function)} using the {@link #DEFAULT} vocabulary. */
    public static <T> Map<String, Integer> counts(List<T> results, Function<T, String> severityOf) {
        return counts(DEFAULT, results, severityOf);
    }
}
