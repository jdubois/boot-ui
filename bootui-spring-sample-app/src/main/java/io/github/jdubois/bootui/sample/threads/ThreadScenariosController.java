package io.github.jdubois.bootui.sample.threads;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, bounded platform-thread workloads for the sample application's Threads lab. */
@RestController
@RequestMapping("/api/sample/threads")
public class ThreadScenariosController implements AutoCloseable {
    private final Duration duration;
    private Scenario current;
    private boolean closed;

    public ThreadScenariosController() {
        this(Duration.ofSeconds(30));
    }

    ThreadScenariosController(Duration duration) {
        this.duration = duration;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @GetMapping
    public synchronized Status status() {
        return current == null ? new Status("none", false, 0, List.of()) : current.status();
    }

    @PostMapping("/{scenario}")
    public synchronized Status start(@PathVariable String scenario) {
        if (!List.of("monitor", "synchronizer", "cpu", "waiting", "deadlock").contains(scenario)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown thread scenario.");
        }
        if (closed) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Thread scenarios are closed.");
        if (current != null && current.status().active()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Stop the active thread scenario before starting another.");
        }
        Scenario next = new Scenario(scenario, duration);
        switch (scenario) {
            case "monitor" -> {
                Object monitor = new Object();
                CountDownLatch held = new CountDownLatch(1);
                next.worker("owner", () -> {
                    synchronized (monitor) {
                        held.countDown();
                        next.await(next.stop);
                    }
                });
                for (int i = 1; i <= 3; i++)
                    next.worker("waiter-" + i, () -> {
                        if (next.await(held))
                            synchronized (monitor) {
                                next.result++;
                            }
                    });
            }
            case "synchronizer" -> {
                ReentrantLock lock = new ReentrantLock();
                CountDownLatch held = new CountDownLatch(1);
                next.worker(
                        "owner",
                        () -> withLock(lock, () -> {
                            held.countDown();
                            next.await(next.stop);
                        }));
                for (int i = 1; i <= 3; i++)
                    next.worker("waiter-" + i, () -> {
                        if (next.await(held)) withLock(lock, () -> next.result++);
                    });
            }
            case "cpu" ->
                next.worker("worker", () -> {
                    long value = 1;
                    while (!next.cancelled.get() && System.nanoTime() < next.deadline) {
                        for (int i = 0; i < 4096; i++) value = (value * 1_664_525 + 1_013_904_223) ^ i;
                        next.result = value;
                    }
                });
            case "waiting" -> {
                for (int i = 1; i <= 3; i++) next.worker("worker-" + i, () -> next.await(next.stop));
            }
            case "deadlock" -> {
                ReentrantLock left = new ReentrantLock();
                ReentrantLock right = new ReentrantLock();
                CountDownLatch bothHeld = new CountDownLatch(2);
                next.worker("left", () -> deadlock(next, left, right, bothHeld));
                next.worker("right", () -> deadlock(next, right, left, bothHeld));
            }
        }
        // Interruptible acquisition lets both the timeout and Stop release a genuine two-lock cycle.
        next.worker("cleanup", () -> {
            next.await(next.stop);
            next.cancel();
        });
        current = next;
        try {
            next.threads.forEach(Thread::start);
        } catch (RuntimeException | Error failure) {
            next.cancel();
            throw failure;
        }
        return next.status();
    }

    @PostMapping("/stop")
    public synchronized Status stop() {
        if (current != null) {
            current.cancel();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (Thread thread : current.threads) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ResponseStatusException(
                            HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while stopping thread scenario.");
                }
            }
            if (current.status().active()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Thread scenario is still stopping. Retry shortly.");
            }
        }
        return status();
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        stop();
    }

    private static void deadlock(Scenario scenario, ReentrantLock first, ReentrantLock second, CountDownLatch held) {
        withLock(first, () -> {
            held.countDown();
            if (scenario.await(held)) withLock(second, () -> scenario.result++);
        });
    }

    private static void withLock(ReentrantLock lock, Runnable work) {
        try {
            lock.lockInterruptibly();
            try {
                work.run();
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public record Status(String scenario, boolean active, long expiresAt, List<String> threads) {}

    private static final class Scenario {
        private final String name;
        private final List<Thread> threads = new ArrayList<>();
        private final CountDownLatch stop = new CountDownLatch(1);
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final long deadline;
        private final long expiresAt;
        private volatile long result;

        private Scenario(String name, Duration duration) {
            this.name = name;
            deadline = System.nanoTime() + duration.toNanos();
            expiresAt = System.currentTimeMillis() + duration.toMillis();
        }

        private void worker(String suffix, Runnable work) {
            Thread thread = new Thread(work, "sample-threads-" + name + "-" + suffix);
            thread.setDaemon(true);
            threads.add(thread);
        }

        private boolean await(CountDownLatch latch) {
            try {
                return latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) && !cancelled.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                stop.countDown();
                threads.forEach(Thread::interrupt);
            }
        }

        private Status status() {
            return new Status(
                    name,
                    threads.stream().anyMatch(Thread::isAlive),
                    expiresAt,
                    threads.stream()
                            .map(Thread::getName)
                            .filter(name -> !name.endsWith("-cleanup"))
                            .toList());
        }
    }
}
