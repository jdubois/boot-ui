package io.github.jdubois.bootui.engine.heapdump;

/**
 * Immutable, framework-neutral settings for {@link HeapDumpService}.
 *
 * <p>These are <em>static</em> configuration values (read once when the service is constructed),
 * so they are modelled as a plain record snapshot rather than a live policy interface. Each adapter
 * maps its own configuration ({@code bootui.heap-dump.*} on Spring, the equivalent on Quarkus) onto
 * this record when it builds the engine service, keeping the engine free of any host-framework
 * configuration type.</p>
 *
 * @param outputDir directory where {@code .hprof} dumps are written
 * @param captureEnabled whether the capture action is permitted
 * @param allowRawDownload whether the raw (unmasked) {@code .hprof} file may be downloaded
 * @param maxDumps maximum number of dump files retained before the oldest are evicted
 * @param maxClasses maximum number of class-histogram entries held in memory
 * @param topClasses default number of histogram entries surfaced in a report
 */
public record HeapDumpSettings(
        String outputDir,
        boolean captureEnabled,
        boolean allowRawDownload,
        int maxDumps,
        int maxClasses,
        int topClasses) {}
