package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryCollectorTests {

    @TempDir
    Path tempDir;

    @Test
    void latestGcEventIsSelectedByCompletionTimestampNotLongestDuration() {
        MemoryCollector.LastGcEvent latest = MemoryCollector.latestGcEvent(List.of(
                new MemoryCollector.LastGcEventCandidate(2_000, 1_500, "G1 Old Generation"),
                new MemoryCollector.LastGcEventCandidate(5_000, 25, "G1 Young Generation")));

        assertThat(latest.durationMillis()).isEqualTo(25);
        assertThat(latest.collectorName()).isEqualTo("G1 Young Generation");
    }

    @Test
    void cgroupLimitAndCurrentUsageShareTheSamePathReader() throws Exception {
        Path limit = tempDir.resolve("memory.max");
        Path current = tempDir.resolve("memory.current");
        Files.writeString(limit, "1073741824\n");
        Files.writeString(current, "536870912\n");
        ContainerMemoryLimitDetector detector = new ContainerMemoryLimitDetector(List.of(limit), List.of(current));

        assertThat(detector.detectLimit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(detector.detectCurrentUsage()).isEqualTo(OptionalLong.of(536_870_912L));
    }
}
