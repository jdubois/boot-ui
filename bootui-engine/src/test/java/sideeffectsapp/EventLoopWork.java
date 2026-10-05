package sideeffectsapp;

import io.github.jdubois.bootui.agent.bridge.Blocking;

/**
 * Application code run on a thread standing for an event loop, calling the bridge's substitutes as the agent's rewritten
 * {@code Thread.sleep} and {@code Object.wait} call sites would, for the engine's Side Effects tests.
 */
public final class EventLoopWork {

    private EventLoopWork() {}

    /** Registers the calling thread as an event loop, as an adapter does. */
    public static void register() {
        Blocking.registerEventLoop();
    }

    public static void sleep(long millis) throws InterruptedException {
        Blocking.sleep(millis);
    }

    public static void waitOn(Object monitor, long millis) throws InterruptedException {
        synchronized (monitor) {
            Blocking.waitOn(monitor, millis);
        }
    }
}
