package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Paketo {@code libjvm}-style {@link MemoryCalculator}.
 *
 * <p>Verifies the partition formula, live-observation adaptations, rendered-budget boundaries, and the
 * intentionally small Java 17+ option surface.
 */
class MemoryCalculatorTests {

    private static final long MB = 1024L * 1024L;

    private final MemoryCalculator calculator = new MemoryCalculator();

    @Test
    void heapIsTotalMinusAlignedFixedRegionsAndHeadroom() {
        MemoryCalculationDto result = calculator.calculate(1024 * MB, 250, 10_000, 0, 42, 10_000);

        long rawMetaspace = (long) Math.ceil((14_000_000L + 5_800L * 10_000L) * MemoryCalculator.META_SAFETY_FACTOR);
        long expectedMetaspace = roundUpToMiB(rawMetaspace);
        long expectedFixed = MemoryCalculator.DIRECT_MEMORY_BYTES
                + expectedMetaspace
                + MemoryCalculator.CODE_CACHE_BYTES
                + MemoryCalculator.STACK_BYTES_PER_THREAD * 250L;
        long expectedHeap = 1024 * MB - expectedFixed;

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
    void metaspaceAppliesSafetyFactorAndRoundsUpToAMebibyte() {
        MemoryCalculationDto baseline = calculator.calculate(1024 * MB, 250, 0, 0, 1, 0);
        long expectedBaseline = roundUpToMiB((long) Math.ceil(14_000_000L * MemoryCalculator.META_SAFETY_FACTOR));
        assertThat(baseline.metaspaceBytes()).isEqualTo(expectedBaseline);

        MemoryCalculationDto oneThousandClasses = calculator.calculate(1024 * MB, 250, 1_000, 0, 1, 1_000);
        long expected =
                roundUpToMiB((long) Math.ceil((14_000_000L + 5_800L * 1_000L) * MemoryCalculator.META_SAFETY_FACTOR));
        assertThat(oneThousandClasses.metaspaceBytes()).isEqualTo(expected);
    }

    @Test
    void headroomReducesAvailableHeap() {
        MemoryCalculationDto noHeadroom = calculator.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);
        MemoryCalculationDto withHeadroom = calculator.calculate(1024 * MB, 250, 5_000, 10, 1, 5_000);

        long expectedHeadroom = (long) ((10 / 100.0) * (1024 * MB));
        assertThat(withHeadroom.headRoomBytes()).isEqualTo(expectedHeadroom);
        assertThat(withHeadroom.heapBytes()).isEqualTo(noHeadroom.heapBytes() - expectedHeadroom);
        assertThat(withHeadroom.headRoomPercent()).isEqualTo(10);
    }

    @Test
    void bareMetalOptionsContainOnlyTheModeledMemorySettings() {
        MemoryCalculationDto result = calculator.calculate(1024 * MB, 250, 5_000, 0, 1, 5_000);

        assertThat(result.jvmOptions())
                .contains("-Xms", "-Xmx", "-XX:MaxMetaspaceSize=", "-XX:ReservedCodeCacheSize=240m", "-Xss1024k")
                .doesNotContain(
                        "-XX:MaxDirectMemorySize=",
                        "-XX:+AlwaysPreTouch",
                        "-XX:+UseG1GC",
                        "-XX:+UseZGC",
                        "-XX:+UseStringDeduplication",
                        "-XX:+UseCompactObjectHeaders",
                        "-XX:+ExitOnOutOfMemoryError",
                        "-XX:+HeapDumpOnOutOfMemoryError",
                        "-XX:HeapDumpPath=");
    }

