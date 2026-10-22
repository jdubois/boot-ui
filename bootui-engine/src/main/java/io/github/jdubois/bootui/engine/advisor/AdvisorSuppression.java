package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorSuppressedFindingDto;
import io.github.jdubois.bootui.core.dto.AdvisorSuppressionDto;
import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the findings a developer dismissed one by one do to the report of one completed scan, computed at read time from
 * that scan's retained findings. Nothing here mutates the retained findings or the comparison with the previous scan, so
 * restoring a finding takes effect on the next read without a rescan.
 *
 * <p>A finding is matched by {@code (ruleId, identity)} against the findings the scan retained. A finding the scan
 * counted but could not retain, because the retention budget ran out, can neither be matched nor suppressed, so its
 * rule is reported as incomplete. A rule that is itself dismissed keeps its meaning and takes precedence.</p>
 */
public final class AdvisorSuppression {

    /** The finding is among the findings the scan retained. */
    public static final String MATCHED = "MATCHED";

    /** The scan concluded the rule for the finding's scope and no longer reports it. */
    public static final String ORPHANED = "ORPHANED";

    /** The scan cannot tell whether the finding still exists. */
    public static final String UNVERIFIED = "UNVERIFIED";

    /** The advisor has no rule with the finding's rule id. */
    public static final String UNKNOWN_RULE = "UNKNOWN_RULE";

    /** The most dismissed findings a report lists; the rest are counted. */
    public static final int MAX_LISTED = 100;

    private final Map<String, Rule> rules;
    private final AdvisorSuppressionDto dto;

    AdvisorSuppression(Map<String, Rule> rules, AdvisorSuppressionDto dto) {
        this.rules = rules;
        this.dto = dto;
    }

    /** A projection that suppresses nothing and lists the dismissed findings of this advisor as unverified. */
    static AdvisorSuppression unverified(List<DismissedFindingDto> belonging, Set<String> catalog) {
        List<AdvisorSuppressedFindingDto> findings = new ArrayList<>();
        for (DismissedFindingDto finding : belonging.subList(0, Math.min(belonging.size(), MAX_LISTED))) {
            boolean unknown = catalog != null && !catalog.contains(finding.ruleId());
            findings.add(new AdvisorSuppressedFindingDto(
                    finding.ruleId(), finding.identity(), unknown ? UNKNOWN_RULE : UNVERIFIED));
        }
        return new AdvisorSuppression(
                Map.of(),
                new AdvisorSuppressionDto(0, List.of(), findings, Math.max(0, belonging.size() - MAX_LISTED)));
    }

    /** The suppression of {@code ruleId}, or {@code null} when none of its retained findings is dismissed. */
    public Rule rule(String ruleId) {
        return rules.get(ruleId);
    }

    /** What the report carries: the total, the rules matched incompletely, and every dismissed finding's state. */
    public AdvisorSuppressionDto dto() {
        return dto;
    }

    /**
     * The retained findings of one rule that are dismissed.
     *
     * @param suppressedCount how many retained findings of the rule are dismissed
     */
    public static final class Rule {

        private final List<AdvisorViolation> retained;
        private final boolean[] suppressed;
        private final int suppressedCount;

        Rule(List<AdvisorViolation> retained, boolean[] suppressed, int suppressedCount) {
            this.retained = retained;
            this.suppressed = suppressed;
            this.suppressedCount = suppressedCount;
        }

        public int suppressedCount() {
            return suppressedCount;
        }

        /**
         * The active preview of the rule: the scan's own samples without the dismissed ones, topped up with the next
         * active retained findings so a rule keeps showing as many as the scan did.
         *
         * <p>A scan's sample {@code i} is its retained finding {@code i}; a sample beyond the retained findings, or
         * whose text differs from its retained finding, is never treated as dismissed, because only retained findings
         * can be matched. The preview of a rule that retained nothing is therefore kept as is.</p>
         *
         * @param samples the scan's sample texts for this rule
         * @param limit the most samples a rule shows
         */
        public Sample sample(List<String> samples, int limit) {
            List<Integer> kept = new ArrayList<>();
            for (int index = 0; index < samples.size(); index++) {
                boolean dismissed = index < retained.size()
                        && suppressed[index]
                        && retained.get(index).text().equals(samples.get(index));
                if (!dismissed) kept.add(index);
            }
            List<AdvisorViolation> added = new ArrayList<>();
            for (int index = samples.size(); index < retained.size() && kept.size() + added.size() < limit; index++) {
                if (!suppressed[index]) added.add(retained.get(index));
            }
            return new Sample(List.copyOf(kept), List.copyOf(added));
        }
    }

    /**
     * @param kept the indexes of the scan's samples that stay
     * @param added further active retained findings that join the preview after them
     */
    public record Sample(List<Integer> kept, List<AdvisorViolation> added) {}

    static AdvisorSuppression of(
            AdvisorViolationCollector.Snapshot index,
            Set<String> catalog,
            Set<String> dismissedRules,
            List<DismissedFindingDto> belonging) {
        Map<String, Set<String>> byRule = new HashMap<>();
        for (DismissedFindingDto finding : belonging) {
            byRule.computeIfAbsent(finding.ruleId(), id -> new HashSet<>()).add(finding.identity());
        }
        Map<String, Rule> rules = new HashMap<>();
        List<String> incomplete = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, AdvisorViolationCollector.Rule> entry :
                index.rules().entrySet()) {
            Set<String> identities = byRule.get(entry.getKey());
            if (identities == null || dismissedRules.contains(entry.getKey())) continue;
            List<AdvisorViolation> retained = entry.getValue().violations();
            boolean[] flags = new boolean[retained.size()];
            int count = 0;
            for (int i = 0; i < flags.length; i++) {
                String identity = retained.get(i).identity();
                flags[i] = identity != null && identities.contains(identity);
                if (flags[i]) count++;
            }
            if (isTruncated(entry.getValue())) incomplete.add(entry.getKey());
            if (count > 0) {
                rules.put(entry.getKey(), new Rule(retained, flags, count));
                total += count;
            }
        }
        List<AdvisorSuppressedFindingDto> findings = new ArrayList<>();
        for (DismissedFindingDto finding : belonging.subList(0, Math.min(belonging.size(), MAX_LISTED))) {
            findings.add(new AdvisorSuppressedFindingDto(
                    finding.ruleId(), finding.identity(), state(index, catalog, finding)));
        }
        return new AdvisorSuppression(
                rules,
                new AdvisorSuppressionDto(total, incomplete, findings, Math.max(0, belonging.size() - MAX_LISTED)));
    }

    private static String state(
            AdvisorViolationCollector.Snapshot index, Set<String> catalog, DismissedFindingDto finding) {
        if (catalog != null && !catalog.contains(finding.ruleId())) return UNKNOWN_RULE;
        AdvisorViolationCollector.Rule rule = index.rules().get(finding.ruleId());
        if (rule != null) {
            for (AdvisorViolation violation : rule.violations()) {
                if (finding.identity().equals(violation.identity())) return MATCHED;
            }
            if (isTruncated(rule)) return UNVERIFIED;
        }
        return index.coverage().concludes(finding.ruleId(), AdvisorFindingIdentity.scope(finding.identity()))
                ? ORPHANED
                : UNVERIFIED;
    }

    private static boolean isTruncated(AdvisorViolationCollector.Rule rule) {
        return rule.violations().size() < rule.violationCount();
    }
}
