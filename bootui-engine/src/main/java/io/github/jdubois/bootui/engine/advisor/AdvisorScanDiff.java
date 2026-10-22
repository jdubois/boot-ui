package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorChangesDto;
import io.github.jdubois.bootui.core.dto.AdvisorResolvedFindingDto;
import io.github.jdubois.bootui.core.dto.AdvisorRuleChangeDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares the retained findings of two completed scans, keyed by {@code (ruleId, identity)}.
 *
 * <p>Every claim is directional and fails closed. A finding present in both scans is unchanged. A finding only in the
 * current scan is new only when the previous scan concluded its rule and kept all of that rule's findings; a finding
 * only in the previous scan is resolved only when the current scan did. Otherwise it is counted as unverified, so a
 * rule that failed, was skipped, lacked evidence, or lost findings to the retention budget can never make findings
 * look fixed. Findings that appear several times under one identity are matched as a multiset.</p>
 */
final class AdvisorScanDiff {

    static final String NEW = "NEW";
    static final String UNCHANGED = "UNCHANGED";

    private static final int MAX_NOTES = 10;

    private AdvisorScanDiff() {}

    /** The comparison and, per rule, one marker for each retained finding of the current scan ({@code null} when unverified). */
    record Result(AdvisorChangesDto changes, Map<String, List<String>> markers) {}

    /** A comparison that could not be made because the current scan concluded nothing. */
    static AdvisorChangesDto notCompared(String previousScanId, AdvisorViolationCollector.Coverage coverage) {
        List<String> notes = new ArrayList<>();
        notes.add("This scan could not evaluate any rule, so it was not compared with the previous scan.");
        notes.addAll(coverage.notes());
        return new AdvisorChangesDto(previousScanId, false, 0, 0, 0, 0, List.of(), notes, 0, List.of());
    }

