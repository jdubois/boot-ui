package io.github.jdubois.bootui.agent.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The transport ring and its intern table (PLAN-v2 M5-3): bounded, never blocking, one drainer, losses counted. */
class AgentRingTests {

    private final List<Object> keep = new ArrayList<>();

    @BeforeEach
    void install() {
        AgentBridge.reset();
        AgentBridge.install(request -> {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "ok");
            return answer;
        });
    }

    @AfterEach
    void reset() {
        AgentBridge.reset();
    }

    @Test
    void capacityIsClampedAndRoundedToAPowerOfTwo() {
        assertThat(AgentRing.capacity(0)).isEqualTo(65_536);
        assertThat(AgentRing.capacity(-5)).isEqualTo(65_536);
        assertThat(AgentRing.capacity(10)).isEqualTo(1024);
        assertThat(AgentRing.capacity(1000)).isEqualTo(1024);
        assertThat(AgentRing.capacity(1024)).isEqualTo(1024);
        assertThat(AgentRing.capacity(5000)).isEqualTo(8192);
        assertThat(AgentRing.capacity(Integer.MAX_VALUE)).isEqualTo(1 << 22);
    }

    @Test
    void theRingIsAllocatedAtTheFirstClaimAskingForARingSensorNotBefore() {
        claim("shop", List.of("executors"), 4096);
        assertThat(AgentRing.ring()).isNull();
        assertThat(AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 1, 2, 3, 4))
                .isFalse();
        assertThat(AgentRing.dropped(AgentRing.SENSOR_INVENTORY)).isEqualTo(1);

        claim("shop", List.of("inventory"), 4096);
        assertThat(AgentRing.ring()).isNotNull();
        assertThat(AgentRing.ring().capacity).isEqualTo(4096);

        claim("shop", List.of("inventory"), 1 << 20);
        assertThat(AgentRing.ring().capacity).as("kept for the JVM's life").isEqualTo(4096);
    }

    @Test
    void concurrentProducersBelowCapacityLoseNothingAndKeepEachProducersOrder() throws Exception {
        long token = claim("shop", List.of("inventory"), 4096);
        int producers = 4;
        int each = 1000;
        publishConcurrently(producers, each);

        List<long[]> drained = new ArrayList<>();
        assertThat(AgentRing.drain(token, copyInto(drained))).isEqualTo(producers * each);

        long[] lastSeen = new long[producers];
        java.util.Arrays.fill(lastSeen, -1);
        for (long[] record : drained) {
            assertThat(record[AgentRing.SENSOR]).isEqualTo(AgentRing.SENSOR_INVENTORY);
            int producer = (int) record[AgentRing.PAYLOAD];
            long sequence = record[AgentRing.PAYLOAD + 1];
            assertThat(sequence).isEqualTo(lastSeen[producer] + 1);
            lastSeen[producer] = sequence;
        }
        assertThat(lastSeen).containsOnly(each - 1L);
        assertThat(AgentRing.dropped(AgentRing.SENSOR_INVENTORY)).isZero();
        assertThat(AgentRing.lost()).isZero();
    }

    @Test
    void aFullRingDropsAndCountsExactlyWhatItCouldNotHold() throws Exception {
        long token = claim("shop", List.of("inventory"), 1024);
        int producers = 4;
        int each = 700;
        publishConcurrently(producers, each);

        List<long[]> drained = new ArrayList<>();
        assertThat(AgentRing.drain(token, copyInto(drained))).isEqualTo(1024);
        assertThat(AgentRing.dropped(AgentRing.SENSOR_INVENTORY)).isEqualTo(producers * each - 1024L);
        assertThat(AgentRing.status()).containsEntry("size", 0L);

        assertThat(AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 9, 9, 9, 9))
                .as("drained slots are reused")
                .isTrue();
    }

    @Test
    void producersAndOneDrainerTogetherAccountForEveryRecord() throws Exception {
        long token = claim("shop", List.of("inventory"), 1024);
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger received = new AtomicInteger();
        Thread drainer = new Thread(() -> {
            Consumer<long[]> sink = record -> received.incrementAndGet();
            while (!done.get()) {
                AgentRing.drain(token, sink);
            }
            AgentRing.drain(token, sink);
        });
        drainer.start();
        int producers = 4;
        int each = 20_000;
        publishConcurrently(producers, each);
        done.set(true);
        drainer.join(10_000);

        assertThat(AgentRing.lost())
                .as("a drainer polling in a tight loop never skips a slot")
                .isZero();
        assertThat(received.get() + AgentRing.dropped(AgentRing.SENSOR_INVENTORY))
                .isEqualTo((long) producers * each);
    }

    @Test
    void aSlotClaimedAndNeverPublishedIsSkippedAfterFiftyDrainsAndCountedLost() throws Exception {
        long token = claim("shop", List.of("inventory"), 1024);
        AgentRing.stuckNanos = 20_000_000L;
        AgentRing.Ring ring = AgentRing.ring();
        long stuck = ring.claim();
        for (int i = 0; i < 3; i++) {
            assertThat(AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, i, 0, 0, 0))
                    .isTrue();
        }
        List<long[]> drained = new ArrayList<>();
        for (int i = 1; i < AgentRing.STUCK_DRAINS; i++) {
            assertThat(AgentRing.drain(token, copyInto(drained)))
                    .as("drain %d waits for the claimed slot", i)
                    .isZero();
        }
        Thread.sleep(25);

        assertThat(AgentRing.drain(token, copyInto(drained))).isEqualTo(3);
        assertThat(AgentRing.lost()).isEqualTo(1);
        assertThat(drained).extracting(record -> record[AgentRing.PAYLOAD]).containsExactly(0L, 1L, 2L);

        assertThat(ring.write(stuck, AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 7, 7, 7, 7))
                .as("the producer publishing late fails rather than corrupting a later lap")
                .isFalse();
        assertThat(AgentRing.status()).containsEntry("latePublishes", 1L);
        assertThat(AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 3, 0, 0, 0))
                .isTrue();
        drained.clear();
        assertThat(AgentRing.drain(token, copyInto(drained))).isOne();
        assertThat(drained.get(0)[AgentRing.PAYLOAD]).isEqualTo(3L);
    }

    @Test
    void aProducerReachingASkippedSlotAfterTheNextLapWroteItLeavesThatRecordIntact() throws Exception {
        long token = claim("shop", List.of("inventory"), 1024);
        AgentRing.stuckNanos = 0L;
        AgentRing.Ring ring = AgentRing.ring();
        long stuck = ring.claim();
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, -1, 0, 0, 0);
        List<long[]> drained = new ArrayList<>();
        for (int i = 0; i < AgentRing.STUCK_DRAINS; i++) {
            AgentRing.drain(token, copyInto(drained));
        }
        assertThat(AgentRing.lost()).as("the stuck slot was skipped").isOne();
        drained.clear();
        // A whole lap later, the stuck slot holds the record of position stuck + capacity.
        for (int i = 0; i < ring.capacity; i++) {
            assertThat(AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, i, 0, 0, 0))
                    .isTrue();
        }

        assertThat(ring.write(stuck, AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, -666, -666, -666, -666))
                .as("the late producer writes nothing")
                .isFalse();

        assertThat(AgentRing.drain(token, copyInto(drained))).isEqualTo(ring.capacity);
        assertThat(drained)
                .extracting(record -> record[AgentRing.PAYLOAD])
                .doesNotContain(-666L)
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, ring.capacity)
                        .boxed()
                        .toList());
        assertThat(AgentRing.status()).containsEntry("latePublishes", 1L).containsEntry("tornRecords", 0L);
    }

    @Test
    void aRecordPublishedWithoutItsStampIsDroppedAndCountedLost() {
        long token = claim("shop", List.of("inventory"), 1024);
        AgentRing.Ring ring = AgentRing.ring();
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 1, 0, 0, 0);
        ring.publishTorn(ring.claim());
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 3, 0, 0, 0);

        List<long[]> drained = new ArrayList<>();
        assertThat(AgentRing.drain(token, copyInto(drained))).isEqualTo(2);

        assertThat(drained).extracting(record -> record[AgentRing.PAYLOAD]).containsExactly(1L, 3L);
        assertThat(AgentRing.lost()).isOne();
        assertThat(AgentRing.status()).containsEntry("tornRecords", 1L);
    }

    @Test
    void aDrainerStopsAtTheFirstRecordOfANewerClaimGeneration() {
        long first = claim("shop", List.of("inventory"), 1024);
        long firstGeneration = CodeInventory.currentGeneration();
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, firstGeneration, 1L, 1, 0, 0, 0);
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, firstGeneration, 1L, 2, 0, 0, 0);
        long[] second = new long[1];
        List<long[]> stale = new ArrayList<>();

        int drained = AgentRing.drain(first, record -> {
            stale.add(record.clone());
            if (second[0] == 0L) {
                // A new claim starts while the previous run's drainer is still at work, and records at once.
                second[0] = claim("shop", List.of("inventory"), 1024);
                AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, CodeInventory.currentGeneration(), 1L, 3, 0, 0, 0);
            }
        });

        assertThat(drained).isEqualTo(2);
        assertThat(stale).extracting(record -> record[AgentRing.PAYLOAD]).containsExactly(1L, 2L);
        assertThat(AgentRing.status()).containsEntry("newerGenerationStops", 1L);
        List<long[]> next = new ArrayList<>();
        assertThat(AgentRing.drain(second[0], copyInto(next))).isOne();
        assertThat(next.get(0)[AgentRing.PAYLOAD]).isEqualTo(3L);
        assertThat(next.get(0)[AgentRing.GENERATION]).isGreaterThan(firstGeneration);
    }

    @Test
    void producersRacingForOneSlotNeverReportTheRingFullWhileItHasRoom() throws Exception {
        long token = claim("shop", List.of("inventory"), 1024);
        int producers = 8;
        int each = 100;
        publishConcurrently(producers, each);

        assertThat(AgentRing.dropped(AgentRing.SENSOR_INVENTORY)).isZero();
        assertThat(AgentRing.drain(token, record -> {})).isEqualTo(producers * each);
    }

    @Test
    void fiftyQuickDrainsAloneDoNotSkipAClaimedSlot() {
        long token = claim("shop", List.of("inventory"), 1024);
        AgentRing.ring().claim();
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 1, 0, 0, 0);
        for (int i = 0; i < 200; i++) {
            assertThat(AgentRing.drain(token, record -> {})).isZero();
        }
        assertThat(AgentRing.lost()).isZero();
    }

    @Test
    void aClaimedSlotPublishedBeforeFiftyDrainsIsReadInOrder() {
        long token = claim("shop", List.of("inventory"), 1024);
        AgentRing.Ring ring = AgentRing.ring();
        long slow = ring.claim();
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 1, 0, 0, 0);
        List<long[]> drained = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AgentRing.drain(token, copyInto(drained));
        }
        assertThat(drained).isEmpty();

        assertThat(ring.write(slow, AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 0, 0, 0, 0))
                .isTrue();
        assertThat(AgentRing.drain(token, copyInto(drained))).isEqualTo(2);
        assertThat(drained).extracting(record -> record[AgentRing.PAYLOAD]).containsExactly(0L, 1L);
        assertThat(AgentRing.lost()).isZero();
    }

    @Test
    void aStaleTokenDrainsNothing() {
        long first = claim("shop", List.of("inventory"), 1024);
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 1, 0, 0, 0);
        long second = claim("shop", List.of("inventory"), 1024);

        List<long[]> drained = new ArrayList<>();
        assertThat(AgentRing.drain(first, copyInto(drained))).isZero();
        assertThat(drained).isEmpty();
        assertThat(AgentRing.status()).containsEntry("staleDrains", 1L);
        assertThat(AgentRing.drain(second, copyInto(drained))).isOne();
    }

    @Test
    void twoConcurrentDrainersNeverBothDrain() throws Exception {
        long token = claim("shop", List.of("inventory"), 1024);
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 1, 0, 0, 0);
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 2, 0, 0, 0);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = pool.submit(() -> AgentRing.drain(token, record -> {
                inside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(AgentRing.drain(token, record -> {})).isZero();
            assertThat(AgentRing.status()).containsEntry("busyDrains", 1L);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailingSinkLosesOnlyItsRecordAndNeverThrows() {
        long token = claim("shop", List.of("inventory"), 1024);
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 1, 0, 0, 0);
        AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, 2, 0, 0, 0);

        assertThat(AgentRing.drain(token, record -> {
                    throw new IllegalStateException("sink");
                }))
                .isEqualTo(2);
        assertThat(AgentRing.status()).containsEntry("sinkErrors", 2L);
        assertThat(AgentRing.drain(token, null)).isZero();
    }

    @Test
    void theInternTableIsScopedToItsClaimGeneration() {
        claim("shop", List.of("inventory"), 1024);
        long generation = CodeInventory.currentGeneration();
        assertThat(AgentRing.intern("/orders/{id}")).isOne();
        assertThat(AgentRing.intern("/cart")).isEqualTo(2);
        assertThat(AgentRing.intern("/orders/{id}")).isOne();
        assertThat(AgentRing.intern(null)).isZero();
        assertThat(AgentRing.interned(generation, 1)).containsExactly("/orders/{id}", "/cart");
        assertThat(AgentRing.interned(generation, 2)).containsExactly("/cart");

        claim("shop", List.of("inventory"), 1024);
        long next = CodeInventory.currentGeneration();
        assertThat(AgentRing.interned(generation, 1))
                .as("another generation's table")
                .isNull();
        assertThat(AgentRing.interned(next, 1)).isEmpty();
        assertThat(AgentRing.intern("/cart")).isOne();
    }

    @Test
    void aFullInternTableAnswersUnknownAndCountsIt() {
        claim("shop", List.of("inventory"), 1024);
        for (int i = 0; i < AgentRing.DEFAULT_INTERNS; i++) {
            assertThat(AgentRing.intern("route-" + i)).isEqualTo(i + 1);
        }

        assertThat(AgentRing.intern("one too many")).isZero();
        assertThat(AgentRing.intern("route-7"))
                .as("known strings keep their id")
                .isEqualTo(8);
        assertThat(AgentRing.internOverflow()).isOne();
        assertThat(AgentRing.status()).containsEntry("interned", AgentRing.DEFAULT_INTERNS);
    }

    private void publishConcurrently(int producers, int each) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int producer = p;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    AgentRing.publish(AgentRing.SENSOR_INVENTORY, 1, 1L, 1L, producer, i, 0, 0);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static Consumer<long[]> copyInto(List<long[]> records) {
        return record -> records.add(record.clone());
    }

    private long claim(String application, List<String> sensors, int ringCapacity) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("application", application);
        request.put("mode", "dev");
        request.put("packages", List.of("com.example"));
        request.put("sensors", sensors);
        request.put("ringCapacity", ringCapacity);
        Supplier<Object> capture = () -> null;
        Function<Object, AutoCloseable> reopen = snapshot -> null;
        keep.add(capture);
        keep.add(reopen);
        Map<String, Object> result = AgentBridge.claim(request, capture, reopen);
        assertThat(result.get("status")).isEqualTo(AgentBridge.ARMED);
        return (Long) result.get("token");
    }
}
