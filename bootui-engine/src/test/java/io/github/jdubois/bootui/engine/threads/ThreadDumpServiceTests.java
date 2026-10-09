package io.github.jdubois.bootui.engine.threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.core.dto.ThreadInfoDto;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;

/**
 * Slice tests for {@link ThreadDumpService}. The service reads the live JVM via
 * {@code ThreadMXBean}, so the running test thread itself provides deterministic data. The
 * exposure decision is supplied through a fixed {@link ExposurePolicy} double so the test stays
 * framework-neutral (no Spring environment binding).
 */
class ThreadDumpServiceTests {

    private final ExposurePolicy masked = new FixedExposure(ValueExposure.MASKED, true);

    @Test
    void reportCapturesLiveThreadsWithSummary() {
        ThreadDumpService service = new ThreadDumpService(masked);

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
        ThreadDumpService service = new ThreadDumpService(masked);
        Thread current = Thread.currentThread();

        ThreadDumpReport byName = service.report(current.getName(), null, null, null);
        assertThat(byName.threads()).anyMatch(t -> t.name().equals(current.getName()));

        ThreadDumpReport byState = service.report(null, "runnable", null, null);
        assertThat(byState.threads()).allMatch(t -> t.state().equals("RUNNABLE"));
    }

    @Test
    void reportPagesResults() {
        ThreadDumpService service = new ThreadDumpService(masked);

        ThreadDumpReport firstPage = service.report(null, null, 0, 1);

        assertThat(firstPage.threads()).hasSize(1);
        assertThat(firstPage.page().limit()).isEqualTo(1);
        assertThat(firstPage.page().total()).isGreaterThan(1);
        assertThat(firstPage.page().hasMore()).isTrue();
    }

    @Test
    void metadataOnlyModeHidesStackTraces() {
        ThreadDumpService service = new ThreadDumpService(new FixedExposure(ValueExposure.METADATA_ONLY, true));

        ThreadDumpReport report = service.report(null, null, null, null);

        assertThat(report.threads()).allMatch(t -> t.stackTrace().isEmpty());
        assertThat(report.threads()).allMatch(t -> t.lockName() == null);
    }

    @Test
    void defaultModeIncludesStackTraces() {
        ThreadDumpService service = new ThreadDumpService(masked);

        ThreadDumpReport report = service.report(Thread.currentThread().getName(), null, null, null);

        assertThat(report.threads())
                .filteredOn(t -> t.name().equals(Thread.currentThread().getName()))
                .anySatisfy(t -> assertThat(t.stackTrace()).isNotEmpty());
    }

    @Test
    void rawDumpRendersThreadNames() {
        ThreadDumpService service = new ThreadDumpService(masked);

        String dump = service.rawDump();

        assertThat(dump).isNotNull();
        assertThat(dump).contains("BootUI thread dump");
        assertThat(dump).contains(Thread.currentThread().getName());
    }

    @Test
    void unavailableWhenThreadMxBeanMissing() {
        ThreadDumpService service = new ThreadDumpService(null, masked);

        assertThat(service.available()).isFalse();
        ThreadDumpReport report = service.report(null, null, null, null);
        assertThat(report.available()).isFalse();
        assertThat(report.unavailableReason()).isNotNull();
        assertThat(report.threads()).isEmpty();
        assertThat(service.rawDump()).isNull();
    }

    @Test
    void capturedThreadsExposeBasicMetadata() {
        ThreadDumpService service = new ThreadDumpService(masked);

        ThreadDumpReport report = service.report(null, null, null, null);

        ThreadInfoDto first = report.threads().get(0);
        assertThat(first.id()).isPositive();
        assertThat(first.name()).isNotBlank();
        assertThat(first.state()).isNotBlank();
    }

    @Test
    void peakThreadsIsNeverBelowTheLiveThreadsOfTheSameSnapshot() {
        ThreadMXBean real = ManagementFactory.getThreadMXBean();
        // The peak counter is read separately from the dump; a stale or reset counter must not undercut it.
        ThreadMXBean stalePeak = mock(ThreadMXBean.class, AdditionalAnswers.delegatesTo(real));
        doReturn(1).when(stalePeak).getPeakThreadCount();

        ThreadDumpReport report = new ThreadDumpService(stalePeak, masked).report(null, null, null, null);

        assertThat(report.totalThreads()).isGreaterThan(1);
        assertThat(report.peakThreads()).isEqualTo(report.totalThreads());
        ThreadDumpReport live = new ThreadDumpService(masked).report(null, null, null, null);
        assertThat(live.peakThreads()).isGreaterThanOrEqualTo(live.totalThreads());
    }

    /**
     * Minimal {@link ExposurePolicy} test double; the record accessors satisfy the interface.
     */
    private record FixedExposure(ValueExposure valueExposure, boolean maskSecrets) implements ExposurePolicy {}
}
