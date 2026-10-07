package io.github.jdubois.bootui.sample.sideeffects;

import java.text.SimpleDateFormat;

/**
 * The thread-locals sensor's seeded holders ({@code docs/PLAN-v2.md} §5.16, M5-5f): a tenant held for the request's
 * duration, which a route forgets to clear, a per-thread date format filled by {@code get()}, and a value a filter set
 * before BootUI's scope opened. Their values are never what the sensor reads.
 */
public final class TenantContext {

    /** The current request's tenant. */
    public static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** A per-thread cache with an initial value: reported, flagged, since it is the application's. */
    public static final ThreadLocal<SimpleDateFormat> FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    /** A scheduled run's tenant, which {@link ScheduledTenant} forgets to clear: reported for the run, no request. */
    public static final ThreadLocal<String> JOB = new ThreadLocal<>();

    /** Set by a filter outside BootUI's scope and never cleared: there before every request, never reported. */
    public static final ThreadLocal<String> BEFORE = new ThreadLocal<>();

    private TenantContext() {}

    /** Whether the calling thread is a virtual thread, whose thread locals the sensor never scans: not pooled. */
    public static boolean virtual() {
        return Thread.currentThread().getClass().getName().contains("VirtualThread");
    }
}
