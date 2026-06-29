package io.github.jdubois.bootui.engine.web;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityKpiDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral assembly of the Live Activity merged stream + KPI summary from already-masked
 * source reports. The Spring adapter has its own richer, controller-fed service (SQL/exception/security
 * correlation); the Quarkus adapter, which has not yet ported those capture layers, feeds this honest
 * partial assembler: HTTP requests + JVM heap KPIs, with SQL/EXCEPTION declared unavailable. Thread
 * correlation is deliberately omitted — on the Vert.x event loop thread identity does not map to a
 * single request, so entries carry trace ids only.
 */
public final class LiveActivityAssembler {

    private static final long SLOW_MS = 500L;

    /** Builds the report from already-masked HTTP exchanges (newest-first) and an optional health status. */
    public LiveActivityReport report(HttpExchangesReport requests, String healthStatus, int limit) {
        List<String> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        sources.add("requests");
        warnings.add("SQL trace and exceptions are not yet captured on Quarkus");

        List<HttpExchangeDto> exchanges = requests == null ? List.of() : requests.exchanges();
        List<ActivityEntryDto> entries = new ArrayList<>();
        long errors = 0;
        long slowest = 0;
        String slowestPath = null;
        List<Long> durations = new ArrayList<>();
        for (HttpExchangeDto e : exchanges) {
            long ts = e.timestamp() == null ? 0L : e.timestamp().toEpochMilli();
            String severity = severity(e.status(), e.durationMs());
            if (e.status() >= 400) {
                errors++;
            }
            if (e.durationMs() != null) {
                durations.add(e.durationMs());
                if (e.durationMs() > slowest) {
                    slowest = e.durationMs();
                    slowestPath = e.path();
                }
            }
            entries.add(new ActivityEntryDto(
                    e.id(),
                    "REQUEST",
                    ts,
                    severity,
                    (e.method() == null ? "" : e.method() + " ") + (e.path() == null ? "" : e.path()),
                    e.status() + (e.durationMs() == null ? "" : " · " + e.durationMs() + "ms"),
                    e.durationMs(),
                    e.traceId(),
                    e.method(),
                    e.path(),
                    e.status(),
                    null,
                    false,
                    null,
                    e.principal()));
        }
        if (limit > 0 && entries.size() > limit) {
            entries = entries.subList(0, limit);
        }

        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        typeCounts.put("REQUEST", entries.size());

        ActivityKpiDto kpis = new ActivityKpiDto(
                0d,
                exchanges.isEmpty() ? 0d : (errors * 100d) / exchanges.size(),
                percentile(durations, 50),
                percentile(durations, 95),
                slowestPath,
                slowest == 0 ? null : slowest,
                0,
                0d,
                null,
                healthStatus,
                heapUsed(),
                heapMax());
        return new LiveActivityReport(true, entries, typeCounts, kpis, sources, warnings);
    }

    private String severity(int status, Long durationMs) {
        if (status >= 500) {
            return "ERROR";
        }
        if (status >= 400) {
            return "WARN";
        }
        if (durationMs != null && durationMs >= SLOW_MS) {
            return "SLOW";
        }
        return "OK";
    }

    private Long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100d * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private Long heapUsed() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap == null ? null : heap.getUsed();
    }

    private Long heapMax() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap == null || heap.getMax() < 0 ? null : heap.getMax();
    }
}
