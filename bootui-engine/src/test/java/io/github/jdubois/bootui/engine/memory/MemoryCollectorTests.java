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

    @Test
    void resolvesNestedCgroupV2PathsAndComputesWorkingSet() throws Exception {
        Path mountPoint = tempDir.resolve("cgroup2");
        Path processCgroup = mountPoint.resolve("kubepods/pod-a");
        Files.createDirectories(processCgroup);
        Files.writeString(processCgroup.resolve("memory.max"), "1073741824\n");
        Files.writeString(processCgroup.resolve("memory.current"), "805306368\n");
        Files.writeString(processCgroup.resolve("memory.stat"), "inactive_file 268435456\n");
        Path selfCgroup = tempDir.resolve("self.cgroup");
        Path mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(selfCgroup, "0::/kubepods/pod-a\n");
        Files.writeString(mountInfo, "29 23 0:26 / " + mountPoint + " rw - cgroup2 cgroup rw\n");

        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);
        ContainerMemoryLimitDetector.CgroupMemorySample sample = detector.detect();

        assertThat(sample.limit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(sample.current()).isEqualTo(OptionalLong.of(805_306_368L));
        assertThat(sample.workingSet()).isEqualTo(OptionalLong.of(536_870_912L));
    }

    @Test
    void usesTheMostRestrictiveFiniteParentCgroupLimit() throws Exception {
        Path mountPoint = tempDir.resolve("cgroup2");
        Path parentCgroup = mountPoint.resolve("kubepods");
        Path processCgroup = parentCgroup.resolve("pod-a");
        Files.createDirectories(processCgroup);
        Files.writeString(processCgroup.resolve("memory.max"), "2147483648\n");
        Files.writeString(processCgroup.resolve("memory.current"), "536870912\n");
        Files.writeString(parentCgroup.resolve("memory.max"), "1073741824\n");
        Path selfCgroup = tempDir.resolve("self.cgroup");
        Path mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(selfCgroup, "0::/kubepods/pod-a\n");
        Files.writeString(mountInfo, "29 23 0:26 / " + mountPoint + " rw - cgroup2 cgroup rw\n");

        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);

        assertThat(detector.detectLimit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(detector.detectCurrentUsage()).isEqualTo(OptionalLong.of(536_870_912L));
    }

    @Test
    void resolvesNestedCgroupV1MemoryControllerPaths() throws Exception {
        Path mountPoint = tempDir.resolve("memory");
        Path processCgroup = mountPoint.resolve("docker/example");
        Files.createDirectories(processCgroup);
        Files.writeString(processCgroup.resolve("memory.limit_in_bytes"), "1073741824\n");
        Files.writeString(processCgroup.resolve("memory.usage_in_bytes"), "805306368\n");
        Files.writeString(processCgroup.resolve("memory.stat"), "total_inactive_file 268435456\n");
        Path selfCgroup = tempDir.resolve("self.cgroup");
        Path mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(selfCgroup, "7:memory:/docker/example\n");
        Files.writeString(mountInfo, "30 23 0:27 / " + mountPoint + " rw - cgroup cgroup rw,memory\n");

        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);
        ContainerMemoryLimitDetector.CgroupMemorySample sample = detector.detect();

        assertThat(sample.limit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(sample.current()).isEqualTo(OptionalLong.of(805_306_368L));
        assertThat(sample.workingSet()).isEqualTo(OptionalLong.of(536_870_912L));
    }

    @Test
    void fallsBackToV1MemoryControllerWhenUnifiedMountHasNoMemoryLimit() throws Exception {
        Path unifiedMount = tempDir.resolve("unified");
        Path memoryMount = tempDir.resolve("memory");
        Path processCgroup = memoryMount.resolve("docker/example");
        Files.createDirectories(unifiedMount);
        Files.createDirectories(processCgroup);
        Files.writeString(processCgroup.resolve("memory.limit_in_bytes"), "1073741824\n");
        Files.writeString(processCgroup.resolve("memory.usage_in_bytes"), "536870912\n");
        Path selfCgroup = tempDir.resolve("self.cgroup");
        Path mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(selfCgroup, "0::/\n7:memory:/docker/example\n");
        Files.writeString(
                mountInfo,
                "29 23 0:26 / " + unifiedMount + " rw - cgroup2 cgroup rw\n" + "30 23 0:27 / " + memoryMount
                        + " rw - cgroup cgroup rw,memory\n");

        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);

        assertThat(detector.detectLimit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(detector.detectCurrentUsage()).isEqualTo(OptionalLong.of(536_870_912L));
    }

    @Test
    void resolvesNamespaceRootWhenMountInfoUsesTheHostCgroupRoot() throws Exception {
        Path mountPoint = tempDir.resolve("memory");
        Files.createDirectories(mountPoint);
        Files.writeString(mountPoint.resolve("memory.limit_in_bytes"), "1073741824\n");
        Files.writeString(mountPoint.resolve("memory.usage_in_bytes"), "536870912\n");
        Path selfCgroup = tempDir.resolve("self.cgroup");
        Path mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(selfCgroup, "7:memory:/\n");
        Files.writeString(mountInfo, "30 23 0:27 /docker/example " + mountPoint + " rw - cgroup cgroup rw,memory\n");

        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);

        assertThat(detector.detectLimit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(detector.detectCurrentUsage()).isEqualTo(OptionalLong.of(536_870_912L));
    }

    @Test
    void parsesTerabyteMemorySizesAcceptedByHotSpot() {
        assertThat(MemoryCollector.parseMemorySize("1t")).isEqualTo(1024L * 1024 * 1024 * 1024);
        assertThat(MemoryCollector.parseMemorySize("1T")).isEqualTo(1024L * 1024 * 1024 * 1024);
    }
}
