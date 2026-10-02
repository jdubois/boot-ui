package io.github.jdubois.bootui.engine.activity;

/**
 * A running capture of Live Activity entries into an {@link ActivityStore}: the runtime journal's subscriber
 * ({@code docs/PLAN-v2.md} §5.3), which replaced the 1.x poller of the merged panel feed in 2.0.0. Closing it stops the
 * capture after a last pass.
 */
public interface ActivityCapture extends AutoCloseable {

    @Override
    void close();
}
