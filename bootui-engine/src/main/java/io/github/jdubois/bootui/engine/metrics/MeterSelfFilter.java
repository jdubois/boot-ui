package io.github.jdubois.bootui.engine.metrics;

import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import java.util.function.Predicate;

/**
 * Framework-neutral meter-visibility filter: hides Micrometer meters that describe BootUI's own
 * {@code /bootui/**} HTTP traffic, so the Metrics panel never reports the console's own requests.
 *
 * <p>The path/tag matching is delegated to the shared {@link SelfTelemetryClassifier} — the same
 * transform-side classifier the Traces read model uses — so "what counts as BootUI's own path" has a
 * single definition across panels. Micrometer is a sanctioned engine dependency (the neutral metrics API
 * used by both Spring Boot and Quarkus), so this filter lives in {@code bootui-engine} and both adapters
 * feed it as the {@link MetricsReportProvider} meter predicate: the Spring adapter through
 * {@code BootUiSelfDataFilter} (which composes it), the Quarkus adapter directly. A meter is recognized as
 * BootUI's own when any of its {@linkplain SelfTelemetryClassifier#isPathTag(String) path-carrying tags}
 * holds a {@linkplain SelfTelemetryClassifier#isBootUiPath(String) BootUI path} (e.g. the {@code uri} tag
 * of an {@code http.server.requests} meter pointing at {@code /bootui/api/...}).</p>
 */
public final class MeterSelfFilter {

    private final SelfTelemetryClassifier classifier;

    public MeterSelfFilter(SelfTelemetryClassifier classifier) {
        this.classifier = classifier;
    }

    /** Whether the meter describes BootUI's own traffic (a path-carrying tag points at a BootUI path). */
    public boolean isBootUiMeter(Meter meter) {
        if (meter == null || meter.getId() == null) {
            return false;
        }
        for (Tag tag : meter.getId().getTags()) {
            if (classifier.isPathTag(tag.getKey()) && classifier.isBootUiPath(tag.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the meter should be visible in the panel (honoring the classifier's exclude-self flag). */
    public boolean shouldIncludeMeter(Meter meter) {
        return classifier.shouldInclude(isBootUiMeter(meter));
    }

    /** The visibility test as a reusable predicate for {@link MetricsReportProvider}. */
    public Predicate<Meter> includePredicate() {
        return this::shouldIncludeMeter;
    }
}
