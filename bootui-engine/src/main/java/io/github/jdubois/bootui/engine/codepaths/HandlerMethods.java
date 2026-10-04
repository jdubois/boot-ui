package io.github.jdubois.bootui.engine.codepaths;

import java.util.List;

/**
 * What a route's call tree says about its handler ({@code docs/PLAN-v2.md} §5.14, §5.5), for
 * {@code route-time-breakdown}'s split: over the route's warm requests, the own time of the application methods entered
 * in the handler phase, and the top {@value #TOP} by own time. A method's own time is its self time minus the SQL, REST
 * client, cache, and AI calls stamped to it (M5-4c); calls recorded without a stamp, as on another thread than the one
 * that issued them, stay in the own time of a method that waited for them, which is why {@code route-time-breakdown}
 * does not split a handler whose unstamped calls take a tenth of its time or more. Asynchronous work is never part of
 * it.
 *
 * @param route the route label
 * @param requests the warm requests whose trees were merged
 * @param handlerNanos the own time of every handler-phase method node, summed over those requests
 * @param methods the top methods by own time, slowest first
 * @param assemblyOnly whether a request's handler only assembled its result: it ran on an event loop, returned a
 *     reactive or asynchronous result, or BootUI could not tell where its work ran; in which case the route is never
 *     split
 * @param stampedCalls the warm requests' recorded calls carrying a stamp
 * @param unstampedCalls the warm requests' recorded calls without one, recorded on another thread than the one that
 *     issued them, whose time stays in the own time of a method that waited for them
 * @param unstampedNanos those calls' time, summed over the warm requests, unknown durations counted as 0
 */
public record HandlerMethods(
        String route,
        long requests,
        long handlerNanos,
        List<Method> methods,
        boolean assemblyOnly,
        long stampedCalls,
        long unstampedCalls,
        long unstampedNanos) {

    /** The most methods named. */
    public static final int TOP = 5;

    public HandlerMethods {
        methods = List.copyOf(methods);
    }

    /** A handler whose requests recorded no call. */
    public HandlerMethods(String route, long requests, long handlerNanos, List<Method> methods, boolean assemblyOnly) {
        this(route, requests, handlerNanos, methods, assemblyOnly, 0L, 0L, 0L);
    }

    /** A handler whose requests recorded {@code unstampedCalls} without a stamp, of unknown time. */
    public HandlerMethods(
            String route,
            long requests,
            long handlerNanos,
            List<Method> methods,
            boolean assemblyOnly,
            long stampedCalls,
            long unstampedCalls) {
        this(route, requests, handlerNanos, methods, assemblyOnly, stampedCalls, unstampedCalls, 0L);
    }

    /**
     * One method's own time in the handler phase, summed over the route's warm requests.
     *
     * @param key the method's key, {@code class#name+descriptor}
     * @param label a short label, {@code SimpleClass.method}
     * @param ownNanos its self time minus the recorded calls stamped to it
     */
    public record Method(String key, String label, long ownNanos) {}
}
