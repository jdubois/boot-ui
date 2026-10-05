package io.github.jdubois.bootui.engine.insights;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each observation kind's external validation, the one place that decides it ({@code docs/PLAN-v2.md} M4-20, D35, D36):
 * the outcome of the per-kind gate applied to the M4-20 rerun, as recorded in {@code docs/V2-VALIDATION-REPORT.md}
 * ("Per-kind gates"). A kind passes with at least 3 default-visible facts on at least 2 applications, at least half of
 * them useful to both reviewers, and nothing misleading; below that it is folded into its panel or hidden; a kind that
 * stayed silent is listed, marked as not externally validated.
 *
 * <p>The outcome decides whether a kind's rows are listed by default, and its reason is shown with the kind on every
 * stack, in the panel and to agents. A kind this registry does not name, such as one of M5's observations, is a kind
 * added after M4-20: it ships as rows of its own panel and is not listed until it passes the gate (D36).</p>
 */
public final class ExternalValidation {

    /** The outcome of a kind's per-kind gate. */
    public enum Outcome {
        /** Passed its per-kind gate: listed by default. */
        PASSED(true),
        /** Silent, or never exercised, on every validation application: listed, marked as not externally validated. */
        NOT_VALIDATED(true),
        /** Missed its per-kind gate: folded into the panel that shows the same evidence, and not listed by default. */
        FAILED(false),
        /** Too few facts to judge: not listed by default, and not externally validated. */
        UNDER_SAMPLED(false),
        /** Not listed by default by design, whatever its validation (D18 revisited by M4-19). */
        NOT_LISTED(false),
        /** Added after M4-20: rows of its own panel until it passes the per-kind gate (D36). */
        NOT_JUDGED(false);

        private final boolean listed;

        Outcome(boolean listed) {
            this.listed = listed;
        }

        /** Whether a kind with this outcome is listed by default. */
        public boolean listed() {
            return listed;
        }
    }

    /**
     * One kind's outcome and why, a sentence shown with the kind.
     *
     * @param kind the observation kind
     * @param outcome its per-kind gate's outcome
     * @param reason why it is listed, marked, folded, or hidden, naming where its evidence is when it is folded
     */
    public record Entry(String kind, Outcome outcome, String reason) {}

    static final String PASSED = "It passed its external validation (M4-20): on the validation applications, at least"
            + " half its facts were useful to both reviewers, and none was misleading.";

    static final String SILENT = "It found nothing on the seven validation applications (M4-20), so it is not"
            + " externally validated: no row of it has been judged by reviewers.";

    static final String NOT_EXERCISED = "Its check never ran on the seven validation applications (M4-20), so it is not"
            + " externally validated: no row of it has been judged by reviewers.";

    static final String TOO_FEW_FACTS = "The validation run (M4-20) produced too few facts of this kind to judge it, so"
            + " it is not externally validated and not listed by default.";

    static final String FAILED = "It did not pass its external validation (M4-20): fewer than half its facts on the"
            + " validation applications were useful to both reviewers";

    static final String AFTER_M4_20 = "Added after the validation run (D36): it is shown as rows of its own panel, and"
            + " is listed here only once its seeded case and counterexample pass and an external run passes the per-kind"
            + " gate.";

    private static final Map<String, Entry> KINDS = build();

    private ExternalValidation() {}

    /** Every kind this registry names, in the validation report's order, then M5's observations. */
    public static Map<String, Entry> kinds() {
        return KINDS;
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> kinds = new LinkedHashMap<>();
        put(
                kinds,
                RouteTimeBreakdown.KIND,
                Outcome.FAILED,
                FAILED + ", so it is not listed by default. A request's Why this route is slow, in Live Activity,"
                        + " shows its route's breakdown.");
        put(kinds, RepeatedSelects.KIND, Outcome.UNDER_SAMPLED, TOO_FEW_FACTS);
        put(kinds, LazySqlAfterHandler.KIND, Outcome.UNDER_SAMPLED, TOO_FEW_FACTS);
        put(
                kinds,
                ExceptionHotspots.KIND,
                Outcome.FAILED,
                FAILED + ", so it is not listed by default. The Exceptions panel lists the same exceptions and"
                        + " links here.");
        put(kinds, ErrorsBehind2xx.KIND, Outcome.PASSED, PASSED);
        put(
                kinds,
                ConnectionsPerRequest.KIND,
                Outcome.FAILED,
                FAILED + ", and one was misleading, so it is not listed by default. The Database connection pools"
                        + " panel shows the pools and links here.");
        put(kinds, SafeMethodDml.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, TransactionAcrossRemoteCall.KIND, Outcome.NOT_VALIDATED, NOT_EXERCISED);
        put(kinds, SplitTransactionWrites.KIND, Outcome.UNDER_SAMPLED, TOO_FEW_FACTS);
        put(kinds, AfterCommitWrites.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, TransactionalListenerSkipped.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, ProxyBypass.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, FrameworkWarningsByRoute.KIND, Outcome.UNDER_SAMPLED, TOO_FEW_FACTS);
        put(kinds, EventLoopBlocking.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(
                kinds,
                AiUsageByRoute.KIND,
                Outcome.FAILED,
                FAILED + ", and one was misleading, so it is not listed by default. The AI panel lists the same calls"
                        + " with their tokens and links here.");
        put(kinds, AnonymousDataReach.KIND, Outcome.UNDER_SAMPLED, TOO_FEW_FACTS);
        put(kinds, AnonymousSuccessOnRestrictedRoute.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, OrmAutoFlush.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, LargePersistenceContext.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, GcInflatedLatency.KIND, Outcome.NOT_LISTED, DefaultListing.MEMORY);
        put(kinds, HeapGrowthAfterGc.KIND, Outcome.NOT_LISTED, DefaultListing.MEMORY);
        put(kinds, WorkAfterResponse.KIND, Outcome.NOT_VALIDATED, SILENT);
        put(kinds, ChangedCodeNotExecuted.KIND, Outcome.PASSED, PASSED);
        // M5's observations (§5.17), Side Effects, Code Paths, and Code Inventory rows until they pass the gate (D36).
        for (String kind : new String[] {
            "exceptions-caught-in-code",
            "hidden-outbound-calls",
            "thread-local-left-set",
            "resource-not-closed",
            "threads-per-request",
            "request-input-in-sink"
        }) {
            put(kinds, kind, Outcome.NOT_JUDGED, AFTER_M4_20);
        }
        return Collections.unmodifiableMap(kinds);
    }

    private static void put(Map<String, Entry> kinds, String kind, Outcome outcome, String reason) {
        kinds.put(kind, new Entry(kind, outcome, reason));
    }

    /** {@code kind}'s outcome; a kind this registry does not name was added after M4-20 and is not judged (D36). */
    public static Entry of(String kind) {
        Entry entry = KINDS.get(kind);
        return entry != null ? entry : new Entry(kind, Outcome.NOT_JUDGED, AFTER_M4_20);
    }
}
