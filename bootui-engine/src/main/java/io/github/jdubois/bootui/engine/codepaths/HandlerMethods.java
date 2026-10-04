package io.github.jdubois.bootui.engine.codepaths;

import java.util.List;

/**
 * What a route's call tree says about its handler ({@code docs/PLAN-v2.md} §5.14, §5.5, M5-4b), for
 * {@code route-time-breakdown}'s handler split: over the route's warm requests, the self time of the application methods
 * entered in the handler phase, and the top {@value #TOP} by self time. Asynchronous work is never part of it.
 *
 * @param route the route label
 * @param requests the warm requests whose trees were merged
 * @param handlerNanos the self time of every handler-phase method node, summed over those requests
 * @param methods the top methods by self time, slowest first
 * @param assemblyOnly whether a request's handler only assembled its result: it ran on an event loop, returned a
 *     reactive or asynchronous result, or BootUI could not tell where its work ran; in which case the
 *     route is never split
 */
public record HandlerMethods(
        String route, long requests, long handlerNanos, List<Method> methods, boolean assemblyOnly) {

    /** The most methods named. */
    public static final int TOP = 5;

    public HandlerMethods {
        methods = List.copyOf(methods);
    }

    /**
     * One method's self time in the handler phase, summed over the route's warm requests.
     *
     * @param key the method's key, {@code class#name+descriptor}
     * @param label a short label, {@code SimpleClass.method}
     * @param selfNanos its self time
     */
    public record Method(String key, String label, long selfNanos) {}
}
