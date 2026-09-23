package io.github.jdubois.bootui.engine.sqltrace;

import static io.github.jdubois.bootui.engine.sqltrace.SqlTraceEntryFixtures.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.jdubois.bootui.core.dto.SqlStatementRankingDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlStatementRankingTests {

    private static final int N_PLUS_ONE = SqlTraceGrouping.DEFAULT_N_PLUS_ONE_THRESHOLD;

    @Test
    void fixtureBuilderCarriesEveryFieldTheseTestsRelyOn() {
        SqlTraceEntryFixtures.selfCheck();
    }

    @Test
    void groupsEquivalentParameterizedExecutionsIntoOneRankedStatement() {
        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(
                List.of(
                        entry("select * from users where id = 1").lasting(10).build(),
                        entry("select * from users where id = 2").lasting(30).build(),
                        entry("select * from users where id = 3").lasting(20).build()),
                N_PLUS_ONE);

        assertThat(ranked.distinct()).isEqualTo(1);
        SqlStatementRankingDto row = ranked.statements().get(0);
        assertThat(row.sql()).isEqualTo("select * from users where id = ?");
        assertThat(row.executions()).isEqualTo(3);
        assertThat(row.totalDurationMillis()).isEqualTo(60);
        assertThat(row.maxDurationMillis()).isEqualTo(30);
        assertThat(row.avgDurationMillis()).isEqualTo(20.0);
        assertThat(row.shareOfRetainedTimePercent()).isEqualTo(100.0);
    }

    @Test
    void ranksBySlowestCumulativeTimeFirstSoTheWorstOffenderLeads() {
        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(
                List.of(
                        entry("select * from a").lasting(500).build(),
                        entry("select * from b").lasting(10).build(),
                        entry("select * from b").lasting(10).build(),
                        entry("select * from b").lasting(10).build()),
                N_PLUS_ONE);

        assertThat(ranked.statements())
                .extracting(SqlStatementRankingDto::sql)
                .containsExactly("select * from a", "select * from b");
    }

    @Test
    void carriesEveryCriterionOnEachRowSoTheBrowserCanResortExactly() {
        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(
                List.of(
                        entry("select * from slow").lasting(900).build(),
                        entry("select * from chatty").lasting(1).build(),
                        entry("select * from chatty").lasting(1).build(),
                        entry("select * from chatty").lasting(1).build(),
                        entry("select * from broken").failed().lasting(2).build()),
                N_PLUS_ONE);

        SqlStatementRankingDto chatty = row(ranked, "select * from chatty");
        assertThat(chatty.executions()).isEqualTo(3);
        assertThat(row(ranked, "select * from broken").errorCount()).isEqualTo(1);
        assertThat(row(ranked, "select * from slow").maxDurationMillis()).isEqualTo(900);
    }

    @Test
    void includesTheTopStatementOfEveryCriterionEvenWhenItIsNotTheSlowestOverall() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        // Twelve heavyweight statements crowd out every cumulative-duration slot.
        for (int i = 0; i < 12; i++) {
            entries.add(entry("select * from heavy" + i).lasting(1_000).build());
        }
        // A cheap statement executed far more often than anything else must still be rankable by count.
        for (int i = 0; i < 50; i++) {
            entries.add(entry("select * from chatty where id = " + i).lasting(1).build());
        }

        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(entries, N_PLUS_ONE);

        assertThat(ranked.statements())
                .extracting(SqlStatementRankingDto::sql)
                .contains("select * from chatty where id = ?");
        assertThat(ranked.truncated()).isTrue();
        assertThat(ranked.distinct()).isEqualTo(13);
    }

    @Test
    void boundsTheResponseAtTheUnionOfEachCriterionsTopSlots() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            entries.add(entry("select * from table" + i).lasting(i).failed().build());
        }

        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(entries, N_PLUS_ONE);

        assertThat(ranked.distinct()).isEqualTo(400);
        assertThat(ranked.statements()).hasSizeLessThanOrEqualTo(5 * SqlStatementRanking.TOP_PER_CRITERION);
        assertThat(ranked.truncated()).isTrue();
    }

    @Test
    void breaksTiesDeterministicallyInsteadOfFollowingBufferOrder() {
        List<SqlTraceEntryDto> forward = List.of(
                entry("select * from zeta").lasting(10).build(),
                entry("select * from alpha").lasting(10).build());
        List<SqlTraceEntryDto> reversed = List.of(
                entry("select * from alpha").lasting(10).build(),
                entry("select * from zeta").lasting(10).build());

        assertThat(SqlStatementRanking.rank(forward, N_PLUS_ONE).statements())
                .extracting(SqlStatementRankingDto::sql)
                .isEqualTo(SqlStatementRanking.rank(reversed, N_PLUS_ONE).statements().stream()
                        .map(SqlStatementRankingDto::sql)
                        .toList());
    }

    @Test
    void computesPercentilesOverTheRetainedDistributionNotFromTheMean() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            entries.add(entry("select * from t where id = " + i).lasting(i).build());
        }

        SqlStatementRankingDto row =
                SqlStatementRanking.rank(entries, N_PLUS_ONE).statements().get(0);

        assertThat(row.p50DurationMillis()).isEqualTo(50);
        assertThat(row.p95DurationMillis()).isEqualTo(95);
        assertThat(row.p99DurationMillis()).isEqualTo(99);
    }

    @Test
    void ranksAWindowInWhichEveryExecutionIsSubMillisecond() {
        // The state of a developer's local database: primary-key reads that finish in a few hundred
        // microseconds. Truncating them to whole milliseconds left every total at zero and the panel with
        // nothing to rank.
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(entry("select * from users where id = " + i)
                    .lastingMicros(300)
                    .build());
        }
        for (int i = 0; i < 100; i++) {
            entries.add(entry("select * from orders where id = " + i)
                    .lastingMicros(900)
                    .category("SELECT")
                    .build());
        }

        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(entries, N_PLUS_ONE);

        assertThat(ranked.totalDurationMicros()).isEqualTo(120_000);
        assertThat(ranked.statements()).hasSize(2);
        assertThat(ranked.statements())
                .allSatisfy(row -> assertThat(row.totalDurationMillis()).isGreaterThan(0));
        SqlStatementRankingDto heaviest = ranked.statements().get(0);
        assertThat(heaviest.sql()).contains("orders");
        assertThat(heaviest.totalDurationMillis()).isEqualTo(90.0);
        assertThat(heaviest.avgDurationMillis()).isEqualTo(0.9);
        assertThat(heaviest.maxDurationMillis()).isEqualTo(0.9);
        assertThat(heaviest.p95DurationMillis()).isEqualTo(0.9);
        assertThat(heaviest.p99DurationMillis()).isEqualTo(0.9);
        assertThat(heaviest.shareOfRetainedTimePercent()).isEqualTo(75.0);
        assertThat(heaviest.topFor()).contains("TOTAL_DURATION");
    }

    @Test
    void reportsSharesThatAddUpToTheRetainedWindow() {
        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(
                List.of(
                        entry("select * from a").lasting(75).build(),
                        entry("select * from b").lasting(25).build()),
                N_PLUS_ONE);

        assertThat(ranked.totalDurationMicros()).isEqualTo(100_000);
        assertThat(ranked.statements())
                .extracting(SqlStatementRankingDto::shareOfRetainedTimePercent)
                .containsExactly(75.0, 25.0);
    }

    @Test
    void flagsARepeatedSelectAsAPossibleNPlusOneUsingThePanelsExistingThreshold() {
        List<SqlTraceEntryDto> repeated = new ArrayList<>();
        for (int i = 0; i < N_PLUS_ONE; i++) {
            repeated.add(entry("select * from line_item where order_id = " + i)
                    .from("OrderService.load(OrderService.java:42)")
                    .build());
        }

        SqlStatementRankingDto row =
                SqlStatementRanking.rank(repeated, N_PLUS_ONE).statements().get(0);

        assertThat(row.potentialNPlusOne()).isTrue();
        assertThat(row.callSites()).containsExactly("OrderService.load(OrderService.java:42)");
    }

    @Test
    void doesNotFlagARepeatedWriteAsAnNPlusOne() {
        List<SqlTraceEntryDto> repeated = new ArrayList<>();
        for (int i = 0; i < N_PLUS_ONE + 5; i++) {
            repeated.add(entry("insert into audit values (" + i + ")")
                    .category("INSERT")
                    .build());
        }

        assertThat(SqlStatementRanking.rank(repeated, N_PLUS_ONE)
                        .statements()
                        .get(0)
                        .potentialNPlusOne())
                .isFalse();
    }

    @Test
    void boundsCallSitesPerStatementLikeTheRestOfThePanel() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(entry("select * from t where id = " + i)
                    .from("Caller" + i + ".load(Caller.java:" + i + ")")
                    .build());
        }

        assertThat(SqlStatementRanking.rank(entries, N_PLUS_ONE)
                        .statements()
                        .get(0)
                        .callSites())
                .hasSize(SqlTraceGrouping.MAX_CALL_SITES_PER_GROUP);
    }

    @Test
    void reportsNothingForAnEmptyWindowRatherThanFabricatingZeroRows() {
        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(List.of(), N_PLUS_ONE);

        assertThat(ranked.statements()).isEmpty();
        assertThat(ranked.truncated()).isFalse();
        assertThat(ranked.distinct()).isZero();
        assertThat(ranked.totalDurationMicros()).isZero();
    }

    @Test
    void reportsZeroSharesWhenTheWindowHoldsNoMeasurableTime() {
        SqlStatementRanking.Ranked ranked =
                SqlStatementRanking.rank(List.of(entry("select 1").lasting(0).build()), N_PLUS_ONE);

        assertThat(ranked.statements().get(0).shareOfRetainedTimePercent()).isZero();
    }

    @Test
    void neverExposesALiteralValueInARankedStatement() {
        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(
                List.of(
                        entry("select * from users where email = 'ada@example.com'")
                                .build(),
                        entry("select * from users where email = 'grace@example.com'")
                                .build()),
                N_PLUS_ONE);

        assertThat(ranked.statements()).hasSize(1);
        assertThat(ranked.statements().get(0).sql()).doesNotContain("@example.com");
    }

    @Test
    void keepsAStatementThatOnlyEarnsItsPlaceOnATailPercentile() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int statement = 0; statement < 12; statement++) {
            for (int execution = 0; execution < 150; execution++) {
                entries.add(entry("select * from heavy" + statement + " where id = " + execution)
                        .lasting(execution == 149 ? 5_000 : 200)
                        .build());
            }
        }
        for (int execution = 0; execution < 100; execution++) {
            entries.add(entry("select * from spiky where id = " + execution)
                    .lasting(execution >= 96 ? 250 : 1)
                    .build());
        }

        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(entries, N_PLUS_ONE);
        SqlStatementRankingDto spiky = row(ranked, "select * from spiky where id = ?");

        assertThat(spiky.p95DurationMillis()).isEqualTo(1);
        assertThat(spiky.p99DurationMillis()).isEqualTo(250);
        assertThat(spiky.topFor()).containsExactly("P99_DURATION");
    }

    @Test
    void neverRanksAStatementOnACriterionItScoresZeroOn() {
        SqlStatementRanking.Ranked ranked =
                SqlStatementRanking.rank(List.of(entry("select 1").lasting(5).build()), N_PLUS_ONE);

        assertThat(ranked.statements().get(0).errorCount()).isZero();
        assertThat(ranked.statements().get(0).topFor()).doesNotContain("ERROR_COUNT");
    }

    @Test
    void reportsAPositiveMeanForAGroupRankedOnItsMean() {
        // A mean below one microsecond is still positive, so the group earns its AVG_DURATION slot; the
        // reported mean must not round to 0 and read as unmeasured.
        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(
                List.of(
                        entry("select * from users where id = 1")
                                .lastingMicros(1)
                                .build(),
                        entry("select * from users where id = 2")
                                .lastingMicros(0)
                                .build(),
                        entry("select * from users where id = 3")
                                .lastingMicros(0)
                                .build()),
                N_PLUS_ONE);

        SqlStatementRankingDto row = ranked.statements().get(0);
        assertThat(row.topFor()).contains("AVG_DURATION");
        assertThat(row.avgDurationMillis()).isGreaterThan(0.0).isCloseTo(1.0 / 3_000.0, within(1e-12));
    }

    @Test
    void marksARowWhoseDeepLinkIdsWereTruncated() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < SqlStatementAggregate.MAX_LINKED_ENTRIES + 25; i++) {
            entries.add(entry("select * from busy where id = " + i).lasting(1).build());
        }

        SqlStatementRankingDto row =
                SqlStatementRanking.rank(entries, N_PLUS_ONE).statements().get(0);

        assertThat(row.entryIds()).hasSize(SqlStatementAggregate.MAX_LINKED_ENTRIES);
        assertThat(row.entryIdsTruncated()).isTrue();
        assertThat(row.executions()).isEqualTo(SqlStatementAggregate.MAX_LINKED_ENTRIES + 25L);
    }

    @Test
    void doesNotMarkTruncationWhenEveryExecutionIsDeepLinkable() {
        SqlStatementRankingDto row = SqlStatementRanking.rank(
                        List.of(entry("select 1").build(), entry("select 1").build()), N_PLUS_ONE)
                .statements()
                .get(0);

        assertThat(row.entryIdsTruncated()).isFalse();
        assertThat(row.entryIds()).hasSize(2);
    }

    @Test
    void boundsTheRankedUnionAcrossEveryCriterion() {
        List<SqlTraceEntryDto> entries = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            SqlTraceEntryFixtures.Builder builder = entry("select * from t" + i).lasting(i);
            if (i % 3 == 0) {
                builder.failed();
            }
            entries.add(builder.build());
        }

        SqlStatementRanking.Ranked ranked = SqlStatementRanking.rank(entries, N_PLUS_ONE);

        assertThat(ranked.distinct()).isEqualTo(400);
        assertThat(ranked.statements()).hasSizeLessThanOrEqualTo(SqlStatementRanking.MAX_RANKED_STATEMENTS);
        assertThat(ranked.truncated()).isTrue();
    }

    private static SqlStatementRankingDto row(SqlStatementRanking.Ranked ranked, String sql) {
        return ranked.statements().stream()
                .filter(candidate -> candidate.sql().equals(sql))
                .max(Comparator.comparingLong(SqlStatementRankingDto::executions))
                .orElseThrow(() -> new AssertionError("no ranked row for " + sql));
    }
}
