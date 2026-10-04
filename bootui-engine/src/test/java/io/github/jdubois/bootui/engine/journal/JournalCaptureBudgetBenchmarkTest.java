package io.github.jdubois.bootui.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.spi.CorrelationContext;
import io.github.jdubois.bootui.spi.ThreadKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The runtime journal's capture budgets of {@code docs/PLAN-v2.md} §8: the application thread's path (snapshot, envelope,
 * and {@code offer}) under 2 µs at p99, and a dispatcher that sustains at least 20,000 events a second, both with the
 * run's aggregates attached as every adapter attaches them.
 *
 * <p>Timings depend on the machine, so this is an opt-in measurement, never a CI gate. It takes about a minute and
 * writes its report to {@code target/capture-budgets/journal.md}:</p>
 *
 * <pre>./mvnw -pl bootui-engine test -Dtest=JournalCaptureBudgetBenchmarkTest -Dbootui.benchmark=true</pre>
 *
 * <p>The offer path is timed per call with {@link System#nanoTime()}, whose own cost (tens of nanoseconds) is included,
 * so the percentiles are an upper bound. The stack walk that keeps a statement's application frames also runs on the
 * application thread, only when call sites are kept; it is timed apart, since its cost grows with the stack's depth.</p>
 */
@EnabledIfSystemProperty(named = "bootui.benchmark", matches = "true")
class JournalCaptureBudgetBenchmarkTest {

    private static final int WARM_UP_OPERATIONS = 300_000;

    private static final int MEASURED_OPERATIONS_PER_THREAD = 200_000;

    private static final int CHILDREN_PER_REQUEST = 4;

    private static final String SELECT = "select o.id, o.total, o.status from orders o where o.customer_id = ?";

    private final List<String> report = new ArrayList<>();

    @Test
    void measuresTheJournalCaptureBudgets() throws Exception {
        report.add("# Runtime journal capture budgets");
        report.add("");
        report.add("JDK " + System.getProperty("java.version") + ", "
                + Runtime.getRuntime().availableProcessors() + " processors, " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ".");
        report.add("");
        report.add("| Measure | Producers | p50 | p99 | p99.9 | max | Budget |");
        report.add("| --- | --- | --- | --- | --- | --- | --- |");

        long[] single = offerLatencies(1);
        long[] eight = offerLatencies(8);
        row("Snapshot, envelope, and offer", 1, single, "< 2 µs p99");
        row("Snapshot, envelope, and offer", 8, eight, "< 2 µs p99");
        long[] frames = frameCaptureLatencies();
        row("Application frames stack walk (call sites kept)", 1, frames, "not budgeted apart");
        long[] duringClear = offerLatenciesWhileClearDrainIsPaused();
        row("Offer while a detached-queue drain is pending", 1, duringClear, "report only");

        report.add("");
        report.add("| Dispatcher | Offered per second | Seconds | Accepted | Dropped | Recorded per second | Budget |");
        report.add("| --- | --- | --- | --- | --- | --- | --- |");
        Throughput paced = dispatch(4, 20_000, Duration.ofSeconds(10));
        Throughput unpaced = dispatch(4, 0, Duration.ofSeconds(10));
        throughputRow("Paced at the budget", paced, "≥ 20,000/s with no drop");
        throughputRow("Unpaced, 4 producers", unpaced, "capacity");

        Path out = Path.of("target", "capture-budgets", "journal.md");
        write(out);
        System.out.println(String.join(System.lineSeparator(), report));

        assertThat(percentile(single, 99)).as("single-producer p99 offer path").isLessThan(2_000L);
        assertThat(paced.dropped()).as("events dropped at 20,000 per second").isZero();
    }

    /** Times each request's children and the request itself as an adapter publishes them, on {@code threads}. */
    private long[] offerLatencies(int threads) throws Exception {
        try (RuntimeJournal journal = journal()) {
            runProducers(journal, threads, WARM_UP_OPERATIONS / threads, null);
            long[][] samples = new long[threads][];
            runProducers(journal, threads, MEASURED_OPERATIONS_PER_THREAD, samples);
            journal.awaitDrained(Duration.ofSeconds(30));
            long[] all = Arrays.stream(samples)
                    .flatMapToLong(Arrays::stream)
                    .sorted()
                    .toArray();
            return all;
        }
    }

    private void runProducers(RuntimeJournal journal, int threads, int operations, long[][] samples) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "http-nio-8080-exec-bench");
            thread.setDaemon(true);
            return thread;
        });
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int producer = t;
                futures.add(pool.submit(() -> {
                    long[] timings = samples == null ? null : new long[operations];
                    start.await();
                    String thread = Thread.currentThread().getName();
                    int done = 0;
                    long request = 0;
                    while (done < operations) {
                        String requestId = String.format(Locale.ROOT, "%08x%08x", producer, request++);
                        try (var scope = BootUiCorrelation.open(CorrelationContext.forRequest(requestId))) {
                            for (int child = 0; child <= CHILDREN_PER_REQUEST && done < operations; child++) {
                                long before = System.nanoTime();
                                CorrelationContext context = BootUiCorrelation.current();
                                RuntimeEventPayload payload = child < CHILDREN_PER_REQUEST
                                        ? new SqlPayload(SELECT, "OrderRepository.findByCustomer:42", "orders", false)
                                        : new HttpPayload(
                                                "GET", "/api/orders/" + request, "/api/orders/{id}", null, 200);
                                JournalSource source =
                                        child < CHILDREN_PER_REQUEST ? JournalSource.SQL : JournalSource.HTTP;
                                journal.offer(RuntimeEvent.of(
                                        source,
                                        System.currentTimeMillis(),
                                        250_000,
                                        context,
                                        thread,
                                        ThreadKind.WORKER,
                                        false,
                                        payload));
                                long elapsed = System.nanoTime() - before;
                                if (timings != null) {
                                    timings[done] = elapsed;
                                }
                                done++;
                            }
                        }
                    }
                    if (samples != null) {
                        samples[producer] = timings;
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long[] frameCaptureLatencies() {
        for (int i = 0; i < 50_000; i++) {
            deep(60, null, 0);
        }
        long[] timings = new long[50_000];
        for (int i = 0; i < timings.length; i++) {
            deep(60, timings, i);
        }
        Arrays.sort(timings);
        return timings;
    }

    /** Times offers into the replacement queue while a full detached queue is deliberately kept undrained. */
    private long[] offerLatenciesWhileClearDrainIsPaused() throws Exception {
        CountDownLatch beforeDrain = new CountDownLatch(1);
        CountDownLatch releaseDrain = new CountDownLatch(1);
        try (RuntimeJournal journal =
                new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start(), false, null, ignored -> {
                    beforeDrain.countDown();
                    try {
                        releaseDrain.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                })) {
            RuntimeEvent event = new RuntimeEvent(
                    JournalSource.SQL,
                    System.currentTimeMillis(),
                    250_000,
                    null,
                    null,
                    null,
                    "http-nio-8080-exec-bench",
                    ThreadKind.WORKER,
                    false,
                    new SqlPayload(SELECT, "OrderRepository.findByCustomer:42", "orders", false));
            for (int i = 0; i < journal.settings().queueCapacity(); i++) {
                journal.offer(event);
            }
            long acceptedBeforeClear = journal.status().accepted().values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
            CompletableFuture<Long> clearing = CompletableFuture.supplyAsync(journal::offloadRetainedData);
            assertThat(beforeDrain.await(30, TimeUnit.SECONDS)).isTrue();
            long[] timings = new long[journal.settings().routineQueueLimit()];
            for (int i = 0; i < timings.length; i++) {
                long before = System.nanoTime();
                boolean accepted = journal.offer(event);
                timings[i] = System.nanoTime() - before;
                assertThat(accepted).isTrue();
            }
            releaseDrain.countDown();
            assertThat(clearing.get(30, TimeUnit.SECONDS)).isEqualTo(acceptedBeforeClear);
            Arrays.sort(timings);
            return timings;
        } finally {
            releaseDrain.countDown();
        }
    }

    /** Walks the stack {@code depth} frames below this test, about as deep as a servlet request's repository call. */
    private static void deep(int depth, long[] timings, int index) {
        if (depth > 0) {
            deep(depth - 1, timings, index);
            return;
        }
        long before = System.nanoTime();
        ApplicationFrames.capture();
        long elapsed = System.nanoTime() - before;
        if (timings != null) {
            timings[index] = elapsed;
        }
    }

    record Throughput(int offeredPerSecond, double seconds, long accepted, long dropped, double recordedPerSecond) {}

    /** Offers {@code perSecond} events a second in total (0 for as fast as possible) and measures what was recorded. */
    private Throughput dispatch(int producers, int perSecond, Duration duration) throws Exception {
        try (RuntimeJournal journal = journal()) {
            AtomicLong offered = new AtomicLong();
            long deadline = System.nanoTime() + duration.toNanos();
            long intervalNanos = perSecond == 0 ? 0 : TimeUnit.SECONDS.toNanos(1) * producers / perSecond;
            ExecutorService pool = Executors.newFixedThreadPool(producers);
            long started = System.nanoTime();
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int p = 0; p < producers; p++) {
                    int producer = p;
                    futures.add(pool.submit(() -> {
                        long next = System.nanoTime();
                        long request = 0;
                        while (System.nanoTime() < deadline) {
                            String requestId = String.format(Locale.ROOT, "%08x%08x", producer, request++);
                            CorrelationContext context = CorrelationContext.forRequest(requestId);
                            for (int child = 0; child <= CHILDREN_PER_REQUEST; child++) {
                                if (intervalNanos > 0) {
                                    next += intervalNanos;
                                    long wait = next - System.nanoTime();
                                    if (wait > 0) {
                                        LockSupport.parkNanos(wait);
                                    }
                                }
                                boolean http = child == CHILDREN_PER_REQUEST;
                                journal.offer(RuntimeEvent.of(
                                        http ? JournalSource.HTTP : JournalSource.SQL,
                                        System.currentTimeMillis(),
                                        250_000,
                                        context,
                                        "http-nio-8080-exec-" + producer,
                                        ThreadKind.WORKER,
                                        false,
                                        http
                                                ? new HttpPayload(
                                                        "GET", "/api/orders/" + request, "/api/orders/{id}", null, 200)
                                                : new SqlPayload(
                                                        SELECT, "OrderRepository.findByCustomer:42", "orders", false)));
                                offered.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(5, TimeUnit.MINUTES);
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(journal.awaitDrained(Duration.ofSeconds(60))).isTrue();
            double seconds = (System.nanoTime() - started) / 1e9;
            JournalStatus status = journal.status();
            return new Throughput(
                    perSecond, seconds, status.lastSequence(), status.droppedTotal(), status.lastSequence() / seconds);
        }
    }

    private static RuntimeJournal journal() {
        RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
        journal.addListener(new JournalAggregates());
        return journal;
    }

    private void row(String measure, int producers, long[] sorted, String budget) {
        report.add(String.format(
                Locale.ROOT,
                "| %s | %d | %s | %s | %s | %s | %s |",
                measure,
                producers,
                micros(percentile(sorted, 50)),
                micros(percentile(sorted, 99)),
                micros(percentile(sorted, 99.9)),
                micros(sorted[sorted.length - 1]),
                budget));
    }

    private void throughputRow(String label, Throughput result, String budget) {
        report.add(String.format(
                Locale.ROOT,
                "| %s | %s | %.1f | %,d | %,d | %,.0f | %s |",
                label,
                result.offeredPerSecond() == 0
                        ? "unpaced"
                        : String.format(Locale.ROOT, "%,d", result.offeredPerSecond()),
                result.seconds(),
                result.accepted(),
                result.dropped(),
                result.recordedPerSecond(),
                budget));
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String micros(long nanos) {
        return String.format(Locale.ROOT, "%.2f µs", nanos / 1_000.0);
    }

    private void write(Path out) throws IOException {
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), report) + System.lineSeparator());
    }
}
