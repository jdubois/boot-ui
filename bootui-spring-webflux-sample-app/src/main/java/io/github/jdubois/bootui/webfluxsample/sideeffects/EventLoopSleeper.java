package io.github.jdubois.bootui.webfluxsample.sideeffects;

import org.springframework.stereotype.Component;

/**
 * A blocking call, which reactive code must never make on an event loop: Side Effects' Blocking tab reports it when it
 * runs on one ({@code docs/PLAN-v2.md} §5.16, M5-5c).
 */
@Component
public class EventLoopSleeper {

    /** How long each call sleeps. */
    public static final long MILLIS = 50L;

    /** Sleeps {@value #MILLIS} ms on the calling thread and names it. */
    public String sleepOnEventLoop() {
        try {
            Thread.sleep(MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return Thread.currentThread().getName();
    }
}
