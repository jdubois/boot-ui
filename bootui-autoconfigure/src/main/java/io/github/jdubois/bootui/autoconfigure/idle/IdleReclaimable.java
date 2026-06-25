package io.github.jdubois.bootui.autoconfigure.idle;

/**
 * A live diagnostic buffer that can release its retained data and stop recording while the BootUI
 * console is idle, then resume once a developer interacts with the console again.
 *
 * <p>BootUI fills several bounded in-memory buffers (captured SQL, ingested traces, request/security
 * correlation windows) from the host application's own traffic. In development that traffic keeps
 * flowing even when nobody has the console open, so those buffers sit at their steady-state size for
 * no observable benefit. Implementations of this interface let {@link ConsoleActivityTracker} reclaim
 * that memory after a configurable period of console inactivity and refill it on demand.</p>
 *
 * <p>Implementations must keep the idle gate <em>independent</em> of any user-facing pause control
 * (for example the SQL Trace panel's pause button): resuming from idle re-enables recording only with
 * respect to idleness and must never override a recording state the developer chose explicitly.</p>
 */
public interface IdleReclaimable {

    /**
     * Stops accepting new data and releases what is already retained so the memory can be reclaimed
     * while the console is idle. Must be safe to call repeatedly and from a background thread.
     */
    void suspendForIdle();

    /**
     * Resumes accepting new data after the console becomes active again. Retained data is intentionally
     * not restored; the buffer refills from live traffic. Must be safe to call repeatedly.
     */
    void resumeFromIdle();
}
