package io.github.jdubois.bootui.engine.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CaptureRetentionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TieredCaptureBufferTests {

    @Test
    void reservesTheConfiguredShareRoundedDownAndNeverTheWholeBuffer() {
        assertThat(TieredCaptureBuffer.reservedCapacity(200, 25)).isEqualTo(50);
        assertThat(TieredCaptureBuffer.reservedCapacity(10, 25)).isEqualTo(2);
        assertThat(TieredCaptureBuffer.reservedCapacity(3, 25)).isZero();
        assertThat(TieredCaptureBuffer.reservedCapacity(10, 0)).isZero();
        assertThat(TieredCaptureBuffer.reservedCapacity(10, -5)).isZero();
        assertThat(TieredCaptureBuffer.reservedCapacity(10, 100)).isEqualTo(9);
        assertThat(TieredCaptureBuffer.reservedCapacity(10, 250)).isEqualTo(9);
        assertThat(TieredCaptureBuffer.reservedCapacity(1, 100)).isZero();
        assertThat(TieredCaptureBuffer.reservedCapacity(0, 50)).isZero();
        assertThat(TieredCaptureBuffer.reservedCapacity(Integer.MAX_VALUE, 100)).isEqualTo(Integer.MAX_VALUE - 1);
    }

    @Test
    void floodOfRoutineRecordsKeepsTheMostRecentFailuresUpToTheReservedCapacity() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(10, 30);
        for (int i = 1; i <= 5; i++) {
            buffer.add("failure-" + i, true);
        }
        for (int i = 1; i <= 1_000; i++) {
            buffer.add("ok-" + i, false);
        }

        List<String> retained = buffer.newestFirst();
        assertThat(retained).hasSize(10);
        // Only the three most recent failures fit the reservation; the older two aged out with routine traffic.
        assertThat(retained).containsSubsequence("failure-5", "failure-4", "failure-3");
        assertThat(retained).doesNotContain("failure-1", "failure-2");
        assertThat(retained.subList(0, 7))
                .containsExactly("ok-1000", "ok-999", "ok-998", "ok-997", "ok-996", "ok-995", "ok-994");
        TieredCaptureBuffer.Snapshot<String> snapshot = buffer.snapshot();
        assertThat(snapshot.reserved()).isEqualTo(3);
        assertThat(snapshot.evicted()).isEqualTo(1_005 - 10);
    }

    @Test
    void readsStayNewestFirstAcrossBothTiers() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(6, 50);
        buffer.add("a", false);
        buffer.add("b", true);
        buffer.add("c", false);
        buffer.add("d", true);
        buffer.add("e", false);

        assertThat(buffer.newestFirst()).containsExactly("e", "d", "c", "b", "a");
        assertThat(buffer.oldestFirst()).containsExactly("a", "b", "c", "d", "e");

        buffer.add("f", false);
        buffer.add("g", false);
        assertThat(buffer.newestFirst()).containsExactly("g", "f", "e", "d", "c", "b");
        // "c" goes before the older "b", because "b" is held in the reserved share.
        buffer.add("h", false);
        assertThat(buffer.newestFirst()).containsExactly("h", "g", "f", "e", "d", "b");
    }

    @Test
    void fullReservedShareEvictsItsOwnOldestRecordByAge() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(4, 50);
        buffer.add("f1", true);
        buffer.add("f2", true);
        buffer.add("ok1", false);
        buffer.add("f3", true);
        // Full with three flagged records, one more than the reservation: f1 is outside it and is the oldest record.
        buffer.add("ok2", false);
        assertThat(buffer.newestFirst()).containsExactly("ok2", "f3", "ok1", "f2");

        // f3 and f4 now fill the two-record reservation, so f2 competes by age and is older than ok1.
        buffer.add("f4", true);
        assertThat(buffer.newestFirst()).containsExactly("f4", "ok2", "f3", "ok1");
        // Both flagged records are reserved, so the oldest routine record goes.
        buffer.add("ok3", false);
        assertThat(buffer.newestFirst()).containsExactly("ok3", "f4", "ok2", "f3");
        assertThat(buffer.snapshot().reserved()).isEqualTo(2);
    }

    @Test
    void failureOnlyTrafficStillFillsTheWholeCapacity() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(5, 20);
        for (int i = 1; i <= 8; i++) {
            buffer.add("failure-" + i, true);
        }

        assertThat(buffer.newestFirst())
                .containsExactly("failure-8", "failure-7", "failure-6", "failure-5", "failure-4");
        TieredCaptureBuffer.Snapshot<String> snapshot = buffer.snapshot();
        assertThat(snapshot.reserved()).isEqualTo(1);
        assertThat(snapshot.evicted()).isEqualTo(3);
    }

    @Test
    void capacityOfOneAlwaysKeepsTheNewestRecord() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(1, 100);
        assertThat(buffer.reservedCapacity()).isZero();

        buffer.add("failure", true);
        buffer.add("ok", false);
        assertThat(buffer.newestFirst()).containsExactly("ok");
        buffer.add("another-failure", true);
        assertThat(buffer.newestFirst()).containsExactly("another-failure");
        assertThat(buffer.snapshot().reserved()).isZero();
        assertThat(buffer.evicted()).isEqualTo(2);
    }

    @Test
    void zeroShareEvictsStrictlyOldestFirst() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(3, 0);
        buffer.add("failure", true);
        buffer.add("ok1", false);
        buffer.add("ok2", false);
        buffer.add("ok3", false);

        assertThat(buffer.newestFirst()).containsExactly("ok3", "ok2", "ok1");
        assertThat(buffer.snapshot().reserved()).isZero();
    }

    @Test
    void newestRecordIsAlwaysRetainedWhenTheReservationIsAlmostTheWholeBuffer() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(3, 100);
        buffer.add("f1", true);
        buffer.add("f2", true);
        buffer.add("ok1", false);
        buffer.add("ok2", false);

        assertThat(buffer.newestFirst()).containsExactly("ok2", "f2", "f1");
    }

    @Test
    void clearDropsRecordsButKeepsTheEvictionCount() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(2, 50);
        buffer.add("a", false);
        buffer.add("b", true);
        buffer.add("c", false);
        buffer.add(null, true);

        buffer.clear();

        assertThat(buffer.newestFirst()).isEmpty();
        assertThat(buffer.size()).isZero();
        assertThat(buffer.evicted()).isEqualTo(1);
        CaptureRetentionDto retention = buffer.snapshot().retention(250);
        assertThat(retention).isEqualTo(new CaptureRetentionDto(false, 2, 1, 0, 0, 1L, 250L));
    }

    @Test
    void retentionCountsReconcileWithTheRetainedRecords() {
        TieredCaptureBuffer<String> buffer = new TieredCaptureBuffer<>(8, 25);
        for (int i = 0; i < 30; i++) {
            buffer.add("r" + i, i % 4 == 0);
        }

        TieredCaptureBuffer.Snapshot<String> snapshot = buffer.snapshot();
        CaptureRetentionDto retention = snapshot.retention(-1);
        assertThat(retention.applicationManaged()).isFalse();
        assertThat(retention.capacity()).isEqualTo(8);
        assertThat(retention.reservedCapacity()).isEqualTo(2);
        assertThat(retention.retained())
                .isEqualTo(snapshot.newestFirst().size())
                .isEqualTo(8);
        assertThat(retention.reserved()).isEqualTo(2);
        assertThat(retention.evicted()).isEqualTo(30 - 8);
        assertThat(retention.slowThresholdMillis()).isZero();
        assertThat(snapshot.oldestFirst()).isEqualTo(reversed(snapshot.newestFirst()));
    }

    @Test
    void matchesTheReferenceEvictionModelUnderRandomMixedLoad() {
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            int capacity = 1 + random.nextInt(12);
            int share = random.nextInt(110);
            double failureRate = random.nextDouble();
            TieredCaptureBuffer<Integer> buffer = new TieredCaptureBuffer<>(capacity, share);
            ReferenceModel model = new ReferenceModel(capacity, TieredCaptureBuffer.reservedCapacity(capacity, share));
            for (int value = 0; value < 80; value++) {
                boolean flagged = random.nextDouble() < failureRate;
                buffer.add(value, flagged);
                model.add(value, flagged);

                TieredCaptureBuffer.Snapshot<Integer> snapshot = buffer.snapshot();
                assertThat(snapshot.newestFirst())
                        .as("capacity %d, share %d, after %d", capacity, share, value)
                        .isEqualTo(model.newestFirst());
                assertThat(snapshot.retained()).isLessThanOrEqualTo(capacity);
                assertThat(snapshot.reserved()).isLessThanOrEqualTo(snapshot.reservedCapacity());
                assertThat(snapshot.retained() + snapshot.evicted()).isEqualTo(value + 1L);
                assertThat(snapshot.newestFirst().get(0)).isEqualTo(value);
            }
        }
    }

    @Test
    void concurrentWritersNeverExceedCapacityAndCountsReconcile() throws Exception {
        TieredCaptureBuffer<Integer> buffer = new TieredCaptureBuffer<>(50, 25);
        int threads = 8;
        int perThread = 5_000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                int offset = t * perThread;
                executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        buffer.add(offset + i, i % 7 == 0);
                        if (i % 97 == 0) {
                            TieredCaptureBuffer.Snapshot<Integer> snapshot = buffer.snapshot();
                            assertThat(snapshot.retained()).isLessThanOrEqualTo(50);
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        TieredCaptureBuffer.Snapshot<Integer> snapshot = buffer.snapshot();
        assertThat(snapshot.retained()).isEqualTo(50);
        assertThat(snapshot.evicted()).isEqualTo((long) threads * perThread - 50);
        assertThat(snapshot.reserved()).isEqualTo(12);
        assertThat(snapshot.newestFirst()).doesNotHaveDuplicates();
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /**
     * The retention rule stated directly: when over capacity, evict the oldest record that is not one of the
     * {@code reservedCapacity} most recent flagged records.
     */
    private static final class ReferenceModel {

        private final int capacity;
        private final int reservedCapacity;
        private final List<Integer> values = new ArrayList<>();
        private final List<Boolean> flags = new ArrayList<>();

        private ReferenceModel(int capacity, int reservedCapacity) {
            this.capacity = capacity;
            this.reservedCapacity = reservedCapacity;
        }

        void add(int value, boolean flagged) {
            values.add(value);
            flags.add(flagged);
            if (values.size() <= capacity) {
                return;
            }
            int flaggedCount =
                    (int) flags.stream().filter(Boolean::booleanValue).count();
            int flaggedSeen = 0;
            for (int i = 0; i < values.size(); i++) {
                boolean protectedRecord = false;
                if (flags.get(i)) {
                    flaggedSeen++;
                    protectedRecord = flaggedCount - flaggedSeen < reservedCapacity;
                }
                if (!protectedRecord) {
                    values.remove(i);
                    flags.remove(i);
                    return;
                }
            }
            values.remove(0);
            flags.remove(0);
        }

        List<Integer> newestFirst() {
            return reversed(values);
        }
    }
}
