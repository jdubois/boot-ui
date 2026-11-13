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
    void acquisitionOmissionsInvalidatePopulationRulesEvenWhenTheDetailPageIsNotFull() {
        var quality = new io.github.jdubois.bootui.core.dto.ThreadCaptureQuality(
                "ThreadMXBean",
                List.of("PLATFORM"),
                3000,
                0,
                0,
                3000,
                128,
                false,
                true,
                true,
                false,
                List.of("deadline"));
        var report = new io.github.jdubois.bootui.core.dto.ThreadDumpReport(
                true,
                null,
                1000L,
                0,
                0,
                3000,
                3000,
                false,
                true,
                false,
                List.of(),
                List.of(),
                List.of(),
                new io.github.jdubois.bootui.core.dto.PageMetadata(0, 0, 0, 1000, 0, false),
                quality);
        MemoryContext context =
                new MemoryCollector(() -> report, () -> null, ContainerMemoryLimitDetector.disabled()).collect();

        assertThat(context.threads().total()).isEqualTo(3000);
        assertThat(context.threads().populationComplete()).isFalse();
        assertThat(context.threads().detailsTruncated()).isTrue();
        assertThat(new HighBlockedThreadRatioRule().evaluate(context).status()).isEqualTo("SKIPPED");
        assertThat(new PlatformThreadStackReservationRule().evaluate(context).status())
                .isEqualTo("SKIPPED");
    }

    @Test
    void latestGcEventIsSelectedByCompletionTimestampNotLongestDuration() {
        MemoryCollector.LastGcEvent latest = MemoryCollector.latestGcEvent(List.of(
                new MemoryCollector.LastGcEventCandidate(2_000, 1_500, "G1 Old Generation"),
                new MemoryCollector.LastGcEventCandidate(5_000, 25, "G1 Young Generation")));

        assertThat(latest.durationMillis()).isEqualTo(25);
        assertThat(latest.collectorName()).isEqualTo("G1 Young Generation");
    }

    @Test
    void latestGcEventIgnoresConcurrentCycleBeansThatOverlapTheApplication() {
        MemoryCollector.LastGcEvent latest = MemoryCollector.latestGcEvent(List.of(
                new MemoryCollector.LastGcEventCandidate(5_000, 2_400, "ZGC Major Cycles"),
                new MemoryCollector.LastGcEventCandidate(4_900, 1, "ZGC Major Pauses"),
                new MemoryCollector.LastGcEventCandidate(6_000, 1_800, "Shenandoah Cycles")));

        assertThat(latest.collectorName()).isEqualTo("ZGC Major Pauses");
        assertThat(latest.durationMillis()).isEqualTo(1);
        assertThat(MemoryCollector.latestGcEvent(
                                List.of(new MemoryCollector.LastGcEventCandidate(5_000, 2_400, "ZGC Cycles")))
                        .durationMillis())
                .isEqualTo(-1);
    }

    @Test
    void gcFillerRowsAreNotApplicationHeapContent() {
        String raw = """
                 num     #instances         #bytes  class name (module)
                -------------------------------------------------------
                   1:          1000        8000000  [B (java.base@27)
                   2:           200        3000000  [Ljdk.internal.vm.FillerElement; (java.base@27)
                   3:            10        1000000  Ljdk.internal.vm.FillerArray; (java.base@21)
                   4:             5         500000  Ljava.internal.vm.FillerArray; (java.base@19)
                   5:            50            800  jdk.internal.vm.FillerObject (java.base@27)
                   6:           100           1600  java.lang.String (java.base@27)
                Total          1365       12502400
                """;

        List<io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto> rows = MemoryCollector.parseHistogram(raw);

        assertThat(rows)
                .extracting(io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto::className)
                .containsExactly("byte[]", "java.lang.String");
        MemoryContext context =
                new MemoryCollector(() -> null, () -> raw, ContainerMemoryLimitDetector.disabled()).collect();
        assertThat(context.heapContent().totalBytes()).isEqualTo(8_001_600L);
        assertThat(context.heapContent().totalInstances()).isEqualTo(1_100L);
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
    void usesTheMostRestrictiveFiniteParentCgroupLimitAndReadsAncestorCurrentAndStat() throws Exception {
        Path mountPoint = tempDir.resolve("cgroup2");
        Path parentCgroup = mountPoint.resolve("kubepods");
        Path processCgroup = parentCgroup.resolve("pod-a");
        Files.createDirectories(processCgroup);
        Files.writeString(processCgroup.resolve("memory.max"), "2147483648\n");
        Files.writeString(processCgroup.resolve("memory.current"), "268435456\n");
        Files.writeString(processCgroup.resolve("memory.stat"), "inactive_file 67108864\n");
        Files.writeString(parentCgroup.resolve("memory.max"), "1073741824\n");
        Files.writeString(parentCgroup.resolve("memory.current"), "805306368\n");
        Files.writeString(parentCgroup.resolve("memory.stat"), "inactive_file 268435456\n");
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
    void fallsBackToLeafCurrentAndStatWhenAncestorLimitDirectoryLacksCurrentFile() throws Exception {
        Path mountPoint = tempDir.resolve("cgroup2");
        Path parentCgroup = mountPoint.resolve("kubepods");
        Path processCgroup = parentCgroup.resolve("pod-a");
        Files.createDirectories(processCgroup);
        Files.writeString(processCgroup.resolve("memory.max"), "2147483648\n");
        Files.writeString(processCgroup.resolve("memory.current"), "536870912\n");
        Files.writeString(processCgroup.resolve("memory.stat"), "inactive_file 134217728\n");
        Files.writeString(parentCgroup.resolve("memory.max"), "1073741824\n");
        Files.writeString(parentCgroup.resolve("memory.stat"), "inactive_file 402653184\n");
        Path selfCgroup = tempDir.resolve("self.cgroup");
        Path mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(selfCgroup, "0::/kubepods/pod-a\n");
        Files.writeString(mountInfo, "29 23 0:26 / " + mountPoint + " rw - cgroup2 cgroup rw\n");

        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);
        ContainerMemoryLimitDetector.CgroupMemorySample sample = detector.detect();

        assertThat(sample.limit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(sample.current()).isEqualTo(OptionalLong.of(536_870_912L));
        assertThat(sample.workingSet()).isEqualTo(OptionalLong.of(402_653_184L));
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
    void skipsCgroupDiscoveryWhenProcFilesAreUnavailable() {
        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(
                tempDir.resolve("missing-cgroup"), tempDir.resolve("missing-mountinfo"));

        assertThat(detector.detect()).isEqualTo(ContainerMemoryLimitDetector.CgroupMemorySample.unavailable());
    }

    @Test
    void parsesTerabyteMemorySizesAcceptedByHotSpot() {
        assertThat(MemoryCollector.parseMemorySize("1t")).isEqualTo(1024L * 1024 * 1024 * 1024);
        assertThat(MemoryCollector.parseMemorySize("1T")).isEqualTo(1024L * 1024 * 1024 * 1024);
    }

    @Test
    void parseThreadStackBytesPrefersLiveOptionAndUsesLastWinsOnInputArguments() {
        assertThat(MemoryCollector.parseThreadStackBytes(List.of("-Xss2m", "-Xss256k"), "512"))
                .isEqualTo(512L * 1024);
        assertThat(MemoryCollector.parseThreadStackBytes(List.of("-Xss2m", "-Xss256k"), null))
                .isEqualTo(256L * 1024);
        assertThat(MemoryCollector.parseThreadStackBytes(
                        List.of("-Xss2m", "-XX:ThreadStackSize=512", "-Xss384k"), null))
                .isEqualTo(384L * 1024);
        assertThat(MemoryCollector.parseThreadStackBytes(List.of("-Xss384k", "-XX:ThreadStackSize=512"), null))
                .isEqualTo(512L * 1024);
        assertThat(MemoryCollector.parseThreadStackBytes(List.of(), null))
                .isEqualTo(MemoryContext.RuntimeData.DEFAULT_THREAD_STACK_BYTES);
    }

    @Test
    void safeCollectionUsageCapturesSupportedPoolAndHandlesUnsupportedPool() {
        java.lang.management.MemoryPoolMXBean supported =
                org.mockito.Mockito.mock(java.lang.management.MemoryPoolMXBean.class);
        org.mockito.Mockito.when(supported.getCollectionUsage())
                .thenReturn(new java.lang.management.MemoryUsage(0, 128, 256, 512));
        assertThat(MemoryCollector.safeCollectionUsage(supported).getUsed()).isEqualTo(128);

        java.lang.management.MemoryPoolMXBean unsupported =
                org.mockito.Mockito.mock(java.lang.management.MemoryPoolMXBean.class);
        org.mockito.Mockito.when(unsupported.getCollectionUsage())
                .thenThrow(new UnsupportedOperationException("collection usage not supported"));
        assertThat(MemoryCollector.safeCollectionUsage(unsupported)).isNull();
    }

    @Test
    void readsCgroupV2MemoryHighAndMemoryEventsEvenWhenMemoryMaxIsUnlimited() throws Exception {
        Path mountPoint = tempDir.resolve("cgroup2");
        Path processCgroup = mountPoint.resolve("kubepods/pod-qos");
        Files.createDirectories(processCgroup);
        Files.writeString(processCgroup.resolve("memory.max"), "max\n");
        Files.writeString(processCgroup.resolve("memory.high"), "805306368\n");
        Files.writeString(processCgroup.resolve("memory.current"), "751619276\n");
        Files.writeString(
                processCgroup.resolve("memory.events"), "low 0\nhigh 14\nmax 2\noom 1\noom_kill 1\noom_group_kill 0\n");
        Path selfCgroup = tempDir.resolve("self.cgroup");
        Path mountInfo = tempDir.resolve("mountinfo");
        Files.writeString(selfCgroup, "0::/kubepods/pod-qos\n");
        Files.writeString(mountInfo, "29 23 0:26 / " + mountPoint + " rw - cgroup2 cgroup rw\n");

        ContainerMemoryLimitDetector detector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);
        ContainerMemoryLimitDetector.CgroupMemorySample sample = detector.detect();

        assertThat(sample.limit()).isEmpty();
        assertThat(sample.high()).isEqualTo(OptionalLong.of(805_306_368L));
        assertThat(sample.current()).isEqualTo(OptionalLong.of(751_619_276L));
        assertThat(sample.events()).isEqualTo(new MemoryContext.CgroupMemoryEvents(14, 2, 1, 1));
    }

    @Test
    void aggregatesDynamicClassLoadersAndGeneratedProxiesBeforeTop200Truncation() {
        StringBuilder raw = new StringBuilder("""
                 num     #instances         #bytes  class name (module)
                -------------------------------------------------------
                """);
        int row = 1;
        // 210 large application classes so the top-200 display list truncates everything after rank 200
        for (int i = 1; i <= 210; i++) {
            raw.append(String.format(" %4d: %12d %14d  com.example.Payload%03d%n", row++, 10, (300 - i) * 100_000L, i));
        }
        // Dynamic ClassLoader and 5,005 generated proxy rows in the long tail beyond rank 200
        raw.append(String.format(" %4d: %12d %14d  java.net.URLClassLoader%n", row++, 65, 5_200L));
        raw.append(String.format(" %4d: %12d %14d  jdk.internal.loader.ClassLoaders$AppClassLoader%n", row++, 1, 120L));
        for (int i = 0; i < 5_005; i++) {
            raw.append(String.format(" %4d: %12d %14d  com.example.Service$$SpringCGLIB$$%d%n", row++, 1, 32L, i));
        }

        MemoryContext context =
                new MemoryCollector(() -> null, raw::toString, ContainerMemoryLimitDetector.disabled()).collect();

        assertThat(context.heapContent().histogram()).hasSize(200);
        assertThat(context.heapContent().dynamicClassLoaderInstances()).isEqualTo(65L);
        assertThat(context.heapContent().generatedProxyClassCount()).isEqualTo(5_005);
        assertThat(new ClassLoaderAndProxyBloatRule().evaluate(context).status())
                .isEqualTo("VIOLATION");
    }

    @Test
    void ignoresUnconstrainedParentCgroupEventsAndReadsHighUsageFromHighLimitDirectory() throws Exception {
        Path mountPoint = tempDir.resolve("cgroup2-hierarchy");
        Path parentSlice = mountPoint.resolve("user.slice");
        Path leafService = parentSlice.resolve("app.service");
        Files.createDirectories(leafService);
        // Unconstrained parent slice has historical OOM kills from a sibling process, while leaf is clean and unlimited
        Files.writeString(parentSlice.resolve("memory.max"), "max\n");
        Files.writeString(parentSlice.resolve("memory.high"), "max\n");
        Files.writeString(parentSlice.resolve("memory.events"), "high 50\nmax 10\noom 3\noom_kill 3\n");
        Files.writeString(leafService.resolve("memory.max"), "max\n");
        Files.writeString(leafService.resolve("memory.high"), "max\n");
        Files.writeString(leafService.resolve("memory.events"), "high 0\nmax 0\noom 0\noom_kill 0\n");
        Path selfCgroup = tempDir.resolve("self-slice.cgroup");
        Path mountInfo = tempDir.resolve("slice.mountinfo");
        Files.writeString(selfCgroup, "0::/user.slice/app.service\n");
        Files.writeString(mountInfo, "29 23 0:26 / " + mountPoint + " rw - cgroup2 cgroup rw\n");

        ContainerMemoryLimitDetector unconstrainedDetector =
                ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);
        assertThat(unconstrainedDetector.detect())
                .isEqualTo(ContainerMemoryLimitDetector.CgroupMemorySample.unavailable());

        // Parent enforces memory.max (1 GiB, current 850 MiB), while leaf enforces memory.high (256 MiB, current 100
        // MiB)
        Files.writeString(parentSlice.resolve("memory.max"), "1073741824\n");
        Files.writeString(parentSlice.resolve("memory.current"), "891289600\n");
        Files.writeString(parentSlice.resolve("memory.events"), "high 0\nmax 0\noom 0\noom_kill 0\n");
        Files.writeString(leafService.resolve("memory.high"), "268435456\n");
        Files.writeString(leafService.resolve("memory.current"), "104857600\n");

        ContainerMemoryLimitDetector splitDetector = ContainerMemoryLimitDetector.fromProcFiles(selfCgroup, mountInfo);
        ContainerMemoryLimitDetector.CgroupMemorySample sample = splitDetector.detect();

        assertThat(sample.limit()).isEqualTo(OptionalLong.of(1_073_741_824L));
        assertThat(sample.current()).isEqualTo(OptionalLong.of(891_289_600L));
        assertThat(sample.high()).isEqualTo(OptionalLong.of(268_435_456L));
        assertThat(sample.highCurrent()).isEqualTo(OptionalLong.of(104_857_600L));

        MemoryContext context = new MemoryCollector(() -> null, () -> null, splitDetector).collect();
        assertThat(new ContainerCgroupThrottlingAndOomRule().evaluate(context).status())
                .isEqualTo("PASS");
    }

    @Test
    void excludesJdkReflectionDelegatingClassLoadersAndIncludesNestedDynamicLoaders() {
        String raw = """
                 num     #instances         #bytes  class name (module)
                -------------------------------------------------------
                   1:           400          32000  jdk.internal.reflect.DelegatingClassLoader (java.base@21)
                   2:           300          24000  sun.reflect.DelegatingClassLoader
                   3:            30           2400  groovy.lang.GroovyClassLoader$InnerLoader
                   4:            25           2000  net.bytebuddy.dynamic.loading.ByteArrayClassLoader$ChildFirst
                """;
        MemoryContext context =
                new MemoryCollector(() -> null, () -> raw, ContainerMemoryLimitDetector.disabled()).collect();

        assertThat(context.heapContent().dynamicClassLoaderClassCount()).isEqualTo(2);
        assertThat(context.heapContent().dynamicClassLoaderInstances()).isEqualTo(55L);
        assertThat(new ClassLoaderAndProxyBloatRule().evaluate(context).status())
                .isEqualTo("VIOLATION");
    }
}