    static Result compare(
            String previousScanId,
            AdvisorViolationCollector.Snapshot previous,
            AdvisorViolationCollector.Snapshot current) {
        Set<String> ruleIds = new LinkedHashSet<>(current.rules().keySet());
        ruleIds.addAll(previous.rules().keySet());

        int added = 0;
        int resolvedTotal = 0;
        int unchanged = 0;
        int unverified = 0;
        int unkeyedCurrent = 0;
        int unkeyedPrevious = 0;
        List<AdvisorResolvedFindingDto> resolvedList = new ArrayList<>();
        List<AdvisorRuleChangeDto> ruleChanges = new ArrayList<>();
        Map<String, List<String>> markers = new LinkedHashMap<>();

        for (String ruleId : ruleIds) {
            AdvisorViolationCollector.Rule before = previous.rules().get(ruleId);
            AdvisorViolationCollector.Rule after = current.rules().get(ruleId);
            boolean beforeTruncated = before != null && before.violations().size() < before.violationCount();
            boolean afterTruncated = after != null && after.violations().size() < after.violationCount();

            Map<String, List<AdvisorViolation>> oldGroups = group(before);
            Map<String, List<AdvisorViolation>> newGroups = group(after);
            unkeyedPrevious += unkeyed(before);
            unkeyedCurrent += unkeyed(after);

            String[] ruleMarkers =
                    new String[after == null ? 0 : after.violations().size()];
            int ruleNew = 0;
            int ruleResolved = 0;
            int ruleUnverified = 0;

            Set<String> identities = new LinkedHashSet<>(newGroups.keySet());
            identities.addAll(oldGroups.keySet());
            Map<String, List<Integer>> indexesByIdentity = indexes(after);
            for (String identity : identities) {
                List<AdvisorViolation> oldFindings = oldGroups.getOrDefault(identity, List.of());
                List<AdvisorViolation> newFindings = newGroups.getOrDefault(identity, List.of());
                List<Integer> newIndexes = indexesByIdentity.getOrDefault(identity, List.of());
                String scope = AdvisorFindingIdentity.scope(identity);
                int common = Math.min(oldFindings.size(), newFindings.size());
                unchanged += common;
                for (int i = 0; i < common; i++) ruleMarkers[newIndexes.get(i)] = UNCHANGED;

                int extraNew = newFindings.size() - common;
                if (extraNew > 0) {
                    if (previous.coverage().concludes(ruleId, scope) && !beforeTruncated) {
                        ruleNew += extraNew;
                        for (int i = common; i < newFindings.size(); i++) ruleMarkers[newIndexes.get(i)] = NEW;
                    } else {
                        ruleUnverified += extraNew;
                    }
                }
                int extraOld = oldFindings.size() - common;
                if (extraOld > 0) {
                    if (current.coverage().concludes(ruleId, scope) && !afterTruncated) {
                        ruleResolved += extraOld;
                        for (int i = common; i < oldFindings.size(); i++) {
                            if (resolvedList.size() < AdvisorChangesDto.MAX_RESOLVED) {
                                resolvedList.add(new AdvisorResolvedFindingDto(
                                        ruleId, oldFindings.get(i).text()));
                            }
                        }
                    } else {
                        ruleUnverified += extraOld;
                    }
                }
            }
            added += ruleNew;
            resolvedTotal += ruleResolved;
            unverified += ruleUnverified;
            if ((ruleNew > 0 || ruleResolved > 0 || ruleUnverified > 0)
                    && ruleChanges.size() < AdvisorChangesDto.MAX_RULES) {
                ruleChanges.add(new AdvisorRuleChangeDto(ruleId, ruleNew, ruleResolved, ruleUnverified));
            }
            if (ruleMarkers.length > 0) {
                markers.put(
                        ruleId, Collections.unmodifiableList(new ArrayList<>(java.util.Arrays.asList(ruleMarkers))));
            }
        }

        boolean previousTruncated = previous.retained() < previous.total();
        boolean currentTruncated = current.retained() < current.total();
        List<String> notes = new ArrayList<>();
        if (currentTruncated) {
            notes.add("This scan retained " + current.retained() + " of " + current.total()
                    + " findings, so findings of truncated rules cannot be shown as resolved.");
        }
        if (previousTruncated) {
            notes.add("The previous scan retained " + previous.retained() + " of " + previous.total()
                    + " findings, so findings of truncated rules cannot be shown as new.");
        }
        if (unkeyedCurrent + unkeyedPrevious > 0) {
            notes.add("Some findings have no stable identity and were not compared.");
        }
        if (unverified > 0) {
            notes.add(unverified + " finding(s) were not compared because a scan did not fully evaluate their rule"
                    + " (an error, a skipped check, incomplete evidence, or truncated details).");
        }
        notes.addAll(current.coverage().notes());
        notes.addAll(previous.coverage().notes());
        boolean complete = notes.isEmpty() && !previousTruncated && !currentTruncated;
        AdvisorChangesDto changes = new AdvisorChangesDto(
                previousScanId,
                complete,
                added,
                resolvedTotal,
                unchanged,
                unkeyedCurrent,
                resolvedList,
                notes.subList(0, Math.min(MAX_NOTES, notes.size())),
                unverified,
                ruleChanges);
        return new Result(changes, Collections.unmodifiableMap(markers));
    }

    private static Map<String, List<AdvisorViolation>> group(AdvisorViolationCollector.Rule rule) {
        Map<String, List<AdvisorViolation>> groups = new LinkedHashMap<>();
        if (rule == null) return groups;
        for (AdvisorViolation violation : rule.violations()) {
            if (violation.identity() != null) {
                groups.computeIfAbsent(violation.identity(), ignored -> new ArrayList<>())
                        .add(violation);
            }
        }
        return groups;
    }

    private static Map<String, List<Integer>> indexes(AdvisorViolationCollector.Rule rule) {
        Map<String, List<Integer>> indexes = new LinkedHashMap<>();
        if (rule == null) return indexes;
        List<AdvisorViolation> violations = rule.violations();
        for (int i = 0; i < violations.size(); i++) {
            String identity = violations.get(i).identity();
            if (identity != null) {
                indexes.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(i);
            }
        }
        return indexes;
    }

    private static int unkeyed(AdvisorViolationCollector.Rule rule) {
        if (rule == null) return 0;
        int count = 0;
        for (AdvisorViolation violation : rule.violations()) {
            if (violation.identity() == null) count++;
        }
        return count;
    }
}
