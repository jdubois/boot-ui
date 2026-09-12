package io.github.jdubois.bootui.engine.postgres;

import java.util.List;

/**
 * What a rule found: the measured values it fired on, and the concrete rows behind them.
 *
 * <p>A rule returns {@code null} rather than an empty match when it did not fire, so "checked and clean" and
 * "fired with no samples" stay distinguishable.</p>
 */
record PostgresRuleMatch(String evidence, List<String> samples) {

    PostgresRuleMatch {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    static PostgresRuleMatch of(String evidence) {
        return new PostgresRuleMatch(evidence, List.of());
    }

    static PostgresRuleMatch of(String evidence, List<String> samples) {
        return new PostgresRuleMatch(evidence, samples);
    }
}
