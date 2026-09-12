package io.github.jdubois.bootui.engine.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PostgresHelpersTests {

    @Test
    void formatHandlesNullsUnitsAndRelationsDeterministically() {
        assertThat(PostgresFormat.percent(null)).isEqualTo("unknown");
        assertThat(PostgresFormat.percent(0.901)).isEqualTo("90.1%");
        assertThat(PostgresFormat.millis(12.345)).isEqualTo("12.3 ms");
        assertThat(PostgresFormat.seconds(null)).isEqualTo("unknown");
        assertThat(PostgresFormat.bytes(null)).isEqualTo("unknown");
        assertThat(PostgresFormat.bytes(1023L)).isEqualTo("1023 B");
        assertThat(PostgresFormat.bytes(1024L * 1024L)).isEqualTo("1.0 MB");
        assertThat(PostgresFormat.count(42L)).isEqualTo("42");
        assertThat(PostgresFormat.relation("public", "orders")).isEqualTo("public.orders");
        assertThat(PostgresFormat.relation(" ", "orders")).isEqualTo("orders");
    }

    @Test
    void readBudgetReportsExhaustionAndClampsTimeouts() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        PostgresReadBudget budget = PostgresReadBudget.of(Duration.ofSeconds(5), now::get);

        assertThat(budget.exhausted()).isFalse();
        assertThat(budget.remainingSecondsAtMost(10)).isEqualTo(5);
        assertThat(budget.remainingSecondsAtMost(2)).isEqualTo(2);

        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.remainingSecondsAtMost(10)).isEqualTo(1);
    }

    @Test
    void queryTextAppliesExposureRedactionWhitespaceAndTruncation() {
        String query = "select  *\nfrom users where password='secret' and url='******db/orders'";

        assertThat(PostgresQueryText.sanitize(query, exposure(ValueExposure.METADATA_ONLY, true), 200))
                .isEqualTo("******");
        assertThat(PostgresQueryText.sanitize(query, exposure(ValueExposure.MASKED, true), 200))
                .contains("password='******'")
                .doesNotContain("secret")
                .doesNotContain("u:p");
        assertThat(PostgresQueryText.sanitize(query, exposure(ValueExposure.FULL, false), 30))
                .hasSize(30)
                .endsWith("…")
                .doesNotContain("u:p");
        assertThat(PostgresQueryText.sanitize(" ", exposure(ValueExposure.FULL, false), 30))
                .isNull();
    }

    @Test
    void queryTextMasksDollarQuotedBodies() {
        ExposurePolicy masked = exposure(ValueExposure.MASKED, true);

        assertThat(PostgresQueryText.sanitize(
                        "do $$ begin perform set_config('x', 'secret', false); end $$", masked, 200))
                .doesNotContain("secret")
                .contains("do $$******$$");
        assertThat(PostgresQueryText.sanitize(
                        "create function f() returns int as $body$ select 42 $body$ language sql", masked, 200))
                .doesNotContain("42")
                .contains("language sql");
        assertThat(PostgresQueryText.sanitize("do $$ unterminated body", masked, 200))
                .doesNotContain("unterminated");
    }

    @Test
    void queryTextMasksEscapeStringsInsteadOfStoppingAtTheEscapedQuote() {
        ExposurePolicy masked = exposure(ValueExposure.MASKED, true);

        // In an E'' string a backslash escapes the quote, so matching it with the standard rule ends the
        // literal early and publishes everything that followed it — here, the password.
        assertThat(PostgresQueryText.sanitize(
                        "select E'it\\'s' as note, password from users where password='hunter2'", masked, 200))
                .doesNotContain("hunter2")
                .doesNotContain("it\\'s");
        // A statement cut off mid-literal by track_activity_query_size must mask to the end, not give up.
        assertThat(PostgresQueryText.sanitize("select 'unterminated secret", masked, 200))
                .doesNotContain("secret");
        // The E must not be taken from the end of an identifier, which would corrupt the statement shown.
        assertThat(PostgresQueryText.sanitize("select * from t where name like'a%'", masked, 200))
                .contains("like'******'");
    }

    @Test
    void vacuumDueHonoursPerTableReloptionsOverrides() {
        // A table that sets its own autovacuum_vacuum_threshold is judged by that number, not the cluster's.
        assertThat(PostgresVacuumCollector.override("10", 50d)).isEqualTo(10d);
        assertThat(PostgresVacuumCollector.override(null, 50d)).isEqualTo(50d);
        assertThat(PostgresVacuumCollector.override("not-a-number", 50d)).isEqualTo(50d);

        // PostgreSQL accepts every unambiguous spelling of false for a boolean storage parameter.
        assertThat(PostgresVacuumCollector.isFalse("false")).isTrue();
        assertThat(PostgresVacuumCollector.isFalse("off")).isTrue();
        assertThat(PostgresVacuumCollector.isFalse("n")).isTrue();
        assertThat(PostgresVacuumCollector.isFalse("0")).isTrue();
        assertThat(PostgresVacuumCollector.isFalse("true")).isFalse();
        assertThat(PostgresVacuumCollector.isFalse(null)).isFalse();
        // "o" is ambiguous between on and off; PostgreSQL rejects it, and it must not read as disabled.
        assertThat(PostgresVacuumCollector.isFalse("o")).isFalse();
    }

    @Test
    void vacuumThresholdFloorsSoTheComparisonMatchesPostgreSql() {
        // PostgreSQL compares an integer dead count against the fractional threshold + scale_factor * tuples.
        // 50 + 0.19992 * 5000 = 1049.6: 1,050 dead tuples are due, and rounding up to 1,050 would miss that.
        assertThat(PostgresVacuumCollector.vacuumThreshold(5000L, 50d, 0.19992d, -1d))
                .isEqualTo(1049L);
        assertThat(PostgresVacuumCollector.vacuumThreshold(1000L, 50d, 0.2d, -1d))
                .isEqualTo(250L);
        assertThat(PostgresVacuumCollector.vacuumThreshold(null, 50d, 0.2d, -1d))
                .isNull();
    }

    @Test
    void vacuumThresholdHonoursThePostgreSql18Cap() {
        // PostgreSQL 18 caps the scaled threshold with autovacuum_vacuum_max_threshold. Ignoring the cap
        // would report a huge table as not yet due when PostgreSQL considers it due.
        assertThat(PostgresVacuumCollector.vacuumThreshold(1_000_000_000L, 50d, 0.2d, 100_000_000d))
                .isEqualTo(100_000_000L);
        // A negative cap means the setting is disabled, and an older server has no such setting at all.
        assertThat(PostgresVacuumCollector.vacuumThreshold(1_000_000_000L, 50d, 0.2d, -1d))
                .isEqualTo(200_000_050L);
    }

    @Test
    void rowsDistinguishEmptyAvailableFromFailedAndCopyRows() {
        List<String> mutable = new java.util.ArrayList<>(List.of("one"));
        PostgresRows<String> rows = PostgresRows.available(mutable, true);
        mutable.add("two");

        assertThat(rows.available()).isTrue();
        assertThat(rows.rows()).containsExactly("one");
        assertThat(rows.truncated()).isTrue();
        assertThat(rows.empty()).isFalse();

        PostgresRows<String> failed = PostgresRows.failed("permission denied");
        assertThat(failed.available()).isFalse();
        assertThat(failed.empty()).isTrue();
        assertThat(failed.reason()).isEqualTo("permission denied");
    }

    @Test
    void defaultLimitsKeepTimeoutAtLeastOneSecond() {
        assertThat(new PostgresInsightLimits(
                                1, 1, 1, 1, 1, 1, 1, 10, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO)
                        .statementTimeoutSeconds())
                .isEqualTo(1);
    }

    private static ExposurePolicy exposure(ValueExposure valueExposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return valueExposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }
}
