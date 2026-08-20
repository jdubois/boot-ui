package io.github.jdubois.bootui.engine.grpc;

import io.github.jdubois.bootui.core.dto.GrpcCallMetricsDto;
import io.github.jdubois.bootui.core.dto.GrpcStatusCountDto;
import io.github.jdubois.bootui.spi.GrpcCallMetricSample;
import io.github.jdubois.bootui.spi.GrpcCallSide;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Indexes the native gRPC metric samples once per report so services, methods, and observed client calls can
 * be joined without rescanning the registry.
 *
 * <p>Attribution is deliberately conservative. A sample with no service tag cannot be attributed to anything
 * and is dropped rather than folded into an arbitrary row; a sample with a service but no method contributes
 * to the service total only. A bucket that was never populated stays absent, so the report says "no matching
 * series" instead of showing a confident zero.</p>
 */
final class GrpcCallAggregates {

    /** Display bound on the per-row status breakdown, so a status explosion cannot dominate the response. */
    static final int MAX_STATUS_COUNTS = 20;

    private final Map<String, Accumulator> accumulators = new LinkedHashMap<>();
    private final Map<GrpcCallSide, Map<String, Set<String>>> observed = new LinkedHashMap<>();

    private GrpcCallAggregates() {}

    static GrpcCallAggregates of(List<GrpcCallMetricSample> samples) {
        GrpcCallAggregates aggregates = new GrpcCallAggregates();
        if (samples == null) {
            return aggregates;
        }
        for (GrpcCallMetricSample sample : samples) {
            aggregates.add(sample);
        }
        return aggregates;
    }

    private void add(GrpcCallMetricSample sample) {
        if (sample == null || sample.side() == null) {
            return;
        }
        String service = trimToNull(sample.service());
        if (service == null) {
            return;
        }
        String method = trimToNull(sample.method());
        accumulate(key(sample.side(), service, null), sample);
        if (method != null) {
            accumulate(key(sample.side(), service, method), sample);
        }
        observed.computeIfAbsent(sample.side(), side -> new TreeMap<>())
                .computeIfAbsent(service, name -> new TreeSet<>());
        if (method != null) {
            observed.get(sample.side()).get(service).add(method);
        }
    }

    private void accumulate(String key, GrpcCallMetricSample sample) {
        accumulators.computeIfAbsent(key, unused -> new Accumulator()).add(sample);
    }

    /** Whether any sample was attributable to a service, and therefore whether any row can carry metrics. */
    boolean isEmpty() {
        return accumulators.isEmpty();
    }

    /** Aggregate metrics for one method, or {@link GrpcCallMetricsDto#UNAVAILABLE} when no series matched. */
    GrpcCallMetricsDto forMethod(GrpcCallSide side, String service, String method) {
        return toDto(accumulators.get(key(side, service, method)));
    }

    /** Aggregate metrics for one service, or {@link GrpcCallMetricsDto#UNAVAILABLE} when no series matched. */
    GrpcCallMetricsDto forService(GrpcCallSide side, String service) {
        return toDto(accumulators.get(key(side, service, null)));
    }

    /** The services and methods observed on {@code side}, ordered by name; empty when none were observed. */
    Map<String, Set<String>> observedServices(GrpcCallSide side) {
        return observed.getOrDefault(side, Map.of());
    }

    private static GrpcCallMetricsDto toDto(Accumulator accumulator) {
        if (accumulator == null) {
            return GrpcCallMetricsDto.UNAVAILABLE;
        }
        List<GrpcStatusCountDto> statuses = accumulator.statusCounts.entrySet().stream()
                .map(entry -> new GrpcStatusCountDto(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(GrpcStatusCountDto::count)
                        .reversed()
                        .thenComparing(GrpcStatusCountDto::status))
                .limit(MAX_STATUS_COUNTS)
                .toList();
        Double average = accumulator.totalDurationMs != null && accumulator.count > 0
                ? accumulator.totalDurationMs / accumulator.count
                : null;
        return new GrpcCallMetricsDto(
                true,
                accumulator.count,
                accumulator.activeCalls,
                accumulator.totalDurationMs,
                accumulator.maxDurationMs,
                average,
                statuses);
    }

    private static String key(GrpcCallSide side, String service, String method) {
        return side.name() + '\u0000' + service + '\u0000' + (method == null ? "" : method);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class Accumulator {

        private long count;
        private Double totalDurationMs;
        private Double maxDurationMs;
        private Long activeCalls;
        private final Map<String, Long> statusCounts = new TreeMap<>();

        private void add(GrpcCallMetricSample sample) {
            count += Math.max(0L, sample.count());
            if (sample.totalDurationMs() != null) {
                totalDurationMs = (totalDurationMs == null ? 0d : totalDurationMs) + sample.totalDurationMs();
            }
            if (sample.maxDurationMs() != null) {
                maxDurationMs = maxDurationMs == null
                        ? sample.maxDurationMs()
                        : Math.max(maxDurationMs, sample.maxDurationMs());
            }
            if (sample.activeCalls() != null) {
                activeCalls = (activeCalls == null ? 0L : activeCalls) + sample.activeCalls();
            }
            String status = trimToNull(sample.status());
            if (status != null && sample.count() > 0) {
                statusCounts.merge(status, sample.count(), Long::sum);
            }
        }
    }
}