    @Test
    void bareMetalOptionsAreSpaceSeparatedTokensAcrossRepresentativeInputs() {
        // Regression test for a StringBuilder bug where "-Xms" and "-Xmx" were appended
        // without a separating space, producing an unparseable token such as
        // "-Xms369m-Xmx369m". containsExactly on the split tokens fails loudly if any two
        // options are concatenated instead of separated by exactly one space.
        long[][] representativeInputs = {
            // {totalMemoryBytes, threadCount, loadedClasses, headRoomPercent, liveThreadCount, liveLoadedClasses}
            {1024 * MB, 250, 5_000, 0, 1, 5_000},
            {2048 * MB, 250, 5_000, 10, 40, 5_000},
            {64L * 1024 * MB, 10_000, 200_000, 30, 5_000, 200_000},
            {518 * MB, 250, 0, 0, 10, 0}, // boundary: smallest total that still renders a 1 MiB heap
        };

        for (long[] input : representativeInputs) {
            MemoryCalculationDto result = calculator.calculate(
                    input[0], (int) input[1], (int) input[2], (int) input[3], (int) input[4], (int) input[5]);

            assertThat(result.valid()).as("input %s", (Object) input).isTrue();

            long heapMb = result.heapBytes() / MB;
            long metaMb = result.metaspaceBytes() / MB;

            List<String> tokens = List.of(result.jvmOptions().split(" "));
            assertThat(tokens)
                    .as("tokens for input %s", (Object) input)
                    .containsExactly(
                            "-Xms" + heapMb + "m",
                            "-Xmx" + heapMb + "m",
                            "-XX:MaxMetaspaceSize=" + metaMb + "m",
                            "-XX:ReservedCodeCacheSize=240m",
                            "-Xss1024k");
        }
    }

    @Test
    void kubernetesOptionsAreSpaceSeparatedTokensWithoutConcatenation() {
        MemoryCalculationDto result = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);

        String options = calculator.buildKubernetesJvmOptions(result, 42.125, 33.5);
        long metaMb = result.metaspaceBytes() / MB;

