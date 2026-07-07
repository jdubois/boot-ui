package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.ClassUtils;

/**
 * Contributes the Actuator endpoint exposure defaults BootUI needs for its
 * local developer panels.
 *
 * <p>Defaults are contributed as lowest-priority library defaults through the shared
 * {@code defaultProperties} property source, and only for keys the host application has not
 * already configured. Spring Boot keeps {@code defaultProperties} pinned to the very bottom of
 * the environment after every {@code EnvironmentPostProcessor} has run (via
 * {@link DefaultPropertiesPropertySource#moveToEnd}), so any host-provided value always wins over
 * BootUI &mdash; matching the standard Spring Boot contract for environment-contributed defaults.
 */
public class BootUiActuatorDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 10;

    public static final String REQUIRED_ENDPOINTS =
            "health,info,beans,conditions,configprops,env,loggers,mappings," + "metrics,startup,scheduledtasks";

    public static final String TRACING_SAMPLING_PROBABILITY_PROPERTY = "management.tracing.sampling.probability";

    public static final String TRACING_SAMPLING_PROBABILITY = "1.0";

    /**
     * Log level BootUI applies (as an overridable default) to the tracing libraries it drives at full
     * sampling. Raising {@link #TRACING_SAMPLING_PROBABILITY} to {@code 1.0} runs the OpenTelemetry SDK
     * and Micrometer Tracing span/propagation machinery on every request, so when the host has the root
     * logger at {@code DEBUG} those libraries flood the console with low-value lines (for example
     * {@code Invalid TraceId in B3 header: null}, {@code Will propagate new baggage context}, and
     * {@code Ignoring setStatus() description since status is not ERROR}). Pinning these specific
     * loggers to {@code INFO} silences that DEBUG chatter while leaving the host's other loggers
     * untouched; the host can still opt back in by setting the matching {@code logging.level.*} key.
     */
    public static final String TRACING_LOG_LEVEL = "INFO";

    public static final Map<String, String> TRACING_LOG_LEVEL_DEFAULTS = Map.of(
            "logging.level.io.opentelemetry", TRACING_LOG_LEVEL,
            "logging.level.io.micrometer.tracing", TRACING_LOG_LEVEL);

    public static final String REACTOR_CONTEXT_PROPAGATION_PROPERTY = "spring.reactor.context-propagation";

    public static final String REACTOR_CONTEXT_PROPAGATION_VALUE = "auto";

    /**
     * The OpenTelemetry SDK class {@code ReactiveOpenTelemetryCorrelationConfiguration} itself gates on,
     * checked by name here too (rather than importing it, which would pull an optional dependency into
     * this always-loaded {@code EnvironmentPostProcessor}) so this default is contributed exactly when
     * that WebFlux-only trace-id wiring will actually be active.
     */
    private static final String OTEL_SPAN_EXPORTER_CLASS = "io.opentelemetry.sdk.trace.export.SpanExporter";

    private static final Map<String, Object> ACTUATOR_DEFAULTS = Map.of(
            "management.endpoints.web.exposure.include",
            REQUIRED_ENDPOINTS,
            "management.endpoint.health.show-details",
            "always");

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!BootUiActivationCondition.resolve(environment, application.getClassLoader())
                .enabled()) {
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>(ACTUATOR_DEFAULTS);
        if (telemetryEnabled(environment) && tracesPanelEnabled(environment)) {
            defaults.put(TRACING_SAMPLING_PROBABILITY_PROPERTY, TRACING_SAMPLING_PROBABILITY);
            defaults.putAll(TRACING_LOG_LEVEL_DEFAULTS);
        }
        if (reactiveOpenTelemetryCorrelationActive(application)) {
            defaults.put(REACTOR_CONTEXT_PROPAGATION_PROPERTY, REACTOR_CONTEXT_PROPAGATION_VALUE);
        }

        // Only contribute defaults the host has not already configured through any property source,
        // so BootUI never overrides application-provided actuator settings. containsProperty is used
        // (instead of getProperty) so an unresolvable placeholder in a host value cannot raise here.
        defaults.keySet().removeIf(environment::containsProperty);
        if (defaults.isEmpty()) {
            return;
        }

        // Merge into the shared defaultProperties source, which Spring Boot keeps as the lowest-priority
        // source, so configuration added by any other mechanism (including later post-processors) wins.
        MutablePropertySources sources = environment.getPropertySources();
        DefaultPropertiesPropertySource.addOrMerge(defaults, sources);
    }

    private boolean telemetryEnabled(ConfigurableEnvironment environment) {
        return environment.getProperty("bootui.telemetry.enabled", Boolean.class, true);
    }

    private boolean tracesPanelEnabled(ConfigurableEnvironment environment) {
        return environment.getProperty("bootui.panels.traces.enabled", Boolean.class, true);
    }

    /**
     * WebFlux has no thread-per-request invariant: a single reactive chain hops between the Netty
     * event loop, {@code boundedElastic} (blocking JDBC calls), and {@code parallel} schedulers.
     * BootUI's reactive Live Activity/SQL-trace/exception/security-log correlation stamps each capture
     * point with {@code Span.current()}'s trace id (see {@code ReactiveOtelTraceIdProvider}), which only
     * resolves correctly across those thread hops when Reactor's automatic context propagation is on
     * ({@code Hooks.enableAutomaticContextPropagation()}). Spring Boot 4.1 only enables that when
     * {@value #REACTOR_CONTEXT_PROPAGATION_PROPERTY} is {@code auto}; its own default is {@code limited},
     * which leaves the OpenTelemetry trace context ThreadLocal empty on every thread but the one the
     * request happened to start on. Scoped to reactive applications with the OpenTelemetry SDK on the
     * classpath, since that is exactly when {@code ReactiveOpenTelemetryCorrelationConfiguration} wires
     * the trace-id-stamping beans this default exists to support.
     */
    private boolean reactiveOpenTelemetryCorrelationActive(SpringApplication application) {
        return application.getWebApplicationType() == WebApplicationType.REACTIVE
                && ClassUtils.isPresent(OTEL_SPAN_EXPORTER_CLASS, application.getClassLoader());
    }

    public static boolean isBootUiActuatorDefault(String key, String value) {
        if (key == null || value == null) {
            return false;
        }
        String normalized = value.trim();
        return switch (key) {
            case "management.endpoints.web.exposure.include" -> REQUIRED_ENDPOINTS.equals(normalized);
            case "management.endpoint.health.show-details" -> "always".equalsIgnoreCase(normalized);
            case TRACING_SAMPLING_PROBABILITY_PROPERTY -> TRACING_SAMPLING_PROBABILITY.equals(normalized);
            case "logging.level.io.opentelemetry", "logging.level.io.micrometer.tracing" ->
                TRACING_LOG_LEVEL.equalsIgnoreCase(normalized);
            case REACTOR_CONTEXT_PROPAGATION_PROPERTY -> REACTOR_CONTEXT_PROPAGATION_VALUE.equalsIgnoreCase(normalized);
            default -> false;
        };
    }
}
