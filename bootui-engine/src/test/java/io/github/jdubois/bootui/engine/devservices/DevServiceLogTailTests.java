package io.github.jdubois.bootui.engine.devservices;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.DevServiceLogReport;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DevServiceLogTailTests {

    private static final String LOGS = "starting\nPOSTGRES_PASSWORD=pg-secret\nready token: tok-7\n";

    @Test
    void masksSecretAssignmentsInContainerLogsByDefault() {
        DevServiceLogReport report =
                DevServiceLogTail.report("db", () -> LOGS, 1024, policy(ValueExposure.MASKED, true));

        assertThat(report.id()).isEqualTo("db");
        assertThat(report.logs())
                .isEqualTo("starting\nPOSTGRES_PASSWORD=******\nready token: ******\n")
                .doesNotContain("pg-secret", "tok-7");
        assertThat(report.truncated()).isFalse();
        assertThat(report.maxBytes()).isEqualTo(1024);
        assertThat(report.logsOmitted()).isFalse();
    }

    @Test
    void masksTheCredentialAfterAnAuthorizationSchemeBeforeTheTailIsCut() {
        String logs = "starting\nproxy sent Authorization: Basic dXNlcjpwYXNzd29yZA==\nready\n";

        DevServiceLogReport report =
                DevServiceLogTail.report("proxy", () -> logs, 39, policy(ValueExposure.MASKED, true));

        assertThat(report.logs())
                .isEqualTo("sent Authorization: Basic ******\nready\n")
                .doesNotContain("dXNlcjpwYXNz");
        assertThat(report.truncated()).isTrue();
    }

    @Test
    void omitsLogsUnderMetadataOnlyWithoutReadingTheContainer() {
        AtomicInteger reads = new AtomicInteger();

        DevServiceLogReport report = DevServiceLogTail.report(
                "db",
                () -> {
                    reads.incrementAndGet();
                    return LOGS;
                },
                1024,
                policy(ValueExposure.METADATA_ONLY, true));

        assertThat(report).isEqualTo(new DevServiceLogReport("db", null, false, 1024, true));
        assertThat(reads).hasValue(0);
    }

    @Test
    void returnsLogsVerbatimUnderFullOrWithMaskingOff() {
        assertThat(DevServiceLogTail.report("db", () -> LOGS, 1024, policy(ValueExposure.FULL, true))
                        .logs())
                .isEqualTo(LOGS);
        assertThat(DevServiceLogTail.report("db", () -> LOGS, 1024, policy(ValueExposure.MASKED, false))
                        .logs())
                .isEqualTo(LOGS);
    }

    @Test
    void treatsMissingLogsAsEmpty() {
        DevServiceLogReport report =
                DevServiceLogTail.report("db", () -> null, 1024, policy(ValueExposure.MASKED, true));

        assertThat(report.logs()).isEmpty();
        assertThat(report.logsOmitted()).isFalse();
    }

    @Test
    void masksBeforeCuttingSoATailNeverKeepsAValueWithoutItsKey() {
        String secretValue = "x".repeat(40);
        String logs = "a".repeat(100) + " password=" + secretValue + "\nlast line";
        int maxBytes = secretValue.length() + "\nlast line".length() - 5;

        DevServiceLogReport masked =
                DevServiceLogTail.report("db", () -> logs, maxBytes, policy(ValueExposure.MASKED, true));
        DevServiceLogReport full =
                DevServiceLogTail.report("db", () -> logs, maxBytes, policy(ValueExposure.FULL, true));

        assertThat(full.logs()).as("the raw cut lands inside the value").contains("xxxxx");
        assertThat(masked.truncated()).isTrue();
        assertThat(masked.logs()).doesNotContain("x").endsWith("******\nlast line");
    }

    @Test
    void truncatesToTheNewestBytesWithoutSplittingACodePoint() {
        String logs = "é".repeat(10);

        DevServiceLogReport report = DevServiceLogTail.report("db", () -> logs, 5, policy(ValueExposure.FULL, true));

        assertThat(report.truncated()).isTrue();
        assertThat(report.logs()).isEqualTo("éé");
    }

    private static ExposurePolicy policy(ValueExposure exposure, boolean maskSecrets) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return maskSecrets;
            }
        };
    }
}
