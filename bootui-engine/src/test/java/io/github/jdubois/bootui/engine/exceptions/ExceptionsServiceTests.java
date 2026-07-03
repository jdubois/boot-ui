package io.github.jdubois.bootui.engine.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import org.junit.jupiter.api.Test;

class ExceptionsServiceTests {

    private static ExposurePolicy policy(ValueExposure exposure, boolean mask) {
        return new ExposurePolicy() {
            @Override
            public ValueExposure valueExposure() {
                return exposure;
            }

            @Override
            public boolean maskSecrets() {
                return mask;
            }
        };
    }

    @Test
    void assemblesGroupedReport() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", "GET", "/x", "Handler#x", "web");

        ExceptionsReport report = new ExceptionsService(policy(ValueExposure.FULL, true)).report(store);

        assertThat(report.available()).isTrue();
        assertThat(report.totalExceptions()).isEqualTo(1);
        assertThat(report.groups()).hasSize(1);
        assertThat(report.groups().get(0).message()).isEqualTo("boom");
    }

    @Test
    void surfacesLastTraceIdFromOccurrence() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "worker-1", "GET", "/x", "Handler#x", "web", "trace-a");

        ExceptionsReport report = new ExceptionsService(policy(ValueExposure.FULL, true)).report(store);

        assertThat(report.groups().get(0).lastTraceId()).isEqualTo("trace-a");
    }

    @Test
    void lastTraceIdIsNullWhenNoneRecorded() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", "GET", "/x", "Handler#x", "web");

        ExceptionsReport report = new ExceptionsService(policy(ValueExposure.FULL, true)).report(store);

        assertThat(report.groups().get(0).lastTraceId()).isNull();
    }

    @Test
    void masksSecretAssignmentsInMaskedMode() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("auth failed password=hunter2"), "main", null, null, null, "log");

        ExceptionGroupDto group = new ExceptionsService(policy(ValueExposure.MASKED, true))
                .report(store)
                .groups()
                .get(0);

        assertThat(group.message())
                .contains("password=")
                .doesNotContain("hunter2")
                .contains("******");
    }

    @Test
    void omitsMessageInMetadataOnlyMode() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");

        assertThat(new ExceptionsService(policy(ValueExposure.METADATA_ONLY, true))
                        .report(store)
                        .groups()
                        .get(0)
                        .message())
                .isNull();
    }

    @Test
    void reportSurfacesStatusAndRegressionCount() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");

        ExceptionGroupDto group = new ExceptionsService(policy(ValueExposure.FULL, true))
                .report(store)
                .groups()
                .get(0);

        assertThat(group.status()).isEqualTo("OPEN");
        assertThat(group.regressionCount()).isZero();
    }

    @Test
    void parseStatusAcceptsValidValuesCaseInsensitively() {
        assertThat(ExceptionsService.parseStatus("open")).isEqualTo(ExceptionStore.Status.OPEN);
        assertThat(ExceptionsService.parseStatus("Acknowledged")).isEqualTo(ExceptionStore.Status.ACKNOWLEDGED);
        assertThat(ExceptionsService.parseStatus("RESOLVED")).isEqualTo(ExceptionStore.Status.RESOLVED);
    }

    @Test
    void parseStatusRejectsBlankOrUnknownValues() {
        assertThatThrownBy(() -> ExceptionsService.parseStatus(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExceptionsService.parseStatus("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExceptionsService.parseStatus("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExceptionsService.parseStatus("CLOSED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateStatusReturnsTheMappedDtoOnSuccess() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");
        String fingerprint = store.groups().get(0).fingerprint();

        ExceptionGroupDto updated =
                new ExceptionsService(policy(ValueExposure.FULL, true)).updateStatus(store, fingerprint, "resolved");

        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo("RESOLVED");
        assertThat(updated.regressionCount()).isZero();
    }

    @Test
    void updateStatusReturnsNullForAnUnknownStoreOrFingerprint() {
        ExceptionsService service = new ExceptionsService(policy(ValueExposure.FULL, true));

        assertThat(service.updateStatus(null, "abc", "OPEN")).isNull();

        ExceptionStore store = new ExceptionStore(100, 25, 50);
        assertThat(service.updateStatus(store, "does-not-exist", "OPEN")).isNull();
    }

    @Test
    void updateStatusValidatesBeforeCheckingExistenceSoAnInvalidStatusAlwaysYieldsAnException() {
        ExceptionsService service = new ExceptionsService(policy(ValueExposure.FULL, true));

        assertThatThrownBy(() -> service.updateStatus(null, "unknown-fingerprint", "CLOSED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
