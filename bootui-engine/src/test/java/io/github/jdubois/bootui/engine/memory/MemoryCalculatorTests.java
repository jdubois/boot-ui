package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.MemoryCalculationDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, Long.MIN_VALUE})
    void missingMetaspaceObservationPreservesTheClassModel(long observedCommittedBytes) {
        MemoryCalculationDto neutral = calculator.calculate(1024 * MB, 250, 1_000, 10, 250, 1_000);
        MemoryCalculationDto observed = calculator.calculate(
                1024 * MB,
                250,
                1_000,
                10,
                250,
                1_000,
                false,
                "spring.threads.virtual.enabled",
                0,
                MemoryCalculator.MIN_REQUESTED_HEAP_BYTES,
                observedCommittedBytes);

        assertThat(observed).isEqualTo(neutral);
        assertThat(calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000, 0, 128 * MB, observedCommittedBytes))
                .isEqualTo(calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000));
    }

    @Test
    void smallerObservedMetaspaceCannotReduceTheClassModel() {
        MemoryCalculationDto neutral = calculator.calculate(1024 * MB, 250, 10_000, 10, 250, 10_000);
        MemoryCalculationDto observed = calculator.calculate(
                1024 * MB,
                250,
                10_000,
                10,
                250,
                10_000,
                false,
                "spring.threads.virtual.enabled",
                0,
                MemoryCalculator.MIN_REQUESTED_HEAP_BYTES,
                MB);

        assertThat(observed).isEqualTo(neutral);
        assertThat(calculator.defaultTotalMemoryBytes(0, 0, 250, 10_000, 0, 128 * MB, MB))
                .isEqualTo(calculator.defaultTotalMemoryBytes(0, 0, 250, 10_000));
    }

    @ParameterizedTest
    @CsvSource({"0, 320", "1, 321", "838861, 322"})
    void observedMetaspaceAllowanceRoundsUpInBothOptionsAndTheBudget(long extraByte, long expectedMetaMiB) {
        long observedCommittedBytes = 256 * MB + extraByte;
        long requiredHeap = MemoryCalculator.minimumRequestedHeapBytes(90 * MB, false);
        MemoryCalculationDto result = calculator.calculate(
                1024 * MB, 250, 0, 10, 250, 0, false, null, 0, requiredHeap, observedCommittedBytes);
        long requiredMetaspace = observedCommittedBytes + (observedCommittedBytes + 3) / 4;

        assertThat(result.valid()).isTrue();
        assertThat(result.totalMemoryBytes()).isEqualTo(1024 * MB);
        assertThat(result.metaspaceBytes()).isEqualTo(expectedMetaMiB * MB).isGreaterThanOrEqualTo(requiredMetaspace);
        assertThat(optionMebibytes(result.jvmOptions(), "-XX:MaxMetaspaceSize=") * MB)
                .isEqualTo(result.metaspaceBytes());
        double percentage = MemoryKubernetesSizer.heapPercentage(result);
        assertThat(optionMebibytes(
                                calculator.buildKubernetesJvmOptions(result, percentage, percentage),
                                "-XX:MaxMetaspaceSize=")
                        * MB)
                .isEqualTo(result.metaspaceBytes());
        assertThat(optionMebibytes(result.jvmOptions(), "-Xmx") * MB).isGreaterThanOrEqualTo(requiredHeap);
        assertThat(renderedPercentageHeapBytes(result)).isGreaterThanOrEqualTo(requiredHeap);
        assertThat(result.fixedRegionsBytes()).isEqualTo(result.metaspaceBytes() + 500 * MB);
        assertThat(result.heapBytes() + result.fixedRegionsBytes() + result.headRoomBytes())
                .isEqualTo(result.totalMemoryBytes());
    }

    @ParameterizedTest
    @CsvSource({"0, 128, 1088, 768", "250, 300, 1280, 960"})
    void defaultFloorIncludesObservedMetaspaceAndTheSameObservedHeapAllowance(
            long observedHeapMiB, long requiredHeapMiB, long expectedTotalMiB, long previousTotalMiB) {
        long observedMetaspace = 256 * MB + 1;
        long requiredHeap = MemoryCalculator.minimumRequestedHeapBytes(observedHeapMiB * MB, true);
        long total = calculator.defaultTotalMemoryBytes(0, 0, 250, 0, 0, requiredHeap, observedMetaspace);
        MemoryCalculationDto result =
                calculator.calculate(total, 250, 0, 10, 250, 0, false, null, 0, requiredHeap, observedMetaspace);

        assertThat(total).isEqualTo(expectedTotalMiB * MB);
        assertThat(requiredHeap).isEqualTo(requiredHeapMiB * MB);
        assertThat(result.valid()).isTrue();
        assertThat(result.metaspaceBytes()).isEqualTo(321 * MB);
        assertThat(optionMebibytes(result.jvmOptions(), "-Xmx") * MB).isGreaterThanOrEqualTo(requiredHeap);
        assertThat(renderedPercentageHeapBytes(result)).isGreaterThanOrEqualTo(requiredHeap);
        assertThat(calculator
                        .calculate(
                                previousTotalMiB * MB,
                                250,
                                0,
                                10,
                                250,
                                0,
                                false,
                                null,
                                0,
                                requiredHeap,
                                observedMetaspace)
                        .valid())
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            longs = {1536L * MB, Long.MAX_VALUE / 2, 7_378_697_629_483_820_644L, Long.MAX_VALUE - 1, Long.MAX_VALUE})
    void oversizedObservedMetaspaceFailsClosedWithoutWrappingOrRaisingTheDefaultClamp(long observedCommittedBytes) {
        long total = calculator.defaultTotalMemoryBytes(0, 0, 250, 0, 0, 128 * MB, observedCommittedBytes);
        MemoryCalculationDto result =
                calculator.calculate(total, 250, 0, 10, 250, 0, false, null, 0, 128 * MB, observedCommittedBytes);

        assertThat(total).isEqualTo(2048 * MB);
        assertThat(result.metaspaceBytes()).isGreaterThanOrEqualTo(observedCommittedBytes);
        assertThat(result.fixedRegionsBytes()).isGreaterThanOrEqualTo(result.metaspaceBytes());
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(result.heapBytes()).isZero();
        assertThat(result.jvmOptions()).isEmpty();
        assertThat(calculator.buildKubernetesJvmOptions(result, 0, 0)).isEmpty();
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
            {520 * MB, 250, 0, 0, 10, 0},
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
    void rejectsKnownInvalidHeapRequestsIncludingPercentageTruncation() {
        MemoryCalculationDto invalid = calculator.calculate(517 * MB, 250, 0, 0, 10, 0);
        MemoryCalculationDto oneMebibyte = calculator.calculate(518 * MB, 250, 0, 0, 10, 0);
        MemoryCalculationDto truncatedPercentage = calculator.calculate(519 * MB, 250, 0, 0, 10, 0);
        MemoryCalculationDto boundary = calculator.calculate(520 * MB, 250, 0, 0, 10, 0);

        assertThat(invalid.valid()).isFalse();
        assertThat(invalid.error()).contains("2 MiB", "percentage");
        assertThat(invalid.heapBytes()).isZero();
        assertThat(invalid.jvmOptions()).isEmpty();
        assertThat(oneMebibyte.valid()).isFalse();
        // A fixed 2 MiB request reaches HotSpot's generic bound, but 0.385% of 519 MiB does not.
        assertThat(truncatedPercentage.valid()).isFalse();
        assertThat(truncatedPercentage.jvmOptions()).isEmpty();
        assertThat(calculator.buildKubernetesJvmOptions(truncatedPercentage, 0.385, 0.385))
                .isEmpty();

        assertThat(boundary.valid()).isTrue();
        assertThat(boundary.heapBytes()).isEqualTo(3 * MB);
        assertThat(List.of(boundary.jvmOptions().split(" "))).contains("-Xms3m", "-Xmx3m");
    }

    @Test
    void acceptsExactGenericBoundWhenBothRepresentationsReachItWithoutInflation() {
        MemoryCalculationDto result = calculator.calculate(400 * MB, 131, 0, 0, 10, 0);

        assertThat(result.valid()).isTrue();
        assertThat(result.heapBytes()).isEqualTo(2 * MB);
        assertThat(List.of(result.jvmOptions().split(" "))).contains("-Xms2m", "-Xmx2m");
        assertThat(MemoryKubernetesSizer.heapPercentage(result)).isEqualTo(0.5);
    }

    @Test
    void extremeClassAndDirectObservationsLeaveAnInvalidRatherThanOverflowingBudget() {
        MemoryCalculationDto result = calculator.calculate(
                Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 30, 10, 0, false, null, Long.MAX_VALUE);

        assertThat(result.valid()).isFalse();
        assertThat(result.fixedRegionsBytes()).isPositive();
        assertThat(result.heapBytes()).isZero();
        assertThat(result.jvmOptions()).isEmpty();
    }

    @Test
    void defaultTotalMemoryDoesNotWrapExtremeOrUnknownObservations() {
        assertThat(calculator.defaultTotalMemoryBytes(Long.MAX_VALUE, Long.MAX_VALUE, 250, 0))
                .isEqualTo(2048 * MB);
        assertThat(calculator.defaultTotalMemoryBytes(Long.MIN_VALUE, Long.MIN_VALUE, 250, 0))
                .isEqualTo(calculator.defaultTotalMemoryBytes(0, 0, 250, 0));
        assertThat(calculator.defaultTotalMemoryBytes(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .isEqualTo(2048 * MB);
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
    void defaultHeapFloorIncludesHeadroomBeforeRoundingTheTotal() {
        long total = calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000);
        MemoryCalculationDto result =
                calculator.calculate(total, 250, 1_000, MemoryKubernetesSizer.DEFAULT_HEADROOM_PERCENT, 250, 1_000);

        assertThat(total).isEqualTo(768 * MB);
        assertThat(result.fixedRegionsBytes()).isEqualTo(524 * MB);
        assertDefaultHeapFloor(result);

        MemoryCalculationDto manual = calculator.calculate(704 * MB, 250, 1_000, 10, 250, 1_000);
        assertThat(manual.valid()).isTrue();
        assertThat(manual.heapBytes()).isLessThan(128 * MB);
        assertThat(optionMebibytes(manual.jvmOptions(), "-Xmx")).isEqualTo(109);
    }

    @ParameterizedTest
    @CsvSource({
        "756, 0, 1280",
        "757, 0, 1280",
        "758, 0, 1344",
        "1333, 0, 1984",
        "1000, 50000, 1984",
        "250, 100000, 1536"
    })
    void defaultHeapFloorSurvivesTotalAndOptionRounding(int threads, int classes, long expectedTotalMiB) {
        long total = calculator.defaultTotalMemoryBytes(0, 0, threads, classes);
        MemoryCalculationDto result = calculator.calculate(
                total, threads, classes, MemoryKubernetesSizer.DEFAULT_HEADROOM_PERCENT, threads, classes);

        assertThat(total).isEqualTo(expectedTotalMiB * MB);
        assertDefaultHeapFloor(result);
    }

    @ParameterizedTest
    @CsvSource({"256, 0, 256, 1024", "510, 0, 510, 1280", "510, 1, 511, 1344"})
    void defaultHeapFloorUsesTheSameRoundedDirectMemoryAsTheCalculation(
            long observedDirectMiB, long extraByte, long expectedDirectMiB, long expectedTotalMiB) {
        long observedDirectBytes = observedDirectMiB * MB + extraByte;
        long total = calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000, observedDirectBytes);
        MemoryCalculationDto result = calculator.calculate(
                total,
                250,
                1_000,
                MemoryKubernetesSizer.DEFAULT_HEADROOM_PERCENT,
                250,
                1_000,
                false,
                null,
                observedDirectBytes,
                MemoryCalculator.MIN_DEFAULT_HEAP_BYTES);

        assertThat(total).isEqualTo(expectedTotalMiB * MB);
        assertThat(result.directMemoryBytes()).isEqualTo(expectedDirectMiB * MB);
        assertDefaultHeapFloor(result);
    }

    @ParameterizedTest
    @ValueSource(longs = {1300L * MB, Long.MAX_VALUE})
    void observedDirectMemoryCannotBypassTheDefaultUpperClamp(long observedDirectBytes) {
        long total = calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000, observedDirectBytes);
        MemoryCalculationDto result = calculator.calculate(
                total,
                250,
                1_000,
                MemoryKubernetesSizer.DEFAULT_HEADROOM_PERCENT,
                250,
                1_000,
                false,
                null,
                observedDirectBytes,
                MemoryCalculator.MIN_DEFAULT_HEAP_BYTES);

        assertThat(total).isEqualTo(2048 * MB);
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("128 MiB", "2048 MiB");
        assertThat(result.jvmOptions()).isEmpty();
        assertThat(calculator.buildKubernetesJvmOptions(result, 0, 0)).isEmpty();
    }

    @Test
    void observedHeapDefaultFloorIncludesAllowanceHeadroomAndPercentageRounding() {
        long observedHeapUsed = 210 * MB;
        long requiredHeap = MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed, true);
        long total = calculator.defaultTotalMemoryBytes(0, 0, 633, 0, 0, requiredHeap);
        MemoryCalculationDto result = calculator.calculate(total, 633, 0, 10, 633, 0, false, null, 0, requiredHeap);

        assertThat(requiredHeap).isEqualTo(252 * MB);
        // 1280 MiB leaves exactly 252 MiB, but its floored 19.687% requests less.
        assertThat(total).isEqualTo(1344 * MB);
        assertThat(result.valid()).isTrue();
        assertThat(optionMebibytes(result.jvmOptions(), "-Xms") * MB).isGreaterThanOrEqualTo(requiredHeap);
        assertThat(optionMebibytes(result.jvmOptions(), "-Xmx") * MB).isGreaterThanOrEqualTo(requiredHeap);
        assertThat(renderedPercentageHeapBytes(result)).isGreaterThanOrEqualTo(requiredHeap);
        assertThat(MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed + 1, true))
                .isEqualTo(requiredHeap + 2);
        assertThat(MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed + 1, false))
                .isEqualTo(observedHeapUsed + 1);
    }

    @Test
    void defaultSizingRetriesWhenOnlyTheFixedHeapOptionMissesTheObservedTarget() {
        long observedHeapUsed = 145 * MB + 1;
        long requiredHeap = MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed, true);
        // Render the initial candidate under the neutral bound to isolate the two rounding paths.
        MemoryCalculationDto beforeRetry = calculator.calculate(768 * MB, 250, 0, 10, 250, 0);

        assertThat(requiredHeap).isEqualTo(174 * MB + 2);
        assertThat(beforeRetry.valid()).isTrue();
        assertThat(optionMebibytes(beforeRetry.jvmOptions(), "-Xmx") * MB)
                .isEqualTo(174 * MB)
                .isLessThan(requiredHeap);
        assertThat(renderedPercentageHeapBytes(beforeRetry))
                .isEqualTo(182_659_590L)
                .isGreaterThanOrEqualTo(requiredHeap);

        long total = calculator.defaultTotalMemoryBytes(0, 0, 250, 0, 0, requiredHeap);
        MemoryCalculationDto result = calculator.calculate(total, 250, 0, 10, 250, 0, false, null, 0, requiredHeap);

        assertThat(total).isEqualTo(832 * MB);
        assertThat(result.valid()).isTrue();
        assertThat(List.of(result.jvmOptions().split(" "))).contains("-Xms231m", "-Xmx231m");
        assertThat(optionMebibytes(result.jvmOptions(), "-Xmx") * MB).isGreaterThanOrEqualTo(requiredHeap);
        double percentage = MemoryKubernetesSizer.heapPercentage(result);
        String percentageOptions = calculator.buildKubernetesJvmOptions(result, percentage, percentage);
        assertThat(List.of(percentageOptions.split(" ")))
                .contains("-XX:MaxRAMPercentage=27.86", "-XX:MinRAMPercentage=27.86", "-XX:InitialRAMPercentage=27.86");
        assertThat(renderedPercentageHeapBytes(result)).isEqualTo(243_054_883L).isGreaterThanOrEqualTo(requiredHeap);
    }

    @Test
    void observedHeapBoundRejectsOneByteAboveTheFixedOptionEvenWhenPercentageFits() {
        long fixedHeap = 397 * MB;
        MemoryCalculationDto exact = calculateWithObservedHeap(1024 * MB, 250, 1_000, 10, fixedHeap);
        MemoryCalculationDto below = calculateWithObservedHeap(1024 * MB, 250, 1_000, 10, fixedHeap + 1);

        assertThat(exact.valid()).isTrue();
        assertThat(optionMebibytes(exact.jvmOptions(), "-Xmx") * MB).isEqualTo(fixedHeap);
        assertThat(renderedPercentageHeapBytes(exact)).isGreaterThan(fixedHeap + 1);
        assertThat(below.valid()).isFalse();
        assertThat(below.heapBytes()).isZero();
        assertThat(below.jvmOptions()).isEmpty();
        assertThat(below.error()).contains((fixedHeap + 1) + " bytes");
    }

    @Test
    void observedHeapBoundChecksTheSerializedPercentageToTheByte() {
        MemoryCalculationDto baseline = calculator.calculate(1024 * MB, 250, 1_000, 0, 250, 1_000);
        long percentageHeap = renderedPercentageHeapBytes(baseline);
        MemoryCalculationDto exact = calculateWithObservedHeap(1024 * MB, 250, 1_000, 0, percentageHeap);
        MemoryCalculationDto below = calculateWithObservedHeap(1024 * MB, 250, 1_000, 0, percentageHeap + 1);

        assertThat(percentageHeap).isLessThan(baseline.heapBytes());
        assertThat(optionMebibytes(baseline.jvmOptions(), "-Xmx") * MB).isGreaterThan(percentageHeap + 1);
        assertThat(exact.valid()).isTrue();
        assertThat(renderedPercentageHeapBytes(exact)).isEqualTo(percentageHeap);
        assertThat(below.valid()).isFalse();
        assertThat(below.jvmOptions()).isEmpty();
        assertThat(calculator.buildKubernetesJvmOptions(below, 0, 0)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void absentOrNegativeObservedHeapPreservesNeutralOverloads(long observedHeapUsed) {
        long defaultMinimum = MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed, true);
        assertThat(defaultMinimum).isEqualTo(MemoryCalculator.MIN_DEFAULT_HEAP_BYTES);
        assertThat(MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed, false))
                .isEqualTo(MemoryCalculator.MIN_REQUESTED_HEAP_BYTES);
        assertThat(calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000, 0, defaultMinimum))
                .isEqualTo(calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000));
        assertThat(calculateWithObservedHeap(400 * MB, 131, 0, 0, observedHeapUsed))
                .isEqualTo(calculator.calculate(400 * MB, 131, 0, 0, 131, 0, false, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {2048L * MB, Long.MAX_VALUE / 2, Long.MAX_VALUE - 1, Long.MAX_VALUE})
    void oversizedObservedHeapCannotWrapOrEscapeTheDefaultClamp(long observedHeapUsed) {
        long requiredHeap = MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed, true);
        long total = calculator.defaultTotalMemoryBytes(
                Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, requiredHeap);
        MemoryCalculationDto defaultPlan =
                calculator.calculate(total, 250, 1_000, 10, 250, 1_000, false, null, 0, requiredHeap);
        MemoryCalculationDto manualPlan = calculateWithObservedHeap(1024 * MB, 250, 1_000, 0, observedHeapUsed);

        assertThat(requiredHeap).isGreaterThanOrEqualTo(observedHeapUsed);
        assertThat(total).isEqualTo(2048 * MB);
        assertThat(defaultPlan.valid()).isFalse();
        assertThat(defaultPlan.jvmOptions()).isEmpty();
        assertThat(defaultPlan.error()).contains(requiredHeap + " bytes");
        assertThat(calculator.buildKubernetesJvmOptions(defaultPlan, 0, 0)).isEmpty();
        assertThat(manualPlan.valid()).isFalse();
        assertThat(manualPlan.jvmOptions()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE / 2})
    void hugeRequiredHeapCannotOverflowTheDefaultFloorWithASmallFootprint(long requiredHeap) {
        assertThat(calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000, 0, requiredHeap))
                .isEqualTo(2048 * MB);
        assertThat(calculator.defaultTotalMemoryBytes(MB, MB, 250, 1_000, 0, requiredHeap))
                .isEqualTo(2048 * MB);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void fixedAndRequiredHeapAtTheDefaultClampStillNeedHeadroom(long extraByte) {
        long fixedBytes =
                calculator.calculate(2048 * MB, 250, 1_000, 0, 250, 1_000).fixedRegionsBytes();
        long requiredHeap = 2048 * MB - fixedBytes + extraByte;
        long total = calculator.defaultTotalMemoryBytes(0, 0, 250, 1_000, 0, requiredHeap);
        MemoryCalculationDto result =
                calculator.calculate(total, 250, 1_000, 10, 250, 1_000, false, null, 0, requiredHeap);

        assertThat(fixedBytes + requiredHeap).isEqualTo(2048 * MB + extraByte);
        assertThat(total).isEqualTo(2048 * MB);
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isNotBlank();
        assertThat(result.jvmOptions()).isEmpty();
        assertThat(calculator.buildKubernetesJvmOptions(result, 0, 0)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1500, 0", "250, 190000", "10000, 200000"})
    void defaultUpperClampCannotProduceARecommendationBelowTheDefaultHeapFloor(int threads, int classes) {
        long total = calculator.defaultTotalMemoryBytes(0, 0, threads, classes);
        MemoryCalculationDto result = calculator.calculate(
                total,
                threads,
                classes,
                MemoryKubernetesSizer.DEFAULT_HEADROOM_PERCENT,
                threads,
                classes,
                false,
                null,
                0,
                MemoryCalculator.MIN_DEFAULT_HEAP_BYTES);

        assertThat(total).isEqualTo(2048 * MB);
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("128 MiB", "2048 MiB");
        assertThat(result.jvmOptions()).isEmpty();
        assertThat(calculator.buildKubernetesJvmOptions(result, 0, 0)).isEmpty();

        if (threads < 10_000) {
            MemoryCalculationDto manual = calculator.calculate(
                    total, threads, classes, MemoryKubernetesSizer.DEFAULT_HEADROOM_PERCENT, threads, classes);
            assertThat(manual.valid()).isTrue();
            assertThat(manual.heapBytes()).isLessThan(128 * MB);
        }
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

    private MemoryCalculationDto calculateWithObservedHeap(
            long totalBytes, int threads, int classes, int headroom, long observedHeapUsed) {
        return calculator.calculate(
                totalBytes,
                threads,
                classes,
                headroom,
                threads,
                classes,
                false,
                null,
                0,
                MemoryCalculator.minimumRequestedHeapBytes(observedHeapUsed, false));
    }

    private long renderedPercentageHeapBytes(MemoryCalculationDto result) {
        double percentage = MemoryKubernetesSizer.heapPercentage(result);
        String options = calculator.buildKubernetesJvmOptions(result, percentage, percentage);
        Matcher matcher = Pattern.compile("-XX:MaxRAMPercentage=([\\d.]+)").matcher(options);
        assertThat(matcher.find()).isTrue();
        return new BigDecimal(matcher.group(1))
                .multiply(BigDecimal.valueOf(result.totalMemoryBytes()))
                .movePointLeft(2)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
    }

    private static void assertDefaultHeapFloor(MemoryCalculationDto result) {
        assertThat(result.valid()).isTrue();
        assertThat(result.heapBytes()).isGreaterThanOrEqualTo(128 * MB);
        assertThat(optionMebibytes(result.jvmOptions(), "-Xmx")).isGreaterThanOrEqualTo(128);
        long percentage = MemoryCalculator.heapPercentageThousandths(result.heapBytes(), result.totalMemoryBytes());
        assertThat(percentage * result.totalMemoryBytes()).isGreaterThanOrEqualTo(128 * MB * 100_000);
    }

    private static long optionMebibytes(String options, String optionPrefix) {
        Matcher matcher =
                Pattern.compile(Pattern.quote(optionPrefix) + "(\\d+)m").matcher(options);
        assertThat(matcher.find()).as("option %s in %s", optionPrefix, options).isTrue();
        return Long.parseLong(matcher.group(1));
    }
}
