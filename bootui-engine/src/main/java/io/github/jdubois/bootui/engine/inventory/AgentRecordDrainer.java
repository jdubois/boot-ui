package io.github.jdubois.bootui.engine.inventory;

import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine's one drainer of the BootUI agent's transport ring for a claim ({@code docs/PLAN-v2.md} §5.13, M5-3): a
 * BootUI daemon, {@value #THREAD_NAME}, drains it every {@value #INTERVAL_MILLIS} ms through the claim's token, and a
 * read drains it once more first ({@link #drainNow()}), and each record goes to the route of its sensor id (the
 * inventory sensor's now; M5-4 adds Code Paths'). The thread marks its work as BootUI's own, so what it loads is not
 * counted as the application's, and stops when the claim ends or {@link #close()} is called, at the latest when the
 * application context closes or Quarkus shuts down, so no thread keeps a run reachable after a restart.
 *
 * <p>Routes receive one reused {@code long[]} per record and copy what they keep. The bridge lets one drainer at a time
 * drain, so routes are never called concurrently.
 */
public final class AgentRecordDrainer implements AutoCloseable {

    /** The drain thread's name. */
    public static final String THREAD_NAME = "bootui-agent-drain";

    /** How often the thread drains. */
    public static final long INTERVAL_MILLIS = 100;

    /** Record layout ({@code AgentRing}): the sensor id. */
    public static final int SENSOR = 0;

    /** Record layout: the record type, per sensor. */
    public static final int TYPE = 1;

    /** Record layout: the claim generation. */
    public static final int GENERATION = 2;

    /** Record layout: the time, in epoch milliseconds. */
    public static final int TIME = 3;

    /** Record layout: the first of four payload longs. */
    public static final int PAYLOAD = 4;

    /** The inventory sensor's id in records ({@code AgentRing.SENSOR_INVENTORY}). */
    public static final int SENSOR_INVENTORY = 1;

    private static final Logger log = Logger.getLogger(AgentRecordDrainer.class.getName());

    private final AgentClaim claim;
    private final AgentBridgeAccess access;
    private final Map<Integer, Consumer<long[]>> routes;
    private final Consumer<long[]> sink;
    private final AtomicLong drained = new AtomicLong();
    private final AtomicLong unrouted = new AtomicLong();
    private volatile boolean closed;
    private Thread thread;

    /**
     * @param claim the claim whose token drains the ring
     * @param access the bridge, to mark the drain thread's work as BootUI's own
     * @param routes each sensor id's route
     */
    public AgentRecordDrainer(AgentClaim claim, AgentBridgeAccess access, Map<Integer, Consumer<long[]>> routes) {
        this.claim = claim;
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.routes = Map.copyOf(routes);
        this.sink = record -> {
            Consumer<long[]> route = this.routes.get((int) record[SENSOR]);
            if (route == null) {
                unrouted.incrementAndGet();
                return;
            }
            try {
                route.accept(record);
            } catch (RuntimeException ex) {
                log.log(Level.FINE, "BootUI could not route an agent record", ex);
            }
        };
    }

    /** Starts the drain thread once. Does nothing once closed or without an armed claim. */
    public synchronized void start() {
        if (thread != null || closed || claim == null || !claim.armed()) {
            return;
        }
        Thread started = new Thread(this::run, THREAD_NAME);
        started.setDaemon(true);
        // The thread needs no class loader of the application, and must not keep one reachable.
        started.setContextClassLoader(null);
        thread = started;
        started.start();
    }

    /** Drains what is waiting on the calling thread, as before a read. Returns how many records were drained. */
    public int drainNow() {
        if (closed || claim == null) {
            return 0;
        }
        boolean previous = access.bootUiWork(true);
        try {
            return drainOnce();
        } finally {
            access.bootUiWork(previous);
        }
    }

    /** Stops the drain thread, after a last drain. Idempotent. */
    @Override
    public void close() {
        Thread running;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = thread;
            thread = null;
        }
        if (running != null) {
            LockSupport.unpark(running);
            try {
                running.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Whether the drain thread is running. */
    public synchronized boolean running() {
        return thread != null && thread.isAlive();
    }

    /** How many records were drained. */
    public long drained() {
        return drained.get();
    }

    /** How many drained records had no route. */
    public long unrouted() {
        return unrouted.get();
    }

    private void run() {
        access.bootUiWork(true);
        try {
            while (!closed && !claim.ended()) {
                drainOnce();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(INTERVAL_MILLIS));
            }
            drainOnce();
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI's agent drain thread stopped", ex);
        }
    }

    private int drainOnce() {
        int count = claim.drain(sink);
        drained.addAndGet(count);
        return count;
    }
}
