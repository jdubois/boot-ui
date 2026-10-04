package io.github.jdubois.bootui.engine.insights;

import java.util.List;
import java.util.Map;

/**
 * Which findings the default list shows ({@code docs/PLAN-v2.md} M4-19, D35): the rules that need more than one
 * finding, or apply to a whole kind. A kind's own rules, such as a route's prominence, are decided where it is
 * evaluated. A finding left out stays in the report with its reason, so <b>Show all routes</b>, a search, its id, and an
 * agent query naming its kind or route, or {@code all}, still reach it.
 */
final class DefaultListing {

    /** Why a garbage-collection or heap row is left out: it is reached from the Memory panel (revisiting D18). */
    static final String MEMORY =
            "Garbage collection and heap rows are reached from the Memory panel rather than listed" + " by default.";

    /**
     * Why a row of D29's four kinds is left out: listed once the cross-observation counterexample harness passes
     * (M4-18e). Remove a kind from {@link #UNLISTED_KINDS} when it does.
     */
    static final String NOT_YET_VALIDATED =
            "This check is not listed by default until its counterexample fixtures pass across observations.";

    /** The kinds left out whole, with why. */
    static final Map<String, String> UNLISTED_KINDS = Map.of(
            GcInflatedLatency.KIND, MEMORY,
            HeapGrowthAfterGc.KIND, MEMORY,
            TransactionalListenerSkipped.KIND, NOT_YET_VALIDATED,
            AfterCommitWrites.KIND, NOT_YET_VALIDATED,
            OrmAutoFlush.KIND, NOT_YET_VALIDATED,
            LargePersistenceContext.KIND, NOT_YET_VALIDATED);

    /** Why a statement after the handler is left out when Repeated SELECTs already reports it. */
    static final String REPORTED_AS_REPEATED =
            "Repeated SELECTs already reports this statement on this route, from" + " the same call site.";

    private DefaultListing() {}

    /** {@code finding} of {@code kind}, left out of the default list when a whole-kind or cross-kind rule says so. */
    static Finding apply(String kind, Finding finding, List<Finding> repeatedSelects) {
        String whole = UNLISTED_KINDS.get(kind);
        if (whole != null) {
            return finding.unlisted(whole);
        }
        if (LazySqlAfterHandler.KIND.equals(kind) && LazySqlAfterHandler.reportedBy(finding, repeatedSelects)) {
            return finding.unlisted(REPORTED_AS_REPEATED);
        }
        return finding;
    }
}
