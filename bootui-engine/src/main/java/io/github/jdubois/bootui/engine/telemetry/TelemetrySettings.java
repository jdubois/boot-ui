package io.github.jdubois.bootui.engine.telemetry;

/**
 * Live, framework-neutral view of the {@code bootui.telemetry.*} settings the engine telemetry
 * services need. Adapters back this with their own configuration source (Spring
 * {@code @ConfigurationProperties}, Quarkus MicroProfile Config, ...) and read it live, so a runtime
 * override is honored without rebinding the engine.
 */
public interface TelemetrySettings {

    /** Whether trace capture and the OTLP receiver are enabled. */
    boolean enabled();

    /** Whether spans classified as BootUI's own traffic are dropped on capture. */
    boolean excludeSelfSpans();

    /** Maximum number of distinct traces retained in memory before the oldest is evicted. */
    int maxTraces();

    /** Maximum number of spans retained per trace. */
    int maxSpansPerTrace();

    /** Maximum length of a single attribute string value before truncation. */
    int maxAttributeValueBytes();

    /**
     * Whether BootUI stamps its {@code bootui.*} enrichment attributes on request spans. Defaults to
     * {@code true} so enrichment follows telemetry being on; adapters back it with {@code bootui.telemetry.enrich}
     * so an operator can turn enrichment off while leaving capture on.
     */
    default boolean enrichmentEnabled() {
        return true;
    }

    /**
     * Fixed snapshot of telemetry settings, useful for tests and for adapters whose configuration
     * never changes at runtime.
     */
    static TelemetrySettings of(
            boolean enabled,
            boolean excludeSelfSpans,
            int maxTraces,
            int maxSpansPerTrace,
            int maxAttributeValueBytes) {
        return new TelemetrySettings() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public boolean excludeSelfSpans() {
                return excludeSelfSpans;
            }

            @Override
            public int maxTraces() {
                return maxTraces;
            }

            @Override
            public int maxSpansPerTrace() {
                return maxSpansPerTrace;
            }

            @Override
            public int maxAttributeValueBytes() {
                return maxAttributeValueBytes;
            }
        };
    }
}
