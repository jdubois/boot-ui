package io.github.jdubois.bootui.engine.insights;

import java.util.regex.Pattern;

/**
 * The sentences an evaluated check's reason carries for work it examined and judged but does not report, as opposed to
 * evidence it could not see or count. The agent's answer drops these and keeps the rest, so a check that left changed
 * methods, requests, or sources out still says so ({@link RuntimeInsightsAgentView}). Each sentence is built here and
 * matched here, so the two cannot drift apart.
 */
final class JudgedWithoutFinding {

    private static final Pattern SENTENCES = Pattern.compile("(?:"
            + "Examined \\d+ collections that reclaimed old-generation space; their retained occupancy did not meet"
            + " this check's growth threshold\\."
            + "|\\d+ transactional methods? kept a transaction open only across calls under [\\d.]+ ms, so"
            + " (?:it is|they are) not reported\\."
            + "|\\d+ statements? that SQL after the handler returned reports on the same route, from the same call site,"
            + " (?:is|are) left to it, which names the cause\\.)");

    private JudgedWithoutFinding() {}

    /** Heap growth examined these collections and their retained occupancy stayed under its threshold. */
    static String heapUnderThreshold(int collections) {
        return "Examined " + collections + " collections that reclaimed old-generation space; their retained occupancy"
                + " did not meet this check's growth threshold.";
    }

    /** Transactional methods whose remote calls were all fast, judged and not reported. */
    static String fastTransactions(long methods, long minCallNanos) {
        return InsightText.counted(methods, "transactional method")
                + " kept a transaction open only across calls under "
                + InsightText.millis(minCallNanos) + " ms, so " + (methods == 1 ? "it is" : "they are")
                + " not reported.";
    }

    /** Repeated statements left to SQL after the handler, which reports them with their cause. */
    static String leftToLazySql(int statements) {
        return InsightText.counted(statements, "statement") + " that SQL after the handler returned reports on the"
                + " same route, from the same call site, " + (statements == 1 ? "is" : "are")
                + " left to it, which names the cause.";
    }

    /**
     * What an evaluated check's {@code reason} says it could not see or count, without the sentences above; {@code
     * null} when nothing is left. Any sentence not built here counts as a gap, so a new reason is never hidden.
     */
    static String gaps(String reason) {
        if (reason == null) {
            return null;
        }
        String left = SENTENCES
                .matcher(reason)
                .replaceAll("")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return left.isEmpty() ? null : left;
    }
}
