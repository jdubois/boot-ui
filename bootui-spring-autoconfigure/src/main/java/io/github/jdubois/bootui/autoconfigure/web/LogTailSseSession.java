package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One Spring MVC log-tail subscription. Buffer callbacks only perform a bounded queue offer; a
 * dedicated daemon worker owns all potentially blocking {@link SseEmitter} I/O.
 */
final class LogTailSseSession implements AutoCloseable {

    @FunctionalInterface
    interface EventSender {
        void send(SseEmitter emitter, LogLineDto line) throws IOException;
    }

    private static final Runnable NOOP = () -> {};

    private final SseEmitter emitter;
    private final ArrayBlockingQueue<LogLineDto> pending;
    private final ExecutorService workerExecutor;
    private final EventSender sender;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object lifecycleMonitor = new Object();

    private Runnable unsubscribe = NOOP;
    private boolean workerScheduled;
    private volatile Thread workerThread;
    private TerminalAction terminalAction;

    LogTailSseSession(
            SseEmitter emitter,
            int queueCapacity,
            ExecutorService workerExecutor,
            EventSender sender,
            Runnable onClose) {
        this.emitter = emitter;
        this.pending = new ArrayBlockingQueue<>(queueCapacity);
        this.workerExecutor = workerExecutor;
        this.sender = sender;
        this.onClose = onClose;
    }

    SseEmitter emitter() {
        return emitter;
    }

    void start(LogTailBuffer buffer) {
        boolean overflow = false;
        RejectedExecutionException rejected = null;
        synchronized (lifecycleMonitor) {
            if (closed.get()) {
                return;
            }

            LogTailBuffer.Subscription subscription = buffer.subscribeWithReplay(this::enqueue);
            unsubscribe = subscription.unsubscribe();
            for (LogLineDto line : subscription.backlog()) {
                if (!pending.offer(line)) {
                    overflow = true;
                    break;
                }
            }

            if (!overflow && !closed.get()) {
                try {
                    workerExecutor.execute(this::sendLoop);
                    workerScheduled = true;
                } catch (RejectedExecutionException ex) {
                    rejected = ex;
                }
            }
        }

        if (overflow) {
            fail(overloadError());
        } else if (rejected != null) {
            fail(new IllegalStateException("No BootUI log-tail stream worker is available", rejected));
        }
    }

    private void enqueue(LogLineDto line) {
        boolean overflow;
        synchronized (lifecycleMonitor) {
            if (closed.get()) {
                return;
            }
            overflow = !pending.offer(line);
        }
        if (overflow) {
            fail(overloadError());
        }
    }

    private void sendLoop() {
        workerThread = Thread.currentThread();
        try {
            while (!closed.get()) {
                LogLineDto line = pending.take();
                sender.send(emitter, line);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (!closed.get()) {
                fail(new IllegalStateException("BootUI log-tail stream worker was interrupted", ex));
            }
        } catch (IOException | IllegalStateException ex) {
            fail(ex);
        } finally {
            workerThread = null;
            runTerminalAction();
            finish();
        }
    }

    void complete() {
        releaseResources(new TerminalAction(null));
    }

    private void fail(Throwable error) {
        releaseResources(new TerminalAction(error));
    }

    private void releaseResources(TerminalAction requestedTerminalAction) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        Runnable currentUnsubscribe;
        boolean currentWorkerScheduled;
        Thread currentWorkerThread;
        synchronized (lifecycleMonitor) {
            currentUnsubscribe = unsubscribe;
            unsubscribe = NOOP;
            pending.clear();
            terminalAction = requestedTerminalAction;
            currentWorkerScheduled = workerScheduled;
            currentWorkerThread = workerThread;
        }
        currentUnsubscribe.run();
        if (currentWorkerThread != null && currentWorkerThread != Thread.currentThread()) {
            currentWorkerThread.interrupt();
        }
        if (!currentWorkerScheduled) {
            runTerminalAction();
            finish();
        }
    }

    private void runTerminalAction() {
        TerminalAction action;
        synchronized (lifecycleMonitor) {
            action = terminalAction;
            terminalAction = null;
        }
        if (action == null) {
            return;
        }
        try {
            if (action.error() == null) {
                emitter.complete();
            } else {
                emitter.completeWithError(action.error());
            }
        } catch (IllegalStateException ex) {
            // The container ended the async request concurrently (client disconnect or shutdown), so
            // it already owns completion and the emitter can no longer be completed from this thread.
        }
    }

    private void finish() {
        if (finished.compareAndSet(false, true)) {
            onClose.run();
        }
    }

    private static IllegalStateException overloadError() {
        return new IllegalStateException("BootUI log-tail stream disconnected because its pending event queue is full");
    }

    /**
     * Container-driven termination (completion, timeout, or error callback). The request is already
     * over, so any terminal action still pending from a failed send is dropped rather than replayed
     * against a dead async context.
     */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            terminalAction = null;
        }
        releaseResources(null);
    }

    private record TerminalAction(Throwable error) {}
}
