package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
        JournalQueue queue = new JournalQueue(4, 2, null, null);

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
    void aPauseEndsAsSoonAsTheQueueHoldsHalfItsRoutineShare() throws Exception {
        JournalQueue queue = new JournalQueue(10, 8, null, null);
        for (int i = 0; i < 3; i++) {
            queue.offer(event(i, false), false);
        }
        AtomicReference<Long> pausedNanos = new AtomicReference<>();
        Thread dispatcher = new Thread(() -> {
            long started = System.nanoTime();
            try {
                queue.awaitFilling(TimeUnit.SECONDS.toNanos(30));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            pausedNanos.set(System.nanoTime() - started);
        });
        dispatcher.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (dispatcher.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(dispatcher.getState())
                .as("three of a routine share of eight do not end the pause")
                .isEqualTo(Thread.State.TIMED_WAITING);

        queue.offer(event(3, false), false);

        dispatcher.join(5_000);
        assertThat(dispatcher.isAlive())
                .as("the fourth offer signals the waiting dispatcher")
                .isFalse();
        assertThat(pausedNanos.get()).isLessThan(TimeUnit.SECONDS.toNanos(5));
        long started = System.nanoTime();
        queue.offer(event(4, false), false);
        queue.awaitFilling(TimeUnit.SECONDS.toNanos(30));
        assertThat(System.nanoTime() - started)
                .as("a queue already past half its share is not waited on")
                .isLessThan(TimeUnit.SECONDS.toNanos(5));
    }

    @Test
    void aPauseEndsWhenTheQueueIsDetachedOrTheWaiterInterrupted() throws Exception {
        JournalQueue queue = new JournalQueue(10, 8, null, null);
        CompletableFuture<Void> paused = CompletableFuture.runAsync(() -> {
            try {
                queue.awaitFilling(TimeUnit.SECONDS.toNanos(30));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(50);
        queue.detach();
        paused.get(5, TimeUnit.SECONDS);

        JournalQueue other = new JournalQueue(10, 8, null, null);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                other.awaitFilling(TimeUnit.SECONDS.toNanos(30));
            } catch (InterruptedException ex) {
                thrown.set(ex);
            }
        });
        waiter.start();
        Thread.sleep(50);
        waiter.interrupt();
        waiter.join(5_000);
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void anAcceptedEventIsCountedBeforeTheDispatcherCanTakeIt() throws Exception {
        AtomicReference<JournalQueue> self = new AtomicReference<>();
        AtomicReference<CompletableFuture<Integer>> dispatcher = new AtomicReference<>();
        AtomicReference<Boolean> takenWhileCounting = new AtomicReference<>();
        List<Integer> counted = new ArrayList<>();
        JournalQueue queue = new JournalQueue(4, 4, null, source -> {
            counted.add(source);
            if (dispatcher.get() == null) {
                CompletableFuture<Integer> taking =
                        CompletableFuture.supplyAsync(() -> self.get().drainTo(new ArrayList<>(), 10));
                dispatcher.set(taking);
                try {
                    taking.get(100, TimeUnit.MILLISECONDS);
                    takenWhileCounting.set(true);
                } catch (java.util.concurrent.TimeoutException ex) {
                    takenWhileCounting.set(false);
                } catch (Exception ex) {
                    takenWhileCounting.set(true);
                }
            }
        });
        self.set(queue);

        assertThat(queue.offer(event(1, false), false)).isEqualTo(JournalQueue.ACCEPTED);

        assertThat(takenWhileCounting.get())
                .as("the dispatcher cannot take the event until it is counted")
                .isFalse();
        assertThat(dispatcher.get().get(5, TimeUnit.SECONDS))
                .as("then takes it")
                .isEqualTo(1);
        for (int i = 2; i <= 5; i++) {
            assertThat(queue.offer(event(i, false), false)).isEqualTo(JournalQueue.ACCEPTED);
        }
        assertThat(queue.offer(event(6, true), true)).isEqualTo(JournalQueue.FULL);
        assertThat(counted)
                .as("a refused event is not counted as accepted")
                .hasSize(5)
                .containsOnly(JournalSource.SQL.ordinal());
    }

    @Test
    void routineEventsNeverOvershootTheirShareUnderConcurrentOffers() throws Exception {
        int producers = 8;
        int perProducer = 2_000;
        JournalQueue queue = new JournalQueue(1_000, 600, null, null);
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
                        if (outcome == JournalQueue.ACCEPTED) {
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
        JournalQueue queue = new JournalQueue(4, 4, null, null);
        queue.offer(event(1, false), false);
        queue.offer(event(2, false), false);

        assertThat(queue.detach()).isEqualTo(2);

        assertThat(queue.detach()).as("only the first detach drops events").isZero();
        assertThat(queue.size()).isZero();
        assertThat(queue.offer(event(3, true), true)).isEqualTo(JournalQueue.DETACHED);
        assertThat(queue.drainTo(new ArrayList<>(), 10)).isZero();
        assertThat(queue.poll(30, TimeUnit.SECONDS))
                .as("a detached queue never makes the dispatcher wait")
                .isNull();
    }

    @Test
    void aSealedQueueTakesNothingMoreButKeepsWhatItHeldForTheLastDrain() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null, null);
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
        assertThat(queue.detach()).isZero();
        assertThat(queue.offer(event(2, false), false))
                .as("never detached, so an offer is never retried forever")
                .isEqualTo(JournalQueue.FULL);
        assertThat(queue.refusing()).isTrue();
    }

    @Test
    void detachingWakesADispatcherWaitingOnTheQueue() throws Exception {
        JournalQueue queue = new JournalQueue(4, 4, null, null);
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
        return outcome == JournalQueue.ACCEPTED;
    }

    private static RuntimeEvent event(long epochMillis, boolean failedOrSlow) {
        return RuntimeEvent.of(JournalSource.SQL, epochMillis, 1, null, null, null, failedOrSlow, null);
    }
}
