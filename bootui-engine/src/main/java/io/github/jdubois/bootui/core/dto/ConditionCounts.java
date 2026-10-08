package io.github.jdubois.bootui.core.dto;

public record ConditionCounts(
        int positiveTotal,
        int positiveMatched,
        int negativeTotal,
        int negativeMatched,
        int unconditionalTotal,
        int exclusionsTotal) {}
