package io.github.jdubois.bootui.core.dto;

/**
 * Top-level report for the Hibernate Statistics panel.
 *
 * <p>{@code available} is {@code true} only when a Hibernate {@code SessionFactory} was resolved
 * <em>and</em> statistics collection is enabled. {@code enableAvailable} is {@code true} only for the
 * recoverable disabled-statistics state, where the user can explicitly enable collection for the current
 * runtime. {@code statistics} is {@code null} whenever {@code available} is {@code false}, and
 * {@code unavailableReason} then explains why.</p>
 */
public record HibernateStatisticsReport(
        boolean available, boolean enableAvailable, String unavailableReason, HibernateStatisticsDto statistics) {}
