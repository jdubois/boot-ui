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

    private static final String HISTOGRAM_WITH_LARGE_OBJECT = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:          1000          80000  [B (java.base@25)
               2:           500          24000  java.lang.String (java.base@25)
               3:           100           4000  java.util.HashMap$Node (java.base@25)
               4:             1           5000  com.example.LargeCache (none)
            Total          1601         113000
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

    private HeapDumpService serviceWithHistogram(Path dir, String histogram) {
        return new HeapDumpService(
                config(),
                dir,
                (file, live) -> Files.writeString(file, "fake-hprof"),
                () -> histogram,
                Clock.fixed(Instant.parse("2026-05-31T05:00:00Z"), ZoneOffset.UTC),
                true);
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
    void filterByPrefixReturnsOnlyMatchingClasses(@TempDir Path dir) {
        HeapDumpService service = service(config(), dir, true);
        service.analyze();

        HeapDumpReport filtered = service.report("java.lang");
        assertThat(filtered.topClasses()).hasSize(1);
        assertThat(filtered.topClasses().get(0).className()).isEqualTo("java.lang.String");
        // rank is preserved from the full heap-wide ordering (java.lang.String is rank 2)
        assertThat(filtered.topClasses().get(0).rank()).isEqualTo(2);
    }

    @Test
    void filterWithNoMatchReturnsEmptyList(@TempDir Path dir) {
        HeapDumpService service = service(config(), dir, true);
        service.analyze();

        HeapDumpReport filtered = service.report("com.myapp");
        assertThat(filtered.topClasses()).isEmpty();
    }

    @Test
    void filterDoesNotAffectHistogramTotals(@TempDir Path dir) {
        HeapDumpService service = service(config(), dir, true);
        HeapDumpReport full = service.analyze();

        HeapDumpReport filtered = service.report("java.lang");
        assertThat(filtered.histogramTotalInstances()).isEqualTo(full.histogramTotalInstances());
        assertThat(filtered.histogramTotalBytes()).isEqualTo(full.histogramTotalBytes());
    }

    @Test
    void blankFilterReturnsSameAsNoFilter(@TempDir Path dir) {
        HeapDumpService service = service(config(), dir, true);
        service.analyze();

        assertThat(service.report("").topClasses()).isEqualTo(service.report().topClasses());
        assertThat(service.report("   ").topClasses()).isEqualTo(service.report().topClasses());
    }

    @Test
    void maxClassesLimitsEntriesStoredInMemory(@TempDir Path dir) {
        BootUiProperties.HeapDump config = config();
        config.setMaxClasses(2);
        HeapDumpService service = service(config, dir, true);
        HeapDumpReport report = service.analyze();

        // Only the top-2 classes by bytes are kept; topClasses defaults to 25 so all stored are shown
        assertThat(report.topClasses()).hasSize(2);
        assertThat(report.topClasses().get(0).className()).isEqualTo("[B");
        assertThat(report.topClasses().get(1).className()).isEqualTo("java.lang.String");
    }

    @Test
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

    @Test
    void bigObjectsSmartFilterSortsByBytesPerInstance(@TempDir Path dir) {
        // HISTOGRAM_WITH_LARGE_OBJECT has com.example.LargeCache: 1 instance, 5000 bytes
        // → 5000 bytes/instance, biggest per-object even though total bytes is small
        HeapDumpService service = serviceWithHistogram(dir, HISTOGRAM_WITH_LARGE_OBJECT);
        service.analyze();

        HeapDumpReport report = service.report("", HeapDumpService.SMART_FILTER_BIG_OBJECTS);

        assertThat(report.topClasses()).isNotEmpty();
        assertThat(report.topClasses().get(0).className()).isEqualTo("com.example.LargeCache");
        assertThat(report.topClasses().get(1).className()).isEqualTo("[B");
        assertThat(report.topClasses().get(2).className()).isEqualTo("java.lang.String");
    }

    @Test
    void collectionBloatSmartFilterReturnsOnlyCollectionClasses(@TempDir Path dir) {
        // HISTOGRAM has java.util.HashMap$Node (collection inner class) plus [B and java.lang.String
        HeapDumpService service = service(config(), dir, true);
        service.analyze();

        HeapDumpReport report = service.report("", HeapDumpService.SMART_FILTER_COLLECTION_BLOAT);

        assertThat(report.topClasses()).hasSize(1);
        assertThat(report.topClasses().get(0).className()).isEqualTo("java.util.HashMap$Node");
    }

    @Test
    void smartFilterAndPrefixFilterCombine(@TempDir Path dir) {
        HeapDumpService service = serviceWithHistogram(dir, HISTOGRAM_WITH_LARGE_OBJECT);
        service.analyze();

        // big-objects + text prefix "java" should return only java.lang.String (rank 3 per-instance)
        HeapDumpReport report = service.report("java", HeapDumpService.SMART_FILTER_BIG_OBJECTS);

        assertThat(report.topClasses()).hasSize(1);
        assertThat(report.topClasses().get(0).className()).isEqualTo("java.lang.String");
    }
}
