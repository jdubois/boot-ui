package io.github.jdubois.bootui.autoconfigure.otlp;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.telemetry.TelemetrySettings;

/**
 * Spring adapter for the engine {@link TelemetrySettings} seam. Reads {@code bootui.telemetry.*} from
 * {@link BootUiProperties} <em>live</em> on every call, so a runtime override is honored without the
 * engine telemetry services needing to rebind.
 *
 * <p>Public so adapter tests can construct a {@code new TelemetryStore(new SpringTelemetrySettings(properties))}.</p>
 */
public final class SpringTelemetrySettings implements TelemetrySettings {

    private final BootUiProperties properties;

    public SpringTelemetrySettings(BootUiProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        return properties.getTelemetry().isEnabled();
    }

    @Override
    public boolean excludeSelfSpans() {
        return properties.getTelemetry().isExcludeSelfSpans();
    }

    @Override
    public int maxTraces() {
        return properties.getTelemetry().getMaxTraces();
    }

    @Override
    public int maxSpansPerTrace() {
        return properties.getTelemetry().getMaxSpansPerTrace();
    }

    @Override
    public int maxAttributeValueBytes() {
        return properties.getTelemetry().getMaxAttributeValueBytes();
    }

    @Override
    public boolean enrichmentEnabled() {
        return properties.getTelemetry().isEnrich();
    }
}