        List<String> tokens = List.of(options.split(" "));
        assertThat(tokens)
                .containsExactly(
                        "-XX:MaxRAMPercentage=42.125",
                        "-XX:MinRAMPercentage=42.125",
                        "-XX:InitialRAMPercentage=33.5",
                        "-XX:MaxMetaspaceSize=" + metaMb + "m",
                        "-XX:ReservedCodeCacheSize=240m",
                        "-Xss1024k");
    }

    @Test
    void renderedOptionsStayWithinTheModeledBudget() {
        MemoryCalculationDto result = calculator.calculate(1024 * MB, 250, 5_001, 7, 40, 5_001);

        long renderedHeap = optionMebibytes(result.jvmOptions(), "-Xmx") * MB;
        long renderedMetaspace = optionMebibytes(result.jvmOptions(), "-XX:MaxMetaspaceSize=") * MB;
        long renderedTotal = renderedHeap
                + renderedMetaspace
                + result.codeCacheBytes()
                + result.directMemoryBytes()
                + result.stackBytesTotal()
                + result.headRoomBytes();

        assertThat(renderedHeap).isLessThanOrEqualTo(result.heapBytes());
        assertThat(renderedMetaspace).isGreaterThanOrEqualTo(result.metaspaceBytes());
        assertThat(renderedTotal).isLessThanOrEqualTo(result.totalMemoryBytes());
    }

    @Test
    void observedDirectMemoryRaisesTheModelWithoutCreatingAHardCap() {
        long observedDirectMemory = 33 * MB + 1;

        MemoryCalculationDto result = calculator.calculate(
                2048 * MB, 250, 5_000, 10, 40, 5_000, false, "spring.threads.virtual.enabled", observedDirectMemory);

        assertThat(result.directMemoryBytes()).isEqualTo(34 * MB);
        assertThat(result.jvmOptions()).doesNotContain("-XX:MaxDirectMemorySize=");
    }

    @Test
    void kubernetesOptionsCoverSmallHeapErgonomicsWithoutChoosingACollector() {
        MemoryCalculationDto result = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000);

        String options = calculator.buildKubernetesJvmOptions(result, 42.125, 42.125);

        assertThat(options)
                .contains(
                        "-XX:MaxRAMPercentage=42.125",
                        "-XX:MinRAMPercentage=42.125",
                        "-XX:InitialRAMPercentage=42.125",
                        "-XX:MaxMetaspaceSize=",
                        "-XX:ReservedCodeCacheSize=240m",
                        "-Xss1024k")
                .doesNotContain(
                        "-Xmx",
                        "-Xms",
                        "-XX:+UseContainerSupport",
                        "-XX:MaxDirectMemorySize=",
                        "-XX:+UseG1GC",
                        "-XX:+UseZGC");
    }

    @Test
    void rejectsAPlanThatCannotRenderAtLeastOneMebibyteOfHeap() {
        MemoryCalculationDto invalid = calculator.calculate(517 * MB, 250, 0, 0, 10, 0);
        MemoryCalculationDto boundary = calculator.calculate(518 * MB, 250, 0, 0, 10, 0);

        assertThat(invalid.valid()).isFalse();
        assertThat(invalid.error()).contains("less than 1 MiB");
        assertThat(invalid.heapBytes()).isZero();
        assertThat(invalid.jvmOptions()).isEmpty();

        assertThat(boundary.valid()).isTrue();
        assertThat(boundary.heapBytes()).isEqualTo(MB);
        // Exact token equality (not mere substring containment) so a missing separator between
        // "-Xms1m" and "-Xmx1m" (e.g. a concatenated "-Xms1m-Xmx1m") fails this assertion.
        assertThat(List.of(boundary.jvmOptions().split(" "))).contains("-Xms1m", "-Xmx1m");
    }

    @Test
    void clampsOutOfRangeInputsToTheUiContract() {
        MemoryCalculationDto belowMinimum = calculator.calculate(-1, -1, -1, -50, 10, 0);
        MemoryCalculationDto aboveMaximum =
                calculator.calculate(Long.MAX_VALUE, Integer.MAX_VALUE, 10_000, 1_000, 10, 10_000);

        assertThat(belowMinimum.totalMemoryBytes()).isEqualTo(MemoryCalculator.MIN_TOTAL_MEMORY_BYTES);
        assertThat(belowMinimum.threadCount()).isEqualTo(MemoryCalculator.MIN_THREAD_COUNT);
        assertThat(belowMinimum.headRoomPercent()).isEqualTo(MemoryCalculator.MIN_HEAD_ROOM_PERCENT);
        assertThat(belowMinimum.loadedClasses()).isZero();

        assertThat(aboveMaximum.totalMemoryBytes()).isEqualTo(MemoryCalculator.MAX_TOTAL_MEMORY_BYTES);
        assertThat(aboveMaximum.threadCount()).isEqualTo(MemoryCalculator.MAX_THREAD_COUNT);
        assertThat(aboveMaximum.headRoomPercent()).isEqualTo(MemoryCalculator.MAX_HEAD_ROOM_PERCENT);
    }

    @Test
    void defaultTotalMemoryIsBoundedAndAlignedRegardlessOfHostFootprint() {
        long onLargeHost = calculator.defaultTotalMemoryBytes(2L * 1024 * MB, 300L * MB, 40, 15_000);
        long onTinyApp = calculator.defaultTotalMemoryBytes(32L * MB, 16L * MB, 10, 500);

        assertThat(onLargeHost).isBetween(384L * MB, 2048L * MB);
        assertThat(onTinyApp).isBetween(384L * MB, 2048L * MB);
        assertThat(onLargeHost % (64L * MB)).isZero();
        assertThat(onTinyApp % (64L * MB)).isZero();
    }

    @Test
    void defaultThreadCountKeepsThePaketoPlatformThreadFloor() {
        assertThat(MemoryCalculator.defaultThreadCount(10)).isEqualTo(250);
        assertThat(MemoryCalculator.defaultThreadCount(250)).isEqualTo(250);
        assertThat(MemoryCalculator.defaultThreadCount(500)).isEqualTo(500);
    }

    @Test
    void virtualThreadDetectionDoesNotDiscountPlatformThreadStacks() {
        MemoryCalculationDto platformThreads = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000, false);
        MemoryCalculationDto virtualThreads = calculator.calculate(1024 * MB, 250, 5_000, 10, 40, 5_000, true);

        assertThat(virtualThreads.virtualThreadsEnabled()).isTrue();
        assertThat(virtualThreads.stackBytesPerThread()).isEqualTo(MemoryCalculator.STACK_BYTES_PER_THREAD);
        assertThat(virtualThreads.stackBytesTotal()).isEqualTo(platformThreads.stackBytesTotal());
        assertThat(virtualThreads.heapBytes()).isEqualTo(platformThreads.heapBytes());
        assertThat(virtualThreads.jvmOptions()).contains("-Xss1024k").doesNotContain("spring.threads.virtual.enabled");
    }

    private static long roundUpToMiB(long bytes) {
        return ((bytes + MB - 1) / MB) * MB;
    }

    private static long optionMebibytes(String options, String optionPrefix) {
        Matcher matcher =
                Pattern.compile(Pattern.quote(optionPrefix) + "(\\d+)m").matcher(options);
        assertThat(matcher.find()).as("option %s in %s", optionPrefix, options).isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
