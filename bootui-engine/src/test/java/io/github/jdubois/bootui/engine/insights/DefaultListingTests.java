package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The default list's whole-kind rule ({@code docs/PLAN-v2.md} M4-19, M4-20): each kind's external validation, recorded
 * once in {@link ExternalValidation}, decides whether its rows are listed.
 */
class DefaultListingTests {

    private static final String SITE = "com.example.OrderReport.getLines(OrderReport.java:24)";

    /** The outcomes of the per-kind gates, as docs/V2-VALIDATION-REPORT.md "Per-kind gates" records them. */
    private static final Map<String, ExternalValidation.Outcome> REPORTED = Map.ofEntries(
            Map.entry(RouteTimeBreakdown.KIND, ExternalValidation.Outcome.FAILED),
            Map.entry(RepeatedSelects.KIND, ExternalValidation.Outcome.UNDER_SAMPLED),
            Map.entry(LazySqlAfterHandler.KIND, ExternalValidation.Outcome.UNDER_SAMPLED),
            Map.entry(ExceptionHotspots.KIND, ExternalValidation.Outcome.FAILED),
            Map.entry(ErrorsBehind2xx.KIND, ExternalValidation.Outcome.PASSED),
            Map.entry(ConnectionsPerRequest.KIND, ExternalValidation.Outcome.FAILED),
            Map.entry(SafeMethodDml.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(TransactionAcrossRemoteCall.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(SplitTransactionWrites.KIND, ExternalValidation.Outcome.UNDER_SAMPLED),
            Map.entry(AfterCommitWrites.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(TransactionalListenerSkipped.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(ProxyBypass.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(FrameworkWarningsByRoute.KIND, ExternalValidation.Outcome.UNDER_SAMPLED),
            Map.entry(EventLoopBlocking.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(AiUsageByRoute.KIND, ExternalValidation.Outcome.FAILED),
            Map.entry(AnonymousDataReach.KIND, ExternalValidation.Outcome.UNDER_SAMPLED),
            Map.entry(AnonymousSuccessOnRestrictedRoute.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(OrmAutoFlush.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(LargePersistenceContext.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(GcInflatedLatency.KIND, ExternalValidation.Outcome.NOT_LISTED),
            Map.entry(HeapGrowthAfterGc.KIND, ExternalValidation.Outcome.NOT_LISTED),
            Map.entry(WorkAfterResponse.KIND, ExternalValidation.Outcome.NOT_VALIDATED),
            Map.entry(ChangedCodeNotExecuted.KIND, ExternalValidation.Outcome.PASSED));

    @Test
    void everyKindOf20HasTheOutcomeItsPerKindGateRecorded() {
        List<String> kinds = RuntimeInsightsService.defaultObservations().stream()
                .map(Observation::kind)
                .toList();

        assertThat(kinds).containsExactlyInAnyOrderElementsOf(REPORTED.keySet());
        for (String kind : kinds) {
            ExternalValidation.Entry entry = ExternalValidation.kinds().get(kind);
            assertThat(entry).as(kind + " is registered explicitly").isNotNull();
            assertThat(entry.outcome()).as(kind).isEqualTo(REPORTED.get(kind));
            assertThat(entry.reason()).as(kind).isNotBlank();
        }
    }

    @Test
    void m5sObservationsAndUnknownKindsAreNotJudgedAndNotListedUntilTheyPassTheGate() {
        for (String kind : List.of(
                "exceptions-caught-in-code",
                "hidden-outbound-calls",
                "thread-local-left-set",
                "resource-not-closed",
                "threads-per-request",
                "request-input-in-sink")) {
            assertThat(ExternalValidation.kinds().get(kind).outcome())
                    .as(kind + " ships as panel rows (D36)")
                    .isEqualTo(ExternalValidation.Outcome.NOT_JUDGED);
        }
        assertThat(ExternalValidation.of("a-kind-added-later").outcome())
                .isEqualTo(ExternalValidation.Outcome.NOT_JUDGED);
        assertThat(DefaultListing.apply("a-kind-added-later", finding(true)).listed())
                .isFalse();
    }

    @Test
    void onlyKindsThatPassedOrStayedSilentAreListedAndTheOthersSayWhy() {
        Finding finding = finding(true);
        for (Map.Entry<String, ExternalValidation.Outcome> kind : REPORTED.entrySet()) {
            Finding applied = DefaultListing.apply(kind.getKey(), finding);
            boolean listed = kind.getValue() == ExternalValidation.Outcome.PASSED
                    || kind.getValue() == ExternalValidation.Outcome.NOT_VALIDATED;
            assertThat(applied.listed()).as(kind.getKey()).isEqualTo(listed);
            if (!listed) {
                assertThat(applied.unlisted())
                        .isEqualTo(ExternalValidation.of(kind.getKey()).reason());
            }
        }
        assertThat(DefaultListing.apply(GcInflatedLatency.KIND, finding).unlisted())
                .isEqualTo(DefaultListing.MEMORY);
    }

    @Test
    void aKindThatFailedItsGateReplacesTheRowsOwnReasonAndNamesThePanelItIsFoldedInto() {
        Finding notProminent = finding(true).unlisted(RouteTimeBreakdown.NOT_PROMINENT);

        assertThat(DefaultListing.apply(RouteTimeBreakdown.KIND, notProminent).unlisted())
                .contains("did not pass its external validation")
                .contains("Why this route is slow");
        assertThat(DefaultListing.apply(ExceptionHotspots.KIND, finding(true)).unlisted())
                .contains("Exceptions panel");
        assertThat(DefaultListing.apply(ConnectionsPerRequest.KIND, finding(true))
                        .unlisted())
                .contains("Database connection pools panel");
        assertThat(DefaultListing.apply(AiUsageByRoute.KIND, finding(true)).unlisted())
                .contains("AI panel");
        assertThat(DefaultListing.apply(SafeMethodDml.KIND, notProminent).unlisted())
                .as("a listed kind keeps its own rule")
                .isEqualTo(RouteTimeBreakdown.NOT_PROMINENT);
    }

    @Test
    void aRepeatedSelectIsLeftToLazySqlOnlyWhenItReportsTheSameStatementAndCallSiteAtLeastAsSufficiently() {
        Finding repeated =
                finding("GET /r:abc", List.of("Request", "Executions", "Time (ms)", "Call site", "Phase"), SITE, true);
        List<String> lazyColumns = List.of("Request", "Executions", "Call site");

        assertThat(LazySqlAfterHandler.reports(List.of(finding("GET /r:abc", lazyColumns, SITE, true)), repeated))
                .isTrue();
        assertThat(LazySqlAfterHandler.reports(List.of(finding("GET /r:other", lazyColumns, SITE, true)), repeated))
                .as("another statement or route")
                .isFalse();
        assertThat(LazySqlAfterHandler.reports(
                        List.of(finding("GET /r:abc", lazyColumns, "com.example.Other.load(Other.java:3)", true)),
                        repeated))
                .as("another call site")
                .isFalse();
        assertThat(LazySqlAfterHandler.reports(List.of(finding("GET /r:abc", lazyColumns, SITE, false)), repeated))
                .as("an insufficient statement after the handler does not replace a sufficient repeat")
                .isFalse();
        assertThat(LazySqlAfterHandler.reports(
                        List.of(finding("GET /r:abc", lazyColumns, SITE, true)),
                        finding("GET /r:abc", repeated.columns(), SITE, false)))
                .isTrue();
    }

    private static Finding finding(boolean sufficient) {
        return finding("k", List.of("Request", "Call site"), SITE, sufficient);
    }

    private static Finding finding(String key, List<String> columns, String site, boolean sufficient) {
        List<String> row = new java.util.ArrayList<>();
        for (String column : columns) {
            row.add(column.equals("Call site") ? site : "1");
        }
        return new Finding(
                key,
                key.substring(0, key.indexOf(':') < 0 ? key.length() : key.indexOf(':')),
                sufficient,
                "s",
                3,
                3,
                List.of(),
                List.of("r1"),
                columns,
                List.of(row),
                List.of());
    }
}
