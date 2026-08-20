package io.github.jdubois.bootui.engine.grpc;

import io.github.jdubois.bootui.spi.GrpcCallMetricSample;
import io.github.jdubois.bootui.spi.GrpcCallSide;
import io.github.jdubois.bootui.spi.GrpcMetricsProvider;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Reads aggregate gRPC call metrics from the Micrometer registry the application already publishes.
 *
 * <p>This provider only <em>reads</em>. BootUI never registers a gRPC interceptor or observation handler to
 * create a missing metric family, because doing so would change the call path of the application being
 * inspected. When none of the known meter families is present the provider reports itself unavailable and the
 * panel says so, instead of rendering zeros that look like measurements.</p>
 *
 * <p>Two instrumentation conventions are supported, because the two supported stacks use different ones:</p>
 * <ul>
 *   <li><strong>Interceptor convention</strong> — Micrometer's {@code MetricCollecting*Interceptor}, used by
 *       Quarkus Micrometer's gRPC binder: timers named {@code grpc.server.processing.duration} /
 *       {@code grpc.client.processing.duration} tagged {@code service}, {@code method}, {@code statusCode}.</li>
 *   <li><strong>Observation convention</strong> — Micrometer Observation's gRPC instrumentation, used by
 *       Spring gRPC when observability is enabled: timers named {@code grpc.server} / {@code grpc.client}
 *       tagged {@code rpc.service}, {@code rpc.method}, {@code grpc.status_code}, plus long task timers named
 *       {@code grpc.server.active} / {@code grpc.client.active} carrying in-progress calls.</li>
 * </ul>
 *
 * <p>Micrometer is a deliberate direct engine dependency (the Metrics panel already relies on it): it is the
 * framework-neutral metrics API shared by Spring Boot and Quarkus, so no extra abstraction is warranted.</p>
 */
public final class MicrometerGrpcMetricsProvider implements GrpcMetricsProvider {

    private static final Set<String> SERVER_TIMERS = Set.of("grpc.server.processing.duration", "grpc.server");
    private static final Set<String> CLIENT_TIMERS = Set.of("grpc.client.processing.duration", "grpc.client");
    private static final Set<String> SERVER_ACTIVE = Set.of("grpc.server.active");
    private static final Set<String> CLIENT_ACTIVE = Set.of("grpc.client.active");

    private static final List<String> SERVICE_TAGS = List.of("rpc.service", "service");
    private static final List<String> METHOD_TAGS = List.of("rpc.method", "method");
    private static final List<String> STATUS_TAGS = List.of("grpc.status_code", "statusCode", "rpc.error_code");

    private static final String NO_REGISTRY =
            "No metrics registry is available, so gRPC call aggregates cannot be reported.";
    private static final String NO_SERIES =
            "The metrics registry publishes no native gRPC call series, so gRPC call aggregates cannot be "
                    + "reported.";

    private final Supplier<MeterRegistry> registrySupplier;

    public MicrometerGrpcMetricsProvider(Supplier<MeterRegistry> registrySupplier) {
        this.registrySupplier = registrySupplier;
    }

    @Override
    public boolean available() {
        MeterRegistry registry = registry();
        if (registry == null) {
            return false;
        }
        for (Meter meter : registry.getMeters()) {
            if (matches(meter)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String unavailableReason() {
        if (registry() == null) {
            return NO_REGISTRY;
        }
        return available() ? null : NO_SERIES;
    }

    private static boolean matches(Meter meter) {
        String name = meter.getId().getName();
        if (meter instanceof Timer) {
            return SERVER_TIMERS.contains(name) || CLIENT_TIMERS.contains(name);
        }
        if (meter instanceof LongTaskTimer) {
            return SERVER_ACTIVE.contains(name) || CLIENT_ACTIVE.contains(name);
        }
        return false;
    }

    @Override
    public List<GrpcCallMetricSample> samples() {
        MeterRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<GrpcCallMetricSample> samples = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (meter instanceof Timer timer) {
                if (SERVER_TIMERS.contains(name)) {
                    samples.add(timerSample(GrpcCallSide.SERVER, timer));
                } else if (CLIENT_TIMERS.contains(name)) {
                    samples.add(timerSample(GrpcCallSide.CLIENT, timer));
                }
            } else if (meter instanceof LongTaskTimer longTaskTimer) {
                if (SERVER_ACTIVE.contains(name)) {
                    samples.add(activeSample(GrpcCallSide.SERVER, longTaskTimer));
                } else if (CLIENT_ACTIVE.contains(name)) {
                    samples.add(activeSample(GrpcCallSide.CLIENT, longTaskTimer));
                }
            }
        }
        return samples;
    }

    private GrpcCallMetricSample timerSample(GrpcCallSide side, Timer timer) {
        return new GrpcCallMetricSample(
                side,
                tag(timer, SERVICE_TAGS),
                tag(timer, METHOD_TAGS),
                tag(timer, STATUS_TAGS),
                timer.count(),
                timer.totalTime(TimeUnit.MILLISECONDS),
                timer.max(TimeUnit.MILLISECONDS),
                null);
    }

    /**
     * In-progress calls carry {@code count = 0} on purpose: the long task timer counts the same calls the
     * completion timer will count, so adding its count would double the call total.
     */
    private GrpcCallMetricSample activeSample(GrpcCallSide side, LongTaskTimer timer) {
        return new GrpcCallMetricSample(
                side, tag(timer, SERVICE_TAGS), tag(timer, METHOD_TAGS), null, 0L, null, null, (long)
                        timer.activeTasks());
    }

    private static String tag(Meter meter, List<String> candidates) {
        for (String candidate : candidates) {
            String value = meter.getId().getTag(candidate);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private MeterRegistry registry() {
        if (registrySupplier == null) {
            return null;
        }
        try {
            return registrySupplier.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
