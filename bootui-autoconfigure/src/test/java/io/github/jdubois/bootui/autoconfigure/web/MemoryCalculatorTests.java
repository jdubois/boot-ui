package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.web.MemoryCalculator.JdkVersion;
import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Paketo {@code libjvm}-style {@link MemoryCalculator}.
 *
 * <p>Verifies the partition formula, libjvm-equivalent constants, and the JDK
 * version gating that controls {@code -XX:+UseCompactObjectHeaders}.
 */
class MemoryCalculatorTests {

    private static final long MB = 1024L * 1024L;

    private static final JdkVersion JDK_25 = () -> 25;
    private static final JdkVersion JDK_24 = () -> 24;

    @Test
    void heapIsTotalMinusFixedRegionsAndHeadroom() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto result = calc.calculate(1024 * MB, 250, 10_000, 0, 42, 10_000);

        long expectedMetaspace =
                (long) Math.ceil((14_000_000L + 5_800L * 10_000L) * MemoryCalculator.META_SAFETY_FACTOR);
        long expectedFixed = MemoryCalculator.DIRECT_MEMORY_BYTES
                + expectedMetaspace
                + MemoryCalculator.CODE_CACHE_BYTES
                + MemoryCalculator.STACK_BYTES_PER_THREAD * 250L;
        long expectedHeap = 1024 * MB - 0 - expectedFixed;

        assertThat(result.valid()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.totalMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(result.metaspaceBytes()).isEqualTo(expectedMetaspace);
        assertThat(result.codeCacheBytes()).isEqualTo(MemoryCalculator.CODE_CACHE_BYTES);
        assertThat(result.directMemoryBytes()).isEqualTo(MemoryCalculator.DIRECT_MEMORY_BYTES);
        assertThat(result.stackBytesPerThread()).isEqualTo(MemoryCalculator.STACK_BYTES_PER_THREAD);
        assertThat(result.stackBytesTotal()).isEqualTo(MemoryCalculator.STACK_BYTES_PER_THREAD * 250L);
        assertThat(result.fixedRegionsBytes()).isEqualTo(expectedFixed);
        assertThat(result.headRoomBytes()).isZero();
        assertThat(result.heapBytes()).isEqualTo(expectedHeap);
        assertThat(result.threadCount()).isEqualTo(250);
        assertThat(result.loadedClasses()).isEqualTo(10_000);
        assertThat(result.liveThreadCount()).isEqualTo(42);
        assertThat(result.liveLoadedClassCount()).isEqualTo(10_000);
    }

    @Test
    void metaspaceAppliesSafetyFactorOnLiveClassCount() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto a = calc.calculate(1024 * MB, 250, 0, 0, 1, 0);
        long expectedBaseline = (long) Math.ceil(14_000_000L * MemoryCalculator.META_SAFETY_FACTOR);
        assertThat(a.metaspaceBytes()).isEqualTo(expectedBaseline);

