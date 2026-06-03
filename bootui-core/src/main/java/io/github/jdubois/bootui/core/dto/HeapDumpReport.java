package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the Heap Dump diagnostics panel.
 *
 * <p>The class histogram is computed only by explicit capture/analyze actions (each of
 * which triggers a full GC); passive reads return the last computed histogram.</p>
 */
public record HeapDumpReport(
        boolean hotspotAvailable,
        boolean captureEnabled,
        boolean rawDownloadEnabled,
        String outputDirectory,
        int maxDumps,
        int dumpCount,
        long liveHeapUsedBytes,
        long freeDiskBytes,
        HeapDumpCaptureStatusDto capture,
        List<HeapDumpFileDto> dumps,
        long histogramTotalInstances,
        long histogramTotalBytes,
        List<HeapClassHistogramEntryDto> topClasses) {}
