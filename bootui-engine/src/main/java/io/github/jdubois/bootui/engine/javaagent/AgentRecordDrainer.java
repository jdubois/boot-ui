package io.github.jdubois.bootui.engine.javaagent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine's one drainer of the BootUI agent for a claim ({@code docs/PLAN-v2.md} §5.13, M5-3, M5-4a): a BootUI daemon,
 * {@value #THREAD_NAME}, drains the agent's transport ring and the code-paths sensor's fragment queue every
 * {@value #INTERVAL_MILLIS} ms through the claim's token, and a read drains them once more first ({@link #drainNow()}).
 * Each sensor's service registers its route: a ring record goes to the route of its sensor id ({@link #route}), a
 * code-paths fragment to the fragment route ({@link #routeCodePaths}). The thread marks its work as BootUI's own, so what
 * it loads is not counted as the application's, runs only while a route is registered, and stops for good when the claim
 * ends or {@link #close()} is called, at the latest when the application context closes or Quarkus shuts down; closing
 * forgets every route, so nothing keeps a run's services reachable after it.
 *
 * <p>Ring routes receive one reused {@code long[]} per record and copy what they keep; the fragment route owns each blob.
 * The bridge lets one drainer at a time drain each queue, so a route is never called concurrently with itself.
 */
public final class AgentRecordDrainer implements AutoCloseable {

    private final Object drainLock = new Object();

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

    /** Method probes' id in records ({@code AgentRing.SENSOR_METHOD_PROBES}). */
    public static final int SENSOR_METHOD_PROBES = 2;

    private static final Logger log = Logger.getLogger(AgentRecordDrainer.class.getName());

    private final AgentClaim claim;
    private final AgentBridgeAccess access;
    private final Map<Integer, Consumer<long[]>> routes = new ConcurrentHashMap<>();
    private volatile Consumer<long[]> codePathsRoute;
    private final Consumer<long[]> sink;
    private final Consumer<long[]> blobSink;
    private final AtomicLong drained = new AtomicLong();
    private final AtomicLong fragments = new AtomicLong();
    private final AtomicLong unrouted = new AtomicLong();
    private volatile boolean closed;
    private Thread thread;

    /**
     * @param claim the claim whose token drains the agent
     * @param access the bridge, to mark the drain thread's work as BootUI's own
     */
    public AgentRecordDrainer(AgentClaim claim, AgentBridgeAccess access) {
        this.claim = claim;
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.sink = record -> {
            Consumer<long[]> route = routes.get((int) record[SENSOR]);
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
        this.blobSink = blob -> {
            Consumer<long[]> route = codePathsRoute;
            if (route == null) {
                unrouted.incrementAndGet();
                return;
            }
            try {
                route.accept(blob);
            } catch (RuntimeException ex) {
                log.log(Level.FINE, "BootUI could not route a code-paths fragment", ex);
            }
        };
    }

    /** Registers {@code route} for the ring records of {@code sensor}, and starts the thread. */
    public void route(int sensor, Consumer<long[]> route) {
        if (route != null && !closed) {
            routes.put(sensor, route);
            start();
        }
    }

    /** Removes {@code route} if it is the one registered for {@code sensor}; the thread stops with the last route. */
    public void unroute(int sensor, Consumer<long[]> route) {
        routes.remove(sensor, route);
        stopWhenUnrouted();
    }

    /** Registers {@code route} for the code-paths sensor's fragments, and starts the thread. */
    public void routeCodePaths(Consumer<long[]> route) {
        if (route != null && !closed) {
            codePathsRoute = route;
            start();
        }
    }

    /** Removes {@code route} if it is the one registered for fragments; the thread stops with the last route. */
    public void unrouteCodePaths(Consumer<long[]> route) {
        synchronized (this) {
            if (codePathsRoute == route) {
                codePathsRoute = null;
            }
        }
        stopWhenUnrouted();
    }

    private boolean routed() {
        return !routes.isEmpty() || codePathsRoute != null;
    }

    /** Starts the drain thread unless it runs, the drainer is closed, nothing is routed, or the claim is not armed. */
    public synchronized void start() {
        if (thread != null || closed || claim == null || !claim.armed() || !routed()) {
            return;
        }
        Thread started = new Thread(this::run, THREAD_NAME);
        started.setDaemon(true);
        // The thread needs no class loader of the application, and must not keep one reachable.
        started.setContextClassLoader(null);
        thread = started;
        started.start();
    }

    /** Drains what is waiting on the calling thread, as before a read. Returns how many records and fragments. */
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

    /** Stops the drain thread for good, after a last drain, and forgets every route. Idempotent. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        stopThread();
        routes.clear();
        codePathsRoute = null;
    }

    private void stopWhenUnrouted() {
        if (!routed()) {
            stopThread();
        }
    }

    private void stopThread() {
        Thread running;
        synchronized (this) {
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

    /** Whether the drainer was closed for good. */
    public boolean closed() {
        return closed;
    }

    /** How many ring records were drained. */
    public long drained() {
        return drained.get();
    }

    /** How many code-paths fragments were drained. */
    public long fragments() {
        return fragments.get();
    }

    /** How many drained records and fragments had no route. */
    public long unrouted() {
        return unrouted.get();
    }

    private void run() {
        access.bootUiWork(true);
        Thread self = Thread.currentThread();
        try {
            while (!closed && !claim.ended() && current(self)) {
                drainOnce();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(INTERVAL_MILLIS));
            }
            drainOnce();
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "BootUI's agent drain thread stopped", ex);
        }
    }

    private synchronized boolean current(Thread self) {
        return thread == self;
    }

    /**
     * One drain of the ring and the fragment queue. Serialized: the bridge lets only one drainer in at a time and turns
     * the other away empty, so a read's {@link #drainNow()} that ran beside the thread's drain would miss what was
     * flushed just before it; waiting for that drain, then draining again, never does.
     */
    private int drainOnce() {
        synchronized (drainLock) {
            return drainUnderLock();
        }
    }

    private int drainUnderLock() {
        int count = claim.drain(sink);
        drained.addAndGet(count);
        int blobs = codePathsRoute == null ? 0 : claim.drainCodePaths(blobSink);
        fragments.addAndGet(blobs);
        return count + blobs;
    }
}
