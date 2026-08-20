package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral seam behind the gRPC panel's aggregate call metrics.
 *
 * <p>BootUI joins these numbers from instrumentation the application already publishes. It never installs a
 * second call interceptor purely to synthesize a missing metric family, so an implementation that finds no
 * matching series must report {@link #available()} {@code false} with a reason, and the panel says the
 * aggregates are unavailable instead of rendering zeros as if they were measurements.</p>
 */
public interface GrpcMetricsProvider {

    /** A provider that reports no metric series at all, used when no registry is wired. */
    GrpcMetricsProvider UNAVAILABLE = new GrpcMetricsProvider() {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String unavailableReason() {
            return "No metrics registry is available, so gRPC call aggregates cannot be reported.";
        }

        @Override
        public List<GrpcCallMetricSample> samples() {
            return List.of();
        }
    };

    /** Whether at least one matching native gRPC metric series was found. */
    boolean available();

    /** Why aggregates are unavailable, or {@code null} when {@link #available()} is {@code true}. */
    String unavailableReason();

    /** The matching samples, unsorted; the engine joins them onto services, methods, and channels. */
    List<GrpcCallMetricSample> samples();
}
