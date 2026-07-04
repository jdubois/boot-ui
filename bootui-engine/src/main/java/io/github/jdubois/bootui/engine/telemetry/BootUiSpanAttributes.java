package io.github.jdubois.bootui.engine.telemetry;

/**
 * The stable {@code bootui.*} span-attribute vocabulary BootUI stamps onto request spans so that a
 * cross-service trace waterfall — assembled from ordinary OpenTelemetry spans exported by every
 * participating service to one aggregator's OTLP receiver — carries genuine BootUI depth (SQL volume /
 * N+1 suspicion, exception presence, service identity) rather than commodity spans alone.
 *
 * <p>These are plain string constants with <strong>no OpenTelemetry dependency</strong>, so the neutral
 * engine and both adapters can reference the same keys. The keys are a published contract: the read/UI
 * side keys off them and other BootUI aggregators may consume them, so treat renames as breaking.</p>
 */
public final class BootUiSpanAttributes {

    /** Boolean marker stamped on span start: this span was produced by a BootUI-enriched service. */
    public static final String ENRICHED = "bootui.enriched";

    /** The enriching service's logical name (mirrors {@code service.name} for convenience). */
    public static final String SERVICE = "bootui.service";

    /** The enriching service instance id (host/pod), when known. */
    public static final String INSTANCE = "bootui.instance";

    /** Running count of SQL statements captured under this span's request. */
    public static final String SQL_QUERIES = "bootui.sql.queries";

    /** Boolean: BootUI's SQL grouping suspects an N+1 access pattern within this request. */
    public static final String SQL_N_PLUS_ONE = "bootui.sql.n_plus_one";

    /** Running count of exceptions captured under this span's request. */
    public static final String EXCEPTIONS = "bootui.exceptions";

    /** The class name of the most recent exception captured under this span's request. */
    public static final String EXCEPTION_TYPE = "bootui.exception.type";

    private BootUiSpanAttributes() {}
}
