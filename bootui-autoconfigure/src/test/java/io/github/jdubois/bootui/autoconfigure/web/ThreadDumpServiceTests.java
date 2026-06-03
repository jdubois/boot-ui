package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import org.junit.jupiter.api.Test;

/**
 * Slice tests for {@link ThreadDumpService}. The service reads the live JVM via
 * {@code ThreadMXBean}, so the running test thread itself provides deterministic data.
 */
class ThreadDumpServiceTests {

    private final BootUiProperties properties = new BootUiProperties();

    @Test
    void reportCapturesLiveThreadsWithSummary() {
        ThreadDumpService service = new ThreadDumpService(properties);

        ThreadDumpReport report = service.report(null, null, null, null);

        assertThat(report.available()).isTrue();
        assertThat(report.unavailableReason()).isNull();
        assertThat(report.capturedAt()).isNotNull();
        assertThat(report.totalThreads()).isPositive();
        assertThat(report.threads()).isNotEmpty();
        assertThat(report.stateCounts()).isNotEmpty();
        assertThat(report.stateCounts().stream().mapToInt(c -> c.count()).sum()).isEqualTo(report.totalThreads());
        assertThat(report.page().total()).isEqualTo(report.totalThreads());
    }

    @Test
    void reportFiltersByQueryAndState() {
        ThreadDumpService service = new ThreadDumpService(properties);
        Thread current = Thread.currentThread();

        ThreadDumpReport byName = service.report(current.getName(), null, null, null);
        assertThat(byName.threads()).anyMatch(t -> t.name().equals(current.getName()));

        ThreadDumpReport byState = service.report(null, "runnable", null, null);
        assertThat(byState.threads()).allMatch(t -> t.state().equals("RUNNABLE"));
    }

    @Test
    void reportPagesResults() {
        ThreadDumpService service = new ThreadDumpService(properties);

        ThreadDumpReport firstPage = service.report(null, null, 0, 1);

        assertThat(firstPage.threads()).hasSize(1);
        assertThat(firstPage.page().limit()).isEqualTo(1);
        assertThat(firstPage.page().total()).isGreaterThan(1);
        assertThat(firstPage.page().hasMore()).isTrue();
    }

    @Test
    void metadataOnlyModeHidesStackTraces() {
        properties.setExposeValues(ValueExposure.METADATA_ONLY);
        ThreadDumpService service = new ThreadDumpService(properties);

        ThreadDumpReport report = service.report(null, null, null, null);

        assertThat(report.threads()).allMatch(t -> t.stackTrace().isEmpty());
        assertThat(report.threads()).allMatch(t -> t.lockName() == null);
    }

    @Test
    void defaultModeIncludesStackTraces() {
        ThreadDumpService service = new ThreadDumpService(properties);

        ThreadDumpReport report = service.report(Thread.currentThread().getName(), null, null, null);

        assertThat(report.threads())
                .filteredOn(t -> t.name().equals(Thread.currentThread().getName()))
                .anySatisfy(t -> assertThat(t.stackTrace()).isNotEmpty());
    }

    @Test
    void rawDumpRendersThreadNames() {
        ThreadDumpService service = new ThreadDumpService(properties);

        String dump = service.rawDump();

        assertThat(dump).isNotNull();
        assertThat(dump).contains("BootUI thread dump");
        assertThat(dump).contains(Thread.currentThread().getName());
    }

    @Test
    void unavailableWhenThreadMxBeanMissing() {
        ThreadDumpService service = new ThreadDumpService(null, properties);

        assertThat(service.available()).isFalse();
        ThreadDumpReport report = service.report(null, null, null, null);
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isNotNull();
        assertThat(report.threads()).isEmpty();
        assertThat(service.rawDump()).isNull();
    }

    @Test
    void capturedThreadsExposeBasicMetadata() {
        ThreadDumpService service = new ThreadDumpService(properties);

        ThreadDumpReport report = service.report(null, null, null, null);

        ThreadInfoDto first = report.threads().get(0);
        assertThat(first.id()).isPositive();
        assertThat(first.name()).isNotBlank();
        assertThat(first.state()).isNotBlank();
    }
}
