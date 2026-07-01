package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.telemetry.TelemetrySettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus implementation of the engine {@link TelemetrySettings} seam, resolved from MicroProfile
 * Config.
 *
 * <p>This is the Quarkus analogue of the Spring adapter's {@code SpringTelemetrySettings}. It reads the
 * {@code bootui.telemetry.*} keys <em>live</em> (per call) from the injected {@link Config}, so a runtime
 * override is honored without the engine telemetry services ({@code TelemetryStore},
 * {@code BootUiSpanExporter}, {@code TracesService}) needing to rebind. The defaults match the Spring
 * adapter's {@code BootUiProperties.Telemetry} defaults exactly so both platforms retain and clamp
 * telemetry identically.</p>
 */
@ApplicationScoped
public class QuarkusTelemetrySettings implements TelemetrySettings {

    private final Config config;

    @Inject
    public QuarkusTelemetrySettings(Config config) {
        this.config = config;
    }

    @Override
    public boolean enabled() {
        return config.getOptionalValue("bootui.telemetry.enabled", Boolean.class)
                .orElse(Boolean.TRUE);
    }

    @Override
    public boolean excludeSelfSpans() {
        return config.getOptionalValue("bootui.telemetry.exclude-self-spans", Boolean.class)
                .orElse(Boolean.TRUE);
    }

    @Override
    public int maxTraces() {
        return config.getOptionalValue("bootui.telemetry.max-traces", Integer.class)
                .orElse(500);
    }

    @Override
    public int maxSpansPerTrace() {
        return config.getOptionalValue("bootui.telemetry.max-spans-per-trace", Integer.class)
                .orElse(500);
    }

    @Override
    public int maxAttributeValueBytes() {
        return config.getOptionalValue("bootui.telemetry.max-attribute-value-bytes", Integer.class)
                .orElse(4 * 1024);
    }
}
