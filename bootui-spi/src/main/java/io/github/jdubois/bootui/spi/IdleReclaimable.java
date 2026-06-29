package io.github.jdubois.bootui.spi;

/**
 * A live diagnostic buffer that can release its retained data and stop recording while the BootUI
 * console is idle, then resume once a developer interacts with the console again.
 *
 * <p>This is the framework-neutral seam: engine-owned buffers (such as the Quarkus HTTP-exchange ring
 * buffer) implement it so an adapter idle tracker can reclaim memory without the engine importing any
 * host-framework type. The Spring adapter keeps its own equivalent interface for its servlet buffers;
 * this one exists so neutral engine buffers stay reclaimable on every framework.</p>
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
