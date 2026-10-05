package io.github.jdubois.bootui.engine.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The default list's whole-kind and cross-kind rules ({@code docs/PLAN-v2.md} M4-19). */
class DefaultListingTests {

    private static final String SITE = "com.example.OrderReport.getLines(OrderReport.java:24)";

    @Test
    void gcAndHeapKindsAreLeftOutWholeAndD29sKindsAreListedOnceTheHarnessPasses() {
        Finding finding = finding("k", List.of("Request", "Call site"), SITE, true);

        assertThat(DefaultListing.apply(GcInflatedLatency.KIND, finding, List.of())
                        .unlisted())
                .isEqualTo(DefaultListing.MEMORY);
        assertThat(DefaultListing.apply(HeapGrowthAfterGc.KIND, finding, List.of())
                        .unlisted())
                .isEqualTo(DefaultListing.MEMORY);
        for (String kind : List.of(
                TransactionalListenerSkipped.KIND,
                AfterCommitWrites.KIND,
                OrmAutoFlush.KIND,
                LargePersistenceContext.KIND)) {
            assertThat(DefaultListing.apply(kind, finding, List.of()).listed())
                    .as(kind + " passes ObservationHonestyHarnessTests (M4-18e)")
                    .isTrue();
        }
        assertThat(DefaultListing.apply(RepeatedSelects.KIND, finding, List.of())
                        .listed())
                .isTrue();
    }

    @Test
    void aStatementAfterTheHandlerIsLeftOutOnlyWhenRepeatedSelectsListsTheSameStatementAndCallSite() {
        Finding lazy = finding("GET /r:abc", List.of("Request", "Executions", "Call site"), SITE, true);
        Finding repeated =
                finding("GET /r:abc", List.of("Request", "Executions", "Time (ms)", "Call site", "Phase"), SITE, true);

        assertThat(DefaultListing.apply(LazySqlAfterHandler.KIND, lazy, List.of(repeated))
                        .unlisted())
                .isEqualTo(DefaultListing.REPORTED_AS_REPEATED);
        assertThat(DefaultListing.apply(
                                LazySqlAfterHandler.KIND,
                                lazy,
                                List.of(finding("GET /r:other", repeated.columns(), SITE, true)))
                        .listed())
                .as("another statement or route")
                .isTrue();
        assertThat(DefaultListing.apply(
                                LazySqlAfterHandler.KIND,
                                lazy,
                                List.of(finding(
                                        "GET /r:abc",
                                        repeated.columns(),
                                        "com.example.Other.load(Other.java:3)",
                                        true)))
                        .listed())
                .as("another call site")
                .isTrue();
        assertThat(DefaultListing.apply(
                                LazySqlAfterHandler.KIND,
                                lazy,
                                List.of(finding("GET /r:abc", repeated.columns(), SITE, false)))
                        .listed())
                .as("an insufficient repeat does not replace a sufficient statement after the handler")
                .isTrue();
        Finding underFloor = new Finding(
                "GET /r:abc",
                "GET /r",
                false,
                "s",
                1,
                1,
                List.of(),
                List.of(),
                repeated.columns(),
                repeated.rows(),
                List.of(RepeatedSelects.UNDER_DEFAULT_FLOOR));
        assertThat(DefaultListing.apply(
                                LazySqlAfterHandler.KIND,
                                finding("GET /r:abc", lazy.columns(), SITE, false),
                                List.of(underFloor))
                        .listed())
                .as("a repeat the agent list leaves out under its floor hides nothing")
                .isTrue();
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
