package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JournalQueueTests {

    @Test
    void keepsTheReservedShareForFailedOrSlowEventsAndTakesEventsInOrder() throws Exception {
        JournalQueue queue = new JournalQueue(4, 2, null);

        assertThat(accepted(queue.offer(event(1, false), false))).isTrue();
        assertThat(accepted(queue.offer(event(2, false), false))).isTrue();
        assertThat(queue.offer(event(3, false), false))
                .as("routine events stop at the routine limit")
                .isEqualTo(JournalQueue.FULL);
        assertThat(accepted(queue.offer(event(4, true), true))).isTrue();
        assertThat(accepted(queue.offer(event(5, true), true))).isTrue();
        assertThat(queue.offer(event(6, true), true)).isEqualTo(JournalQueue.FULL);
        assertThat(queue.size()).isEqualTo(4);

        assertThat(queue.poll(0, TimeUnit.MILLISECONDS).epochMillis()).isEqualTo(1);
        List<RuntimeEvent> batch = new ArrayList<>();
        assertThat(queue.drainTo(batch, 2)).isEqualTo(2);
        assertThat(accepted(queue.offer(event(7, false), false))).isTrue();
        assertThat(queue.drainTo(batch, 10)).isEqualTo(2);
        assertThat(batch).extracting(RuntimeEvent::epochMillis).containsExactly(2L, 4L, 5L, 7L);
        assertThat(queue.poll(0, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void reachingHalfTheRoutineShareAsksForTheDispatcherOnce() {
        JournalQueue queue = new JournalQueue(10, 8, null);

        assertThat(queue.offer(event(1, false), false)).isEqualTo(JournalQueue.ACCEPTED);
        assertThat(queue.offer(event(2, false), false)).isEqualTo(JournalQueue.ACCEPTED);
        assertThat(queue.offer(event(3, false), false)).isEqualTo(JournalQueue.ACCEPTED);
        assertThat(queue.offer(event(4, false), false)).isEqualTo(JournalQueue.ACCEPTED_FILLING);
        assertThat(queue.offer(event(5, false), false)).isEqualTo(JournalQueue.ACCEPTED);
    }

    @Test
    void routineEventsNeverOvershootTheirShareUnderConcurrentOffers() throws Exception {
        int producers = 8;
        int perProducer = 2_000;
        JournalQueue queue = new JournalQueue(1_000, 600, null);
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    int accepted = 0;
                    for (int i = 0; i < perProducer; i++) {
                        int outcome = queue.offer(event(i, false), false);
                        if (outcome == JournalQueue.ACCEPTED || outcome == JournalQueue.ACCEPTED_FILLING) {
                            accepted++;
                        }
                    }
                    return accepted;
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Integer> future : futures) {
                accepted += future.get(30, TimeUnit.SECONDS);
            }
            assertThat(accepted).isEqualTo(600);
            assertThat(queue.size()).isEqualTo(600);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDetachedQueueDropsWhatItHeldAndTakesNothingMore() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null);
        queue.offer(event(1, false), false);
        queue.offer(event(2, false), false);
        queue.offer(event(3, false), false);
        List<RuntimeEvent> taken = new ArrayList<>();
        queue.drainTo(taken, 1);
        queue.offer(event(4, false), false);
        queue.offer(event(5, false), false);

        JournalQueue.Detached dropped = queue.detach();

        assertThat(dropped.count()).isEqualTo(4);
        List<Long> visited = new ArrayList<>();
        dropped.forEach(event -> visited.add(event.epochMillis()));
        assertThat(visited).as("what it held, oldest first, across the wrap").containsExactly(2L, 3L, 4L, 5L);
        assertThat(queue.detach().count())
                .as("only the first detach drops events")
                .isZero();
        assertThat(queue.size()).isZero();
        assertThat(queue.offer(event(3, true), true)).isEqualTo(JournalQueue.DETACHED);
        assertThat(queue.drainTo(new ArrayList<>(), 10)).isZero();
        assertThat(queue.poll(30, TimeUnit.SECONDS))
                .as("a detached queue never makes the dispatcher wait")
                .isNull();
    }

    @Test
    void aSealedQueueTakesNothingMoreButKeepsWhatItHeldForTheLastDrain() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null);
        queue.offer(event(1, false), false);

        queue.seal();

        assertThat(queue.offer(event(2, true), true)).isEqualTo(JournalQueue.DETACHED);
        List<RuntimeEvent> batch = new ArrayList<>();
        assertThat(queue.drainTo(batch, 10)).isEqualTo(1);
        assertThat(batch).extracting(RuntimeEvent::epochMillis).containsExactly(1L);
    }

    @Test
    void aClosedJournalsQueueRefusesEveryEventAndIsNeverDetached() {
        JournalQueue queue = JournalQueue.closed();

        assertThat(queue.offer(event(1, true), true)).isEqualTo(JournalQueue.FULL);
        assertThat(queue.detach().count()).isZero();
        assertThat(queue.offer(event(2, false), false))
                .as("never detached, so an offer is never retried forever")
                .isEqualTo(JournalQueue.FULL);
        assertThat(queue.refusing()).isTrue();
    }

    @Test
    void detachingWakesADispatcherWaitingOnTheQueue() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null);
        AtomicReference<RuntimeEvent> polled = new AtomicReference<>(event(0, false));
        Thread dispatcher = new Thread(() -> {
            try {
                polled.set(queue.poll(30, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        dispatcher.start();
        while (dispatcher.getState() != Thread.State.TIMED_WAITING && dispatcher.isAlive()) {
            Thread.onSpinWait();
        }

        queue.detach();
        dispatcher.join(5_000);

        assertThat(dispatcher.isAlive()).isFalse();
        assertThat(polled.get()).isNull();
    }

    private static boolean accepted(int outcome) {
        return outcome == JournalQueue.ACCEPTED || outcome == JournalQueue.ACCEPTED_FILLING;
    }

    private static RuntimeEvent event(long epochMillis, boolean failedOrSlow) {
        return RuntimeEvent.of(JournalSource.SQL, epochMillis, 1, null, null, null, failedOrSlow, null);
    }
}
