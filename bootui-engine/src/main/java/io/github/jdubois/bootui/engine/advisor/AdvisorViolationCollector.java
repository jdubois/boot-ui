package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Scan-confined collection of counted findings with one shared retention budget.
 * Callers supply their advisor's bounded text formatting and redaction before anything is retained.
 * Each retained detail keeps its optional location, and a rule's samples are cut from the same sanitized
 * records, so a sample and its retained detail always carry the same text and location.
 */
public final class AdvisorViolationCollector {

    /** The scope of a rule that is concluded for every scope its findings carry. */
    public static final String ALL_SCOPES = "*";

    private static final int MAX_COVERAGE_NOTES = 10;

    private final int retentionLimit;
    private final Map<String, MutableRule> rules = new LinkedHashMap<>();
    private final List<String> locationNotes = new ArrayList<>();
    private final Set<String> concluded = new LinkedHashSet<>();
    private final Set<String> unverifiedScopes = new LinkedHashSet<>();
    private final List<String> coverageNotes = new ArrayList<>();
    private boolean scanUnverified;
    private int total;
    private int retained;

    public AdvisorViolationCollector(int retentionLimit) {
        if (retentionLimit <= 0) {
            throw new IllegalArgumentException("Advisor violation retention limit must be positive.");
        }
        this.retentionLimit = retentionLimit;
    }

    /**
     * Returns the unused scan budget without reserving entries.
     * A rule can stage at most this many details and record them only after successful evaluation.
     */
    public int remainingCapacity() {
        return retentionLimit - retained;
    }

    /**
     * Adds a rule's original count and ordered details, before summary sampling or dismissal.
     * Repeated calls for a rule accumulate (for example, across Hibernate persistence units).
     * Missing details still contribute to the count and are reported as incomplete retention.
     */
    public void record(String ruleId, int violationCount, List<String> details, UnaryOperator<String> sanitizer) {
        record(
                ruleId,
                violationCount,
                details == null ? null : AdvisorViolation.withoutLocations(details),
                sanitizer,
                0);
    }

