package io.github.jdubois.bootui.core.dto;

import java.util.List;

public record ConditionsReport(
        List<ConditionEntry> positiveMatches,
        List<ConditionEntry> negativeMatches,
        List<String> unconditionalClasses,
        List<String> exclusions,
        PageMetadata page,
        ConditionCounts counts) {
    public ConditionsReport(
            List<ConditionEntry> positiveMatches,
            List<ConditionEntry> negativeMatches,
            List<String> unconditionalClasses,
            List<String> exclusions) {
        this(
                positiveMatches,
                negativeMatches,
                unconditionalClasses,
                exclusions,
                new PageMetadata(
                        positiveMatches.size() + negativeMatches.size(),
                        positiveMatches.size() + negativeMatches.size(),
                        0,
                        positiveMatches.size() + negativeMatches.size(),
                        positiveMatches.size() + negativeMatches.size(),
                        false),
                new ConditionCounts(
                        positiveMatches.size(),
                        positiveMatches.size(),
                        negativeMatches.size(),
                        negativeMatches.size(),
                        unconditionalClasses.size(),
                        exclusions.size()));
    }
}
