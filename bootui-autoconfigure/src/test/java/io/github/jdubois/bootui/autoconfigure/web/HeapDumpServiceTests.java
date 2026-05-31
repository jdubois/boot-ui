package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.HeapDumpReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapDumpServiceTests {

    private static final String HISTOGRAM = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:          1000          80000  [B (java.base@25)
               2:           500          24000  java.lang.String (java.base@25)
               3:           100           4000  java.util.HashMap$Node (java.base@25)
            Total          1600         108000
            """;

    private BootUiProperties.HeapDump config() {
        return new BootUiProperties.HeapDump();
    }

    private HeapDumpService service(BootUiProperties.HeapDump config, Path dir, boolean hotspot) {
        return new HeapDumpService(
                config,
                dir,
                (file, live) -> Files.writeString(file, "fake-hprof"),
                () -> HISTOGRAM,
                Clock.fixed(Instant.parse("2026-05-31T05:00:00Z"), ZoneOffset.UTC),
                hotspot);
    }

    @Test
    void reportStartsEmptyWithoutAnalysis(@TempDir Path dir) {
        HeapDumpReport report = service(config(), dir, true).report();

        assertThat(report.hotspotAvailable()).isTrue();
        assertThat(report.captureEnabled()).isTrue();
        assertThat(report.rawDownloadEnabled()).isFalse();
        assertThat(report.dumpCount()).isZero();
        assertThat(report.topClasses()).isEmpty();
        assertThat(report.capture().status()).isEqualTo("NOT_CAPTURED");
    }

    @Test
    void analyzeBuildsHistogramSortedByRetainedSize(@TempDir Path dir) {
        HeapDumpReport report = service(config(), dir, true).analyze();

        assertThat(report.capture().status()).isEqualTo("ANALYZED");
        assertThat(report.histogramTotalInstances()).isEqualTo(1600);
        assertThat(report.histogramTotalBytes()).isEqualTo(108000);
        assertThat(report.topClasses()).hasSize(3);
        assertThat(report.topClasses().get(0).className()).isEqualTo("[B");
        assertThat(report.topClasses().get(0).rank()).isEqualTo(1);
        assertThat(report.topClasses().get(0).bytes()).isEqualTo(80000);
        assertThat(report.dumpCount()).isZero();
    }

    @Test
    void captureWritesDumpAndRefreshesHistogram(@TempDir Path dir) {
        HeapDumpReport report = service(config(), dir, true).capture(true);

        assertThat(report.capture().status()).isEqualTo("CAPTURED");
        assertThat(report.dumps()).hasSize(1);
        assertThat(report.dumps().get(0).name()).endsWith(".hprof");
        assertThat(report.dumps().get(0).live()).isTrue();
        assertThat(report.topClasses()).hasSize(3);
    }

    @Test
    void captureRejectedWhenCaptureDisabled(@TempDir Path dir) {
        BootUiProperties.HeapDump config = config();
        config.setCaptureEnabled(false);

        HeapDumpReport report = service(config, dir, true).capture(true);

        assertThat(report.capture().status()).isEqualTo("ERROR");
        assertThat(report.capture().message()).contains("capture-enabled=false");
        assertThat(report.dumps()).isEmpty();
    }

    @Test
    void captureRejectedWhenHotspotUnavailable(@TempDir Path dir) {
        HeapDumpReport report = service(config(), dir, false).capture(true);

        assertThat(report.hotspotAvailable()).isFalse();
        assertThat(report.capture().status()).isEqualTo("ERROR");
        assertThat(report.dumps()).isEmpty();
    }

    @Test
    void evictsOldestDumpsBeyondMax(@TempDir Path dir) {
        BootUiProperties.HeapDump config = config();
        config.setMaxDumps(2);
        HeapDumpService service = service(config, dir, true);

        service.capture(true);
        service.capture(false);
        HeapDumpReport report = service.capture(true);

        assertThat(report.dumps()).hasSize(2);
    }

    @Test
    void deleteRemovesNamedDump(@TempDir Path dir) {
        HeapDumpService service = service(config(), dir, true);
        String name = service.capture(true).dumps().get(0).name();

        HeapDumpReport report = service.delete(name);

        assertThat(report.dumps()).isEmpty();
    }

    @Test
    void deleteRejectsPathTraversal(@TempDir Path dir) {
        HeapDumpService service = service(config(), dir, true);
        service.capture(true);

        HeapDumpReport report = service.delete("../escape.hprof");

        assertThat(report.capture().status()).isEqualTo("ERROR");
        assertThat(report.dumps()).hasSize(1);
    }

    @Test
    void resolveExistingRejectsUnsafeNames(@TempDir Path dir) {
        HeapDumpService service = service(config(), dir, true);
        String name = service.capture(true).dumps().get(0).name();

        assertThat(service.resolveExisting(name)).isNotNull();
        assertThat(service.resolveExisting("../" + name)).isNull();
        assertThat(service.resolveExisting("missing.hprof")).isNull();
        assertThat(service.resolveExisting("evil.txt")).isNull();
        assertThat(service.resolveExisting(null)).isNull();
    }
}
