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
        assertThat(new PostgresInsightLimits(1, 1, 1, 1, 1, 1, 10, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO)
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
