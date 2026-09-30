package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdvisorViolationCollectorTests {

    @Test
    void remainingCapacityTracksOnlySuccessfullyRetainedDetailsAcrossRules() {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(3);
        assertThat(collector.remainingCapacity()).isEqualTo(3);
        collector.record("first", 5, List.of("one"), UnaryOperator.identity());
        assertThat(collector.remainingCapacity()).isEqualTo(2);
        collector.record("missing", 4, List.of(), UnaryOperator.identity());
        assertThat(collector.remainingCapacity()).isEqualTo(2);

        assertThatThrownBy(() -> collector.record("failed", 2, List.of("ok", "fail"), value -> {
                    if ("fail".equals(value)) {
                        throw new IllegalStateException("Cannot sanitize");
                    }
                    return value;
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(collector.remainingCapacity()).isEqualTo(2);
        assertThat(collector.snapshot().rules()).doesNotContainKey("failed");

        AtomicInteger sanitized = new AtomicInteger();
        collector.record("second", 3, List.of("two", "three", "unretained"), value -> {
            sanitized.incrementAndGet();
            return value;
        });
        assertThat(sanitized).hasValue(2);
        assertThat(collector.remainingCapacity()).isZero();
        collector.record("third", 1, List.of("unretained"), value -> {
            throw new AssertionError("The exhausted budget must not sanitize more values.");
        });
        assertThat(collector.remainingCapacity()).isZero();
        assertThat(collector.snapshot().total()).isEqualTo(13);
        assertThat(collector.snapshot().retained()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 11, 9_999, 10_000, 10_001})
    void retainsTheExactOrderedPrefixAndOriginalCountAtTheBudgetBoundary(int count) {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(10_000);
        List<String> details =
                IntStream.range(0, count).mapToObj(i -> "finding-" + i).toList();
        AtomicInteger sanitized = new AtomicInteger();

        collector.record("rule", count, details, value -> {
            sanitized.incrementAndGet();
            return value;
        });

        AdvisorViolationCollector.Snapshot snapshot = collector.snapshot();
        int retained = Math.min(count, 10_000);
        assertThat(snapshot.total()).isEqualTo(count);
        assertThat(snapshot.retained()).isEqualTo(retained);
        assertThat(snapshot.retentionLimit()).isEqualTo(10_000);
        assertThat(sanitized).hasValue(retained);
        if (count == 0) {
            assertThat(snapshot.rules()).isEmpty();
        } else {
            assertThat(snapshot.rules().get("rule").violationCount()).isEqualTo(count);
            assertThat(snapshot.rules().get("rule").details()).containsExactlyElementsOf(details.subList(0, retained));
        }
    }

    @Test
    void oneBudgetSpansRulesAndRepeatedPersistenceUnitContributions() {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(5);
        collector.record("first", 2, List.of("a", "a"), value -> "unit-one/" + value);
        collector.record("second", 2, List.of("z", "y"), UnaryOperator.identity());
        collector.record("first", 3, List.of("b", "b", "c"), value -> "unit-two/" + value);
        collector.record("third", 2, List.of("d", "e"), value -> {
            throw new AssertionError("The exhausted budget must not sanitize more values.");
        });

        AdvisorViolationCollector.Snapshot snapshot = collector.snapshot();
        assertThat(snapshot.total()).isEqualTo(9);
        assertThat(snapshot.retained()).isEqualTo(5);
        assertThat(snapshot.rules().keySet()).containsExactly("first", "second", "third");
        assertThat(snapshot.rules().get("first").violationCount()).isEqualTo(5);
        assertThat(snapshot.rules().get("first").details()).containsExactly("unit-one/a", "unit-one/a", "unit-two/b");
        assertThat(snapshot.rules().get("second").details()).containsExactly("z", "y");
        assertThat(snapshot.rules().get("third").violationCount()).isEqualTo(2);
        assertThat(snapshot.rules().get("third").details()).isEmpty();
    }

    @Test
    void doesNotTraverseOrSanitizeAnUnboundedTailBeyondTheBudget() {
        AtomicInteger reads = new AtomicInteger();
        List<String> details = new AbstractList<>() {
            @Override
            public String get(int index) {
                assertThat(index).isLessThan(3);
                reads.incrementAndGet();
                return "finding-" + index;
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };
        AdvisorViolationCollector collector = new AdvisorViolationCollector(3);

        collector.record("rule", Integer.MAX_VALUE, details, UnaryOperator.identity());

        assertThat(reads).hasValue(3);
        assertThat(collector.snapshot().total()).isEqualTo(Integer.MAX_VALUE);
        assertThat(collector.snapshot().rules().get("rule").details())
                .containsExactly("finding-0", "finding-1", "finding-2");
    }

    @Test
    void sanitizerCollisionsKeepMultiplicityAndOnlySanitizedValuesAreStored() {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(3);
        List<String> originals = new ArrayList<>(List.of("password=one", "password=two", "password=two"));
        collector.record("rule", 3, originals, value -> value.substring(0, value.indexOf('=')) + "=******");
        originals.clear();

        assertThat(collector.snapshot().rules().get("rule").details())
                .containsExactly("password=******", "password=******", "password=******");
        assertThat(collector.snapshot().total()).isEqualTo(3);
    }

    @Test
    void missingUpstreamDetailsDoNotInventCompletenessOrExtraViolations() {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(10);
        collector.record("partial", 4, List.of("known"), UnaryOperator.identity());
        collector.record("unknown", 3, null, UnaryOperator.identity());
        collector.record("extra", 1, List.of("counted", "not-counted"), UnaryOperator.identity());
        collector.record("passed", 0, List.of("not-a-finding"), UnaryOperator.identity());

        assertThat(collector.snapshot().total()).isEqualTo(8);
        assertThat(collector.snapshot().retained()).isEqualTo(2);
        assertThat(collector.snapshot().rules().get("partial").violationCount()).isEqualTo(4);
        assertThat(collector.snapshot().rules().get("unknown").details()).isEmpty();
        assertThat(collector.snapshot().rules().get("extra").details()).containsExactly("counted");
        assertThat(collector.snapshot().rules()).doesNotContainKey("passed");
    }

    @Test
    void snapshotsRemainImmutableAndDetachedFromLaterCollection() {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(3);
        collector.record("rule", 1, List.of("first"), UnaryOperator.identity());
        AdvisorViolationCollector.Snapshot first = collector.snapshot();
        collector.record("rule", 1, List.of("second"), UnaryOperator.identity());
        collector.record("other", 1, List.of("third"), UnaryOperator.identity());

        assertThat(first.total()).isEqualTo(1);
        assertThat(first.rules().keySet()).containsExactly("rule");
        assertThat(first.rules().get("rule").details()).containsExactly("first");
        assertThatThrownBy(() -> first.rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.rules().get("rule").details().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sanitizerFailureDoesNotLeaveAPartiallyRecordedRule() {
        AdvisorViolationCollector collector = new AdvisorViolationCollector(5);
        collector.record("rule", 1, List.of("existing"), UnaryOperator.identity());
        assertThatThrownBy(() -> collector.record("rule", 2, List.of("ok", "fail"), value -> {
                    if ("fail".equals(value)) {
                        throw new IllegalStateException("Cannot sanitize");
                    }
                    return value;
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(collector.snapshot().total()).isEqualTo(1);
        assertThat(collector.snapshot().retained()).isEqualTo(1);
        assertThat(collector.snapshot().rules().get("rule").details()).containsExactly("existing");
    }

    @Test
    void rejectsInvalidCollectionInputsAndCountOverflowWithoutChangingState() {
        assertThatThrownBy(() -> new AdvisorViolationCollector(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdvisorViolationCollector(-1)).isInstanceOf(IllegalArgumentException.class);
        AdvisorViolationCollector collector = new AdvisorViolationCollector(1);
        assertThatThrownBy(() -> collector.record(null, 1, List.of("detail"), UnaryOperator.identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collector.record(" ", 1, List.of("detail"), UnaryOperator.identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collector.record("rule", -1, List.of(), UnaryOperator.identity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collector.record("rule", 1, List.of("detail"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> collector.record("rule", 1, List.of("detail"), value -> null))
                .isInstanceOf(NullPointerException.class);
        assertThat(collector.snapshot().rules()).isEmpty();

        collector.record("rule", Integer.MAX_VALUE, List.of("detail"), UnaryOperator.identity());
        assertThatThrownBy(() -> collector.record("another", 1, List.of("other"), UnaryOperator.identity()))
                .isInstanceOf(ArithmeticException.class);
        assertThat(collector.snapshot().total()).isEqualTo(Integer.MAX_VALUE);
        assertThat(collector.snapshot().rules().keySet()).containsExactly("rule");
    }

    @Test
    void samplesAreCutFromTheRetainedRecordsAndKeepTheirLocationsWhateverTheBudget() {
        io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto first =
                new io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto(
                        "a.First", "m", "METHOD", "First.java", 3, null);
        io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto third =
                new io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto(
                        "a.Third", null, null, "Third.java", null, null);
        List<AdvisorViolation> findings = List.of(
                new AdvisorViolation("first", first),
                AdvisorViolation.of("second"),
                new AdvisorViolation("third", third));
        AdvisorViolationCollector collector = new AdvisorViolationCollector(2);

        List<AdvisorViolation> samples = collector.record("rule", 3, findings, String::toUpperCase, 10);

        assertThat(samples)
                .containsExactly(
                        new AdvisorViolation("FIRST", first),
                        new AdvisorViolation("SECOND", null),
                        new AdvisorViolation("THIRD", third));
        AdvisorViolationCollector.Rule rule = collector.snapshot().rules().get("rule");
        assertThat(rule.violations()).containsExactlyElementsOf(samples.subList(0, 2));
        assertThat(AdvisorViolation.locations(rule.violations())).containsExactly(first, null);
        assertThat(AdvisorViolation.locations(List.of(AdvisorViolation.of("x"))))
                .isEmpty();

        assertThat(collector.record("exhausted", 1, List.of(new AdvisorViolation("late", first)), s -> s, 1))
                .containsExactly(new AdvisorViolation("late", first));
        assertThat(collector.snapshot().rules().get("exhausted").violations()).isEmpty();
    }

    @Test
    void locationCompletionChangesOnlyLocationsAndNotesTravelWithTheSnapshot() {
        io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto located =
                new io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto(
                        "a.B", "m", "METHOD", "B.java", 9, null);
        AdvisorViolationCollector collector = new AdvisorViolationCollector(10);
        collector.record(
                "rule", 2, List.of(new AdvisorViolation("one", located), AdvisorViolation.of("two")), s -> s, 0);
        List<io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto> seen = new ArrayList<>();
        collector.forEachLocation(seen::add);
        assertThat(seen).containsExactly(located);

        collector.mapLocations(location -> location.withSourcePath("/work/B.java"));
        collector.addLocationNotes(List.of("One class kept no path."));

        AdvisorViolationCollector.Snapshot snapshot = collector.snapshot();
        assertThat(snapshot.rules().get("rule").details()).containsExactly("one", "two");
        assertThat(snapshot.rules().get("rule").violations().get(0).location().sourcePath())
                .isEqualTo("/work/B.java");
        assertThat(snapshot.rules().get("rule").violations().get(1).location()).isNull();
        assertThat(snapshot.total()).isEqualTo(2);
        assertThat(snapshot.locationNotes()).containsExactly("One class kept no path.");
        assertThatThrownBy(() -> collector.record("rule", 1, List.of(AdvisorViolation.of("x")), s -> s, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