        MemoryCalculationDto b = calc.calculate(1024 * MB, 250, 1_000, 0, 1, 1_000);
        long expected1k = (long) Math.ceil((14_000_000L + 5_800L * 1_000L) * MemoryCalculator.META_SAFETY_FACTOR);
        assertThat(b.metaspaceBytes()).isEqualTo(expected1k);
    }

    @Test
    void headRoomReducesAvailableHeap() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto noHeadroom = calc.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);
        MemoryCalculationDto withHeadroom = calc.calculate(1024 * MB, 250, 5_000, 10, 1, 5_000);

        long expectedHeadroom = (long) ((10 / 100.0) * (1024 * MB));
        assertThat(withHeadroom.headRoomBytes()).isEqualTo(expectedHeadroom);
        assertThat(withHeadroom.heapBytes()).isEqualTo(noHeadroom.heapBytes() - expectedHeadroom);
        assertThat(withHeadroom.headRoomPercent()).isEqualTo(10);
    }

    @Test
    void gcFlagFlipsToZgcAt4GiBHeap() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto small = calc.calculate(1024 * MB, 250, 1_000, 0, 1, 1_000);
        MemoryCalculationDto large = calc.calculate(8L * 1024 * MB, 250, 1_000, 0, 1, 1_000);

        assertThat(small.jvmOptions()).contains("-XX:+UseG1GC").doesNotContain("-XX:+UseZGC");
        assertThat(large.jvmOptions()).contains("-XX:+UseZGC").doesNotContain("-XX:+UseG1GC");
    }

    @Test
    void jvmOptionsSetXmsEqualToXmx() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);
        MemoryCalculationDto result = calc.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);

        long heapMb = Math.round(result.heapBytes() / (double) MB);
        assertThat(result.jvmOptions()).contains("-Xms" + heapMb + "m").contains("-Xmx" + heapMb + "m");
    }

    @Test
    void bareMetalOptionsPreTouchTheFixedHeap() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);
        MemoryCalculationDto result = calc.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);

        assertThat(result.jvmOptions()).contains("-XX:+AlwaysPreTouch");
    }

    @Test
    void jvmOptionsExpressStackInKilobytes() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);
        MemoryCalculationDto result = calc.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);

        assertThat(result.jvmOptions()).contains("-Xss1024k");
    }

    @Test
    void jvmOptionsExpressFixedRegionsInMegabytes() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);
        MemoryCalculationDto result = calc.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);

        long metaMb = Math.round(result.metaspaceBytes() / (double) MB);
        assertThat(result.jvmOptions())
                .contains("-XX:ReservedCodeCacheSize=240m")
                .contains("-XX:MaxDirectMemorySize=10m")
                .contains("-XX:MaxMetaspaceSize=" + metaMb + "m");
    }

    @Test
    void compactObjectHeadersOnlyOnJdk25OrNewer() {
        MemoryCalculator on25 = new MemoryCalculator(JDK_25);
        MemoryCalculator on24 = new MemoryCalculator(JDK_24);

        MemoryCalculationDto result25 = on25.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);
        MemoryCalculationDto result24 = on24.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);

        assertThat(result25.jvmOptions()).contains("-XX:+UseCompactObjectHeaders");
        assertThat(result24.jvmOptions()).doesNotContain("-XX:+UseCompactObjectHeaders");
    }

    @Test
    void invalidWhenTotalMemoryLeavesNoRoomForHeap() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto result = calc.calculate(256 * MB, 5_000, 100_000, 0, 10, 100_000);

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isNotNull().contains("No room for heap");
        assertThat(result.heapBytes()).isZero();
        assertThat(result.jvmOptions()).isEmpty();
    }

    @Test
    void clampsOutOfRangeInputs() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto result = calc.calculate(-1, -1, -1, -50, 10, 0);

        assertThat(result.totalMemoryBytes()).isGreaterThanOrEqualTo(MemoryCalculator.MIN_TOTAL_MEMORY_BYTES);
        assertThat(result.threadCount()).isGreaterThanOrEqualTo(MemoryCalculator.MIN_THREAD_COUNT);
        assertThat(result.headRoomPercent()).isGreaterThanOrEqualTo(MemoryCalculator.MIN_HEAD_ROOM_PERCENT);
        assertThat(result.loadedClasses()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void clampsOversizedInputs() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto result = calc.calculate(Long.MAX_VALUE, Integer.MAX_VALUE, 10_000, 1_000, 10, 10_000);

        assertThat(result.totalMemoryBytes()).isLessThanOrEqualTo(MemoryCalculator.MAX_TOTAL_MEMORY_BYTES);
        assertThat(result.threadCount()).isLessThanOrEqualTo(MemoryCalculator.MAX_THREAD_COUNT);
        assertThat(result.headRoomPercent()).isLessThanOrEqualTo(MemoryCalculator.MAX_HEAD_ROOM_PERCENT);
    }

    @Test
    void defaultTotalMemoryIsClampedRegardlessOfHostFootprint() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        long onLargeMac = calc.defaultTotalMemoryBytes(2L * 1024 * MB, 300L * MB, 40, 15_000);

        long onTinyApp = calc.defaultTotalMemoryBytes(32L * MB, 16L * MB, 10, 500);

        assertThat(onLargeMac).isLessThanOrEqualTo(2048L * MB);
        assertThat(onLargeMac).isGreaterThanOrEqualTo(384L * MB);
        assertThat(onTinyApp).isGreaterThanOrEqualTo(384L * MB);
        assertThat(onTinyApp).isLessThanOrEqualTo(2048L * MB);
    }

    @Test
    void defaultTotalMemoryIsAlignedTo64MbBoundary() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        long result = calc.defaultTotalMemoryBytes(200L * MB, 100L * MB, 40, 10_000);

        assertThat(result % (64L * MB)).isZero();
    }

    @Test
    void defaultThreadCountFloorsAt250() {
        assertThat(MemoryCalculator.defaultThreadCount(10)).isEqualTo(250);
        assertThat(MemoryCalculator.defaultThreadCount(250)).isEqualTo(250);
        assertThat(MemoryCalculator.defaultThreadCount(500)).isEqualTo(500);
    }

    @Test
    void virtualThreadsReduceStackBudgetWithoutSettingSpringProperty() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);

        MemoryCalculationDto platformThreads = calc.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000, false);
        MemoryCalculationDto virtualThreads = calc.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000, true);

        assertThat(virtualThreads.virtualThreadsEnabled()).isTrue();
        assertThat(virtualThreads.stackBytesPerThread())
                .isEqualTo(MemoryCalculator.VIRTUAL_THREAD_STACK_BYTES_PER_THREAD);
        assertThat(virtualThreads.stackBytesTotal()).isLessThan(platformThreads.stackBytesTotal());
        assertThat(virtualThreads.heapBytes()).isGreaterThan(platformThreads.heapBytes());
        assertThat(virtualThreads.jvmOptions()).contains("-Xss512k").doesNotContain("spring.threads.virtual.enabled");
        assertThat(platformThreads.jvmOptions()).doesNotContain("spring.threads.virtual.enabled");
    }

    @Test
    void virtualThreadDefaultsUseSmallerPlatformThreadFloor() {
        assertThat(MemoryCalculator.defaultThreadCount(10, true)).isEqualTo(80);
        assertThat(MemoryCalculator.defaultThreadCount(80, true)).isEqualTo(80);
        assertThat(MemoryCalculator.defaultThreadCount(120, true)).isEqualTo(120);
    }

    @Test
    void jvmOptionsAlwaysIncludeOomSafetyAndStringDeduplication() {
        MemoryCalculator calc = new MemoryCalculator(JDK_25);
        MemoryCalculationDto result = calc.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);

        assertThat(result.jvmOptions())
                .contains("-XX:+UseStringDeduplication")
                .contains("-XX:+ExitOnOutOfMemoryError")
                .contains("-XX:+HeapDumpOnOutOfMemoryError")
                .contains("-XX:HeapDumpPath=/tmp");
    }
}
