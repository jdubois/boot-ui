package io.github.jdubois.bootui.engine.model;

import java.util.Objects;

/**
 * A host a route, a scheduled job, or an application class opened connections to or sent datagrams to, as the BootUI
 * agent's {@code network} sensor recorded it ({@code docs/PLAN-v2.md} §5.16, M5-5b), for the runtime model's {@link
 * EdgeType#OPENS} edges.
 *
 * @param from {@link #ROUTE}, {@link #SCHEDULED_JOB}, or {@link #CLASS}
 * @param key the route label, the job's task, or the class name, which the model maps to its one bean
 * @param target the {@code host:port}
 * @param count how many connects or sends
 */
public record HostOpen(String from, String key, String target, long count) {

    public static final String ROUTE = "route";
    public static final String SCHEDULED_JOB = "scheduled-job";
    public static final String CLASS = "class";

    public HostOpen {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(target, "target");
    }
}
