package io.github.jdubois.bootui.engine.insights;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each observation kind's external validation, the one place that decides it ({@code docs/PLAN-v2.md} M4-20, D35, D36):
 * the outcome of the per-kind gate applied to the M4-20 rerun, as recorded in {@code docs/V2-VALIDATION-REPORT.md}
 * ("Per-kind gates"). A kind passes with at least 3 default-visible facts on at least 2 applications, at least half of
 * them useful to both reviewers, and nothing misleading; below that it is folded into its panel or hidden; a kind that
 * stayed silent is listed.
 *
 * <p>The outcome decides whether a kind's rows are listed by default, on every stack, in the panel and to agents. It
 * is never shown to users: the validation lives in the plan and the validation report, and a row the default list leaves
 * out says only where its evidence is, when another panel shows it. A kind this registry does not name, such as one of M5's observations, is a kind
 * added after M4-20: it ships as rows of its own panel and is not listed until it passes the gate (D36).</p>
 */
public final class ExternalValidation {

    /** The outcome of a kind's per-kind gate. */
    public enum Outcome {
        /** Passed its per-kind gate: listed by default. */
        PASSED(true),
        /** Silent, or never exercised, on every validation application: listed. */
        NOT_VALIDATED(true),
        /** Missed its per-kind gate: folded into the panel that shows the same evidence, and not listed by default. */
        FAILED(false),
        /** Too few facts to judge: not listed by default. */
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
     * One kind's outcome, and where its rows are when the default list leaves them out. The outcome stays internal: the
     * panel, the API, and agents never show it, only whether a row is listed by default and {@code unlistedReason}.
     *
     * @param kind the observation kind
     * @param outcome its per-kind gate's outcome
     * @param unlistedReason for a kind not listed by default, a sentence in user terms saying where its rows are, or
     *     {@code null} when it is listed
     */
    public record Entry(String kind, Outcome outcome, String unlistedReason) {}

    /** Where the rows of an under-sampled kind are: wherever every row, a search, or a query naming it asks for them. */
    static final String ON_REQUEST =
            "Its rows appear when all rows are shown, or when a search or a query names this check or its route.";

    /** Where the rows of a kind added after M4-20 are (D36): its own panel, until it passes the per-kind gate. */
    static final String OWN_PANEL = "Its rows are shown in their own panel rather than listed by default.";

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
                "A request's Why this route is slow, in Live Activity, shows its route's breakdown.");
        put(kinds, RepeatedSelects.KIND, Outcome.UNDER_SAMPLED, ON_REQUEST);
        put(kinds, LazySqlAfterHandler.KIND, Outcome.UNDER_SAMPLED, ON_REQUEST);
        put(
                kinds,
                ExceptionHotspots.KIND,
                Outcome.FAILED,
                "The Exceptions panel lists the same exceptions and links here.");
        put(kinds, ErrorsBehind2xx.KIND, Outcome.PASSED, null);
        put(
                kinds,
                ConnectionsPerRequest.KIND,
                Outcome.FAILED,
                "The Database connection pools panel shows the pools and links here.");
        put(kinds, SafeMethodDml.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, TransactionAcrossRemoteCall.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, SplitTransactionWrites.KIND, Outcome.UNDER_SAMPLED, ON_REQUEST);
        put(kinds, AfterCommitWrites.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, TransactionalListenerSkipped.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, ProxyBypass.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, FrameworkWarningsByRoute.KIND, Outcome.UNDER_SAMPLED, ON_REQUEST);
        put(kinds, EventLoopBlocking.KIND, Outcome.NOT_VALIDATED, null);
        put(
                kinds,
                AiUsageByRoute.KIND,
                Outcome.FAILED,
                "The AI panel lists the same calls with their tokens and links here.");
        put(kinds, AnonymousDataReach.KIND, Outcome.UNDER_SAMPLED, ON_REQUEST);
        put(kinds, AnonymousSuccessOnRestrictedRoute.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, OrmAutoFlush.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, LargePersistenceContext.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, GcInflatedLatency.KIND, Outcome.NOT_LISTED, DefaultListing.MEMORY);
        put(kinds, HeapGrowthAfterGc.KIND, Outcome.NOT_LISTED, DefaultListing.MEMORY);
        put(kinds, WorkAfterResponse.KIND, Outcome.NOT_VALIDATED, null);
        put(kinds, ChangedCodeNotExecuted.KIND, Outcome.PASSED, null);
        // M5's observations (§5.17), Side Effects, Code Paths, and Code Inventory rows until they pass the gate (D36).
        for (String kind : new String[] {
            "exceptions-caught-in-code",
            "hidden-outbound-calls",
            "thread-local-left-set",
            "resource-not-closed",
            "threads-per-request",
            "request-input-in-sink"
        }) {
            put(kinds, kind, Outcome.NOT_JUDGED, OWN_PANEL);
        }
        return Collections.unmodifiableMap(kinds);
    }

    private static void put(Map<String, Entry> kinds, String kind, Outcome outcome, String unlistedReason) {
        kinds.put(kind, new Entry(kind, outcome, unlistedReason));
    }

    /** {@code kind}'s outcome; a kind this registry does not name was added after M4-20 and is not judged (D36). */
    public static Entry of(String kind) {
        Entry entry = KINDS.get(kind);
        return entry != null ? entry : new Entry(kind, Outcome.NOT_JUDGED, OWN_PANEL);
    }
}
