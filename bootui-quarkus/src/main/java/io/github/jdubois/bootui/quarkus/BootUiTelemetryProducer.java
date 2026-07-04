package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.telemetry.AiUsageService;
import io.github.jdubois.bootui.engine.telemetry.AiUsageSettings;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.Config;

/**
 * Produces the framework-neutral telemetry <em>read</em> services for the Quarkus adapter — the engine
 * {@link TelemetryStore} and the {@link TracesService} / {@link AiUsageService} that transform the spans
 * it holds into the Traces and AI Usage panel DTOs.
 *
 * <p>These are produced <strong>unconditionally</strong> (no OpenTelemetry on the classpath required), so
 * the Traces and AI panels are always available on Quarkus and simply render empty when no spans have been
 * captured — exactly matching the Spring adapter, where the controllers exist whether or not an OTel
 * exporter is wired. The <em>capture</em> side (feeding the store) lives in the separate, OTel-gated
 * {@link BootUiOtelProducer}; none of the services produced here import the OpenTelemetry SDK, so an app
 * without {@code quarkus-opentelemetry} never links it (R2/BF2).</p>
 *
 * <p>Live configuration is read from MicroProfile {@link Config} the same way the Spring adapter reads it
 * from {@code BootUiProperties}:</p>
 * <ul>
 *   <li>The store and the traces transform share the live {@link QuarkusTelemetrySettings} bean (telemetry
 *       enabled flag + retention limits read per call).</li>
 *   <li>{@link #selfTelemetryClassifier(Config)} is the <strong>single shared</strong> self-traffic
 *       classifier for the whole Quarkus adapter, built once from the operator-configured
 *       {@code bootui.path}/{@code bootui.api-path} and {@code bootui.monitoring.exclude-self}.
 *       {@link BootUiEngineProducer} (Metrics, Cache) and the OTel-gated {@link BootUiOtelProducer}
 *       (capture) inject this same instance rather than building their own, so capture and every
 *       transform/display panel can never disagree on which paths are BootUI's own.</li>
 *   <li>The AI usage settings are supplied fresh per request so {@code bootui.ai.*} and
 *       {@code bootui.telemetry.enabled} overrides are honored live.</li>
 * </ul>
 */
@ApplicationScoped
public class BootUiTelemetryProducer {

    @Produces
    @Singleton
    public TelemetryStore telemetryStore(QuarkusTelemetrySettings settings) {
        return new TelemetryStore(settings);
    }

    /**
     * The single shared self-traffic classifier for the whole Quarkus adapter &mdash; injected by both the
     * capture side ({@link BootUiOtelProducer}) and every transform/display consumer ({@link
     * BootUiEngineProducer}, {@link #tracesService}) instead of each building its own. See the class-level
     * javadoc.
     */
    @Produces
    @Singleton
    public SelfTelemetryClassifier selfTelemetryClassifier(Config config) {
        boolean excludeSelf = config.getOptionalValue("bootui.monitoring.exclude-self", Boolean.class)
                .orElse(Boolean.TRUE);
        String path = config.getOptionalValue("bootui.path", String.class).orElse("/bootui");
        String apiPath =
                config.getOptionalValue("bootui.api-path", String.class).orElse("/bootui/api");
        return new SelfTelemetryClassifier(excludeSelf, path, apiPath);
    }

    @Produces
    @Singleton
    public TracesService tracesService(
            TelemetryStore store, QuarkusTelemetrySettings settings, SelfTelemetryClassifier selfClassifier) {
        return new TracesService(store, settings, selfClassifier);
    }

    @Produces
    @Singleton
    public AiUsageService aiUsageService(TelemetryStore store, QuarkusTelemetrySettings settings, Config config) {
        Supplier<AiUsageSettings> aiSettings = () -> new AiUsageSettings(
                settings.enabled(),
                config.getOptionalValue("bootui.ai.max-recent-chats", Integer.class)
                        .orElse(100),
                config.getOptionalValue("bootui.ai.token-series-minutes", Integer.class)
                        .orElse(60),
                config.getOptionalValue("bootui.ai.show-content-capture-banner", Boolean.class)
                        .orElse(Boolean.TRUE));
        return new AiUsageService(store, aiSettings, System::currentTimeMillis);
    }
}