    /**
     * Adds a rule's original count and ordered findings, and returns its first {@code sampleLimit} findings
     * sanitized exactly as they are retained. The retention budget counts findings, never bytes, and a
     * finding's location travels with its text.
     *
     * @return the sanitized samples, independent of the remaining retention budget
     */
    public List<AdvisorViolation> record(
            String ruleId,
            int violationCount,
            List<AdvisorViolation> details,
            UnaryOperator<String> sanitizer,
            int sampleLimit) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("Advisor rule ID must not be blank.");
        }
        if (violationCount < 0) {
            throw new IllegalArgumentException("Advisor violation count must not be negative.");
        }
        if (sampleLimit < 0) {
            throw new IllegalArgumentException("Advisor violation sample limit must not be negative.");
        }
        Objects.requireNonNull(sanitizer, "Advisor violation sanitizer is required.");
        if (violationCount == 0) {
            return List.of();
        }

        int updatedTotal = Math.addExact(total, violationCount);
        MutableRule existing = rules.get(ruleId);
        int updatedCount = Math.addExact(existing == null ? 0 : existing.count, violationCount);
        int available = Math.min(violationCount, remainingCapacity());
        int size = details == null ? 0 : details.size();
        int toRetain = Math.min(size, available);
        int toSample = Math.min(size, sampleLimit);
        int toRead = Math.max(toRetain, toSample);
        List<AdvisorViolation> sanitized = new ArrayList<>(toRead);
        if (toRead > 0) {
            // Never fetch beyond the budgeted head, so an unbounded tail is neither traversed nor sanitized.
            java.util.Iterator<AdvisorViolation> iterator = details.iterator();
            while (sanitized.size() < toRead && iterator.hasNext()) {
                sanitized.add(Objects.requireNonNull(iterator.next(), "Advisor violation is required.")
                        .sanitized(sanitizer));
            }
        }

        MutableRule rule = rules.computeIfAbsent(ruleId, ignored -> new MutableRule());
        rule.details.addAll(sanitized.subList(0, toRetain));
        rule.count = updatedCount;
        total = updatedTotal;
        retained += toRetain;
        return List.copyOf(sanitized.subList(0, toSample));
    }

    /** The retained findings of one rule so far, in recording order; empty for a rule that reported none. */
    public List<AdvisorViolation> retained(String ruleId) {
        MutableRule rule = rules.get(ruleId);
        return rule == null ? List.of() : List.copyOf(rule.details);
    }

    /** Visits every retained location, for example to collect the classes an explicit scan must resolve. */
    public void forEachLocation(Consumer<AdvisorViolationLocationDto> visitor) {
        for (MutableRule rule : rules.values()) {
            for (AdvisorViolation detail : rule.details) {
                if (detail.location() != null) visitor.accept(detail.location());
            }
        }
    }

    /**
     * Replaces every retained location through {@code mapper} without changing any text, count, or order.
     * Scanners use this once, before publishing, to attach source paths resolved during the explicit scan.
     */
    public void mapLocations(UnaryOperator<AdvisorViolationLocationDto> mapper) {
        Objects.requireNonNull(mapper, "Advisor violation location mapper is required.");
        for (MutableRule rule : rules.values()) {
            rule.details.replaceAll(detail ->
                    detail.location() == null ? detail : detail.withLocation(mapper.apply(detail.location())));
        }
    }

    /** Records why some locations of this scan carry no source path; published with the detail metadata. */
    public void addLocationNotes(List<String> notes) {
        if (notes != null) notes.stream().filter(Objects::nonNull).forEach(locationNotes::add);
    }

    /**
     * Declares that {@code ruleId} was evaluated conclusively for {@code scope}: it ran to the end without an error
     * or a gap in the evidence it needs, so a finding it no longer reports is genuinely gone. The scan comparison
     * fails closed, so a rule never declared concluded can neither resolve nor newly report a finding.
     *
     * @param scope the first identity part its findings carry (for example {@code unit=main}), or
     *     {@link #ALL_SCOPES} when the rule covers everything it can see
     */
    public void markConcluded(String ruleId, String scope) {
        concluded.add(concludedKey(ruleId, scope));
    }

    /**
     * Declares that nothing in {@code scope} can be compared in this scan, for example a datasource that could not
     * be read: its findings are neither resolved nor new.
     */
    public void markScopeUnverified(String scope, String reason) {
        unverifiedScopes.add(Objects.requireNonNull(scope, "Advisor scope is required."));
        addNote(reason);
    }

    /** Declares that this scan cannot be compared at all, for example because no rule could run. */
    public void markScanUnverified(String reason) {
        scanUnverified = true;
        addNote(reason);
    }

    private void addNote(String reason) {
        if (reason != null && !reason.isBlank() && coverageNotes.size() < MAX_COVERAGE_NOTES) {
            coverageNotes.add(reason);
        }
    }

    static String concludedKey(String ruleId, String scope) {
        return ruleId + '\u0000' + scope;
    }

    Snapshot snapshot() {
        Map<String, Rule> copy = new LinkedHashMap<>();
        rules.forEach((id, rule) -> copy.put(id, new Rule(rule.count, List.copyOf(rule.details))));
        return new Snapshot(
                total,
                retained,
                retentionLimit,
                Collections.unmodifiableMap(copy),
                List.copyOf(locationNotes),
                new Coverage(
                        Set.copyOf(concluded),
                        Set.copyOf(unverifiedScopes),
                        List.copyOf(coverageNotes),
                        scanUnverified));
    }

    record Rule(int violationCount, List<AdvisorViolation> violations) {

        List<String> details() {
            return AdvisorViolation.texts(violations);
        }
    }

    /** What this scan could and could not conclude, which decides what a comparison may claim. */
    record Coverage(Set<String> concluded, Set<String> unverifiedScopes, List<String> notes, boolean scanUnverified) {

        static final Coverage NONE = new Coverage(Set.of(), Set.of(), List.of(), false);

        boolean concludes(String ruleId, String scope) {
            if (scanUnverified || (scope != null && unverifiedScopes.contains(scope))) return false;
            return concluded.contains(concludedKey(ruleId, scope))
                    || concluded.contains(concludedKey(ruleId, ALL_SCOPES));
        }

        boolean informative() {
            return !scanUnverified && !concluded.isEmpty();
        }
    }

    record Snapshot(
            int total,
            int retained,
            int retentionLimit,
            Map<String, Rule> rules,
            List<String> locationNotes,
            Coverage coverage) {}

    private static final class MutableRule {
        private int count;
        private final List<AdvisorViolation> details = new ArrayList<>();
    }
}
